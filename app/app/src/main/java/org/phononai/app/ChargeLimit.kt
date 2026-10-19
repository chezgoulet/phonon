package org.phononai.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/** The charging-limit states the card can honestly report. */
enum class ChargeMode(val wire: String) {
    LIMIT_80("limit-80"),
    ADAPTIVE("adaptive"),
    OFF("off"),
    UNKNOWN("unknown"),
    ;

    companion object {
        /** Parse the wire name from `/health`; anything unknown is UNKNOWN. */
        fun fromWire(value: String?): ChargeMode =
            values().firstOrNull { it.wire == value } ?: UNKNOWN

        /**
         * `charge_optimization_mode` is 1 for "Limit to 80%" and 0 for off on
         * Pixel. A device without the key reports [UNKNOWN], which is not the
         * same claim as off — we did not read anything.
         */
        fun fromSecure(value: Int?, adaptive: Boolean?): ChargeMode = when {
            value == null -> UNKNOWN
            value == 1 -> LIMIT_80
            adaptive == true -> ADAPTIVE
            else -> OFF
        }
    }
}

/**
 * What the app can establish about the limit, and how it established it.
 *
 * [source] is "secure" when the setting was read directly, "plateau" when it
 * was inferred from the phone sitting at 80% on a charger, "recalibration" when
 * a 100% charge is in progress and the last evidence said 80, and "none"
 * otherwise. [confirmed] is false for anything inferred during recalibration.
 */
data class ChargeReading(
    val mode: ChargeMode,
    val source: String,
    val confirmed: Boolean,
    val detail: String,
)

/**
 * Reads and — only with `WRITE_SECURE_SETTINGS`, granted by adb or a kiosk
 * install — writes the platform's charging limit.
 *
 * The app never pretends to control the charger without that permission: the
 * UI shows a button to the OEM screen instead of a switch that does nothing.
 */
object ChargeSettings {
    const val KEY_MODE = "charge_optimization_mode"
    const val KEY_ADAPTIVE = "adaptive_charging_enabled"

    /** Reading Settings.Secure needs no permission; writing does. */
    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** The raw value, or null when this device does not expose the key. */
    fun readModeValue(context: Context): Int? =
        try {
            Settings.Secure.getInt(context.contentResolver, KEY_MODE)
        } catch (_: Settings.SettingNotFoundException) {
            null
        } catch (_: Throwable) {
            null
        }

    fun readAdaptive(context: Context): Boolean? =
        try {
            Settings.Secure.getInt(context.contentResolver, KEY_ADAPTIVE) != 0
        } catch (_: Settings.SettingNotFoundException) {
            null
        } catch (_: Throwable) {
            null
        }

    /** The limit as the device states it, or null when it states nothing. */
    fun readMode(context: Context): ChargeMode? {
        val value = readModeValue(context) ?: return null
        return ChargeMode.fromSecure(value, readAdaptive(context))
    }

    /**
     * Writes the 80% limit. Returns null on success, or a sentence for the UI.
     *
     * A device that lacks the key says so rather than reporting success for a
     * write that went nowhere.
     */
    fun writeLimit80(context: Context, enabled: Boolean): String? {
        if (!canWrite(context)) {
            return "Phonon does not have WRITE_SECURE_SETTINGS; grant it over adb to use this switch"
        }
        if (readModeValue(context) == null) {
            return "this device does not expose $KEY_MODE; use the OEM charging screen"
        }
        val ok = try {
            Settings.Secure.putInt(context.contentResolver, KEY_MODE, if (enabled) 1 else 0)
        } catch (t: Throwable) {
            false
        }
        if (!ok) return "the platform refused the write"
        // Only touch the adaptive key when the device has it; writing a key a
        // device does not use would be a change nobody asked for.
        if (readAdaptive(context) != null) {
            try {
                Settings.Secure.putInt(context.contentResolver, KEY_ADAPTIVE, if (enabled) 0 else 1)
            } catch (_: Throwable) {
            }
        }
        return null
    }
}

/**
 * Infers the limit from behaviour when the setting cannot be read.
 *
 * The rule is deliberately narrow: plugged in, flat inside 80 ± 1, for at least
 * [windowMs], with at least [minSamples] observations. A phone that happened to
 * pass through 79% is not evidence of anything.
 */
class PlateauDetector(
    private val windowMs: Long = 20 * 60_000L,
    private val targetPct: Int = 80,
    private val tolerancePct: Int = 1,
    private val minSamples: Int = 4,
) {
    data class Verdict(val plateau: Boolean, val spanMs: Long, val samples: Int)

    private data class Sample(val atMs: Long, val levelPct: Int)

    private val samples = ArrayDeque<Sample>()

    fun observe(nowMs: Long, levelPct: Int, charging: Boolean): Verdict {
        if (!charging) {
            // Unplugged: the plateau is over. Drop the history so a later
            // charge does not inherit it.
            samples.clear()
            return Verdict(false, 0, 0)
        }
        samples.addLast(Sample(nowMs, levelPct))
        while (samples.isNotEmpty() && nowMs - samples.first().atMs > windowMs + PRUNE_SLACK_MS) {
            samples.removeFirst()
        }
        // Sampling happens on a tick, so the oldest in-window sample is one
        // tick short of a full window. The slack is what makes "20 minutes"
        // reachable instead of "20 minutes and one tick".
        val recent = samples.filter { nowMs - it.atMs <= windowMs + TICK_SLACK_MS }
        val span = if (recent.size >= 2) nowMs - recent.first().atMs else 0L
        val inBand = recent.isNotEmpty() &&
            recent.all { kotlin.math.abs(it.levelPct - targetPct) <= tolerancePct }
        val plateau = recent.size >= minSamples &&
            span >= windowMs - TICK_SLACK_MS &&
            inBand
        return Verdict(plateau, span, recent.size)
    }

    private companion object {
        /** One sampling tick of forgiveness at each edge of the window. */
        const val TICK_SLACK_MS = 60_000L

        /** Keep a little history past the window so [recent] can be complete. */
        const val PRUNE_SLACK_MS = 5 * 60_000L
    }
}

/**
 * Turns observations into a [ChargeReading], including the recalibration case.
 *
 * Pixel charges to 100% periodically to recalibrate capacity even with the
 * limit on. That must not read as "the limit was turned off", and it must not
 * re-alarm inside [recalibrationHoldMs] of the last proof.
 */
class ChargeLimitTracker(
    private val plateau: PlateauDetector = PlateauDetector(),
    private val recalibrationHoldMs: Long = 12 * 60 * 60_000L,
) {
    private var lastPlateauAtMs = 0L
    private var lastPlateauSpanMs = 0L

    /** Reset for a process that has not observed anything yet. */
    fun reset() {
        lastPlateauAtMs = 0L
        lastPlateauSpanMs = 0L
    }

    fun observe(
        nowMs: Long,
        levelPct: Int,
        charging: Boolean,
        secureMode: ChargeMode?,
    ): ChargeReading {
        // A readable setting outranks any inference, including during a
        // recalibration charge: the device is telling us directly.
        if (secureMode != null && secureMode != ChargeMode.UNKNOWN) {
            return ChargeReading(
                mode = secureMode,
                source = "secure",
                confirmed = true,
                detail = "read from Settings.Secure",
            )
        }
        val verdict = plateau.observe(nowMs, levelPct, charging)
        if (verdict.plateau) {
            lastPlateauAtMs = nowMs
            lastPlateauSpanMs = verdict.spanMs
            return ChargeReading(
                mode = ChargeMode.LIMIT_80,
                source = "plateau",
                confirmed = true,
                detail = "plugged and flat at 80% for ${verdict.spanMs / 60_000} min",
            )
        }
        if (charging && levelPct >= 99 && lastPlateauAtMs > 0 &&
            nowMs - lastPlateauAtMs <= recalibrationHoldMs
        ) {
            return ChargeReading(
                mode = ChargeMode.LIMIT_80,
                source = "recalibration",
                confirmed = false,
                detail = "charging to 100% to recalibrate; last confirmed 80% plateau " +
                    "${(nowMs - lastPlateauAtMs) / 3_600_000} h ago",
            )
        }
        if (charging && levelPct >= 99) {
            return ChargeReading(
                mode = ChargeMode.UNKNOWN,
                source = "none",
                confirmed = false,
                detail = "at 100%: could be a recalibration charge or no limit at all",
            )
        }
        return ChargeReading(
            mode = ChargeMode.UNKNOWN,
            source = "none",
            confirmed = false,
            detail = if (charging) {
                "watching for a plateau at 80%"
            } else {
                "not charging; the limit cannot be inferred"
            },
        )
    }
}

/**
 * Hourly state-of-charge samples for the last week.
 *
 * One sample per hour, and each sample counts for at most one hour, so a gap in
 * sampling under-reports rather than inventing time. This is the number the
 * operator can act on: "held above 80% for N hours this week".
 */
class SocHistory(val capacity: Int = 168) {
    private data class Sample(val atMs: Long, val levelPct: Int)

    private val samples = ArrayDeque<Sample>()

    /** Adds a sample; returns true when the caller should persist the ring. */
    fun record(nowMs: Long, levelPct: Int): Boolean {
        val last = samples.lastOrNull()
        if (last != null && nowMs - last.atMs < MIN_INTERVAL_MS) return false
        samples.addLast(Sample(nowMs, levelPct))
        while (samples.size > capacity) samples.removeFirst()
        return true
    }

    fun size(): Int = samples.size

    fun hoursAbove(threshold: Int, nowMs: Long, windowMs: Long = WEEK_MS): Int {
        if (samples.size < 2) return 0
        var ms = 0L
        val cutoff = nowMs - windowMs
        for (i in 0 until samples.size - 1) {
            val a = samples[i]
            val b = samples[i + 1]
            if (a.atMs < cutoff) continue
            if (a.levelPct > threshold) {
                // Each interval counts for at most one hour: the ring is hourly.
                ms += (b.atMs - a.atMs).coerceIn(0L, HOUR_MS)
            }
        }
        return (ms / HOUR_MS).toInt()
    }

    fun toJson(): String = JSONObject().apply {
        put("samples", JSONArray().apply {
            for (s in samples) {
                put(JSONArray().put(s.atMs).put(s.levelPct))
            }
        })
    }.toString()

    companion object {
        const val HOUR_MS = 3_600_000L
        const val WEEK_MS = 7 * 24 * HOUR_MS

        /** Two samples inside the same hour would double-count the interval. */
        const val MIN_INTERVAL_MS = HOUR_MS - 60_000L

        fun fromJson(text: String?, capacity: Int = 168): SocHistory {
            val history = SocHistory(capacity)
            if (text.isNullOrBlank()) return history
            try {
                val array = JSONObject(text).optJSONArray("samples") ?: return history
                for (i in 0 until array.length()) {
                    val pair = array.optJSONArray(i) ?: continue
                    history.samples.addLast(Sample(pair.optLong(0), pair.optInt(1)))
                }
                while (history.samples.size > capacity) history.samples.removeFirst()
            } catch (_: Throwable) {
                // A corrupt ring is dropped, not fatal: the metric restarts.
            }
            return history
        }
    }
}

/** The `/health` block for charging. No key, no serial, nothing secret. */
object ChargeLimit {
    fun healthJson(
        reading: ChargeReading,
        hoursAbove80: Int,
        canToggle: Boolean,
    ): JSONObject = JSONObject().apply {
        put("mode", reading.mode.wire)
        put("source", reading.source)
        put("confirmed", reading.confirmed)
        put("detail", reading.detail)
        put("hours_above_80", hoursAbove80)
        put("can_toggle", canToggle)
    }

    /** One line for the card: state word plus the evidence behind it. */
    fun cardLine(reading: ChargeReading, hoursAbove80: Int): String {
        val state = when (reading.mode) {
            ChargeMode.LIMIT_80 ->
                if (reading.confirmed) "Limit to 80% is on" else "Limit to 80% (unconfirmed)"
            ChargeMode.ADAPTIVE -> "Adaptive charging is on"
            ChargeMode.OFF -> "No charging limit"
            ChargeMode.UNKNOWN -> "Couldn't confirm the charging limit"
        }
        val evidence = when {
            !reading.confirmed && reading.source == "none" -> ""
            else -> " · ${reading.detail}"
        }
        return "$state$evidence · held above 80% for $hoursAbove80 h this week"
    }
}

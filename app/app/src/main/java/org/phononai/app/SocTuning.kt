package org.phononai.app

import android.os.Build
import org.json.JSONObject
import java.time.Instant

/**
 * A tuning profile measured on one SoC. Keyed by [Build.SOC_MODEL] so a phone
 * inherits the numbers its own chip measured rather than a Pixel's.
 *
 * [prefillThreads] is llama.cpp's `-tb`: prefill is compute-bound and wants more
 * threads than decode, which is bandwidth-bound.
 */
data class TuningProfile(
    val soc: String,
    val decodeThreads: Int,
    val cpuRange: String,
    val prefillThreads: Int,
    val context: Int,
    val keepWarmIntervalS: Int,
    val measuredAt: String,
    val decodeTps: Double,
    val prefillTps: Double,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("soc", soc)
        put("decode_threads", decodeThreads)
        put("cpu_range", cpuRange)
        put("prefill_threads", prefillThreads)
        put("context", context)
        put("keep_warm_interval_s", keepWarmIntervalS)
        put("measured_at", measuredAt)
        put("decode_tps", decodeTps)
        put("prefill_tps", prefillTps)
    }

    companion object {
        fun fromJson(o: JSONObject): TuningProfile = TuningProfile(
            soc = o.optString("soc"),
            decodeThreads = o.optInt("decode_threads", 2),
            cpuRange = o.optString("cpu_range"),
            prefillThreads = o.optInt("prefill_threads", 2),
            context = o.optInt("context", 0),
            keepWarmIntervalS = o.optInt("keep_warm_interval_s", 0),
            measuredAt = o.optString("measured_at"),
            decodeTps = o.optDouble("decode_tps", 0.0),
            prefillTps = o.optDouble("prefill_tps", 0.0),
        )
    }
}

/**
 * The per-SoC tuning store and the sweep that fills it.
 *
 * The sweep is not run automatically on first launch: it restarts the server
 * several times and takes a couple of minutes, so it waits for the operator's
 * "Tune this phone" action. A stored profile *is* applied on startup, because
 * its whole purpose is to replace the generic per-cpuset default with something
 * measured on this chip.
 */
class SocTuning(private val settings: Settings) {

    val soc: String
        get() = (
            // SOC_MODEL is API 31+; HARDWARE is the vendor codename below it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
        ) ?: Build.HARDWARE ?: ""

    /** The stored profile for this SoC, or null when none has been measured. */
    fun profile(): TuningProfile? = profileFor(soc)

    fun profileFor(socModel: String): TuningProfile? {
        val raw = settings.socTuningJson
        if (raw.isBlank()) return null
        return try {
            val root = JSONObject(raw)
            root.optJSONObject(socModel)?.let { TuningProfile.fromJson(it) }
        } catch (_: Throwable) {
            null
        }
    }

    fun save(profile: TuningProfile) {
        val root = try {
            if (settings.socTuningJson.isBlank()) JSONObject() else JSONObject(settings.socTuningJson)
        } catch (_: Throwable) {
            JSONObject()
        }
        root.put(profile.soc, profile.toJson())
        settings.socTuningJson = root.toString()
    }

    fun clear() {
        settings.socTuningJson = ""
    }

    /** For /health; a compact summary of the applied profile, or "none". */
    fun activeProfileJson(): String =
        profile()?.toJson()?.toString() ?: "none"

    /**
     * Apply a stored profile for this SoC as performance overrides. A no-op when
     * no profile exists, which is the first-launch case.
     */
    fun applyIfPresent() {
        val p = profile() ?: return
        settings.setThreadsOverride(p.decodeThreads)
        settings.setPrefillThreadsOverride(p.prefillThreads)
        if (p.cpuRange.isNotBlank() && PerfProfile.validCpuRange(p.cpuRange)) {
            settings.setCpuRangeOverride(p.cpuRange)
        }
        if (p.context > 0) settings.contextSize = p.context
        if (p.keepWarmIntervalS > 0) settings.keepWarmIntervalS = p.keepWarmIntervalS
    }

    companion object {
        /** The decode configurations the sweep tries (thread count). */
        val DECODE_THREADS = listOf(2, 4, 6)

        /** The prefill (`-tb`) configurations the sweep tries. */
        val PREFILL_THREADS = listOf(2, 4)

        /**
         * Reduce a set of measured configs to the best of each, producing the
         * profile to store. Pure so it can be unit tested without a device.
         */
        fun best(
            soc: String,
            cpuRange: String,
            context: Int,
            keepWarmIntervalS: Int,
            decode: List<Pair<Int, Double>>,
            prefill: List<Pair<Int, Double>>,
        ): TuningProfile? {
            val bestDecode = decode.filter { it.second > 0.0 }.maxByOrNull { it.second }
                ?: return null
            val bestPrefill = prefill.filter { it.second > 0.0 }.maxByOrNull { it.second }
                ?: bestDecode
            return TuningProfile(
                soc = soc,
                decodeThreads = bestDecode.first,
                cpuRange = cpuRange,
                prefillThreads = bestPrefill.first,
                context = context,
                keepWarmIntervalS = keepWarmIntervalS,
                measuredAt = Instant.now().toString(),
                decodeTps = bestDecode.second,
                prefillTps = bestPrefill.second,
            )
        }
    }
}

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
 *
 * [engineRev] is the llama.cpp revision the sweep ran against. A profile is only
 * valid for the exact engine that produced it: a rebuild can change how weights
 * are read, so an old profile is rejected rather than applied silently.
 */
data class TuningProfile(
    val soc: String,
    val decodeThreads: Int,
    val decodeCpuRange: String,
    val prefillThreads: Int,
    val contextSize: Int,
    val keepWarmIntervalS: Int,
    val engineRev: String,
    val measuredAt: String,
    val decodeTps: Double,
    val prefillTps: Double,
) {
    /** True when this profile was measured against a different engine build. */
    fun isStale(engineRev: String): Boolean = this.engineRev != engineRev

    fun toJson(): JSONObject = JSONObject().apply {
        put("soc", soc)
        put("decode_threads", decodeThreads)
        put("decode_cpu_range", decodeCpuRange)
        put("prefill_threads", prefillThreads)
        put("context_size", contextSize)
        put("keep_warm_interval_s", keepWarmIntervalS)
        put("engine_rev", engineRev)
        put("measured_at", measuredAt)
        put("decode_tps", decodeTps)
        put("prefill_tps", prefillTps)
    }

    companion object {
        fun fromJson(o: JSONObject): TuningProfile = TuningProfile(
            soc = o.optString("soc"),
            decodeThreads = o.optInt("decode_threads", 2),
            decodeCpuRange = o.optString("decode_cpu_range"),
            prefillThreads = o.optInt("prefill_threads", 2),
            contextSize = o.optInt("context_size", 0),
            keepWarmIntervalS = o.optInt("keep_warm_interval_s", 0),
            engineRev = o.optString("engine_rev"),
            measuredAt = o.optString("measured_at"),
            decodeTps = o.optDouble("decode_tps", 0.0),
            prefillTps = o.optDouble("prefill_tps", 0.0),
        )
    }
}

/** One decode configuration the sweep tries: a thread count and a CPU pin. */
data class DecodeConfig(val threads: Int, val cpuRange: String, val label: String)

/** What [Settings.perfPlan] should actually launch with. */
data class EffectivePerf(
    val threads: Int,
    val cpuRange: String,
    val prefillThreads: Int,
)

/**
 * The per-SoC tuning store and the sweep that fills it.
 *
 * The sweep is not run automatically on first launch: it restarts the server
 * several times and takes a couple of minutes, so it waits for the operator's
 * "Tune this phone" action. A stored profile *is* applied, because its whole
 * purpose is to replace the generic per-cpuset default with something measured
 * on this chip — provided it was measured against the engine currently running.
 */
class SocTuning(private val settings: Settings) {

    val soc: String
        get() = resolveSoc(
            sdkInt = Build.VERSION.SDK_INT,
            // SOC_MODEL is API 31+; HARDWARE is the vendor codename below it.
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
            hardware = Build.HARDWARE,
        )

    /** The stored profile for this SoC, or null when none is valid. */
    fun profile(): TuningProfile? = lookup(settings.socTuningJson, soc, ENGINE_REV)

    /** The stored profile for this SoC whatever engine it was measured against. */
    fun storedAnyEngine(): TuningProfile? = storedRaw(settings.socTuningJson, soc)

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

    /** For /health; the applied profile as JSON, or "none". */
    fun activeProfileJson(): String = profile()?.toJson()?.toString() ?: "none"

    /**
     * The engine revision of a stored profile that no longer matches this build,
     * or null when there is nothing stale. The UI says so and asks for a re-run.
     */
    fun staleEngineRev(): String? = staleEngineRev(settings.socTuningJson, soc, ENGINE_REV)

    companion object {
        /**
         * llama.cpp revision this app's native library was built from (the
         * pinned submodule, see docs/FDROID-GAPS.md). Bump it when the submodule
         * moves: profiles measured against the old binary are then rejected.
         */
        const val ENGINE_REV = "797de63"

        /** The decode configs the sweep tries, in order. */
        fun decodeConfigs(fastClusterRange: String): List<DecodeConfig> {
            val pinned = fastClusterRange.takeIf { PerfProfile.validCpuRange(it) }
            return buildList {
                if (pinned != null) {
                    add(DecodeConfig(2, pinned, "2 threads pinned to $pinned"))
                    add(DecodeConfig(4, pinned, "4 threads pinned to $pinned"))
                } else {
                    // No cluster to pin to: still exercise two thread counts.
                    add(DecodeConfig(2, "", "2 threads unpinned"))
                    add(DecodeConfig(4, "", "4 threads unpinned"))
                }
                add(DecodeConfig(2, "", "2 threads unpinned"))
            }
        }

        /**
         * The `-tb` values the sweep tries: two threads, plus the fast-core count
         * when that is more than two (otherwise four, so there are always two
         * distinct values to compare).
         */
        fun prefillConfigs(fastClusterSize: Int): List<Int> {
            val fast = if (fastClusterSize >= 3) fastClusterSize else 4
            return listOf(2, fast).distinct()
        }

        /** Resolve the SoC key from the platform fields, independently of Android. */
        fun resolveSoc(sdkInt: Int, socModel: String?, hardware: String?): String =
            (if (sdkInt >= 31) socModel else null) ?: hardware ?: ""

        /** The stored profile for [soc] under the same engine [engineRev], or null. */
        fun lookup(json: String, soc: String, engineRev: String): TuningProfile? {
            val p = storedRaw(json, soc) ?: return null
            return p.takeIf { !it.isStale(engineRev) }
        }

        /** The stored profile for [soc] whatever engine it was measured against. */
        fun storedRaw(json: String, soc: String): TuningProfile? {
            if (json.isBlank() || soc.isBlank()) return null
            return try {
                JSONObject(json).optJSONObject(soc)?.let { TuningProfile.fromJson(it) }
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * Return [json] with [soc]'s stored keep-warm interval replaced by
         * [intervalS]. A pure helper so the measured-keep-warm adaptation is
         * unit tested without a device; the input is returned unchanged when
         * there is no profile for [soc] to update.
         */
        fun withKeepWarmInterval(json: String, soc: String, intervalS: Int): String =
            updateProfile(json, soc) { it.copy(keepWarmIntervalS = intervalS) }

        /**
         * Return [json] with [soc]'s stored context replaced by [contextSize].
         * The auto-sized context is recorded here so the profile explains why
         * the phone runs the context it does.
         */
        fun withContext(json: String, soc: String, contextSize: Int): String =
            updateProfile(json, soc) { it.copy(contextSize = contextSize) }

        private inline fun updateProfile(
            json: String,
            soc: String,
            transform: (TuningProfile) -> TuningProfile,
        ): String {
            val p = storedRaw(json, soc) ?: return json
            val root = try {
                JSONObject(json)
            } catch (_: Throwable) {
                return json
            }
            root.put(soc, transform(p).toJson())
            return root.toString()
        }

        /** A stored profile's engine revision when it differs from [engineRev]. */
        fun staleEngineRev(json: String, soc: String, engineRev: String): String? {
            val p = storedRaw(json, soc) ?: return null
            return p.engineRev.takeIf { it.isNotBlank() && it != engineRev }
        }

        /**
         * Pick the values to launch with. Precedence, highest first:
         * an operator override, a valid stored profile, the runtime heuristic.
         * Pure so the precedence is unit tested without a device.
         */
        fun decideEffective(
            userThreads: Int?,
            userCpuRange: String?,
            userPrefillThreads: Int?,
            profile: TuningProfile?,
            autoThreads: Int,
            autoCpuRange: String,
        ): EffectivePerf {
            val range = userCpuRange?.takeIf { PerfProfile.validCpuRange(it) }
                ?: profile?.decodeCpuRange?.takeIf { PerfProfile.validCpuRange(it) }
                ?: autoCpuRange
            return EffectivePerf(
                threads = (userThreads ?: profile?.decodeThreads ?: autoThreads).coerceIn(1, 16),
                cpuRange = range,
                prefillThreads = (userPrefillThreads ?: profile?.prefillThreads ?: 0).coerceIn(0, 16),
            )
        }

        /**
         * Reduce a set of measured configs to the best of each, producing the
         * profile to store. Pure so it can be unit tested without a device.
         */
        fun best(
            soc: String,
            decodeCpuRange: String,
            contextSize: Int,
            keepWarmIntervalS: Int,
            decode: List<Pair<Int, Double>>,
            prefill: List<Pair<Int, Double>>,
            engineRev: String = ENGINE_REV,
        ): TuningProfile? {
            val bestDecode = decode.filter { it.second > 0.0 }.maxByOrNull { it.second }
                ?: return null
            val bestPrefill = prefill.filter { it.second > 0.0 }.maxByOrNull { it.second }
                ?: bestDecode
            return TuningProfile(
                soc = soc,
                decodeThreads = bestDecode.first,
                decodeCpuRange = decodeCpuRange,
                prefillThreads = bestPrefill.first,
                contextSize = contextSize,
                keepWarmIntervalS = keepWarmIntervalS,
                engineRev = engineRev,
                measuredAt = Instant.now().toString(),
                decodeTps = bestDecode.second,
                prefillTps = bestPrefill.second,
            )
        }

        /** The fixed decode prompt the sweep measures against. */
        const val DECODE_PROMPT =
            "You are a concise technical writer. Explain what a KV cache is in a " +
                "local language model, why it makes long conversations cheaper, and " +
                "name one tradeoff. Answer in plain prose, without headings or lists."

        /** Tokens the decode sweep asks for; the fixed 128-token prompt. */
        const val DECODE_TOKENS = 128

        /**
         * The fixed ~600-token prompt the prefill sweep measures against. Prefill
         * throughput only shows up on a long prompt, so this is deliberately long
         * and repeated to make the token count stable across runs.
         */
        val PREFILL_PROMPT: String = buildString {
            repeat(24) {
                append(
                    "The quick brown fox jumps over the lazy dog while the diligent " +
                        "engineer measures prompt processing throughput on a phone that " +
                        "sits on a desk. ",
                )
            }
        }.trim()

        /** Tokens the prefill sweep asks the engine to generate: prefill only. */
        const val PREFILL_TOKENS = 8
    }
}

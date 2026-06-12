package com.chezgoulet.phonon.accel

/**
 * BackendPlanner decides which accelerator backends to attempt, in order,
 * when initializing the LiteRT-LM engine.
 *
 * This is deliberately pure logic with no Android or LiteRT imports so it
 * can be unit-tested on the JVM. The hardware-touching counterpart is
 * [BackendFactory], which maps the names produced here onto actual LiteRT-LM
 * Backend instances.
 *
 * Selection rules:
 *  - An explicit request ("npu", "gpu", "cpu") is honored first, but always
 *    falls back toward CPU so a misconfigured group degrades to slow rather
 *    than dead. The active backend is reported in heartbeats, so the
 *    operator can see the degradation on the dashboard.
 *  - "auto" (or empty/unknown) builds the chain from hardware capability:
 *    NPU is only attempted on SoCs known to have a LiteRT-supported
 *    accelerator path; GPU is attempted broadly (OpenCL/GL delegates are
 *    near-universal on API 29+); CPU is always last and always present.
 */
object BackendPlanner {

    /** Capability snapshot of the device, derivable from android.os.Build. */
    data class DeviceInfo(
        /** Build.SOC_MODEL on API 31+, e.g. "Tensor G3"; empty if unknown. */
        val socModel: String,
        /** Build.HARDWARE, e.g. "zuma", "shusky"; empty if unknown. */
        val hardware: String,
        /** Build.VERSION.SDK_INT */
        val apiLevel: Int,
    )

    /**
     * SoC families with a known-good LiteRT NPU path.
     *
     * Conservative by design: an entry here only means "worth attempting" —
     * initialization failure still falls through to the next backend. Grow
     * this list from community reports (see docs/NPU_ACCELERATION.md).
     */
    private val npuCapableSocPrefixes = listOf(
        "tensor",      // Google Tensor G1–G5 (Pixel 6+) — Edge TPU
        "sm8",         // Qualcomm Snapdragon 8-series — Hexagon (QNN)
        "sm7",         // Qualcomm Snapdragon 7-series — Hexagon (QNN)
        "snapdragon",  // Some OEMs report the marketing name
    )

    /** Hardware board names that imply a Tensor SoC when SOC_MODEL is empty (API < 31). */
    private val tensorBoards = listOf(
        "slider", "raven", "oriole",          // Pixel 6 family (Tensor G1)
        "cloudripper", "panther", "cheetah",  // Pixel 7 family (G2)
        "lynx",                               // Pixel 7a (G2)
        "zuma", "shiba", "husky", "akita",    // Pixel 8 family (G3)
        "zumapro", "caiman", "komodo", "tokay", // Pixel 9 family (G4)
    )

    /** Returns true if the device plausibly has a LiteRT-reachable NPU. */
    fun hasNpuPath(info: DeviceInfo): Boolean {
        val soc = info.socModel.lowercase()
        if (soc.isNotEmpty() && npuCapableSocPrefixes.any { soc.startsWith(it) }) return true
        val hw = info.hardware.lowercase()
        return tensorBoards.any { hw == it || hw.startsWith(it) }
    }

    /**
     * Produces the ordered list of backend names to attempt.
     *
     * Always ends with "cpu" and never contains duplicates. [requested] is
     * the coordinator's `backend` field from the model_load payload; null,
     * empty, or unrecognized values are treated as "auto".
     */
    fun candidates(requested: String?, info: DeviceInfo): List<String> {
        val req = requested?.trim()?.lowercase().orEmpty()

        val chain = when (req) {
            "cpu" -> listOf("cpu")
            "gpu" -> listOf("gpu", "cpu")
            "npu" -> listOf("npu", "gpu", "cpu")
            else -> { // "auto", empty, or unknown
                buildList {
                    if (hasNpuPath(info)) add("npu")
                    add("gpu")
                    add("cpu")
                }
            }
        }
        return chain.distinct()
    }
}

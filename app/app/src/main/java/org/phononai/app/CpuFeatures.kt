package org.phononai.app

import java.io.File

/**
 * The CPU's instruction-set features as the kernel reports them.
 *
 * The app must reason about this for two reasons: it tells an operator (in
 * /health and the device report) which llama.cpp kernels will actually run, and
 * a build that was compiled for one ISA level must refuse to start on a phone
 * that lacks the features it needs instead of dying with SIGILL inside the
 * child process.
 *
 * Features come from the union of the `Features` lines in /proc/cpuinfo. ARM64
 * names the interesting ones: `asimddp` is the dot-product instruction (used by
 * llama.cpp's Q4_K repack path), `i8mm` is the int8 matrix multiply, `asimdhp`
 * and `fphp` are fp16 vector arithmetic. When the file cannot be read the answer
 * is "unknown", never "absent": a guard must not refuse a phone it cannot judge.
 */
data class CpuFeatures(
    val raw: Set<String>,
    /** False when /proc/cpuinfo gave no Features line at all. */
    val known: Boolean,
) {
    val dotprod: Boolean get() = raw.contains("asimddp")
    val i8mm: Boolean get() = raw.contains("i8mm")
    val fp16: Boolean get() = raw.contains("asimdhp") || raw.contains("fphp")
    val sve: Boolean get() = raw.contains("sve") || raw.contains("sve2")
    val sme: Boolean get() = raw.contains("sme")

    /**
     * The best llama.cpp CPU backend this CPU can use, by the naming scheme in
     * ggml's Android variant list. Advisory only: llama.cpp makes the real choice
     * at load time by scoring each shipped backend against these same features.
     */
    val expectedBackend: String
        get() = when {
            !known -> "unknown"
            sme && sve && i8mm && dotprod -> "android_armv9.2"
            sve && i8mm && dotprod -> "android_armv9.0"
            i8mm && dotprod -> "android_armv8.6_1"
            dotprod && fp16 -> "android_armv8.2_2"
            dotprod -> "android_armv8.2_1"
            else -> "android_armv8.0_1"
        }

    /** Human-readable list for the report, in the resource's own words. */
    val summary: String
        get() = if (!known) "unknown" else buildList {
            add(if (dotprod) "dotprod" else "no dotprod")
            if (i8mm) add("i8mm")
            if (fp16) add("fp16")
            if (sve) add("sve")
            if (sme) add("sme")
        }.joinToString(", ")

    companion object {
        fun read(): CpuFeatures = parse(readCpuInfo())

        // /proc/cpuinfo cannot change while the app runs; read it once. The guard
        // runs on every launch, so re-reading it each time would be waste.
        private val cached: CpuFeatures by lazy { read() }

        /** The process-wide feature set, parsed on first use. */
        fun cached(): CpuFeatures = cached

        fun parse(cpuinfo: String?): CpuFeatures {
            if (cpuinfo.isNullOrBlank()) return CpuFeatures(emptySet(), known = false)
            val features = mutableSetOf<String>()
            var sawLine = false
            for (line in cpuinfo.lineSequence()) {
                // Try to keep pre-2.6.29 format and the current one; only the
                // "Features" key carries the aarch64 ISA list we care about.
                if (!line.startsWith("Features")) continue
                val colon = line.indexOf(':')
                if (colon < 0) continue
                sawLine = true
                line.substring(colon + 1).trim().split(' ')
                    .filter { it.isNotBlank() }
                    .forEach { features.add(it.lowercase()) }
            }
            return CpuFeatures(features, known = sawLine)
        }

        private fun readCpuInfo(): String? = try {
            File("/proc/cpuinfo").readText()
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * The instruction-set features the *shipped native build* requires to run at all.
 *
 * A build made with GGML_CPU_ALL_VARIANTS + GGML_BACKEND_DL carries a backend for
 * every ARMv8 level, so the required set is empty and the guard never fires. A
 * build compiled for a single ISA (the old `-march=armv8.2-a+dotprod+fp16`) must
 * declare what it needs; that set arrives through the `phonon.requiredCpuFeatures`
 * Gradle property and is baked into [BuildConfig]. Check [refusal] before
 * launching the child: an unmet requirement is a message, not a SIGILL.
 */
object NativeRequirements {

    /** Feature tokens (BuildConfig spelling) mapped to the kernel they gate. */
    val required: Set<String> = BuildConfig.NATIVE_REQUIRED_CPU_FEATURES
        .split(',')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    /** Required features the CPU positively lacks, in declaration order. */
    fun missing(features: CpuFeatures): List<String> = missingFor(required, features)

    /** [missing] against an explicit requirement set; used by the unit tests. */
    fun missingFor(required: Set<String>, features: CpuFeatures): List<String> {
        // An unknown CPU is never declared broken; only a positive "absent"
        // (a parsed feature list without the token) counts.
        if (!features.known) return emptyList()
        return required.filter { !has(features, it) }
    }

    private fun has(features: CpuFeatures, token: String): Boolean = when (token) {
        "dotprod", "asimddp" -> features.dotprod
        "i8mm" -> features.i8mm
        "fp16", "asimdhp" -> features.fp16
        "sve" -> features.sve
        "sme" -> features.sme
        // An unrecognised token cannot be judged; do not refuse on it.
        else -> true
    }

    /**
     * The operator-facing refusal for a build whose kernels the phone cannot run.
     * Null when the build may start.
     */
    fun refusal(features: CpuFeatures): String? = refusalFor(required, features)

    /** [refusal] against an explicit requirement set; used by the unit tests. */
    fun refusalFor(required: Set<String>, features: CpuFeatures): String? {
        val missing = missingFor(required, features)
        if (missing.isEmpty()) return null
        return if (missing.any { it == "dotprod" || it == "asimddp" }) {
            "this phone is missing the dot-product instructions this build needs"
        } else {
            "this phone is missing the ${missing.joinToString(", ")} instructions this build needs"
        }
    }

    /** Optional separate line for /health, empty when nothing is required. */
    val requiredLabel: String get() = required.joinToString(",")
}

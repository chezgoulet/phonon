package org.phononai.app

import java.io.File

/**
 * One CPU cluster as the kernel sees it: a contiguous run of cores with the
 * same maximum frequency. On a Pixel 7 the three clusters are 0–3 @1.80 GHz,
 * 4–5 @2.35 GHz and 6–7 @2.85 GHz.
 */
data class CpuCluster(val cpus: List<Int>, val maxFreqKhz: Long) {
    val size: Int get() = cpus.size
    val first: Int get() = cpus.firstOrNull() ?: 0
    val last: Int get() = cpus.lastOrNull() ?: 0
    val range: String get() = if (cpus.isEmpty()) "" else "$first-$last"
    val isBig: Boolean get() = maxFreqKhz >= BIG_FREQ_KHZ

    companion object {
        /** Cores at or above this are the "big" cluster on current Tensor SoCs. */
        const val BIG_FREQ_KHZ = 2_400_000L
    }
}

/** What the process may run on right now, and what that implies. */
data class CpuDetection(
    val allowed: List<Int>,
    val clusters: List<CpuCluster>,
    val best: CpuCluster?,
    /** True when the allowed set still contains the SoC's fastest cluster. */
    val visible: Boolean,
) {
    val allowedRange: String
        get() = if (allowed.isEmpty()) "" else "${allowed.first()}-${allowed.last()}"
}

/**
 * The performance profile actually used to launch `llama-server`.
 *
 * A field is "auto" when the operator has not overridden it. The profile reads
 * the kernel every time: cpusets are per-process and change when Android moves
 * the app between foreground and top-app, so a cached answer would go stale.
 */
data class PerfPlan(
    val allowed: List<Int>,
    val mode: String,
    val cluster: List<Int>,
    val cpuRange: String,
    val threads: Int,
    val prio: Int,
    val prioBatch: Int,
    val threadsSet: Boolean,
    val cpuRangeSet: Boolean,
    val prioSet: Boolean,
) {
    val auto: Boolean get() = !threadsSet && !cpuRangeSet && !prioSet
    val visible: Boolean get() = mode == MODE_VISIBLE
    val cpuRangeLabel: String get() = cpuRange.ifBlank { "all allowed" }
    val modeLabel: String get() = if (visible) "Visible / kiosk" else "Background"

    /** The one line the Serve screen and the Settings card both use. */
    val summary: String
        get() = if (visible) {
            "Visible mode · all 8 cores · $threads threads on $cpuRangeLabel"
        } else {
            "Background mode · ${allowed.size} cores, no X1 · $threads threads on $cpuRangeLabel"
        }

    companion object {
        const val MODE_VISIBLE = "visible"
        const val MODE_BACKGROUND = "background"
        const val DEFAULT_PRIO = 1
        const val DEFAULT_PRIO_BATCH = 2
    }
}

/**
 * Reads the process's cpuset and the per-core maximum frequencies, then picks
 * the fastest cluster *within the allowed set*.
 *
 * This is deliberately about the allowed set, not the whole SoC: a foreground
 * service on a Pixel 7 is confined to CPUs 0–5 and can never schedule on the
 * X1s. Pinning four threads there spreads them across little cores; pinning two
 * to 4–5 keeps the whole generation on the 2.35 GHz cluster.
 */
object PerfProfile {

    fun detect(
        statusText: String? = readStatus(),
        freqReader: (Int) -> Long = ::readMaxFreqKhz,
    ): CpuDetection {
        val allowed = parseAllowed(statusText ?: "")
        val clusters = clusterize(allowed, freqReader)
        val best = clusters.maxByOrNull { it.maxFreqKhz }
        // "Visible" means the allowed set still reaches the SoC's fastest core.
        // Read the frequencies of every CPU that exposes cpufreq, not just the
        // allowed ones: a background process must be able to tell that it has
        // lost the X1 cluster.
        val allFreqs = (0 until MAX_CPU).map(freqReader).filter { it > 0 }
        val socMax = allFreqs.maxOrNull() ?: 0L
        val visible = if (best == null) {
            false
        } else if (socMax > 0) {
            best.maxFreqKhz >= socMax
        } else {
            // No cpufreq nodes: fall back to "can this process see every core?"
            allowed.size >= 8
        }
        return CpuDetection(allowed, clusters, best, visible)
    }

    private fun clusterize(allowed: List<Int>, freqReader: (Int) -> Long): List<CpuCluster> {
        if (allowed.isEmpty()) return emptyList()
        val out = mutableListOf<CpuCluster>()
        var run = mutableListOf<Int>()
        var freq = freqReader(allowed.first())
        for (cpu in allowed) {
            val f = freqReader(cpu)
            // Contiguity matters: 0–5 split as 0–3 / 4–5 must be two clusters.
            if (run.isNotEmpty() && (cpu != run.last() + 1 || f != freq)) {
                out.add(CpuCluster(run.toList(), freq))
                run = mutableListOf()
            }
            if (run.isEmpty()) freq = f
            run.add(cpu)
        }
        if (run.isNotEmpty()) out.add(CpuCluster(run.toList(), freq))
        return out
    }

    /** `0-5`, `0,2-3`, `7`. Empty or malformed text yields an empty list. */
    fun parseCpuList(text: String): List<Int> {
        val raw = text.substringAfter(':', text).trim()
        if (raw.isEmpty()) return emptyList()
        val out = mutableListOf<Int>()
        for (part in raw.split(',')) {
            val piece = part.trim()
            if (piece.isEmpty()) continue
            val dash = piece.indexOf('-')
            if (dash < 0) {
                piece.toIntOrNull()?.let { out.add(it) }
            } else {
                val lo = piece.substring(0, dash).trim().toIntOrNull() ?: continue
                val hi = piece.substring(dash + 1).trim().toIntOrNull() ?: continue
                if (hi < lo || hi - lo > 64) continue
                for (cpu in lo..hi) out.add(cpu)
            }
        }
        return out.distinct().sorted()
    }

    /** Read `Cpus_allowed_list` from a status file body. */
    fun parseAllowed(statusText: String): List<Int> =
        parseCpuList(statusText.lineSequence().firstOrNull {
            it.startsWith("Cpus_allowed_list:")
        } ?: "")

    private fun readStatus(): String? = try {
        File("/proc/self/status").readText()
    } catch (_: Throwable) {
        null
    }

    private fun readMaxFreqKhz(cpu: Int): Long = try {
        File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
            .readText().trim().toLong()
    } catch (_: Throwable) {
        0L
    }

    /** Loose validation for an operator-entered `--cpu-range` value. */
    fun validCpuRange(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return false
        return v.split(',').all { piece ->
            piece.trim().matches(Regex("""\d+(-\d+)?"""))
        } && parseCpuList(v).isNotEmpty()
    }

    const val MAX_CPU = 16
}

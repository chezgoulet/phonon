package org.phononai.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The `devices/<soc>.yaml` contribution a phone produces about itself.
 *
 * The shape is fixed by `devices/panther.yaml` and the field list in
 * `devices/README.md`: a new field needs a reason, so this renderer emits
 * exactly those keys, in that order. `null` means *unmeasured* and is never an
 * estimate. Everything here is pure so the YAML is unit tested on the JVM.
 */
object DeviceYaml {

    /** Always a candidate: a phone contributing itself is not a review. */
    const val STATUS_CANDIDATE = "candidate"

    /** One phone contributes one unit. */
    const val UNITS_SELF = 1

    const val RUNTIME = "llama.cpp"

    data class Measured(
        val prefillTps: Double? = null,
        val decodeTps: Double? = null,
        val ttftS: Double? = null,
        val after5minTps: Double? = null,
        val after15minTps: Double? = null,
        val peakMemMb: Long? = null,
        val batteryTempC: Double? = null,
    )

    data class Report(
        val device: String,
        val codename: String,
        val soc: String,
        val ramGb: Int,
        val android: Int,
        val rooted: Boolean,
        val runtime: String = RUNTIME,
        val runtimeBuild: String? = null,
        val model: String,
        val context: Int,
        val threads: Int,
        val mtp: Boolean = false,
        val status: String = STATUS_CANDIDATE,
        val units: Int = UNITS_SELF,
        val measured: Measured = Measured(),
        val notes: String = "",
    ) {
        /** The file name this report belongs under, relative to the repo root. */
        val fileName: String get() = "devices/${slug(soc)}.yaml"
    }

    /** Render a report as YAML matching `devices/panther.yaml` exactly. */
    fun render(r: Report): String = buildString {
        appendLine("device: ${scalar(r.device)}")
        appendLine("codename: ${scalar(r.codename)}")
        appendLine("soc: ${scalar(r.soc)}")
        appendLine("ram_gb: ${r.ramGb}")
        appendLine("android: ${r.android}")
        appendLine("rooted: ${r.rooted}")
        appendLine("runtime: ${scalar(r.runtime)}")
        appendLine("runtime_build: ${r.runtimeBuild?.takeIf { it.isNotBlank() }?.let(::scalar) ?: "null"}")
        appendLine("model: ${scalar(r.model)}")
        appendLine("context: ${r.context}")
        appendLine("threads: ${r.threads}")
        appendLine("mtp: ${r.mtp}")
        appendLine("status: ${scalar(r.status)}")
        appendLine("units: ${r.units}")
        appendLine("measured:")
        appendLine("  prefill_tps: ${decimal(r.measured.prefillTps)}")
        appendLine("  decode_tps: ${decimal(r.measured.decodeTps)}")
        appendLine("  ttft_s: ${decimal(r.measured.ttftS)}")
        appendLine("  after_5min_tps: ${decimal(r.measured.after5minTps)}")
        appendLine("  after_15min_tps: ${decimal(r.measured.after15minTps)}")
        appendLine("  peak_mem_mb: ${r.measured.peakMemMb ?: "null"}")
        appendLine("  battery_temp_c: ${decimal(r.measured.batteryTempC, 1)}")
        appendLine("notes: ${block(r.notes)}")
    }

    /** Two decimals, `null` when unmeasured; never an estimate. */
    private fun decimal(value: Double?, places: Int = 2): String =
        value?.let { String.format(Locale.US, "%.${places}f", it) } ?: "null"

    /**
     * A YAML scalar. Plain when it is safe; single-quoted (with `'` doubled) when
     * it is empty or carries a character that would change the parse.
     */
    fun scalar(value: String): String {
        if (value.isEmpty()) return "''"
        val risky = value.first().isWhitespace() || value.last().isWhitespace() ||
            value.any { it in "#:{}[],&*!|>'\"%@`\n\t" }
        return if (risky) "'${value.replace("'", "''")}'" else value
    }

    /** A folded block scalar (`>-`) for the notes, or `''` when empty. */
    private fun block(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "''"
        val body = trimmed.lines().joinToString("\n") { line ->
            if (line.isBlank()) "" else "  ${line.trimEnd()}"
        }
        return ">-\n$body"
    }

    /** `google-tensor-g2` — a filesystem- and repo-safe name for the SoC. */
    fun slug(soc: String): String =
        soc.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "unknown" }

    /**
     * The SoC block the device report carries: this device's core layout with
     * frequencies, CPU features, RAM and the thermal signal in use. Pure so it
     * can be built without a device in a unit test.
     */
    fun socJson(
        soc: String,
        codename: String,
        hardware: String,
        ramGb: Int,
        ramTotalBytes: Long,
        android: Int,
        sdkInt: Int,
        cpuFeatures: String,
        clusters: List<String>,
        allowedCpus: String,
        thermalSource: String,
    ): JSONObject = JSONObject().apply {
        put("soc", soc)
        put("codename", codename)
        put("hardware", hardware)
        put("ram_gb", ramGb)
        put("ram_total_bytes", ramTotalBytes)
        put("android", android)
        put("sdk_int", sdkInt)
        put("cpu_features", cpuFeatures)
        put("clusters", JSONArray(clusters))
        put("allowed_cpus", allowedCpus)
        put("thermal_source", thermalSource)
    }
}

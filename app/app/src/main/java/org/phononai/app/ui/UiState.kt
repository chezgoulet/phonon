package org.phononai.app.ui

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import org.phononai.app.PhononService
import org.phononai.app.Settings
import org.phononai.app.PerfPlan
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Collections

data class Health(
    val state: String,
    val model: String,
    val pid: Int?,
    val uptimeS: Long?,
    val batteryTempC: Float?,
    val thermalPaused: Boolean,
    val pauseReason: String?,
    val batteryLevel: Int?,
    val batteryCharging: Boolean?,
    val batteryCycles: Int?,
    val lastDecodeTps: Double?,
    val lastPromptTps: Double?,
    val restartCount: Int,
    val bindAddress: String,
    val apiKeySet: Boolean,
    val exposedOffHost: Boolean,
    val backendPort: Int,
    val loadMode: String,
    val error: String?,
    val keepWarm: Boolean,
    val keepWarmIntervalS: Int,
    val requestsInFlight: Int,
    val lastCompletionAt: Long?,
    val requestsCompleted: Int,
    val perfMode: String,
    val perfAllowedCpus: String,
    val perfCpuRange: String,
    val perfThreads: Int,
    val perfPrio: Int,
    val perfPrioBatch: Int,
    val perfAuto: Boolean,
    val perfOverrides: String,
    val perfDetectedMode: String,
    val perfDetectedCpuRange: String,
    val perfDetectedThreads: Int,
    val perfHintActive: Boolean,
    val perfHintTargetMs: Double?,
    val perfHintDetail: String,
) {
    /** Mode the running child was launched in; falls back to detection. */
    val perfVisible: Boolean get() = perfMode == PerfPlan.MODE_VISIBLE
    val perfDetectedVisible: Boolean get() = perfDetectedMode == PerfPlan.MODE_VISIBLE

    /** One compact line for the Serve screen. */
    val perfLine: String
        get() = if (perfVisible) {
            "Visible / kiosk · all 8 cores · $perfThreads threads on $perfCpuRange"
        } else {
            "Background · no X1 · $perfThreads threads on $perfCpuRange"
        }
}

fun parseHealth(raw: String): Health {
    val j = JSONObject(raw)
    return Health(
        state = j.optString("state", "loading"),
        model = j.optString("model", ""),
        pid = j.optIntOrNull("pid"),
        uptimeS = j.optLongOrNull("uptime_s"),
        batteryTempC = j.optFloatOrNull("battery_temp_c"),
        thermalPaused = j.optBoolean("thermal_paused"),
        pauseReason = if (j.isNull("pause_reason")) null else j.optString("pause_reason").ifBlank { null },
        batteryLevel = j.optIntOrNull("battery_level"),
        batteryCharging = if (j.has("battery_charging")) j.optBoolean("battery_charging") else null,
        batteryCycles = j.optIntOrNull("battery_cycles"),
        lastDecodeTps = j.optDoubleOrNull("last_decode_tps"),
        lastPromptTps = j.optDoubleOrNull("last_prompt_tps"),
        restartCount = j.optInt("restart_count"),
        bindAddress = j.optString("bind_address", Settings.DEFAULT_BIND_ADDRESS),
        apiKeySet = j.optBoolean("api_key_set"),
        exposedOffHost = j.optBoolean("exposed_off_host"),
        backendPort = j.optInt("backend_port", 8081),
        loadMode = j.optString("load_mode", "mmap"),
        error = if (j.isNull("error")) null else j.optString("error").ifBlank { null },
        keepWarm = j.optBoolean("keep_warm"),
        keepWarmIntervalS = j.optInt("keep_warm_interval_s", 45),
        requestsInFlight = j.optInt("requests_in_flight", 0),
        lastCompletionAt = j.optLongOrNull("last_completion_at"),
        requestsCompleted = j.optInt("requests_completed", 0),
        perfMode = j.optString("perf_mode", PerfPlan.MODE_BACKGROUND),
        perfAllowedCpus = j.optString("perf_allowed_cpus", ""),
        perfCpuRange = j.optString("perf_cpu_range", ""),
        perfThreads = j.optInt("perf_threads", 4),
        perfPrio = j.optInt("perf_prio", PerfPlan.DEFAULT_PRIO),
        perfPrioBatch = j.optInt("perf_prio_batch", PerfPlan.DEFAULT_PRIO_BATCH),
        perfAuto = j.optBoolean("perf_auto", true),
        perfOverrides = j.optString("perf_overrides", ""),
        perfDetectedMode = j.optString("perf_detected_mode", PerfPlan.MODE_BACKGROUND),
        perfDetectedCpuRange = j.optString("perf_detected_cpu_range", ""),
        perfDetectedThreads = j.optInt("perf_detected_threads", 4),
        perfHintActive = j.optBoolean("perf_hint_active", false),
        perfHintTargetMs = j.optDoubleOrNull("perf_hint_target_ms"),
        perfHintDetail = j.optString("perf_hint_detail", ""),
    )
}

data class DownloadProgress(
    val active: Boolean,
    val done: Boolean,
    val bytes: Long,
    val total: Long,
    val error: String?,
    val dest: String,
)

fun parseDownload(raw: String): DownloadProgress {
    val j = JSONObject(raw)
    return DownloadProgress(
        active = j.optBoolean("active"),
        done = j.optBoolean("done"),
        bytes = j.optLong("bytes", 0),
        total = if (j.isNull("total")) -1 else j.optLong("total", -1),
        error = if (j.isNull("error")) null else j.optString("error").ifBlank { null },
        dest = j.optString("dest", ""),
    )
}

data class BenchmarkRun(
    val timestamp: String,
    val load: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val prefillTps: Double,
    val decodeTps: Double,
    val ttftMs: Long,
    val totalMs: Long,
    val peakRssMb: Long,
    val batteryTempC: Float?,
    val context: Int,
    val threads: Int,
)

data class BenchmarkState(
    val running: Boolean,
    val error: String?,
    val last: BenchmarkRun?,
    val history: List<BenchmarkRun>,
)

fun parseBenchmark(raw: String): BenchmarkState {
    val j = JSONObject(raw)
    fun run(o: JSONObject?) = o?.let {
        BenchmarkRun(
            timestamp = it.optString("timestamp", ""),
            load = it.optString("load", ""),
            model = it.optString("model", ""),
            promptTokens = it.optInt("prompt_tokens"),
            completionTokens = it.optInt("completion_tokens"),
            prefillTps = it.optDouble("prefill_tps"),
            decodeTps = it.optDouble("decode_tps"),
            ttftMs = it.optLong("ttft_ms"),
            totalMs = it.optLong("total_ms"),
            peakRssMb = it.optLong("peak_rss_mb"),
            batteryTempC = it.optFloatOrNull("battery_temp_c"),
            context = it.optInt("context"),
            threads = it.optInt("threads"),
        )
    }
    val history = mutableListOf<BenchmarkRun>()
    val array = j.optJSONArray("history")
    if (array != null) {
        for (i in 0 until array.length()) {
            run(array.optJSONObject(i))?.let { history.add(it) }
        }
    }
    return BenchmarkState(
        running = j.optBoolean("running"),
        error = if (j.isNull("error")) null else j.optString("error").ifBlank { null },
        last = run(j.optJSONObject("last")),
        history = history,
    )
}

data class InstalledModel(
    val file: File,
    val active: Boolean,
) {
    val name: String get() = file.name
    val sizeBytes: Long get() = file.length()
}

fun installedModels(context: Context, activePath: String): List<InstalledModel> {
    val dir = File(File(context.filesDir, "models").absolutePath)
    val active = try {
        File(activePath).canonicalPath
    } catch (_: Throwable) {
        activePath
    }
    return dir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
        ?.sortedWith(compareByDescending<File> { it.name == File(activePath).name }
            .thenBy { it.name.lowercase() })
        ?.map { file ->
            val canonical = try {
                file.canonicalPath
            } catch (_: Throwable) {
                file.absolutePath
            }
            InstalledModel(file, canonical == active)
        }
        ?: emptyList()
}

fun modelStorageBytes(models: List<InstalledModel>): Long = models.sumOf { it.sizeBytes }

fun localIpv4(): String? = try {
    Collections.list(NetworkInterface.getNetworkInterfaces())
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { Collections.list(it.inetAddresses).asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        ?.hostAddress
} catch (_: Throwable) {
    null
}

fun endpointHost(health: Health?, context: Context): String {
    val bind = health?.bindAddress ?: Settings(context).bindAddress
    // 0.0.0.0 is not a connectable address; show the phone's LAN address.
    // A loopback bind is shown as loopback, because it is not reachable off-host.
    return if (bind == Settings.BIND_ANY) localIpv4() ?: "127.0.0.1" else bind
}

fun endpointUrl(health: Health?, context: Context): String {
    val port = Settings(context).port
    return "http://${endpointHost(health, context)}:$port/v1"
}

fun generateApiKey(): String {
    val bytes = ByteArray(24)
    SecureRandom().nextBytes(bytes)
    val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    return "sk-phonon-" + token.take(32)
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

fun formatUptime(seconds: Long?): Pair<String, String> {
    if (seconds == null) return "—" to ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> h.toString() to "h ${m}m"
        m > 0 -> m.toString() to "m ${s}s"
        else -> s.toString() to "s"
    }
}

fun formatTemp(temp: Float?): String = if (temp == null) "—" else "%.1f °C".format(temp)

fun modelLabel(name: String): String =
    name.removeSuffix(".gguf").removeSuffix(".GGUF")

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key)) null else optInt(key)

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key)) null else optLong(key)

private fun JSONObject.optFloatOrNull(key: String): Float? =
    if (isNull(key)) null else optDouble(key).toFloat()

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

fun serviceOrNull(): PhononService? = org.phononai.app.AppState.service

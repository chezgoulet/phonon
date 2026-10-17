package org.phononai.app.ui

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import org.phononai.app.PhononService
import org.phononai.app.Settings
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
)

fun parseHealth(raw: String): Health {
    val j = JSONObject(raw)
    return Health(
        state = j.optString("state", "loading"),
        model = j.optString("model", ""),
        pid = j.optIntOrNull("pid"),
        uptimeS = j.optLongOrNull("uptime_s"),
        batteryTempC = j.optFloatOrNull("battery_temp_c"),
        thermalPaused = j.optBoolean("thermal_paused"),
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

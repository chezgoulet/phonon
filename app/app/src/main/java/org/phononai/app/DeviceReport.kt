package org.phononai.app

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.Instant

data class DeviceReportResult(
    val text: String,
    val json: String,
    val textPath: String,
    val jsonPath: String,
)

/**
 * The one-button device report. Everything here is measured or read from the
 * device; the API key is never read into the report, only "set" or "not set".
 *
 * The text form is what ACTION_SEND shares. Both forms are also written to the
 * app's external files directory so they can be pulled with adb when the phone
 * has no share target.
 */
object DeviceReport {

    fun build(context: Context, settings: Settings, healthJson: String?): DeviceReportResult {
        val device = HardwareProfile.detect(context)
        val features = CpuFeatures.read()
        val health = try {
            healthJson?.let { JSONObject(it) }
        } catch (_: Throwable) {
            null
        }
        val model = File(settings.modelPath)
        val mmproj = settings.catalogEntryForCurrentModel()
            ?.let { entry -> File(model.parentFile, entry.mmprojFileName) }
            ?.takeIf { it.isFile }
        val fit = MemoryFit.check(context, model.length(), mmproj?.length() ?: 0L, settings.contextSize)
        val oem = OemGuidance.forDevice()
        val benchmarks = settings.benchmarkHistory
        // The same YAML the "Contribute this phone's profile" action produces,
        // plus the SoC block and the stored tuning profile, are part of the
        // report so one pull carries everything a reviewer needs.
        val field = FieldReporting.build(context, settings, healthJson)
        val opencl = GpuPrefill.present(context.applicationInfo.nativeLibraryDir)

        val json = JSONObject().apply {
            put("generated_at", Instant.now().toString())
            put("device", JSONObject().apply {
                put("model", device.model)
                put("soc", device.soc)
                put("hardware", device.hardware)
                put("android_release", device.androidRelease)
                put("sdk_int", device.sdkInt)
                put("abis", JSONArray(device.abis))
                put("ram_total_bytes", device.totalRamBytes)
                put("ram_gb", device.ramGb)
                put("tier", device.tier.id)
                put("tier_model", device.tier.modelLabel)
                put("tier_context", device.tier.contextSize)
                put("tier_threads", device.tier.threads)
                put("cpu_features", features.summary)
                put("cpu_dotprod", features.dotprod)
                put("cpu_i8mm", features.i8mm)
                put("cpu_expected_backend", features.expectedBackend)
                put("manufacturer", Build.MANUFACTURER ?: "")
            })
            put("soc", field.soc)
            put("tuning_profile", settings.storedTuning()?.toJson() ?: JSONObject.NULL)
            put("capabilities", JSONObject().apply {
                put("native_required_features", NativeRequirements.requiredLabel)
                put("missing_features", JSONArray(NativeRequirements.missing(features)))
                put("startup_refusal", NativeRequirements.refusal(features) ?: JSONObject.NULL)
            })
            put("memory_fit", JSONObject().apply {
                put("fits", fit.fits)
                put("required_bytes", fit.requiredBytes)
                put("available_bytes", fit.availBytes)
                put("kv_estimate_bytes", fit.kvBytes)
                put("model_bytes", fit.modelBytes)
            })
            put("thermal_policy", JSONObject().apply {
                put("source", health?.optString("thermal_source") ?: "unknown")
                put("detail", health?.optString("thermal_detail") ?: "unknown")
                put("user_override", settings.thermalUserOverride)
                put("headroom", health?.optDouble("thermal_headroom") ?: JSONObject.NULL)
                put("status", health?.optInt("thermal_status") ?: JSONObject.NULL)
                put("stop_c", settings.thermalStopC.toDouble())
                put("resume_c", settings.thermalResumeC.toDouble())
            })
            put("oem", JSONObject().apply {
                put("vendor", oem.vendor)
                put("steps", oem.steps)
                put("dont_kill_url", oem.dontKillUrl)
            })
            // Charging limit as the service last established it, plus the week's
            // exposure. Mode, source and hours only — no key, no serial.
            put("charge", JSONObject().apply {
                val charge = health?.optJSONObject("charge")
                put("mode", charge?.optString("mode") ?: "unknown")
                put("source", charge?.optString("source") ?: "none")
                put("confirmed", charge?.optBoolean("confirmed") ?: false)
                put("hours_above_80", charge?.optInt("hours_above_80") ?: 0)
                put("can_toggle", charge?.optBoolean("can_toggle") ?: false)
                put("oem_steps", OemGuidance.chargingForDevice().steps)
            })
            put("app", JSONObject().apply {
                put("package", context.packageName)
                put("version_name", BuildConfig.VERSION_NAME)
                put("version_code", BuildConfig.VERSION_CODE)
            })
            // Engine provenance: which llama.cpp this build carries and how
            // prompts are formatted. Both come from build-time constants via
            // EngineProvenance, never from a revision written in here.
            put("engine_rev", EngineProvenance.engineRev)
            put("template_mode", EngineProvenance.templateMode)
            put("template_source", EngineProvenance.templateSource)
            put("model", JSONObject().apply {
                put("file", model.name)
                put("sha256", sha256(model))
                put("size_bytes", model.length())
                put("context", settings.contextSize)
                put("threads", settings.threads)
                put("load_mode", settings.loadMode)
            })
            put("server", JSONObject().apply {
                put("state", health?.optString("state") ?: "unknown")
                put("bind_address", settings.bindAddress)
                put("port", settings.port)
                put("backend_port", settings.backendPort)
                put("alias", settings.alias)
                put("api_key_set", settings.apiKey.isNotBlank())
                put("keep_warm", settings.keepWarm)
                put("keep_warm_interval_s", settings.effectiveKeepWarmIntervalS())
                put("keep_warm_measured_s", settings.keepWarmAdaptedS)
                put("keep_warm_last_ttft_ms", health?.optLongOrNull("keep_warm_last_ttft_ms") ?: JSONObject.NULL)
                put("context_fit_label", health?.optString("context_fit_label")?.ifBlank { null } ?: "unknown")
                put("context_auto_sized", settings.contextAutoSized)
                put("thermal_stop_c", settings.thermalStopC.toDouble())
                put("thermal_resume_c", settings.thermalResumeC.toDouble())
                put("gateway_endpoint", gatewayEndpoint(context, settings, health))
            })
            put("gpu_prefill", JSONObject().apply {
                put("present", opencl)
                put("status", GpuPrefill.statusLine(opencl))
            })
            put("field_report", JSONObject().apply {
                put("file_name", field.fileName)
                put("path", field.path)
                put("yaml", field.yaml)
            })
            put("benchmarks", JSONArray().apply {
                benchmarks.forEach { run -> put(run as Any) }
            })
        }

        val socClusters = field.soc.optJSONArray("clusters")
            ?.let { arr -> (0 until arr.length()).joinToString(", ") { arr.optString(it) } }
            ?: "none"

        val text = buildString {
            appendLine("Phonon device report")
            appendLine("generated_at: ${json.optString("generated_at")}")
            appendLine()
            appendLine("device:")
            appendLine("  model: ${device.model}")
            appendLine("  soc: ${device.soc}")
            appendLine("  hardware: ${device.hardware}")
            appendLine("  android: ${device.androidRelease} (API ${device.sdkInt})")
            appendLine("  abis: ${device.abis.joinToString(", ")}")
            appendLine("  ram_total_bytes: ${device.totalRamBytes}")
            appendLine("  ram_gb: ${device.ramGb}")
            appendLine("  tier: ${device.tier.id} (${device.tier.modelLabel}, " +
                "${device.tier.contextSize} context, ${device.tier.threads} threads)")
            appendLine("  manufacturer: ${Build.MANUFACTURER ?: ""}")
            appendLine("  cpu_features: ${features.summary}")
            appendLine("  cpu_expected_backend: ${features.expectedBackend}")
            appendLine()
            appendLine("soc:")
            appendLine("  soc: ${field.soc.optString("soc")}")
            appendLine("  codename: ${field.soc.optString("codename")}")
            appendLine("  hardware: ${field.soc.optString("hardware")}")
            appendLine("  ram_gb: ${field.soc.optInt("ram_gb")}")
            appendLine("  clusters: $socClusters")
            appendLine("  allowed_cpus: ${field.soc.optString("allowed_cpus")}")
            appendLine("  cpu_features: ${field.soc.optString("cpu_features")}")
            appendLine("  thermal_source: ${field.soc.optString("thermal_source")}")
            appendLine()
            appendLine("tuning_profile: ${settings.storedTuning()?.toJson()?.toString() ?: "none"}")
            appendLine("field_report: ${field.fileName}")
            appendLine("field_report_path: ${field.path}")
            appendLine("gpu_prefill: ${GpuPrefill.statusLine(opencl)}")
            appendLine()
            appendLine("capabilities:")
            appendLine("  native_required_features: ${NativeRequirements.requiredLabel.ifBlank { "(none)" }}")
            appendLine("  missing_features: ${NativeRequirements.missing(features).joinToString(", ").ifBlank { "(none)" }}")
            appendLine("  startup_refusal: ${NativeRequirements.refusal(features) ?: "none"}")
            appendLine()
            appendLine("memory_fit:")
            appendLine("  fits: ${fit.fits}")
            appendLine("  required_bytes: ${fit.requiredBytes} (${MemoryFit.gb(fit.requiredBytes)} GB)")
            appendLine("  available_bytes: ${fit.availBytes} (${MemoryFit.gb(fit.availBytes)} GB)")
            appendLine("  kv_estimate_bytes: ${fit.kvBytes}")
            appendLine("  model_bytes: ${fit.modelBytes}")
            appendLine()
            appendLine("thermal_policy:")
            appendLine("  source: ${health?.optString("thermal_source") ?: "unknown"}")
            appendLine("  detail: ${health?.optString("thermal_detail") ?: "unknown"}")
            appendLine("  user_override: ${settings.thermalUserOverride}")
            appendLine("  stop_c: ${settings.thermalStopC} · resume_c: ${settings.thermalResumeC}")
            appendLine()
            appendLine("oem:")
            appendLine("  vendor: ${oem.vendor}")
            appendLine("  steps: ${oem.steps}")
            appendLine("  dont_kill_url: ${oem.dontKillUrl}")
            appendLine()
            appendLine("app:")
            appendLine("  package: ${context.packageName}")
            appendLine("  version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine()
            appendLine("engine:")
            appendLine("  engine_rev: ${EngineProvenance.engineRev}")
            appendLine("  template_mode: ${EngineProvenance.templateMode}")
            appendLine("  template_source: ${EngineProvenance.templateSource}")
            appendLine()
            appendLine("model:")
            appendLine("  file: ${model.name}")
            appendLine("  sha256: ${sha256(model)}")
            appendLine("  size_bytes: ${model.length()}")
            appendLine("  context: ${settings.contextSize}")
            appendLine("  threads: ${settings.threads}")
            appendLine("  load_mode: ${settings.loadMode}")
            appendLine()
            appendLine("server:")
            appendLine("  state: ${health?.optString("state") ?: "unknown"}")
            appendLine("  bind_address: ${settings.bindAddress}")
            appendLine("  port: ${settings.port}")
            appendLine("  backend_port: ${settings.backendPort}")
            appendLine("  alias: ${settings.alias}")
            appendLine("  api_key: ${if (settings.apiKey.isNotBlank()) "set" else "not set"}")
            appendLine("  keep_warm: ${settings.keepWarm}")
            appendLine("  keep_warm_interval_s: ${settings.effectiveKeepWarmIntervalS()}")
            appendLine("  keep_warm_measured_s: ${settings.keepWarmAdaptedS}")
            appendLine("  keep_warm_last_ttft_ms: ${health?.optLongOrNull("keep_warm_last_ttft_ms") ?: "null"}")
            appendLine("  context_fit_label: ${health?.optString("context_fit_label")?.ifBlank { null } ?: "unknown"}")
            appendLine("  context_auto_sized: ${settings.contextAutoSized}")
            appendLine("  thermal_stop_c: ${settings.thermalStopC}")
            appendLine("  thermal_resume_c: ${settings.thermalResumeC}")
            appendLine("  endpoint: ${gatewayEndpoint(context, settings, health)}")
            appendLine()
            appendLine("benchmarks:")
            if (benchmarks.isEmpty()) {
                appendLine("  (none)")
            } else {
                benchmarks.forEach { run ->
                    appendLine("  - timestamp: ${run.optString("timestamp")}")
                    appendLine("    load: ${run.optString("load")}")
                    appendLine("    model: ${run.optString("model")}")
                    appendLine("    prompt_tokens: ${run.optInt("prompt_tokens")}")
                    appendLine("    completion_tokens: ${run.optInt("completion_tokens")}")
                    appendLine("    prefill_tps: ${run.optDouble("prefill_tps")}")
                    appendLine("    decode_tps: ${run.optDouble("decode_tps")}")
                    appendLine("    ttft_ms: ${run.optLong("ttft_ms")}")
                    appendLine("    total_ms: ${run.optLong("total_ms")}")
                    appendLine("    peak_rss_mb: ${run.optLong("peak_rss_mb")}")
                    val battery = if (run.isNull("battery_temp_c")) {
                        "n/a"
                    } else {
                        "%.1f".format(run.optDouble("battery_temp_c"))
                    }
                    appendLine("    battery_temp_c: $battery")
                    appendLine("    context: ${run.optInt("context")} · threads: ${run.optInt("threads")}")
                }
            }
        }

        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        dir.mkdirs()
        val textFile = File(dir, "phonon-device-report.txt")
        val jsonFile = File(dir, "phonon-device-report.json")
        textFile.writeText(text)
        jsonFile.writeText(json.toString(2))
        return DeviceReportResult(text, json.toString(2), textFile.absolutePath, jsonFile.absolutePath)
    }

    /** What the gateway must point at; 0.0.0.0 is shown as the LAN address. */
    private fun gatewayEndpoint(context: Context, settings: Settings, health: JSONObject?): String {
        val bind = health?.optString("bind_address").takeUnless { it.isNullOrBlank() }
            ?: settings.bindAddress
        val host = when {
            bind == Settings.BIND_ANY -> localIpv4() ?: "127.0.0.1"
            else -> bind
        }
        return "http://$host:${settings.port}/v1"
    }

    private fun localIpv4(): String? = try {
        java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { java.util.Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    } catch (_: Throwable) {
        null
    }

    private val hashCache = HashMap<String, String>()
    @Synchronized
    private fun sha256(file: File): String {
        if (!file.isFile) return "missing"
        val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        hashCache[key]?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
        } catch (_: Throwable) {
            return "unreadable"
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        hashCache[key] = hex
        return hex
    }
}

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key) || !has(key)) null else optLong(key)


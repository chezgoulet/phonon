package org.phononai.app

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File

data class FieldReportResult(
    val yaml: String,
    val fileName: String,
    val path: String,
    val soc: JSONObject,
)

/**
 * Builds and saves the `devices/<soc>.yaml` contribution a phone makes about
 * itself, from live detection plus the measured tuning profile when one exists.
 *
 * Rendering is pure ([DeviceYaml]); this object only gathers the device's own
 * values and writes the file where `adb pull` can reach it. Nothing here is
 * invented: a figure the phone has not measured is `null`.
 */
object FieldReporting {

    fun build(context: Context, settings: Settings, healthJson: String?): FieldReportResult {
        val device = HardwareProfile.detect(context)
        val detection = PerfProfile.detect()
        val features = CpuFeatures.cached()
        val health = try {
            healthJson?.let { JSONObject(it) }
        } catch (_: Throwable) {
            null
        }
        val profile = settings.storedTuning()
        val lastRun = settings.benchmarkHistory.firstOrNull()
        val modelFile = File(settings.modelPath)
        val entry = settings.catalogEntryForCurrentModel()

        val measured = DeviceYaml.Measured(
            prefillTps = profile?.prefillTps?.takeIf { it > 0.0 }
                ?: lastRun?.optDouble("prefill_tps")?.takeIf { it > 0.0 },
            decodeTps = profile?.decodeTps?.takeIf { it > 0.0 }
                ?: lastRun?.optDouble("decode_tps")?.takeIf { it > 0.0 },
            // ttft and the sustained after-N-minute figures are never estimated:
            // they stay null until a run actually measured them.
            ttftS = null,
            after5minTps = null,
            after15minTps = null,
            peakMemMb = lastRun?.optLongOrNull("peak_rss_mb"),
            batteryTempC = health?.optDoubleOrNull("battery_temp_c"),
        )

        val notes = buildNotes(device, detection, features, health)
        val report = DeviceYaml.Report(
            device = Build.MODEL ?: "",
            codename = codename(),
            soc = device.soc,
            ramGb = device.ramGb,
            android = androidMajor(),
            rooted = RootCheck.present(),
            runtimeBuild = SocTuning.ENGINE_REV,
            // The catalog id is the model's identity in the repo; a bare file
            // name is all we have when the model is not a catalog entry.
            model = entry?.id ?: modelFile.name.removeSuffix(".gguf"),
            context = settings.contextSize,
            threads = settings.threads,
            measured = measured,
            notes = notes,
        )
        val yaml = DeviceYaml.render(report)

        val sosBlock = DeviceYaml.socJson(
            soc = device.soc,
            codename = codename(),
            hardware = device.hardware,
            ramGb = device.ramGb,
            ramTotalBytes = device.totalRamBytes,
            android = androidMajor(),
            sdkInt = device.sdkInt,
            cpuFeatures = features.summary,
            clusters = detection.clusters.map { it.label },
            allowedCpus = detection.allowedRange,
            thermalSource = health?.optString("thermal_source")?.ifBlank { null } ?: "unknown",
        )

        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        dir.mkdirs()
        val file = File(dir, "phonon-${DeviceYaml.slug(device.soc)}.yaml")
        file.writeText(yaml)
        return FieldReportResult(yaml, report.fileName, file.absolutePath, sosBlock)
    }

    /** A short, honest note phrased for a human reading the manifest. */
    private fun buildNotes(
        device: DeviceProfile,
        detection: CpuDetection,
        features: CpuFeatures,
        health: JSONObject?,
    ): String = buildString {
        append("Contributed by the app from live detection on ")
        append("${Build.MANUFACTURER ?: ""} ${Build.MODEL ?: ""}".trim())
        append(" (${device.soc}), ${device.ramGb} GB RAM, Android ${device.androidRelease}")
        append("). Core layout: ")
        append(detection.clusterLabel.ifBlank { "no cpufreq nodes reported" })
        append("; the process may use CPUs ${detection.allowedRange.ifBlank { "unknown" }}")
        append(" (${if (detection.visible) "visible/kiosk" else "background"} cpuset).")
        append(" CPU: ${features.summary}.")
        health?.optString("thermal_source")?.takeIf { it.isNotBlank() }?.let {
            append(" Thermal signal in use: $it.")
        }
        append(" Rooted: ${RootCheck.present()}.")
        append(" measured values come from the on-phone tuning sweep or the latest")
        append(" benchmark run; ttft_s and the sustained after-5/15-minute figures")
        append(" stay null until a run measures them.")
    }

    /** `panther`-style codename from Build.DEVICE, falling back to HARDWARE. */
    private fun codename(): String =
        (Build.DEVICE ?: "").ifBlank { Build.HARDWARE ?: "" }

    /** The Android major version as the manifest records it (e.g. 17). */
    private fun androidMajor(): Int =
        Build.VERSION.RELEASE?.substringBefore('.')?.toIntOrNull()
            ?: Build.VERSION.SDK_INT
}

/**
 * A deliberately shallow root check: the presence of a `su` binary in the usual
 * places. The app cannot prove a device is unrooted, but it should not claim
 * `rooted: false` on a phone that plainly ships `su` either. Best-effort.
 */
object RootCheck {
    private val PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/system/app/Superuser.apk",
    )

    fun present(): Boolean = PATHS.any { path ->
        try {
            File(path).exists()
        } catch (_: Throwable) {
            false
        }
    }
}

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key) || !has(key)) null else optLong(key)

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key) || !has(key)) null else optDouble(key).takeIf { !it.isNaN() }

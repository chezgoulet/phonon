package com.chezgoulet.phonon.health

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.chezgoulet.phonon.PhononApplication
import com.chezgoulet.phonon.coordinator.CoordinatorClient
import com.chezgoulet.phonon.models.HeartbeatRequest
import kotlinx.coroutines.*

/**
 * Periodically collects health telemetry and sends it to the coordinator.
 *
 * Reports every 60 seconds:
 * - Battery level, charging status, capacity %
 * - SoC temperature
 * - Storage free/total
 * - Currently loaded model (if any)
 * - Inference queue depth
 */
class HealthReporter(
    private val context: Context,
    private val coordinatorClient: CoordinatorClient,
    private val isModelRunning: () -> Boolean,
    private val activeBackend: () -> String? = { null },
    private val onTelemetry: ((batteryLevel: Double, batteryTemp: Double, isCharging: Boolean) -> Unit)? = null
) {
    private val tag = "HealthReporter"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val app: PhononApplication get() = context.applicationContext as PhononApplication

    private val intervalMs = 60_000L // 60 seconds

    // Cache last temperature to reduce sysfs reads
    private var lastTempC: Double = 0.0

    fun start() {
        scope.launch {
            while (isActive) {
                try {
                    val heartbeat = collectTelemetry()
                    coordinatorClient.sendHeartbeat(heartbeat)
                    onTelemetry?.invoke(heartbeat.batteryLevel, heartbeat.thermalTempC, heartbeat.batteryCharging)
                    Log.d(tag, "Heartbeat sent: battery=${heartbeat.batteryLevel}%")
                } catch (e: Exception) {
                    Log.w(tag, "Heartbeat error: ${e.message}")
                }
                delay(intervalMs)
            }
        }
    }

    private var lastTelemetry: HeartbeatRequest? = null

    fun stop() {
        scope.cancel()
    }

    /** Send a heartbeat immediately, collecting fresh telemetry. */
    fun forceSend() {
        try {
            val heartbeat = collectTelemetry()
            coordinatorClient.sendHeartbeat(heartbeat)
            onTelemetry?.invoke(heartbeat.batteryLevel, heartbeat.thermalTempC, heartbeat.batteryCharging)
            Log.i(tag, "Forced heartbeat sent: battery=${heartbeat.batteryLevel}%")
        } catch (e: Exception) {
            Log.w(tag, "Forced heartbeat error: ${e.message}")
        }
    }

    private fun collectTelemetry(): HeartbeatRequest {
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryLevel = level.toDouble() / scale.toDouble() * 100.0

        val charging = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let { status ->
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        } ?: false

        // Temperature from battery manager (tenths of °C)
        val tempRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        lastTempC = tempRaw / 10.0

        // Storage
        val statFs = StatFs(Environment.getDataDirectory().absolutePath)
        val blockSize = statFs.blockSizeLong
        val totalBlocks = statFs.blockCountLong
        val freeBlocks = statFs.availableBlocksLong

        val totalGb = (totalBlocks * blockSize) / (1024.0 * 1024.0 * 1024.0)
        val freeGb = (freeBlocks * blockSize) / (1024.0 * 1024.0 * 1024.0)

        // Capacity estimation (android.os.BatteryManager doesn't expose design capacity directly)
        val capacityPct = estimateBatteryCapacity(batteryLevel, charging)

        return HeartbeatRequest(
            deviceId = app.deviceId,
            batteryLevel = batteryLevel,
            batteryCharging = charging,
            batteryCapacityPct = capacityPct,
            thermalTempC = lastTempC,
            storageTotalGb = Math.round(totalGb * 10.0) / 10.0,
            storageFreeGb = Math.round(freeGb * 10.0) / 10.0,
            modelLoaded = if (isModelRunning()) "running" else null,
            modelBackend = if (isModelRunning()) activeBackend() else null,
            queueDepth = 0,
            network = "wlan0", // TODO: detect active network interface
            timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date())
        )
    }

    /**
     * Rough battery capacity estimate. Without Play Services / BatteryManager
     * proprietary API, we can only ballpark based on voltage sag.
     */
    private fun estimateBatteryCapacity(currentLevel: Double, charging: Boolean): Double {
        // Simple heuristic: report current level as capacity approximation
        // A real implementation would use BatteryManager.getIntProperty(EXTRA_ESTIMATE_POWER)
        return Math.round(currentLevel * 10.0) / 10.0
    }
}

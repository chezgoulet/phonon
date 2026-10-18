package org.phononai.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The device's thermal state, sampled every 30 s and pushed on every platform
 * thermal-status change.
 *
 * Battery temperature comes from the sticky BATTERY_CHANGED broadcast. On top of
 * that: the thermal-status listener (API 29+) fires whenever Android itself
 * changes throttling level, and `getThermalHeadroom(30)` (API 30+) is the SoC's
 * own estimate of how close it is to throttling. The policy that acts on these
 * lives in [ThermalPolicy]; this class only observes.
 */
class ThermalMonitor(
    private val context: Context,
    private val onSample: (ThermalSample) -> Unit,
) {
    @Volatile var sample: ThermalSample = ThermalSample(null, null, null)
        private set

    val batteryTempC: Float? get() = sample.batteryTempC
    val thermalStatus: Int? get() = sample.thermalStatus
    val headroom: Float? get() = sample.headroom

    private val powerManager by lazy {
        context.applicationContext.getSystemService(PowerManager::class.java)
    }

    /** Delivered on the main looper; the service updates its state here. */
    private val statusListener = PowerManager.OnThermalStatusChangedListener { status ->
        // Keep the last battery/headroom reading and fold in the new status.
        publish(sample.copy(thermalStatus = status))
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "phonon-thermal").apply { isDaemon = true }
    }

    fun start() {
        registerStatusListener()
        scheduler.scheduleWithFixedDelay(
            {
                try {
                    pollNow()
                } catch (_: Throwable) {
                }
            },
            0,
            INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    fun stop() {
        scheduler.shutdownNow()
        unregisterStatusListener()
    }

    fun pollNow(): ThermalSample? {
        val temp = readBatteryTemp()
        val status = readStatus()
        val headroom = readHeadroom()
        // A poll that produced nothing new must not erase a listener-delivered
        // status with a null.
        val next = ThermalSample(
            batteryTempC = temp ?: sample.batteryTempC,
            thermalStatus = status ?: sample.thermalStatus,
            headroom = headroom,
        )
        publish(next)
        return next
    }

    private fun publish(next: ThermalSample) {
        sample = next
        onSample(next)
    }

    private fun readBatteryTemp(): Float? = try {
        val intent = context.applicationContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        if (tenths == Int.MIN_VALUE || tenths <= 0) null else tenths / 10f
    } catch (_: Throwable) {
        null
    }

    private fun readStatus(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            powerManager?.currentThermalStatus?.takeIf { it >= 0 }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readHeadroom(): Float? {
        // getThermalHeadroom arrived in API 30; below that there is no SoC-level
        // signal and the policy falls back to status or battery temperature.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            powerManager?.getThermalHeadroom(FORECAST_SECONDS)
        } catch (_: Throwable) {
            null
        }
    }

    private fun registerStatusListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            // Called from the service's onCreate on the main thread, so the
            // listener's callbacks land on the main looper.
            powerManager?.addThermalStatusListener(statusListener)
        } catch (t: Throwable) {
            Log.i(TAG, "thermal status listener unavailable: ${t.javaClass.simpleName}")
        }
    }

    private fun unregisterStatusListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            powerManager?.removeThermalStatusListener(statusListener)
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val TAG = "Phonon"
        const val INTERVAL_SECONDS = 30L

        /** Forecast window for getThermalHeadroom: a little beyond one tick. */
        const val FORECAST_SECONDS = 30
    }
}

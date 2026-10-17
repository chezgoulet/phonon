package org.phononai.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Battery temperature every 30 s, read from the sticky BATTERY_CHANGED
 * broadcast. The policy itself (hysteresis, start/stop) lives in the service.
 */
class ThermalMonitor(
    private val context: Context,
    private val onTemperature: (Float) -> Unit,
) {
    @Volatile var batteryTempC: Float? = null
        private set

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "phonon-thermal").apply { isDaemon = true }
    }

    fun start() {
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
    }

    fun pollNow(): Float? {
        val temp = readTemperature() ?: return null
        batteryTempC = temp
        onTemperature(temp)
        return temp
    }

    private fun readTemperature(): Float? {
        return try {
            val intent = context.applicationContext.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ) ?: return null
            val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (tenths == Int.MIN_VALUE || tenths <= 0) null else tenths / 10f
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        const val INTERVAL_SECONDS = 30L
    }
}

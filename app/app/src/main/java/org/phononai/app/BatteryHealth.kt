package org.phononai.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

/**
 * A read-only view of the battery, sampled on the thermal monitor's tick.
 *
 * The app cannot start or stop the charger: that needs root or the ROM. What it
 * can do is refuse to run the device flat, and tell the operator what the
 * platform reports about wear.
 */
data class BatterySnapshot(
    val levelPct: Int,
    val charging: Boolean,
    val tempC: Float,
    val cycleCount: Int?,
    val chargeCounterUah: Long?,
) {
    val known: Boolean get() = levelPct in 0..100
}

object BatteryHealth {
    fun snapshot(context: Context): BatterySnapshot {
        val intent: Intent? = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val tempC = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f

        val manager = context.getSystemService(BatteryManager::class.java)
        val cycles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CYCLE_COUNT)?.takeIf { it >= 0 }
        } else {
            null
        }
        val counter = manager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.takeIf { it > 0 }

        return BatterySnapshot(pct, charging, tempC, cycles, counter)
    }
}

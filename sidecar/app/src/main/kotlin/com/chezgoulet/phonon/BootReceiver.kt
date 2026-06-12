package com.chezgoulet.phonon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Auto-starts the Phonon worker service after device boot.
 * This allows phones without touchscreens to start working
 * as soon as they power on and connect to Wi-Fi.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val serviceIntent = Intent(context, PhononService::class.java)
        if (BuildCheck.atLeastO) {
            try {
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Log.w("BootReceiver", "startForegroundService on boot: ${e::class.simpleName}: ${e.message}")
            }
        } else {
            context.startService(serviceIntent)
        }
    }
}

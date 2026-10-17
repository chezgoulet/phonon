package dev.phonon.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Best effort: Android may defer or block boot starts; the activity is the reliable path. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            context.startForegroundService(Intent(context, PhononService::class.java))
            Log.i("PhononProbe", "boot receiver started foreground service")
        } catch (t: Throwable) {
            Log.e("PhononProbe", "boot receiver could not start service: ${t.message}", t)
        }
    }
}

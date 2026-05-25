package com.chezgoulet.phonon

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Minimal launcher activity. Starts the foreground service and finishes.
 * The service runs indefinitely — the activity exists only to allow
 * launching from the app drawer or auto-start receivers.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start the foreground service
        val intent = Intent(this, PhononService::class.java)
        if (BuildCheck.atLeastO) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // No UI — finish immediately
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Re-launch if the activity is reused
        if (intent.action == Intent.ACTION_MAIN) {
            finish()
        }
    }
}

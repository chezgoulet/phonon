package com.chezgoulet.phonon

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.chezgoulet.phonon.ui.PhononCompanionApp
import com.chezgoulet.phonon.ui.PhononServiceState

/**
 * Companion app activity with Compose-based UI.
 *
 * Binds to PhononService and displays real-time status,
 * telemetry, logs, and an inference visualizer.
 *
 * The service continues running even if this activity is
 * closed (the notification channel stays active).
 */
class MainActivity : ComponentActivity() {

    private val state = PhononServiceState()
    private var boundService: PhononService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? PhononService.LocalBinder
            boundService = binder?.getService()
            boundService?.let { state.syncFrom(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure the foreground service is running
        val intent = Intent(this, PhononService::class.java)
        if (BuildCheck.atLeastO) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        setContent {
            PhononCompanionApp(
                state = state,
                onForceHeartbeat = {
                    boundService?.let { service ->
                        // Trigger a manual heartbeat via HealthReporter
                        service.forceHeartbeat()
                    }
                }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, PhononService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unbindService(connection)
        } catch (_: Exception) {
            // not bound, safe to ignore
        }
        boundService = null
    }
}

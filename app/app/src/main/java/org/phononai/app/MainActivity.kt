package org.phononai.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.phononai.app.ui.PhononApp
import org.phononai.app.ui.theme.PhononTheme

class MainActivity : ComponentActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The Mali driver parks the GPU when the screen turns off, which hangs
     * inference (docs/scope.md). FLAG_KEEP_SCREEN_ON only applies while this
     * activity is visible; the service's partial wake lock keeps the CPU alive
     * when it is not. Kiosk mode keeps the activity visible.
     */
    private val screenOnPoll = object : Runnable {
        override fun run() {
            applyScreenOnFlag()
            mainHandler.postDelayed(this, SCREEN_ON_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Follow the system light/dark setting: the bars take the icon
        // appearance from the same signal the Compose theme reads.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { isNight(it) },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { isNight(it) },
        )
        setContent {
            PhononTheme {
                PhononApp()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        startForegroundService(Intent(this, PhononService::class.java))
    }

    override fun onResume() {
        super.onResume()
        mainHandler.removeCallbacks(screenOnPoll)
        mainHandler.post(screenOnPoll)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(screenOnPoll)
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(screenOnPoll)
        super.onDestroy()
    }

    private fun applyScreenOnFlag() {
        val serving = try {
            AppState.service?.uiServing() == true
        } catch (_: Throwable) {
            false
        }
        if (serving) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private companion object {
        const val SCREEN_ON_POLL_MS = 1_000L

        fun isNight(resources: android.content.res.Resources): Boolean =
            (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}

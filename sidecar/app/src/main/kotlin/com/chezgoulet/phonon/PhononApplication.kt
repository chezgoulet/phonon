package com.chezgoulet.phonon

import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings

class PhononApplication : Application() {

    /** Persistent device identifier (ANDROID_ID — survives resets). */
    lateinit var deviceId: String
        private set

    /** Human-readable device model name. */
    val deviceModel: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"

    /** Android version string. */
    val androidVersion: String get() = Build.VERSION.RELEASE

    /** Network interface name (if known). */
    var networkInterface: String = "wlan0"
        set(value) {
            field = value.ifBlank { "wlan0" }
        }

    /** Current IP address (set by the service on network change). */
    @Volatile
    var ipAddress: String = "0.0.0.0"

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Derive stable device ID from ANDROID_ID
        val androidId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: Build.SERIAL.takeIf { it.isNotBlank() && it != "unknown" }
            ?: java.util.UUID.randomUUID().toString()

        deviceId = androidId

        // Read initial IP
        ipAddress = getIpAddress()
    }

    private fun getIpAddress(): String {
        return try {
            val wifiManager = applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipInt == 0) "0.0.0.0"
            else String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } catch (_: Exception) {
            "0.0.0.0"
        }
    }

    companion object {
        lateinit var instance: PhononApplication
            private set
    }
}

package dev.phonon.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Foreground service that executes the packaged probe once and keeps the
 * process (plus a partial wake lock and the health endpoint) alive.
 */
class PhononService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var healthServer: HealthServer? = null
    private val probeStarted = AtomicBoolean(false)
    private val httpStarted = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        if (httpStarted.compareAndSet(false, true)) {
            HealthServer().also { it.start(); healthServer = it }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (probeStarted.compareAndSet(false, true)) {
            HealthState.serviceStartedAt = OffsetDateTime.now().toString()
            thread(name = "phonon-probe", isDaemon = true) { runProbe() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "service destroying")
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun runProbe() {
        val libraryDir = applicationInfo.nativeLibraryDir
        val probe = File(libraryDir, PROBE_NAME)
        Log.i(TAG, "nativeLibraryDir=$libraryDir")
        Log.i(
            TAG,
            "probe=${probe.absolutePath} exists=${probe.exists()} " +
                "isFile=${probe.isFile} canExecute=${probe.canExecute()} size=${probe.length()}",
        )

        var out = ""
        var err = ""
        var code = -1
        try {
            val process = ProcessBuilder(probe.absolutePath)
                .directory(File(libraryDir))
                .apply { environment()["PATH"] = "/system/bin:/system/xbin" }
                .start()

            val outReader = thread(name = "phonon-probe-stdout") {
                out = process.inputStream.bufferedReader().use { it.readText() }
            }
            val errReader = thread(name = "phonon-probe-stderr") {
                err = process.errorStream.bufferedReader().use { it.readText() }
            }
            outReader.join()
            errReader.join()
            code = process.waitFor()
        } catch (t: Throwable) {
            err = "EXEC_FAILED: ${t.javaClass.name}: ${t.message}"
            Log.e(TAG, err, t)
        }

        val abi = Regex("PHONON_PROBE_ABI=(\\S+)")
            .find(out)?.groupValues?.get(1)
            ?: Build.SUPPORTED_ABIS.firstOrNull()
            ?: "unknown"
        val pid = Regex("PHONON_PROBE_PID=(\\d+)")
            .find(out)?.groupValues?.get(1)?.toIntOrNull()
            ?: -1
        val execOk = code == 0 && out.contains("PHONON_PROBE_OK")

        HealthState.record(execOk, code, out, err, abi, pid)

        Log.i(TAG, "exit_code=$code")
        Log.i(TAG, "abi=$abi pid=$pid")
        Log.i(TAG, "stdout:\n$out")
        Log.i(TAG, "stderr:\n$err")
        Log.i(TAG, "RESULT exec_ok=$execOk exit_code=$code abi=$abi pid=$pid")
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        Log.i(TAG, "wake lock acquired: $WAKE_LOCK_TAG")
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Phonon probe",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the Phonon exec probe service in the foreground"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Phonon probe running")
            .setContentText("Foreground service, wake lock and 127.0.0.1:8080 held")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private companion object {
        const val TAG = "PhononProbe"
        const val CHANNEL_ID = "phonon-probe"
        const val NOTIFICATION_ID = 1
        const val WAKE_LOCK_TAG = "PhononProbe::exec-spike"
        const val PROBE_NAME = "libphonon_probe.so"
    }
}

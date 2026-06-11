package com.chezgoulet.phonon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import com.chezgoulet.phonon.coordinator.CoordinatorClient
import java.io.File
import com.chezgoulet.phonon.health.HealthReporter
import com.chezgoulet.phonon.inference.InferenceServer
import com.chezgoulet.phonon.mdns.MDNSAnnouncer
import com.chezgoulet.phonon.model.ModelManager
import kotlinx.coroutines.*

/**
 * Foreground service that runs the Phonon sidecar.
 *
 * Lifecycle:
 * 1. Creates notification channel and persistent notification
 * 2. Starts mDNS announcer
 * 3. Connects to coordinator (REST register → WebSocket commands)
 * 4. Starts health reporter (60s interval)
 * 5. Starts local inference server (port 9876)
 * 6. On stop, tears everything down gracefully
 */
class PhononService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val app: PhononApplication get() = application as PhononApplication
    private val tag = "PhononService"

    private lateinit var notificationManager: NotificationManager
    private lateinit var coordinatorClient: CoordinatorClient
    private lateinit var healthReporter: HealthReporter
    private lateinit var mdnsAnnouncer: MDNSAnnouncer
    private lateinit var modelManager: ModelManager
    private lateinit var inferenceServer: InferenceServer

    private var wakeLock: PowerManager.WakeLock? = null

    // Coordinator configuration — loaded from phonon.conf, fallback to mDNS, then 255.255.255.255
    internal var coordinatorHost: String = "255.255.255.255"
    internal var coordinatorPort: Int = 8080

    // Status for notification
    @Volatile
    var connectionStatus: String = "connecting"
        private set
    @Volatile
    var loadedModel: String? = null
        private set

    // Telemetry exposed to UI via syncFrom()
    @Volatile
    var batteryLevel: Double = -1.0
        private set

    @Volatile
    var batteryTempC: Double = -1.0
        private set

    @Volatile
    var isCharging: Boolean = false
        private set

    @Volatile
    var isProcessing: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        // Acquire partial wake lock to prevent Doze from killing us
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "phonon:worker"
        ).apply {
            acquire(4 * 60 * 60 * 1000L) // 4 hours, renewable
        }

        // Start components
        startComponents()

        // If killed, restart
        return START_STICKY
    }

    /** Binder for activity binding. */
    inner class LocalBinder : Binder() {
        fun getService(): PhononService = this@PhononService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder? = binder

    /** Force an immediate heartbeat to the coordinator. */
    fun forceHeartbeat() {
        healthReporter.forceSend()
    }

    override fun onDestroy() {
        scope.cancel()
        inferenceServer.stop()
        healthReporter.stop()
        coordinatorClient.stop()
        mdnsAnnouncer.stop()
        wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * Loads coordinator URL from phonon.conf if it exists.
     * Format: coordinator_url=http://host:port
     * Falls back to defaults (255.255.255.255:8080).
     */
    private fun loadCoordinatorConfig() {
        val configFile = File(filesDir, "phonon.conf")
        if (!configFile.exists()) return

        try {
            configFile.useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#") || trimmed.isEmpty()) continue

                    val prefix = "coordinator_url="
                    if (trimmed.startsWith(prefix)) {
                        val url = trimmed.removePrefix(prefix).trim()
                        // Parse "http://host:port"
                        val afterScheme = url.substringAfter("://")
                        val hostPort = afterScheme.split("/").first()
                        val colonIdx = hostPort.lastIndexOf(':')
                        if (colonIdx > 0) {
                            coordinatorHost = hostPort.substring(0, colonIdx)
                            coordinatorPort = hostPort.substring(colonIdx + 1).toIntOrNull() ?: 8080
                        } else {
                            coordinatorHost = hostPort
                            coordinatorPort = 8080
                        }
                        Log.i(tag, "Loaded coordinator URL from config: $coordinatorHost:$coordinatorPort")
                        return@useLines
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to read phonon.conf: ${e.message}")
        }
    }

    private fun startComponents() {
        // Load coordinator URL from config file (ADB-pushed phonon.conf)
        loadCoordinatorConfig()

        // mDNS announcer — announces this phone on _phonon._tcp
        mdnsAnnouncer = MDNSAnnouncer(this, app.deviceId, app.deviceModel)
        mdnsAnnouncer.start()

        // Model manager — loads .litertlm models via LiteRT-LM SDK
        modelManager = ModelManager(this)

        // Inference server — local HTTP server backed by LiteRT-LM
        inferenceServer = InferenceServer(this, modelManager)
        inferenceServer.start()

        // Coordinator client — REST + WebSocket
        coordinatorClient = CoordinatorClient(
            context = this,
            host = coordinatorHost,
            port = coordinatorPort,
            app = app,
            onStatusChange = { status ->
                connectionStatus = status
                updateNotification()
            },
            onModelLoad = { modelName, modelUrl, engine ->
                scope.launch {
                    loadedModel = modelName
                    updateNotification()
                    modelManager.loadModel(modelName, modelUrl)
                }
            },
            onModelUnload = {
                scope.launch {
                    modelManager.unloadModel()
                    loadedModel = null
                    updateNotification()
                }
            },
            onShutdown = {
                stopSelf()
            }
        )
        coordinatorClient.connect()

        // Health reporter — reports telemetry every 60s
        healthReporter = HealthReporter(
            context = this,
            coordinatorClient = coordinatorClient,
            isModelRunning = { modelManager.isRunning() },
            onTelemetry = { level, temp, charging ->
                batteryLevel = level
                batteryTempC = temp
                isCharging = charging
            }
        )
        healthReporter.start()
    }

    private fun createNotificationChannel() {
        if (BuildCheck.atLeastO) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val statusText = when (connectionStatus) {
            "connected" -> getString(R.string.notification_title_connected)
            "disconnected" -> getString(R.string.notification_title_disconnected)
            else -> getString(R.string.notification_title_connecting)
        }

        val modelText = loadedModel ?: "none"
        val deviceText = app.deviceId.takeLast(8)

        val builder = if (BuildCheck.atLeastO) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Phonon Worker")
            .setContentText("Device: $deviceText · $statusText · $modelText")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        private const val CHANNEL_ID = "phonon_worker_status"
        private const val NOTIFICATION_ID = 1001
    }
}

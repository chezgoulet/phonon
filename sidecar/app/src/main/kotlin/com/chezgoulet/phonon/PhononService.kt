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
import android.os.SystemClock
import com.chezgoulet.phonon.coordinator.CoordinatorClient
import com.chezgoulet.phonon.ui.ThemeEngine
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
    private var pairingClient: com.chezgoulet.phonon.pairing.PairingClient? = null

    /** 6-digit pairing code currently shown to the operator, if any. */
    @Volatile
    var pairingCode: String? = null
        private set

    /**
     * Monotonic service start marker for [uptimeSeconds]. elapsedRealtime
     * is unaffected by wall-clock changes and ticks through Doze.
     */
    private var serviceStartElapsedMs: Long = SystemClock.elapsedRealtime()

    /** Seconds since the service started. */
    val uptimeSeconds: Long
        get() = (SystemClock.elapsedRealtime() - serviceStartElapsedMs) / 1000L

    private var wakeLock: PowerManager.WakeLock? = null
    private var vizStateJob: Job? = null
    private var lastInferenceTokens: Int = 0
    private var lastInferenceTimeMs: Long = 0L

    // Coordinator configuration — loaded from phonon.conf, fallback to mDNS, then 255.255.255.255
    internal var coordinatorHost: String = "255.255.255.255"
    internal var coordinatorPort: Int = 8080

    // Status for notification
    @Volatile
    var connectionStatus: String = "connecting"
        private set

    /**
     * Non-null when service startup failed (e.g. identity unsealing);
     * drives the degraded-mode notification instead of a FGS crash loop.
     */
    @Volatile
    var startupError: String? = null
        private set

    /** True once [startComponents] finished successfully. */
    private var componentsStarted = false

    /** True once the mDNS announcer was started; onDestroy stops it on this
     *  independent of [componentsStarted] so a degraded startup cannot leak a
     *  ghost _phonon._tcp advertisement (issue #308). */
    private var mdnsAnnouncerStarted = false
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

    // ── VizState fields ──

    @Volatile
    var lastTokensPerSecond: Float = 0f
        private set

    @Volatile
    var queueDepth: Int = 0
        private set

    @Volatile
    var coordinatorMode: String = "pool"
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

        // Initialize ThemeEngine with built-in packs
        ThemeEngine.initializeWithDefaults()
        ThemeEngine.setLocalDeviceId(app.deviceId)

        // #321: START_STICKY foreground services receive onStartCommand more
        // than once (rebind, stale-intent re-delivery, explicit re-start). Do
        // NOT re-run startComponents() — that would construct NEW instances of
        // mdnsAnnouncer/inferenceServer/coordinatorClient/healthReporter each
        // time and leak all but the latest. If already started, just keep the
        // existing instances and re-assert foreground/wakelock.
        if (!componentsStarted) {
            // Start components. An identity failure (e.g. TransientUnsealException
            // from an early-boot Keystore outage) must degrade LOUDLY — error log
            // plus a degraded notification — rather than propagate out of
            // onStartCommand: a throw here crashes the process, START_STICKY then
            // recreates the service into the same failure, and the foreground
            // service crash-loops. Returning START_NOT_STICKY stops the tight
            // restart cycle; retry happens on explicit start / next boot, matching
            // IdentitySeedStore's retry-on-next-boot semantics.
            try {
                startComponents()
                componentsStarted = true

                // Start VizState update loop (~10fps)
                startVizStateLoop()
            } catch (e: Exception) {
                startupError = (e.message ?: e.javaClass.simpleName).take(120)
                Log.e(tag, "Sidecar startup failed; degraded until next start/boot: $startupError", e)
                updateNotification()
                return START_NOT_STICKY
            }
        }

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
        if (componentsStarted) healthReporter.forceSend()
    }

    override fun onDestroy() {
        vizStateJob?.cancel()
        scope.cancel()
        // The mDNS announcer may have started even if a later component
        // (identity/pairing) failed; stop it independently so a degraded
        // startup cannot leak a ghost advertisement (issue #308).
        if (mdnsAnnouncerStarted) {
            mdnsAnnouncer.stop()
        }
        // Components may never have started (degraded startup path); their
        // fields are lateinit and must not be touched then.
        if (componentsStarted) {
            inferenceServer.stop()
            healthReporter.stop()
            coordinatorClient.stop()
        }
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
        mdnsAnnouncerStarted = true

        // Model manager — loads .litertlm models via LiteRT-LM SDK
        modelManager = ModelManager(this)

        // Device identity + pairing — establishes the auth token that
        // gates both directions: coordinator→phone inference and
        // phone→coordinator heartbeats/commands.
        val httpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val deviceIdentity = com.chezgoulet.phonon.pairing.DeviceIdentity(this)
        pairingClient = com.chezgoulet.phonon.pairing.PairingClient(this, deviceIdentity, httpClient)

        // Inference server — local HTTP server backed by LiteRT-LM.
        // Refuses all inference until paired; afterwards only requests
        // carrying the coordinator's token are served.
        inferenceServer = InferenceServer(
            context = this,
            modelManager = modelManager,
            tokenProvider = { pairingClient?.authToken },
            onInferenceComplete = { tokens, durationMs -> reportInference(tokens, durationMs) }
        )
        inferenceServer.start()

        // Coordinator client — REST + WebSocket
        coordinatorClient = CoordinatorClient(
            context = this,
            host = coordinatorHost,
            port = coordinatorPort,
            app = app,
            pairing = pairingClient,
            onPairingCode = { code ->
                pairingCode = code
                connectionStatus = "pairing: $code"
                updateNotification()
            },
            onStatusChange = { status ->
                connectionStatus = status
                if (status != "pairing") pairingCode = null
                updateNotification()
            },
            onModelLoad = { modelName, modelUrl, engine, backend, checksum ->
                scope.launch {
                    loadedModel = modelName
                    updateNotification()
                    modelManager.loadModel(modelName, modelUrl, backend, checksum)
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
        coordinatorClient.devicePubKeyHex = deviceIdentity.publicKeyHex
        coordinatorClient.connect()

        // Health reporter — reports telemetry every 60s
        healthReporter = HealthReporter(
            context = this,
            coordinatorClient = coordinatorClient,
            isModelRunning = { modelManager.isRunning() },
            activeBackend = { modelManager.currentBackend() },
            queueDepth = { inferenceServer.queueDepth },
            onTelemetry = { level, temp, charging ->
                batteryLevel = level
                batteryTempC = temp
                isCharging = charging
            }
        )
        healthReporter.start()
    }

    /**
     * Coroutine loop that updates VizState fields at ~10fps.
     * Reads service telemetry and arr refinement into observable state.
     * PhononServiceState.toVizState() consumes these values when rendering.
     */
    private fun startVizStateLoop() {
        vizStateJob = scope.launch {
            while (isActive) {
                // Poll the inference server's real load: queueDepth is the
                // number of requests holding/waiting on the engine, and we are
                // "processing" whenever at least one is in flight. These drive
                // the viz packs (LcarsPack, CyberHudPack, …).
                queueDepth = inferenceServer.queueDepth
                isProcessing = queueDepth > 0

                // Update tokens-per-second from the last completed inference.
                if (isProcessing && lastInferenceTimeMs > 0) {
                    val elapsed = System.currentTimeMillis() - lastInferenceTimeMs
                    lastTokensPerSecond = tokensPerSecond(lastInferenceTokens, elapsed)
                } else if (!isProcessing) {
                    lastTokensPerSecond = 0f
                }

                delay(100L) // ~10fps
            }
        }
    }

    /** Called by InferenceServer or ModelManager after each inference completes. */
    fun reportInference(tokens: Int, durationMs: Long) {
        lastInferenceTokens = tokens
        lastInferenceTimeMs = System.currentTimeMillis() - durationMs
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
        val statusText = when {
            startupError != null -> "ERROR: degraded — $startupError"
            pairingCode != null -> "Pairing — code: $pairingCode"
            connectionStatus == "connected" -> getString(R.string.notification_title_connected)
            connectionStatus == "disconnected" -> getString(R.string.notification_title_disconnected)
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

        // Sanity cap on the displayed tokens/sec, guarding against spikes from
        // tiny elapsed durations.
        const val CAP_TOKENS_PER_SEC = 200f

        /**
         * Tokens/sec for a completed inference of [tokens] tokens that took
         * [elapsedMs] ms, capped at [CAP_TOKENS_PER_SEC]. Returns 0 for a
         * non-positive duration. Pure and unit-testable (no Android deps).
         */
        fun tokensPerSecond(tokens: Int, elapsedMs: Long): Float {
            if (elapsedMs <= 0L) return 0f
            return (tokens.toFloat() / elapsedMs * 1000f).coerceAtMost(CAP_TOKENS_PER_SEC)
        }
    }
}

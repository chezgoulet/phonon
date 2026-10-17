package org.phononai.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Foreground service: owns the wake lock, the app API, the llama-server
 * supervisor and the thermal policy. It stays up when only the server is
 * stopped, so /health can honestly report `stopped`.
 */
class PhononService : Service(), ServerManager.Listener, ApiHandler {

    private lateinit var settings: Settings
    private lateinit var server: ServerManager
    private lateinit var thermal: ThermalMonitor
    private lateinit var keepWarm: KeepWarm
    private val requests = RequestTracker()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var api: ApiServer? = null
    @Volatile private var apiPort: Int = -1
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var foregroundStarted = false
    @Volatile private var destroyed = false
    private var configHashAtLaunch: String? = null

    override fun onCreate() {
        super.onCreate()
        AppState.service = this
        settings = Settings(this)
        createNotificationChannel()
        acquireWakeLock()

        server = ServerManager(this, settings)
        server.listener = this

        thermal = ThermalMonitor(this) { temp -> onBatteryTemp(temp) }
        thermal.start()

        keepWarm = KeepWarm(settings, server, requests)
        keepWarm.start()

        startApi()

        thread(name = "phonon-boot", isDaemon = true) {
            server.killStaleServers()
            if (settings.autoStart && File(settings.modelPath).isFile) {
                configHashAtLaunch = currentConfigHash()
                server.start()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        when (intent?.action) {
            ACTION_START_SERVER -> thread(name = "phonon-start", isDaemon = true) {
                configHashAtLaunch = currentConfigHash()
                server.start()
                updateNotification()
            }

            ACTION_STOP_SERVER -> thread(name = "phonon-stop", isDaemon = true) {
                server.stop()
                updateNotification()
            }

            ACTION_DOWNLOAD -> startDownload(intent.getStringExtra(EXTRA_URL))

            ACTION_RELOAD -> thread(name = "phonon-reload", isDaemon = true) {
                reloadSettings()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "service destroying")
        destroyed = true
        AppState.service = null
        api?.stop()
        api = null
        thermal.stop()
        keepWarm.stop()
        server.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    // --- ServerManager.Listener -------------------------------------------

    override fun onServerChanged() {
        mainHandler.post { updateNotification() }
    }

    // --- ApiHandler -------------------------------------------------------

    override fun healthJson(): String {
        val json = JSONObject(server.healthJson(thermal.batteryTempC))
        keepWarm.healthFields(json)
        return json.toString()
    }

    override fun isReady(): Boolean = server.state == ServerState.READY

    override fun logs(): String = server.ringLog.snapshot()

    override fun downloadJson(): String = DownloadState.toJson()

    override fun startDownload(url: String?): String {
        if (DownloadState.active) {
            return JSONObject().put("error", "download already running").toString()
        }
        val chosen = url?.trim().takeUnless { it.isNullOrEmpty() } ?: settings.downloadUrl
        if (!chosen.startsWith("http://") && !chosen.startsWith("https://")) {
            return JSONObject().put("error", "invalid url: $chosen").toString()
        }
        val dest = File(File(filesDir, "models"), modelFileName(chosen))
        DownloadState.begin(chosen, dest.absolutePath)
        thread(name = "phonon-download", isDaemon = true) {
            try {
                ModelDownloader(this).download(chosen, dest) { done, total ->
                    DownloadState.progress(done, total)
                }
                settings.modelPath = dest.absolutePath
                DownloadState.success(dest.absolutePath)
                Log.i(TAG, "download complete: ${dest.absolutePath} (${dest.length()} bytes)")
                if (settings.autoStart && !server.thermalPaused) {
                    configHashAtLaunch = currentConfigHash()
                    server.start()
                }
                updateNotification()
            } catch (t: Throwable) {
                val msg = "${t.javaClass.simpleName}: ${t.message}"
                DownloadState.failure(msg)
                Log.e(TAG, "download failed: $msg", t)
            }
        }
        return DownloadState.toJson()
    }

    override fun startServer() {
        thread(name = "phonon-start", isDaemon = true) {
            configHashAtLaunch = currentConfigHash()
            server.start()
            updateNotification()
        }
    }

    override fun stopServer() {
        thread(name = "phonon-stop", isDaemon = true) {
            server.stop()
            updateNotification()
        }
    }

    override fun settingsJson(): String = settings.asJson()

    override fun updateSettings(body: String): String {
        val json = try {
            JSONObject(body)
        } catch (_: Throwable) {
            return error("invalid JSON body")
        }
        try {
            if (json.has("model_path")) {
                val value = json.optString("model_path").trim()
                if (value.isBlank()) return error("model_path must not be blank")
                settings.modelPath = value
            }
            if (json.has("context")) {
                val value = json.optInt("context", settings.contextSize)
                if (value < 256 || value > 32_768) return error("context must be 256..32768")
                settings.contextSize = value
            }
            if (json.has("threads")) {
                val value = json.optInt("threads", settings.threads)
                if (value < 1 || value > 16) return error("threads must be 1..16")
                settings.threads = value
            }
            if (json.has("port")) {
                val value = json.optInt("port", settings.port)
                if (value < 1024 || value > 65_535) return error("port must be 1024..65535")
                settings.port = value
            }
            if (json.has("backend_port")) {
                val value = json.optInt("backend_port", settings.backendPort)
                if (value < 1024 || value > 65_535) return error("backend_port must be 1024..65535")
                settings.backendPort = value
            }
            if (settings.port == settings.backendPort) {
                return error("port and backend_port must differ")
            }
            if (json.has("reasoning")) settings.reasoning = json.optBoolean("reasoning", settings.reasoning)
            if (json.has("alias")) {
                val value = json.optString("alias").trim()
                if (value.isBlank()) return error("alias must not be blank")
                settings.alias = value
            }
            if (json.has("api_key")) settings.apiKey = json.optString("api_key")
            if (json.has("auto_start")) settings.autoStart = json.optBoolean("auto_start", settings.autoStart)
            if (json.has("download_url")) {
                val value = json.optString("download_url").trim()
                if (!value.startsWith("http://") && !value.startsWith("https://")) {
                    return error("download_url must be http(s)")
                }
                settings.downloadUrl = value
            }
            if (json.has("keep_warm")) {
                settings.keepWarm = json.optBoolean("keep_warm", settings.keepWarm)
            }
            if (json.has("keep_warm_interval_s")) {
                val value = json.optInt("keep_warm_interval_s", settings.keepWarmIntervalS)
                if (value < Settings.MIN_KEEP_WARM_INTERVAL_S) {
                    return error("keep_warm_interval_s must be >= ${Settings.MIN_KEEP_WARM_INTERVAL_S}")
                }
                settings.keepWarmIntervalS = value
            }
            if (json.has("load_mode")) {
                val value = json.optString("load_mode").trim().lowercase()
                if (value !in Settings.LOAD_MODES) {
                    return error("load_mode must be one of ${Settings.LOAD_MODES.joinToString()}")
                }
                settings.loadMode = value
            }
            val stop = if (json.has("thermal_stop_c")) {
                json.optDouble("thermal_stop_c", settings.thermalStopC.toDouble()).toFloat()
            } else {
                settings.thermalStopC
            }
            val resume = if (json.has("thermal_resume_c")) {
                json.optDouble("thermal_resume_c", settings.thermalResumeC.toDouble()).toFloat()
            } else {
                settings.thermalResumeC
            }
            if (stop <= resume) {
                return error("thermal_stop_c must be greater than thermal_resume_c")
            }
            if (stop < 0f || stop > 100f || resume < 0f || resume > 100f) {
                return error("thermal thresholds must be 0..100 C")
            }
            settings.thermalStopC = stop
            settings.thermalResumeC = resume
        } catch (t: Throwable) {
            return error("invalid setting: ${t.message}")
        }

        thread(name = "phonon-reload", isDaemon = true) { reloadSettings() }
        return settings.asJson()
    }

    override fun recordResponseBody(body: String) {
        server.recordTimings(body)
    }

    override fun requestStarted() = requests.beginClient()

    override fun requestFinished() = requests.endClient()

    // --- activity-facing helpers -----------------------------------------

    fun uiHealthJson(): String = healthJson()

    fun uiLogs(): String = logs()

    fun uiDownloadJson(): String = downloadJson()

    // --- internals --------------------------------------------------------

    private fun reloadSettings() {
        if (destroyed) return
        if (apiPort != settings.port) {
            api?.stop()
            api = null
            startApi()
        }

        // Thresholds take effect now, which is what makes the thermal test
        // possible without a heat gun.
        thermal.pollNow()

        val hash = currentConfigHash()
        if (hash != configHashAtLaunch && server.isDesired && !server.thermalPaused) {
            if (server.state == ServerState.READY || server.state == ServerState.LOADING ||
                server.state == ServerState.RESTARTING || server.state == ServerState.ERROR
            ) {
                Log.i(TAG, "server settings changed; restarting llama-server")
                server.restart()
                configHashAtLaunch = hash
            }
        }
        updateNotification()
    }

    private fun currentConfigHash(): String = listOf(
        settings.modelPath,
        settings.contextSize,
        settings.threads,
        settings.backendPort,
        settings.reasoning,
        settings.alias,
        settings.apiKey,
        settings.loadMode,
    ).joinToString("|")

    private fun onBatteryTemp(temp: Float) {
        if (destroyed) return
        val stop = settings.thermalStopC
        val resume = settings.thermalResumeC
        if (!server.thermalPaused && server.isDesired && temp >= stop) {
            Log.i(TAG, "thermal: ${temp}C >= ${stop}C, pausing server")
            server.pauseForThermal()
            updateNotification()
        } else if (server.thermalPaused && temp <= resume) {
            Log.i(TAG, "thermal: ${temp}C <= ${resume}C, resuming server")
            configHashAtLaunch = currentConfigHash()
            server.resumeFromThermal()
            updateNotification()
        }
    }

    private fun startApi() {
        val instance = ApiServer(settings, this)
        instance.start()
        api = instance
        apiPort = settings.port
    }

    private fun startForegroundCompat() {
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
        foregroundStarted = true
    }

    private fun updateNotification() {
        if (destroyed || !foregroundStarted) return
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(): Notification {
        val state = if (::server.isInitialized) server.state.wire else "starting"
        val model = File(settings.modelPath).name
        val temp = if (::thermal.isInitialized) thermal.batteryTempC else null
        val text = if (temp != null) "$model · ${temp}°C" else model
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Phonon · $state")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
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
            "Phonon server",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the local llama.cpp inference server running"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun modelFileName(url: String): String {
        val path = try {
            URI(url).path ?: ""
        } catch (_: Throwable) {
            ""
        }
        val raw = path.substringAfterLast('/')
        val decoded = try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (_: Throwable) {
            raw
        }
        val safe = decoded.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (safe.isBlank()) "model.gguf" else safe
    }

    private fun error(message: String): String =
        JSONObject().put("error", message).toString()

    companion object {
        const val TAG = "Phonon"
        const val ACTION_START_SERVER = "org.phononai.app.action.START_SERVER"
        const val ACTION_STOP_SERVER = "org.phononai.app.action.STOP_SERVER"
        const val ACTION_DOWNLOAD = "org.phononai.app.action.DOWNLOAD"
        const val ACTION_RELOAD = "org.phononai.app.action.RELOAD"
        const val EXTRA_URL = "url"

        private const val CHANNEL_ID = "phonon-server"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "Phonon::llama-server"
    }
}

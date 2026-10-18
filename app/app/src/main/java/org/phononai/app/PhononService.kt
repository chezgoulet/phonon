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
import java.util.concurrent.Executors
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
    private lateinit var hints: PerfHints
    private lateinit var benchmark: BenchmarkRunner
    private val requests = RequestTracker()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var api: ApiServer? = null
    @Volatile private var apiPort: Int = -1
    @Volatile private var apiBind: String? = null

    /**
     * Number of settings reloads currently owning the restart. While it is
     * non-zero control endpoints answer 503 + Retry-After instead of racing a
     * listener or backend that is being replaced.
     */
    private var reloadsInFlight = 0
    private val reloadGuard = Any()
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var foregroundStarted = false
    @Volatile private var destroyed = false

    /** Operator intent: keep the model server running across config changes. */
    @Volatile private var wantServer = false
    private var configHashAtLaunch: String? = null

    /** Serialises reloads so two settings changes cannot rebind each other's config. */
    private val reloadExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "phonon-reload").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        AppState.service = this
        settings = Settings(this)
        // Before the supervisor reads model_path/context/threads: first start
        // after install or data clear gets the RAM-tier defaults.
        settings.applyAutoConfigIfNeeded()
        createNotificationChannel()
        acquireWakeLock()

        server = ServerManager(this, settings)
        server.listener = this
        // Thermal pause drains in-flight completions instead of cutting them.
        server.drainProbe = { requests.inFlightCount }

        thermal = ThermalMonitor(this) { temp -> onBatteryTemp(temp) }
        thermal.start()

        keepWarm = KeepWarm(settings, server, requests)
        keepWarm.start()

        benchmark = BenchmarkRunner(settings, server) { thermal.batteryTempC }

        hints = PerfHints(this)

        startApi()

        thread(name = "phonon-boot", isDaemon = true) {
            server.killStaleServers()
            synchronized(settings.configLock) {
                wantServer = settings.autoStart && File(settings.modelPath).isFile
                val exposure = settings.exposureError
                if (exposure != null) {
                    server.refuseUnsafeExposure(exposure)
                } else if (wantServer) {
                    configHashAtLaunch = currentConfigHash()
                    server.start()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        when (intent?.action) {
            ACTION_START_SERVER -> thread(name = "phonon-start", isDaemon = true) {
                synchronized(settings.configLock) {
                    wantServer = true
                    configHashAtLaunch = currentConfigHash()
                    server.start()
                }
                updateNotification()
            }

            ACTION_STOP_SERVER -> thread(name = "phonon-stop", isDaemon = true) {
                synchronized(settings.configLock) {
                    wantServer = false
                    server.stop()
                }
                updateNotification()
            }

            ACTION_DOWNLOAD -> startDownload(intent.getStringExtra(EXTRA_URL))

            ACTION_RELOAD -> reloadExecutor.execute { reloadSettings() }
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
        reloadExecutor.shutdownNow()
        if (::benchmark.isInitialized) benchmark.cancel()
        thermal.stop()
        keepWarm.stop()
        server.shutdown()
        releaseWakeLock()
        super.onDestroy()
    }

    // --- ServerManager.Listener -------------------------------------------

    override fun onServerChanged() {
        mainHandler.post {
            syncWakeLock()
            updateNotification()
        }
    }

    // --- ApiHandler -------------------------------------------------------

    override fun healthJson(): String {
        val json = JSONObject(server.healthJson(thermal.batteryTempC))
        keepWarm.healthFields(json)
        // Additive activity signal for the UI. Only proxied /v1 client requests
        // are counted; keep-warm beats bypass RequestTracker.beginClient().
        json.put("requests_in_flight", requests.inFlightCount)
        json.put(
            "last_completion_at",
            if (requests.lastCompletionAtMs > 0) requests.lastCompletionAtMs else JSONObject.NULL,
        )
        json.put("requests_completed", requests.completedCount)
        val battery = BatteryHealth.snapshot(this)
        json.put("pause_reason", server.pauseReason ?: JSONObject.NULL)
        if (battery.known) {
            json.put("battery_level", battery.levelPct)
            json.put("battery_charging", battery.charging)
        }
        if (::hints.isInitialized) hints.healthFields(json)
        // A reload between commit and backend restart must not look `ready`
        // just because the previous process is still up.
        if (isRestarting() && json.optString("state") == ServerState.READY.wire) {
            json.put("state", ServerState.RESTARTING.wire)
        }
        return json.toString()
    }

    override fun isReady(): Boolean =
        server.state == ServerState.READY && !isRestarting()

    override fun isRestarting(): Boolean = synchronized(reloadGuard) { reloadsInFlight > 0 }

    private fun beginReload() {
        synchronized(reloadGuard) { reloadsInFlight++ }
    }

    private fun endReload() {
        synchronized(reloadGuard) { if (reloadsInFlight > 0) reloadsInFlight-- }
    }

    override fun logs(): String = server.ringLog.snapshot()

    override fun downloadJson(): String = DownloadState.toJson()

    override fun startDownload(url: String?): String = beginDownload(url, null, null)

    /**
     * Activity-only: download one catalog entry to its declared filename and,
     * once complete, make it the active model. Not an HTTP route; the existing
     * `/model/download` contract is unchanged.
     */
    fun uiDownloadModel(entryId: String): String {
        val entry = ModelCatalog.byId(entryId)
            ?: return JSONObject().put("error", "unknown model: $entryId").toString()
        return beginDownload(entry.url, entry.fileName, entry.id)
    }

    private fun beginDownload(url: String?, fileName: String?, entryId: String?): String {
        // Test-and-set in one synchronized call: two concurrent requests must
        // not both decide the downloader is idle and start a writer each.

        val chosen = url?.trim().takeUnless { it.isNullOrEmpty() } ?: settings.downloadUrl
        if (!chosen.startsWith("http://") && !chosen.startsWith("https://")) {
            return JSONObject().put("error", "invalid url: $chosen").toString()
        }
        val modelsDir = File(filesDir, "models").canonicalFile
        val name = fileName ?: modelFileName(chosen)
        if (name == "." || name == ".." || name.isBlank()) {
            return JSONObject().put("error", "invalid model file name").toString()
        }
        val dest = File(modelsDir, name).canonicalFile
        if (dest.parentFile != modelsDir) {
            return JSONObject().put("error", "model path escapes the models directory").toString()
        }
        if (!DownloadState.beginIfIdle(chosen, dest.absolutePath)) {
            return JSONObject().put("error", "download already running").toString()
        }
        thread(name = "phonon-download", isDaemon = true) {
            try {
                val expectedSha = entryId?.let { ModelCatalog.byId(it) }?.sha256
                ModelDownloader(this).download(chosen, dest, expectedSha, entryId) { done, total ->
                    DownloadState.progress(done, total)
                }
                val entry = entryId?.let { ModelCatalog.byId(it) }
                if (entry != null) {
                    // File, context and alias move together for a catalog
                    // entry; a bare URL download only sets the path.
                    settings.selectModel(entry)
                } else {
                    settings.modelPath = dest.absolutePath
                }
                DownloadState.success(dest.absolutePath)
                Log.i(TAG, "download complete: ${dest.absolutePath} (${dest.length()} bytes)")
                synchronized(settings.configLock) {
                    // If the server was running, it is running the previous
                    // model: apply the new one. If it was stopped, leave it
                    // stopped — autoStart governs boot, not this decision.
                    if (server.isDesired && !server.thermalPaused) {
                        wantServer = true
                        configHashAtLaunch = currentConfigHash()
                        server.start()
                    }
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

    override fun startServer(): String {
        synchronized(settings.configLock) {
            settings.exposureError?.let { message ->
                server.refuseUnsafeExposure(message)
                updateNotification()
                return error(message)
            }
            // Accepting a start we already know will fail misleads the caller.
            val model = File(settings.modelPath)
            if (!model.isFile || !model.canRead()) {
                return error("model not found or unreadable: ${model.absolutePath}")
            }
            wantServer = true
            configHashAtLaunch = currentConfigHash()
        }
        thread(name = "phonon-start", isDaemon = true) {
            synchronized(settings.configLock) { server.start() }
            updateNotification()
        }
        return JSONObject().put("accepted", true).toString()
    }

    override fun stopServer() {
        thread(name = "phonon-stop", isDaemon = true) {
            synchronized(settings.configLock) {
                wantServer = false
                server.stop()
            }
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
        var markedReload = false
        synchronized(settings.configLock) {
            // Phase 1: compute the prospective configuration. Nothing is
            // written until every field, including the bind/key pair, has
            // passed validation.
            var next: ApiServer? = null
            try {
                var modelPath = settings.modelPath
                var context = settings.contextSize
                var threads = settings.threads
                var bindAddress = settings.bindAddress
                var port = settings.port
                var backendPort = settings.backendPort
                var reasoning = settings.reasoning
                var alias = settings.alias
                var apiKey = settings.apiKey
                var autoStart = settings.autoStart
                var downloadUrl = settings.downloadUrl
                var keepWarm = settings.keepWarm
                var keepWarmInterval = settings.keepWarmIntervalS
                var loadMode = settings.loadMode
                var thermalStop = settings.thermalStopC
                var thermalResume = settings.thermalResumeC

                if (json.has("model_path")) {
                    val value = json.optString("model_path").trim()
                    if (value.isBlank()) return error("model_path must not be blank")
                    modelPath = value
                }
                if (json.has("context")) {
                    val value = json.optInt("context", context)
                    if (value < 256 || value > Settings.MAX_CONTEXT) {
                        return error("context must be 256..${Settings.MAX_CONTEXT}")
                    }
                    context = value
                }
                if (json.has("threads")) {
                    val value = json.optInt("threads", threads)
                    if (value < 1 || value > 16) return error("threads must be 1..16")
                    threads = value
                }
                if (json.has("bind_address")) {
                    val value = json.optString("bind_address").trim()
                    Settings.validateBindAddress(value)?.let { return error(it) }
                    bindAddress = value
                }
                if (json.has("port")) {
                    val value = json.optInt("port", port)
                    if (value < 1024 || value > 65_535) return error("port must be 1024..65535")
                    port = value
                }
                if (json.has("backend_port")) {
                    val value = json.optInt("backend_port", backendPort)
                    if (value < 1024 || value > 65_535) return error("backend_port must be 1024..65535")
                    backendPort = value
                }
                if (port == backendPort) {
                    return error("port and backend_port must differ")
                }
                if (json.has("reasoning")) reasoning = json.optBoolean("reasoning", reasoning)
                if (json.has("alias")) {
                    val value = json.optString("alias").trim()
                    if (value.isBlank()) return error("alias must not be blank")
                    alias = value
                }
                if (json.has("api_key")) apiKey = json.optString("api_key")
                if (json.has("auto_start")) autoStart = json.optBoolean("auto_start", autoStart)
                if (json.has("download_url")) {
                    val value = json.optString("download_url").trim()
                    if (!value.startsWith("http://") && !value.startsWith("https://")) {
                        return error("download_url must be http(s)")
                    }
                    downloadUrl = value
                }
                if (json.has("keep_warm")) {
                    keepWarm = json.optBoolean("keep_warm", keepWarm)
                }
                if (json.has("keep_warm_interval_s")) {
                    val value = json.optInt("keep_warm_interval_s", keepWarmInterval)
                    if (value < Settings.MIN_KEEP_WARM_INTERVAL_S) {
                        return error(
                            "keep_warm_interval_s must be >= ${Settings.MIN_KEEP_WARM_INTERVAL_S}",
                        )
                    }
                    keepWarmInterval = value
                }
                if (json.has("load_mode")) {
                    val value = json.optString("load_mode").trim().lowercase()
                    if (value !in Settings.LOAD_MODES) {
                        return error("load_mode must be one of ${Settings.LOAD_MODES.joinToString()}")
                    }
                    loadMode = value
                }
                if (json.has("thermal_stop_c")) {
                    thermalStop =
                        json.optDouble("thermal_stop_c", thermalStop.toDouble()).toFloat()
                }
                if (json.has("thermal_resume_c")) {
                    thermalResume =
                        json.optDouble("thermal_resume_c", thermalResume.toDouble()).toFloat()
                }
                if (thermalStop <= thermalResume) {
                    return error("thermal_stop_c must be greater than thermal_resume_c")
                }
                if (thermalStop < 0f || thermalStop > 100f ||
                    thermalResume < 0f || thermalResume > 100f
                ) {
                    return error("thermal thresholds must be 0..100 C")
                }

                // The whole prospective configuration must be safe, not the
                // transient mix produced by applying fields one at a time.
                if (!Settings.isLoopbackBind(bindAddress) && apiKey.isBlank()) {
                    Log.w(TAG, "settings rejected: bind_address requires api_key")
                    return error("bind_address requires api_key")
                }

                // Phase 2: commit. An api_key change alone does not touch the
                // listener: authorized() reads the live settings, so the new
                // credential applies to the next request on the same socket.
                // Only a bind address or port change rebinds.
                val oldBind = settings.bindAddress
                val rebind = bindAddress != oldBind || port != settings.port

                // Swap the listener before this request's 200 is written. The
                // old socket is closed and the new one bound in the same
                // request, so a client that sees 200 can never observe the
                // port down. Accepted connections (including this one) keep
                // running: stop() only closes the listening socket. The new
                // socket does not accept until after the commit, so it never
                // serves with a half-applied configuration.
                val previous = api
                if (rebind) {
                    previous?.stop()
                    if (api === previous) api = null
                    val candidate = ApiServer(settings, this, bindAddress, port, apiKey)
                    if (!candidate.bind()) {
                        // Restore the listener with the still-current settings.
                        startApi()
                        Log.w(TAG, "settings rejected: cannot bind $bindAddress:$port")
                        return error("cannot bind $bindAddress:$port")
                    }
                    next = candidate
                }

                settings.modelPath = modelPath
                settings.contextSize = context
                // Only a *changed* thread count is an override. Saving an
                // unrelated field while the auto profile is active must leave
                // auto-tuning alone, even though the UI posts the value it
                // shows.
                if (threads != settings.threads) settings.threads = threads
                settings.bindAddress = bindAddress
                settings.port = port
                settings.backendPort = backendPort
                settings.reasoning = reasoning
                settings.alias = alias
                settings.apiKey = apiKey
                settings.autoStart = autoStart
                settings.downloadUrl = downloadUrl
                settings.keepWarm = keepWarm
                settings.keepWarmIntervalS = keepWarmInterval
                settings.loadMode = loadMode
                settings.thermalStopC = thermalStop
                settings.thermalResumeC = thermalResume

                if (next != null) {
                    next.startAccepting()
                    api = next
                    apiPort = port
                    apiBind = bindAddress
                }

                if (rebind || currentConfigHash() != configHashAtLaunch) {
                    beginReload()
                    markedReload = true
                }
            } catch (t: Throwable) {
                next?.stop()
                return error("invalid setting: ${t.message}")
            }
        }

        try {
            reloadExecutor.execute { reloadSettings(markedReload) }
        } catch (_: Throwable) {
            // Service is going away; do not leave control endpoints reporting
            // a reload that will never run.
            if (markedReload) endReload()
            return error("service shutting down")
        }
        return settings.asJson()
    }

    override fun recordResponseBody(body: String) {
        server.recordTimings(body)
    }

    override fun requestStarted(path: String) {
        requests.beginClient()
        // ADPF describes one unit of work: a generated token. Only completion
        // routes generate; /v1/models and friends must not open a session.
        if (isCompletionPath(path)) hints.begin(server.pid, server.perTokenNanos())
    }

    override fun requestFinished(path: String) {
        requests.endClient()
        if (isCompletionPath(path)) hints.end(server.perTokenNanos())
    }

    private fun isCompletionPath(path: String): Boolean =
        path.endsWith("/completions") || path.endsWith("/embeddings")

    /**
     * ApiServer refused to bind because the invariant did not hold at bind
     * time. Stop the backend too and surface the reason in /health.
     */
    override fun refuseUnsafeExposure(message: String) {
        Log.e(TAG, "API bind refused: $message")
        server.refuseUnsafeExposure(message)
        updateNotification()
    }

    // --- activity-facing helpers -----------------------------------------

    fun uiHealthJson(): String = healthJson()

    fun uiBenchmarkJson(): String =
        if (::benchmark.isInitialized) benchmark.stateJson() else "{\"running\":false}"

    fun uiStartBenchmark(): Boolean =
        ::benchmark.isInitialized && benchmark.start()

    fun uiCancelBenchmark() {
        if (::benchmark.isInitialized) benchmark.cancel()
    }

    /**
     * Activity screen-on policy. `ready` covers both idle and generating: a
     * generate does not leave the ready state. A request in flight is included
     * defensively across a brief state transition.
     */
    fun uiServing(): Boolean = server.state == ServerState.READY || requests.inFlightCount > 0

    fun uiLogs(): String = logs()

    fun uiDownloadJson(): String = downloadJson()

    /**
     * Activity-only model switch. Writes file, context and alias as one
     * choice, then reloads: a changed config hash restarts llama-server with
     * the new alias. Not an HTTP route, so the API surface is unchanged.
     * Returns an error message, or null when the switch was accepted.
     */
    fun uiSelectModel(entryId: String): String? {
        val entry = ModelCatalog.byId(entryId) ?: return "unknown model: $entryId"
        val file = ModelCatalog.path(this, entry)
        if (!file.isFile) return "not installed: ${entry.fileName}"
        synchronized(settings.configLock) {
            settings.selectModel(entry)
            // Choosing a model means serving it; leave configHashAtLaunch
            // stale so reloadSettings sees the change and restarts the child.
            wantServer = true
        }
        try {
            reloadExecutor.execute { reloadSettings() }
        } catch (_: Throwable) {
            return "service shutting down"
        }
        return null
    }

    /**
     * Activity-only manual override for a thermal pause. Deliberately not an HTTP
     * route: the operator pressed a button on the phone. The next thermal poll
     * applies the configured policy again, so a phone that is still too warm
     * simply pauses once more.
     */
    fun uiResumeThermal() {
        thread(name = "phonon-resume", isDaemon = true) {
            synchronized(settings.configLock) {
                configHashAtLaunch = currentConfigHash()
                server.resumeFromThermal()
            }
            updateNotification()
        }
    }

    /**
     * Activity-only performance-profile override. Not an HTTP route: the
     * operator edited the Performance card. Writes the fields, then restarts a
     * running child so the new arguments take effect. [reset] returns every
     * field to the runtime-computed default.
     */
    fun uiApplyPerf(
        threads: Int? = null,
        cpuRange: String? = null,
        prio: Int? = null,
        prioBatch: Int? = null,
        reset: Boolean = false,
    ): String? {
        synchronized(settings.configLock) {
            if (reset) {
                settings.clearPerfOverrides()
            } else {
                // Only fields that differ from the runtime default become
                // overrides. Pressing Apply on an untouched field must not
                // silently disable auto-tuning for it.
                val detection = PerfProfile.detect()
                val autoThreads = detection.best?.size ?: minOf(4, detection.allowed.size)
                val autoRange = detection.best?.range.orEmpty()
                threads?.let {
                    if (it < 1 || it > 16) return "threads must be 1..16"
                    if (it != autoThreads) settings.setThreadsOverride(it)
                    else settings.clearThreadsOverride()
                }
                cpuRange?.let {
                    val value = it.trim()
                    if (!PerfProfile.validCpuRange(value)) {
                        return "cpu range must look like 4-5"
                    }
                    if (PerfProfile.parseCpuList(value).any { cpu -> cpu !in detection.allowed }) {
                        return "cpu range $value is outside the allowed set"
                    }
                    if (value != autoRange) settings.setCpuRangeOverride(value)
                    else settings.clearCpuRangeOverride()
                }
                if (prio != null || prioBatch != null) {
                    val current = settings.perfPlan()
                    val p = (prio ?: current.prio).coerceIn(0, 3)
                    val b = (prioBatch ?: current.prioBatch).coerceIn(0, 3)
                    if (p != PerfPlan.DEFAULT_PRIO || b != PerfPlan.DEFAULT_PRIO_BATCH) {
                        settings.setPrioOverride(p, b)
                    } else {
                        settings.clearPrioOverride()
                    }
                }
            }
        }
        thread(name = "phonon-perf-apply", isDaemon = true) {
            synchronized(settings.configLock) {
                configHashAtLaunch = currentConfigHash()
                if (server.isDesired && !server.thermalPaused) server.restart()
            }
            updateNotification()
        }
        return null
    }

    fun uiSetAnimateAvatar(on: Boolean) {
        settings.animateAvatarWhileGenerating = on
    }

    // --- internals --------------------------------------------------------

    private fun reloadSettings(markReload: Boolean = false) {
        try {
            if (destroyed) return
            var restarted = false
            synchronized(settings.configLock) {
                if (destroyed) return
                val exposure = settings.exposureError
                if (exposure != null) {
                    // Unsafe configuration: no off-host listener and no
                    // backend. A loopback-only operator listener stays up so
                    // /health can explain the refusal.
                    server.refuseUnsafeExposure(exposure)
                    if (api == null || apiPort != settings.port ||
                        apiBind != Settings.DEFAULT_BIND_ADDRESS
                    ) {
                        api?.stop()
                        api = null
                        startApi()
                    }
                    thermal.pollNow()
                    updateNotification()
                    return
                }

                val hash = currentConfigHash()
                val changed = hash != configHashAtLaunch
                val desired = wantServer || server.isDesired
                if (changed) configHashAtLaunch = hash

                // The operator listener is not touched here. It is either
                // already serving the committed configuration (a key-only
                // change) or was replaced atomically by updateSettings. A
                // request that arrives from now on gets 503 + Retry-After
                // while control endpoints observe isRestarting().
                if (!server.thermalPaused && desired && changed) {
                    Log.i(TAG, "server settings changed; restarting llama-server")
                    server.restart()
                    restarted = true
                } else if (!server.thermalPaused && desired &&
                    (server.state == ServerState.STOPPED || server.state == ServerState.ERROR)
                ) {
                    // Recover from a refusal or a previous failure without
                    // needing an explicit POST /server/start.
                    Log.i(TAG, "configuration is safe again; starting llama-server")
                    server.start()
                    restarted = true
                }

                if (api == null) startApi()

                // Thresholds take effect now, which is what makes the thermal
                // test possible without a heat gun.
                thermal.pollNow()
                updateNotification()
            }

            // Do not clear the 503 window until the replacement backend is
            // ready, so an immediate retry sees a settled server.
            if (restarted) awaitBackendSettled()
        } finally {
            if (markReload) endReload()
        }
    }

    /**
     * Wait for the restarted backend to reach a terminal state. Readiness is
     * still reported by ServerManager: this only decides when control
     * endpoints stop answering 503.
     */
    private fun awaitBackendSettled() {
        val deadline = System.currentTimeMillis() + RELOAD_SETTLE_TIMEOUT_MS
        while (!destroyed && System.currentTimeMillis() < deadline) {
            when (server.state) {
                ServerState.READY,
                ServerState.STOPPED,
                ServerState.ERROR,
                ServerState.THERMAL_PAUSE,
                -> return
                else -> try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun currentConfigHash(): String {
        val plan = settings.perfPlan()
        return listOf(
            settings.modelPath,
            settings.contextSize,
            plan.threads,
            plan.cpuRange,
            plan.prio,
            plan.prioBatch,
            plan.mode,
            settings.bindAddress,
            settings.port,
            settings.backendPort,
            settings.reasoning,
            settings.alias,
            settings.apiKey,
            settings.loadMode,
        ).joinToString("|")
    }

    private fun onBatteryTemp(temp: Float) {
        if (destroyed) return
        val stop = settings.thermalStopC
        val resume = settings.thermalResumeC

        // Battery policy: do not run an unplugged phone flat. This is the app's
        // only lever — the platform owns the charger, so charge limiting is
        // documented as a ROM/root capability, not something this code claims.
        val battery = BatteryHealth.snapshot(this)
        val pauseBelow = settings.batteryPauseBelow
        if (battery.known && !battery.charging && pauseBelow > 0 &&
            battery.levelPct in 1 until pauseBelow &&
            !server.thermalPaused && server.isDesired
        ) {
            Log.i(TAG, "battery: ${battery.levelPct}% unplugged, pausing server")
            server.pauseForBattery()
            updateNotification()
            return
        }
        if (server.thermalPaused && server.pauseReason == "battery" &&
            (battery.charging || battery.levelPct >= settings.batteryResumeAbove)
        ) {
            Log.i(TAG, "battery: ${battery.levelPct}% charging=${battery.charging}, resuming server")
            configHashAtLaunch = currentConfigHash()
            server.resumeFromThermal()
            updateNotification()
            return
        }

        if (!server.thermalPaused && server.isDesired && temp >= stop) {
            Log.i(TAG, "thermal: ${temp}C >= ${stop}C, pausing server")
            server.pauseForThermal()
            updateNotification()
        } else if (server.thermalPaused && server.pauseReason == "thermal" && temp <= resume) {
            Log.i(TAG, "thermal: ${temp}C <= ${resume}C, resuming server")
            configHashAtLaunch = currentConfigHash()
            server.resumeFromThermal()
            updateNotification()
        }
    }

    private fun startApi() {
        val exposure = settings.exposureError
        // Never bind the operator port off-loopback without a key. In the
        // unsafe case a loopback-only listener remains so /health can explain
        // why nothing started; nothing is exposed to the LAN.
        val bind = if (exposure == null) settings.bindAddress else Settings.DEFAULT_BIND_ADDRESS
        if (exposure != null) {
            Log.w(TAG, "refusing exposure: $exposure; API stays on 127.0.0.1")
        }
        val instance = ApiServer(settings, this, bind, settings.port)
        instance.start()
        api = instance
        apiPort = settings.port
        apiBind = bind
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
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        Log.i(TAG, "wake lock acquired: $WAKE_LOCK_TAG")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Log.i(TAG, "wake lock released: $WAKE_LOCK_TAG")
    }

    /**
     * The partial wake lock exists to keep inference running with the screen
     * off. When the operator stops serving there is nothing to keep awake, so
     * holding it would keep the phone out of deep sleep for no benefit.
     */
    private fun syncWakeLock() {
        val wanted = when (server.state) {
            ServerState.READY, ServerState.LOADING, ServerState.RESTARTING -> true
            else -> false
        }
        if (wanted) acquireWakeLock() else releaseWakeLock()
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
        if (safe == "." || safe == ".." || safe.isBlank()) return "model.gguf"
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

        /** Upper bound on how long control endpoints report 503 after a reload. */
        private const val RELOAD_SETTLE_TIMEOUT_MS = 300_000L
    }
}

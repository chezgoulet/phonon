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
    private lateinit var socTuning: SocTuning
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

    /**
     * Battery sampled on the thermal monitor's 30 s tick. /health is polled
     * every second from the UI, and a sticky-broadcast registration plus a
     * BatteryManager IPC per poll is work the phone does not need to repeat.
     */
    @Volatile private var batterySnapshot: BatterySnapshot? = null

    /** Latest thermal policy result, exposed through /health. */
    @Volatile private var thermalSource: ThermalPolicy.Source = ThermalPolicy.Source.BATTERY
    @Volatile private var thermalDetail: String = "unknown"

    /** True while the manual per-SoC tuning sweep is running. */
    @Volatile private var tuningRunning: Boolean = false
    @Volatile private var tuningState: String = "idle"

    /** Set by the Cancel action; checked between sweep steps. */
    @Volatile private var tuningCancelled: Boolean = false

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
        // The per-SoC store is constructed so /health and the Settings card can
        // read it. Nothing is applied here: perfPlan() consults the stored
        // profile directly, so a fresh install (no profile) is unchanged.
        socTuning = SocTuning(settings)
        createNotificationChannel()
        acquireWakeLock()

        server = ServerManager(this, settings)
        server.listener = this
        // Thermal pause drains in-flight completions instead of cutting them.
        server.drainProbe = { requests.inFlightCount }

        thermal = ThermalMonitor(this) { sample -> onThermalSample(sample) }
        thermal.start()

        keepWarm = KeepWarm(settings, server, requests)
        keepWarm.start()

        benchmark = BenchmarkRunner(settings, server) { thermal.batteryTempC }

        hints = PerfHints(this)

        startApi()

        thread(name = "phonon-boot", isDaemon = true) {
            server.killStaleServers()
            synchronized(settings.configLock) {
                wantServer = settings.autoStart && settings.servingRequested &&
                    File(settings.modelPath).isFile
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
            // Control actions share the single-threaded reload executor with
            // the settings reload, so a launch and a stop cannot interleave
            // now that the teardown no longer runs under settings.configLock.
            ACTION_START_SERVER -> reloadExecutor.execute {
                synchronized(settings.configLock) {
                    wantServer = true
                    configHashAtLaunch = currentConfigHash()
                }
                server.start()
                updateNotification()
            }

            ACTION_STOP_SERVER -> reloadExecutor.execute {
                synchronized(settings.configLock) { wantServer = false }
                server.stop()
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
        // Cached on the 30 s thermal tick; read directly once, before the
        // first tick, so a cold /health still reports the battery.
        val battery = batterySnapshot ?: BatteryHealth.snapshot(this).also { batterySnapshot = it }
        json.put("pause_reason", server.pauseReason ?: JSONObject.NULL)
        if (battery.known) {
            json.put("battery_level", battery.levelPct)
            json.put("battery_charging", battery.charging)
        }
        if (::hints.isInitialized) hints.healthFields(json)
        // Device-agnostic reporting: what the CPU supports, what this build
        // requires, whether the current model fits in memory, and which signal
        // the thermal policy is actually using.
        val features = CpuFeatures.cached()
        json.put("cpu_dotprod", features.dotprod)
        json.put("cpu_i8mm", features.i8mm)
        json.put("cpu_features", features.summary)
        json.put("native_required_features", NativeRequirements.requiredLabel)
        val modelFile = File(settings.modelPath)
        val mmprojBytes = settings.catalogEntryForCurrentModel()
            ?.let { entry -> File(modelFile.parentFile, entry.mmprojFileName) }
            ?.takeIf { it.isFile }?.length() ?: 0L
        val fit = MemoryFit.check(this, modelFile.length(), mmprojBytes, settings.contextSize)
        json.put("mem_avail_bytes", fit.availBytes)
        json.put("mem_avail_gb", MemoryFit.gb(fit.availBytes))
        json.put("mem_required_bytes", fit.requiredBytes)
        json.put("mem_kv_estimate_bytes", fit.kvBytes)
        json.put("mem_fits", fit.fits)
        json.put("thermal_source", thermalSource.name.lowercase())
        json.put("thermal_detail", thermalDetail)
        json.put("thermal_user_override", settings.thermalUserOverride)
        json.put("thermal_status", thermal.thermalStatus ?: JSONObject.NULL)
        json.put("thermal_headroom", thermal.headroom?.toDouble() ?: JSONObject.NULL)
        if (::socTuning.isInitialized) {
            json.put("soc_tuning_profile", socTuning.activeProfileJson())
            // A stored profile measured against a different engine is not
            // applied; say which revision it was, so the UI can ask for a re-run.
            val stale = socTuning.staleEngineRev()
            if (stale != null) json.put("soc_tuning_stale_rev", stale)
        }
        json.put("soc_tuning_running", tuningRunning)
        json.put("soc_tuning_state", tuningState)
        // Charging hygiene: the platform owns charge limiting, so all the app can
        // do is notice and say so. Four hours at 100% is the brief's threshold.
        // The timestamp is persisted, so a service restart does not reset the
        // clock on a phone left plugged in.
        val fullBattery = batterySnapshot ?: BatteryHealth.snapshot(this)
        val oem = OemGuidance.forDevice()
        json.put("oem_vendor", oem.vendor)
        json.put("oem_dont_kill_url", oem.dontKillUrl)
        val fullSince = settings.batteryFullSinceMs
        val nowMs = System.currentTimeMillis()
        val heldFull = OemGuidance.fullHours(fullSince, nowMs)
        val dismissed = fullSince > 0 && settings.chargeWarningDismissedSinceMs == fullSince
        json.put(
            "battery_full_warning",
            OemGuidance.sittingFull(fullBattery, fullSince, nowMs) && !dismissed,
        )
        json.put("battery_full_hours", heldFull ?: 0)
        json.put("battery_full_dismissed", dismissed)
        if (fullSince > 0) json.put("battery_full_since_ms", fullSince)
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
        // Refuse a download that could not be served even after it lands. Only a
        // catalog entry has a known size; a bare URL is downloaded and the fit is
        // decided at load time instead.
        entryId?.let { id ->
            ModelCatalog.byId(id)?.let { entry ->
                val mmproj = if (entry.mmprojSizeBytes > 0) entry.mmprojSizeBytes else 0L
                val fit = MemoryFit.check(this, entry.sizeBytes, mmproj, entry.contextSize)
                if (!fit.fits) {
                    Log.w(TAG, "download refused: ${fit.message}")
                    return JSONObject().put("error", fit.message).toString()
                }
            }
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
                // Vision needs the projector next to the weights; without it
                // llama-server is text-only whatever the model can do.
                if (entry != null && entry.mmprojUrl.isNotBlank()) {
                    val projector = File(modelsDir, entry.mmprojFileName)
                    if (!projector.isFile || projector.length() != entry.mmprojSizeBytes) {
                        DownloadState.begin(entry.mmprojUrl, projector.absolutePath)
                        ModelDownloader(this).download(
                            entry.mmprojUrl, projector, entry.mmprojSha256, entryId,
                        ) { done, total -> DownloadState.progress(done, total) }
                        Log.i(TAG, "vision projector ready: ${projector.absolutePath}")
                    }
                }
                if (entry != null) {
                    // File, context and alias move together for a catalog
                    // entry; a bare URL download only sets the path.
                    settings.selectModel(entry)
                } else {
                    settings.modelPath = dest.absolutePath
                }
                DownloadState.success(dest.absolutePath)
                Log.i(TAG, "download complete: ${dest.absolutePath} (${dest.length()} bytes)")
                val relaunch = synchronized(settings.configLock) {
                    // If the server was running, it is running the previous
                    // model: apply the new one. If it was stopped, leave it
                    // stopped — autoStart governs boot, not this decision.
                    if (server.isDesired && !server.thermalPaused) {
                        wantServer = true
                        configHashAtLaunch = currentConfigHash()
                        true
                    } else {
                        false
                    }
                }
                // The launch goes on the reload executor like every other
                // child start: never under settings.configLock.
                if (relaunch) {
                    try {
                        reloadExecutor.execute { server.start() }
                    } catch (_: Throwable) {
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

    override fun startCatalogDownload(id: String): String = uiDownloadModel(id)

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
            if (!settings.isInsideModelsDir(settings.modelPath)) {
                return error("model_path must be inside the models directory")
            }
            wantServer = true
            settings.servingRequested = true
            configHashAtLaunch = currentConfigHash()
        }
        // The launch shares the reload executor, so it no longer needs the
        // settings lock held across a multi-second teardown.
        try {
            reloadExecutor.execute {
                server.start()
                updateNotification()
            }
        } catch (_: Throwable) {
            return error("service shutting down")
        }
        return JSONObject().put("accepted", true).toString()
    }

    override fun stopServer() {
        synchronized(settings.configLock) {
            wantServer = false
            settings.servingRequested = false
        }
        try {
            reloadExecutor.execute {
                server.stop()
                updateNotification()
            }
        } catch (_: Throwable) {
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
                var rateLimit = settings.rateLimitPerMin

                if (json.has("model_path")) {
                    val value = json.optString("model_path").trim()
                    if (value.isBlank()) return error("model_path must not be blank")
                    // The API sets this path; contain it to the app's own
                    // models directory rather than any file on the device.
                    if (!settings.isInsideModelsDir(value)) {
                        return error("model_path must be inside the models directory")
                    }
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
                if (json.has("rate_limit_per_min")) {
                    val value = json.optInt("rate_limit_per_min", rateLimit)
                    if (value < 0 || value > Settings.MAX_RATE_LIMIT_PER_MIN) {
                        return error(
                            "rate_limit_per_min must be 0..${Settings.MAX_RATE_LIMIT_PER_MIN}",
                        )
                    }
                    rateLimit = value
                }
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
                settings.rateLimitPerMin = rateLimit
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
            beginReload()
            reloadExecutor.execute { reloadSettings(markReload = true) }
        } catch (_: Throwable) {
            endReload()
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
        try {
            reloadExecutor.execute {
                synchronized(settings.configLock) { configHashAtLaunch = currentConfigHash() }
                server.resumeFromThermal()
                updateNotification()
            }
        } catch (_: Throwable) {
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
        // The restart runs on the reload executor, not under
        // settings.configLock, for the same reason as a settings reload.
        try {
            reloadExecutor.execute {
                synchronized(settings.configLock) { configHashAtLaunch = currentConfigHash() }
                if (server.isDesired && !server.thermalPaused) server.restart()
                updateNotification()
            }
        } catch (_: Throwable) {
            return "service shutting down"
        }
        return null
    }

    fun uiSetAnimateAvatar(on: Boolean) {
        settings.animateAvatarWhileGenerating = on
    }

    // --- per-SoC tuning (manual sweep) ------------------------------------

    /** JSON for the Settings card: the stored profile, or "none". */
    fun uiSocTuningJson(): String =
        if (::socTuning.isInitialized) socTuning.profile()?.toJson()?.toString() ?: "none" else "none"

    fun uiSocTuningSoc(): String = if (::socTuning.isInitialized) socTuning.soc else ""

    fun uiClearSocTuning() {
        if (::socTuning.isInitialized) {
            socTuning.clear()
            settings.clearPerfOverrides()
        }
    }

    /**
     * Run the per-SoC sweep. Not automatic: it restarts the server once per
     * configuration and takes a couple of minutes, so it only runs when the
     * operator asks. Returns false when a sweep is already running or the server
     * is not ready. It never touches the selected model: only threads, the CPU
     * pin and `-tb` are varied.
     *
     * NOTE: implemented and unit-tested at the logic level; the end-to-end run
     * still needs a device. See the branch report for the exact steps.
     */
    fun uiTuneThisPhone(): Boolean {
        if (!::socTuning.isInitialized) return false
        if (server.state != ServerState.READY) return false
        synchronized(tuningGuard) {
            if (tuningRunning) return false
            tuningRunning = true
            tuningCancelled = false
        }
        thread(name = "phonon-tune", isDaemon = true) {
            try {
                runTuningSweep()
            } catch (t: Throwable) {
                tuningState = "failed: ${t.javaClass.simpleName}: ${t.message}"
                Log.w(TAG, "tuning failed", t)
            } finally {
                tuningRunning = false
            }
        }
        return true
    }

    /** Ask the running sweep to stop after the current step. */
    fun uiCancelSocTuning() {
        tuningCancelled = true
        tuningState = "cancelling…"
    }

    /** Hide the charge-hygiene line until the phone drops off full. */
    fun uiDismissChargeWarning() {
        settings.chargeWarningDismissedSinceMs = settings.batteryFullSinceMs
    }

    private val tuningGuard = Any()

    private fun runTuningSweep() {
        val detection = PerfProfile.detect()
        val fast = detection.best
        val fastRange = fast?.range.orEmpty()
        val decodeConfigs = SocTuning.decodeConfigs(fastRange)
        val prefillValues = SocTuning.prefillConfigs(fast?.size ?: 0)
        // The operator's own overrides are restored when the sweep ends, so the
        // sweep's temporary values never masquerade as their chosen settings.
        val saved = settings.snapshotPerfOverrides()
        val decode = mutableListOf<Pair<Int, Double>>()
        val prefill = mutableListOf<Pair<Int, Double>>()
        var bestRange = fastRange
        try {
            decodeConfigs.forEachIndexed { i, cfg ->
                if (tuningCancelled) return
                tuningState = "decode ${i + 1}/${decodeConfigs.size}: ${cfg.label}"
                applySweepConfig(cfg.threads, cfg.cpuRange)
                server.restart()
                if (!awaitReady() || tuningCancelled) return@forEachIndexed
                val run = measureOnce(SocTuning.DECODE_PROMPT, SocTuning.DECODE_TOKENS)
                    ?: return@forEachIndexed
                decode.add(cfg.threads to run.optDouble("decode_tps", 0.0))
                if (decode.maxByOrNull { it.second }?.first == cfg.threads) bestRange = cfg.cpuRange
            }
            prefillValues.forEachIndexed { i, tb ->
                if (tuningCancelled) return
                tuningState = "prefill ${i + 1}/${prefillValues.size}: -tb $tb"
                settings.setPrefillThreadsOverride(tb)
                server.restart()
                if (!awaitReady() || tuningCancelled) return@forEachIndexed
                val run = measureOnce(SocTuning.PREFILL_PROMPT, SocTuning.PREFILL_TOKENS)
                    ?: return@forEachIndexed
                prefill.add(tb to run.optDouble("prefill_tps", 0.0))
            }
            val profile = SocTuning.best(
                soc = socTuning.soc,
                decodeCpuRange = bestRange,
                contextSize = settings.contextSize,
                keepWarmIntervalS = settings.keepWarmIntervalS,
                decode = decode,
                prefill = prefill,
                engineRev = SocTuning.ENGINE_REV,
            )
            if (profile == null) {
                tuningState = "failed: no usable measurement"
                return
            }
            socTuning.save(profile)
            tuningState = "done: %.1f tok/s decode at %dt, %.1f tok/s prefill at -tb %d"
                .format(
                    profile.decodeTps, profile.decodeThreads,
                    profile.prefillTps, profile.prefillThreads,
                )
            Log.i(TAG, "tuning profile saved: $tuningState")
        } finally {
            settings.restorePerfOverrides(saved)
            if (tuningCancelled) tuningState = "cancelled"
            // Relaunch once so the final state — the saved profile, or the
            // restored operator overrides — is what the running child gets.
            if (!destroyed) server.restart()
        }
    }

    /** Point a sweep step at [threads] and [cpuRange] (empty = unpinned). */
    private fun applySweepConfig(threads: Int, cpuRange: String) {
        settings.setThreadsOverride(threads)
        if (PerfProfile.validCpuRange(cpuRange)) settings.setCpuRangeOverride(cpuRange)
        else settings.clearCpuRangeOverride()
    }

    private fun awaitReady(timeoutMs: Long = TUNING_READY_TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !destroyed && !tuningCancelled) {
            when (server.state) {
                ServerState.READY -> return true
                ServerState.ERROR, ServerState.THERMAL_PAUSE -> return false
                else -> try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    return false
                }
            }
        }
        return false
    }

    /**
     * One benchmarking run through the app's own /v1, with the prompt the step
     * needs: a short agent-shaped prompt for decode, a long one for prefill.
     * Returns the fresh result, or null when the run failed or was cancelled.
     */
    private fun measureOnce(prompt: String, maxTokens: Int): JSONObject? {
        val before = settings.benchmarkHistory.firstOrNull()?.optString("timestamp")
        benchmark.start(prompt, maxTokens)
        val deadline = System.currentTimeMillis() + TUNING_MEASURE_TIMEOUT_MS
        while (benchmark.running && System.currentTimeMillis() < deadline &&
            !destroyed && !tuningCancelled
        ) {
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                return null
            }
        }
        val latest = settings.benchmarkHistory.firstOrNull() ?: return null
        // Guard against returning a stale entry when this run produced nothing.
        if (latest.optString("timestamp") == before) return null
        return latest
    }

    // --- internals --------------------------------------------------------

    /**
     * Apply a committed settings change. The kill/launch runs on this
     * single-threaded executor but *outside* settings.configLock: a teardown
     * can take seconds (destroy + waitFor), and holding the lock across it
     * would block every concurrent settings read and write. Only the settings
     * mutation itself needs the lock; the executor already serialises
     * restarts, and the control actions run on it too.
     */
    private fun reloadSettings(markReload: Boolean = false) {
        try {
            if (destroyed) return
            var refusal: String? = null
            var action = ReloadAction.None
            synchronized(settings.configLock) {
                if (destroyed) return
                val exposure = settings.exposureError
                if (exposure != null) {
                    // Unsafe configuration: no off-host listener and no
                    // backend. A loopback-only operator listener stays up so
                    // /health can explain the refusal.
                    refusal = exposure
                    if (api == null || apiPort != settings.port ||
                        apiBind != Settings.DEFAULT_BIND_ADDRESS
                    ) {
                        api?.stop()
                        api = null
                        startApi()
                    }
                } else {
                    val hash = currentConfigHash()
                    val changed = hash != configHashAtLaunch
                    val desired = wantServer || server.isDesired
                    if (changed) configHashAtLaunch = hash

                    // The operator listener is not touched here. It is either
                    // already serving the committed configuration (a key-only
                    // change) or was replaced atomically by updateSettings. A
                    // request that arrives from now on gets 503 + Retry-After
                    // while control endpoints observe isRestarting().
                    action = when {
                        server.thermalPaused || !desired -> ReloadAction.None
                        changed -> ReloadAction.Restart
                        server.state == ServerState.STOPPED ||
                            server.state == ServerState.ERROR -> ReloadAction.Start
                        else -> ReloadAction.None
                    }

                    if (api == null) startApi()
                }
            }

            // Everything below can block for seconds; none of it needs
            // settings.configLock, and the reload executor serialises it.
            val refuse = refusal
            if (refuse != null) {
                server.refuseUnsafeExposure(refuse)
                thermal.pollNow()
                updateNotification()
                return
            }

            var restarted = false
            when (action) {
                ReloadAction.Restart -> {
                    Log.i(TAG, "server settings changed; restarting llama-server")
                    server.restart()
                    restarted = true
                }

                ReloadAction.Start -> {
                    // Recover from a refusal or a previous failure without
                    // needing an explicit POST /server/start.
                    Log.i(TAG, "configuration is safe again; starting llama-server")
                    server.start()
                    restarted = true
                }

                ReloadAction.None -> {}
            }

            // Thresholds take effect now, which is what makes the thermal test
            // possible without a heat gun.
            thermal.pollNow()
            updateNotification()

            // Do not clear the 503 window until the replacement backend is
            // ready, so an immediate retry sees a settled server.
            if (restarted) awaitBackendSettled()
        } finally {
            if (markReload) endReload()
        }
    }

    /** What a committed settings change needs from the child lifecycle. */
    private enum class ReloadAction { None, Start, Restart }

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

    private fun onThermalSample(sample: ThermalSample) {
        if (destroyed) return
        val stop = settings.thermalStopC
        val resume = settings.thermalResumeC

        // Battery policy: do not run an unplugged phone flat. This is the app's
        // only lever — the platform owns the charger, so charge limiting is
        // documented as a ROM/root capability, not something this code claims.
        val battery = BatteryHealth.snapshot(this)
        batterySnapshot = battery
        // Track how long the phone has sat at 100% on the charger, for the
        // charge-hygiene warning. Persisted, so a service restart on a phone
        // left plugged in does not quietly restart the clock; reset the instant
        // it drops off full, and clear the operator's dismissal with it.
        if (battery.charging && battery.levelPct == 100) {
            if (settings.batteryFullSinceMs == 0L) {
                settings.batteryFullSinceMs = System.currentTimeMillis()
            }
        } else if (settings.batteryFullSinceMs != 0L) {
            settings.batteryFullSinceMs = 0L
            settings.chargeWarningDismissedSinceMs = 0L
        }
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

        // Thermal policy. The platform's headroom/status signal drives the
        // decision unless the operator set their own battery thresholds; battery
        // temperature is the fallback when neither platform signal exists.
        val decision = ThermalPolicy.evaluate(
            sample = sample,
            stopC = stop,
            resumeC = resume,
            override = settings.thermalUserOverride,
            paused = server.thermalPaused,
            pauseReason = server.pauseReason,
        )
        thermalSource = decision.source
        thermalDetail = decision.detail
        if (decision.pause) {
            Log.i(TAG, "thermal: ${decision.detail} (${decision.source}), pausing server")
            server.pauseForThermal()
            updateNotification()
        } else if (decision.resume) {
            Log.i(TAG, "thermal: ${decision.detail} (${decision.source}), resuming server")
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
        // The manifest declares both dataSync (API 29+) and specialUse (API 34+).
        // Start with the type the running platform understands: passing the
        // specialUse bit below API 34 would name a type the framework does not
        // know, so an Android 10-13 phone gets dataSync instead.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        startForeground(NOTIFICATION_ID, notification, type)
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
        if (safe == "." || safe == ".." || safe.isBlank()) return ""
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
        private const val RELOAD_SETTLE_TIMEOUT_MS = 120_000L

        /** Bounds for the per-SoC tuning sweep's restart-and-measure steps. */
        private const val TUNING_READY_TIMEOUT_MS = 120_000L
        private const val TUNING_MEASURE_TIMEOUT_MS = 120_000L
    }
}

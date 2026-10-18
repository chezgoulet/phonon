package org.phononai.app

import android.content.Context
import android.os.Process as AndroidProcess
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

enum class ServerState(val wire: String) {
    LOADING("loading"),
    READY("ready"),
    RESTARTING("restarting"),
    THERMAL_PAUSE("thermal_pause"),
    STOPPED("stopped"),
    ERROR("error"),
}

/**
 * Owns the llama-server child process.
 *
 * - one process at a time: every launch kills the previous one first, and the
 *   service also sweeps /proc at startup for children left by a dead instance;
 * - unexpected exit -> state restarting, relaunch after 1, 2, 4, ... 30 s;
 * - readiness is a real GET on the backend /health, never an assumption;
 * - stdout/stderr go to a ring buffer as well as the --log-file, which is
 *   tailed because llama.cpp writes most of its output there.
 */
class ServerManager(
    private val context: Context,
    private val settings: Settings,
) {

    fun interface Listener {
        fun onServerChanged()
    }

    @Volatile var listener: Listener? = null
    @Volatile var state: ServerState = ServerState.STOPPED
        private set
    @Volatile var pid: Int = -1
        private set
    @Volatile var restartCount: Int = 0
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var lastDecodeTps: Double? = null
        private set
    @Volatile var lastPromptTps: Double? = null
        private set
    @Volatile var thermalPaused: Boolean = false
        private set
    @Volatile var isDesired: Boolean = false
        private set
    @Volatile var mlockRefused: Boolean = false
        private set

    /** The profile the running child was launched with; null when stopped. */
    @Volatile var planAtLaunch: PerfPlan? = null
        private set

    /** Wall clock when the current child was spawned; changes on every load. */
    @Volatile var loadedAtMs: Long = 0L
        private set

    val ringLog = RingLog(600)

    private val lock = Any()
    @Volatile private var process: Process? = null
    @Volatile private var generation = 0
    @Volatile private var spawnAtMs = 0L
    private var backoffMs = 1_000L
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "phonon-supervisor").apply { isDaemon = true }
    }

    fun start() {
        synchronized(lock) {
            settings.exposureError?.let { message ->
                refuseUnsafeExposure(message)
                return
            }
            if (thermalPaused) {
                lastError = "start refused: thermal pause active"
                ringLog.append(lastError!!)
                return
            }
            if (isDesired && process != null &&
                (state == ServerState.READY || state == ServerState.LOADING || state == ServerState.RESTARTING)
            ) {
                return
            }
            val model = File(settings.modelPath)
            if (!model.isFile) {
                lastError = "model not found: ${model.absolutePath}"
                ringLog.append(lastError!!)
                setState(ServerState.ERROR)
                return
            }
            isDesired = true
            backoffMs = 1_000
            lastError = null
            generation++
            killLocked()
            launchLocked(generation)
        }
    }

    fun restart() {
        stop()
        start()
    }

    /**
     * Refuse to run with an off-loopback bind and no API key. Called before any
     * listener is bound; the app API falls back to loopback so /health can
     * report the error without exposing anything to the LAN.
     */
    fun refuseUnsafeExposure(message: String) {
        synchronized(lock) {
            isDesired = false
            thermalPaused = false
            pauseReason = null
            generation++
            killLocked()
            lastError = message
            ringLog.append("start refused: $message")
            Log.w(TAG, "server start refused: $message")
            setState(ServerState.ERROR)
        }
    }

    /** User stop: server down, app API and foreground service stay up. */
    fun stop() {
        synchronized(lock) {
            isDesired = false
            thermalPaused = false
            pauseReason = null
            generation++
            // Flip the state before the kill so the supervisor's own stop path
            // cannot be observed as "ready" while the child is going away.
            setState(ServerState.STOPPED)
            killLocked()
        }
    }

    /** Thermal stop: remember that the operator still wants the server. */
    /** Set by the service: how many client requests are streaming right now. */
    @Volatile var drainProbe: (() -> Int)? = null

    /** "thermal" or "battery" while paused; null when not paused. */
    @Volatile var pauseReason: String? = null

    /**
     * The model file the running child was actually launched with. Health must
     * report what is serving, not what settings would launch next: they differ
     * between selecting a model and restarting the server.
     */
    @Volatile private var launchedModelName: String? = null

    /**
     * Stop serving because the device is too warm, without cutting a completion
     * in half. New requests are refused as soon as the state leaves READY; the
     * child is killed only once the requests already streaming have finished,
     * or after [DRAIN_MAX_MS], whichever comes first.
     */
    fun pauseForThermal() = pauseServing("thermal")

    /** Pause because the battery is low and the device is not on a charger. */
    fun pauseForBattery() = pauseServing("battery")

    private fun pauseServing(reason: String) {
        synchronized(lock) {
            if (thermalPaused || !isDesired) return
            thermalPaused = true
            pauseReason = reason
            setState(ServerState.THERMAL_PAUSE)
        }
        val probe = drainProbe
        if (probe == null || probe() <= 0) {
            synchronized(lock) {
                generation++
                killLocked()
                ringLog.append("pause ($reason): server stopped")
                setState(ServerState.THERMAL_PAUSE)
            }
            return
        }
        ringLog.append("pause ($reason): waiting for ${probe()} in-flight request(s)")
        thread(name = "phonon-thermal-drain", isDaemon = true) {
            val deadline = System.currentTimeMillis() + DRAIN_MAX_MS
            while (System.currentTimeMillis() < deadline && probe() > 0) {
                Thread.sleep(200)
            }
            val left = probe()
            synchronized(lock) {
                if (!thermalPaused) return@thread
                generation++
                killLocked()
                ringLog.append(
                    if (left > 0) "pause ($reason): stopped with $left request(s) still open"
                    else "pause ($reason): drained, server stopped",
                )
                setState(ServerState.THERMAL_PAUSE)
            }
        }
    }

    fun resumeFromThermal() {
        synchronized(lock) {
            if (!thermalPaused) return
            thermalPaused = false
            generation++
            killLocked()
            if (!isDesired) {
                setState(ServerState.STOPPED)
                return
            }
            backoffMs = 1_000
            ringLog.append("thermal resume: starting server")
            launchLocked(generation)
        }
    }

    /** Service destroy: no retries, no surviving children. */
    fun shutdown() {
        synchronized(lock) {
            isDesired = false
            thermalPaused = false
            pauseReason = null
            generation++
            killLocked()
            setState(ServerState.STOPPED)
        }
        killStaleServers()
        scheduler.shutdownNow()
    }

    /**
     * Kill any llama-server child left behind by an earlier service instance.
     * Reads /proc; on Android only processes of our own uid are visible.
     */
    fun killStaleServers() {
        if (process != null) return
        val pids = findServerPids()
        if (pids.isEmpty()) return
        for (p in pids) {
            ringLog.append("killing stray llama-server pid=$p")
            Log.w(TAG, "killing stray llama-server pid=$p")
            try {
                AndroidProcess.sendSignal(p, 9)
            } catch (t: Throwable) {
                Log.w(TAG, "could not kill pid $p: ${t.message}")
            }
        }
        repeat(20) {
            if (findServerPids().isEmpty()) return
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    fun healthJson(batteryTempC: Float?): String {
        val up = if (spawnAtMs > 0 && pid > 0) {
            ((System.currentTimeMillis() - spawnAtMs) / 1_000L).coerceAtLeast(0L)
        } else {
            null
        }
        // Report the profile the running child actually got. When no child is
        // running, report what would be chosen now.
        val detected = settings.perfPlan()
        val plan = if (pid > 0) planAtLaunch ?: detected else detected
        // A READY state with no live pid is a restart window, not readiness.
        // The process can exit between the supervisor's checks; health must
        // never report ready while there is nothing to serve.
        val reported = if (state == ServerState.READY && pid <= 0) ServerState.RESTARTING else state
        return JSONObject().apply {
            put("state", reported.wire)
            put("model", launchedModelName ?: File(settings.modelPath).name)
            put("pid", if (pid > 0) pid else JSONObject.NULL)
            put("uptime_s", up ?: JSONObject.NULL)
            put("battery_temp_c", batteryTempC?.toDouble() ?: JSONObject.NULL)
            put("thermal_paused", thermalPaused)
            put("last_decode_tps", lastDecodeTps ?: JSONObject.NULL)
            put("last_prompt_tps", lastPromptTps ?: JSONObject.NULL)
            put("restart_count", restartCount)
            put("bind_address", settings.bindAddress)
            put("api_key_set", settings.apiKey.isNotBlank())
            put("exposed_off_host", settings.exposedOffHost)
            put("backend_port", settings.backendPort)
            put("load_mode", settings.loadMode)
            put("mlock_refused", mlockRefused)
            put("error", lastError ?: JSONObject.NULL)
            // Read-only performance profile. The running values are what the
            // child was launched with; detected values are what Android would
            // allow a new child to use right now.
            put("perf_mode", plan.mode)
            put("perf_allowed_cpus", plan.allowed.joinToString(","))
            put("perf_cpu_range", plan.cpuRange)
            put("perf_threads", plan.threads)
            put("perf_prio", plan.prio)
            put("perf_prio_batch", plan.prioBatch)
            put("perf_auto", plan.auto)
            put(
                "perf_overrides",
                listOfNotNull(
                    "threads".takeIf { plan.threadsSet },
                    "cpu_range".takeIf { plan.cpuRangeSet },
                    "priority".takeIf { plan.prioSet },
                ).joinToString(","),
            )
            put("perf_detected_mode", detected.mode)
            put("perf_detected_cpu_range", detected.cpuRange)
            put("perf_detected_threads", detected.threads)
        }.toString()
    }

    /** Per-token time at the last observed decode rate (200 ms until known). */
    fun perTokenNanos(): Long =
        lastDecodeTps?.takeIf { it > 0 }?.let { (1_000_000_000.0 / it).toLong() }
            ?: DEFAULT_TOKEN_NANOS

    /** Called by the proxy for non-streaming response bodies. */
    fun recordTimings(body: String) {
        DECODE_RE.findAll(body).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()?.let {
            if (it > 0) lastDecodeTps = it
        }
        PROMPT_RE.findAll(body).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()?.let {
            if (it > 0) lastPromptTps = it
        }
    }

    private fun launchLocked(gen: Int) {
        launchedModelName = File(settings.modelPath).name
        val bin = File(context.applicationInfo.nativeLibraryDir, BIN_NAME)
        if (!bin.isFile) {
            lastError = "missing native binary: ${bin.absolutePath}"
            isDesired = false
            setState(ServerState.ERROR)
            ringLog.append(lastError!!)
            return
        }
        val model = File(settings.modelPath)
        if (!model.isFile) {
            lastError = "model not found: ${model.absolutePath}"
            isDesired = false
            setState(ServerState.ERROR)
            ringLog.append(lastError!!)
            return
        }

        val logDir = File(context.filesDir, "logs").apply { mkdirs() }
        val logFile = File(logDir, "llama-server.log")
        if (logFile.exists()) logFile.delete()
        val plan = settings.perfPlan()
        planAtLaunch = plan
        val args = buildArgs(model, logFile, plan)
        val printable = args.mapIndexed { i, arg ->
            if (i > 0 && args[i - 1] == "--api-key") "***" else arg
        }.joinToString(" ")
        ringLog.append("start ${bin.absolutePath} $printable")
        Log.i(TAG, "starting ${bin.absolutePath} $printable")

        setState(ServerState.LOADING)
        mlockRefused = false

        val pb = ProcessBuilder(listOf(bin.absolutePath) + args)
            .directory(context.filesDir)
            .apply {
                environment().apply {
                    put("LD_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir)
                    put("HOME", context.filesDir.absolutePath)
                    put("TMPDIR", context.cacheDir.absolutePath)
                    put("PATH", "/system/bin:/system/xbin")
                }
            }

        val p = try {
            pb.start()
        } catch (t: Throwable) {
            lastError = "exec failed: ${t.javaClass.simpleName}: ${t.message}"
            ringLog.append(lastError!!)
            Log.e(TAG, lastError, t)
            setState(ServerState.ERROR)
            scheduleRetryLocked(gen)
            return
        }

        process = p
        pid = processPid(p)
        spawnAtMs = System.currentTimeMillis()
        loadedAtMs = spawnAtMs
        ringLog.append("spawned pid=$pid")

        thread(name = "phonon-out-$gen", isDaemon = true) { pump(p.inputStream, "out") }
        thread(name = "phonon-err-$gen", isDaemon = true) { pump(p.errorStream, "err") }
        thread(name = "phonon-tail-$gen", isDaemon = true) { tailLog(gen, logFile) }
        thread(name = "phonon-wait-$gen", isDaemon = true) { waitForExit(gen, p) }
        thread(name = "phonon-ready-$gen", isDaemon = true) { pollReady(gen, p) }
    }

    private fun buildArgs(model: File, logFile: File, plan: PerfPlan): List<String> = buildList {
        add("-m"); add(model.absolutePath)
        add("-c"); add(settings.contextSize.toString())
        add("-t"); add(plan.threads.toString())
        if (plan.cpuRange.isNotBlank()) {
            add("--cpu-range"); add(plan.cpuRange)
        }
        add("--prio"); add(plan.prio.toString())
        add("--prio-batch"); add(plan.prioBatch.toString())
        add("-np"); add("1")
        add("-fa"); add("on")
        add("--jinja")
        add("--reasoning"); add(if (settings.reasoning) "on" else "off")
        add("--alias"); add(settings.alias)
        // The backend is loopback-only by design: the app's authenticated
        // proxy is the single LAN listener. Binding it to the operator address
        // exposed llama-server's unauthenticated /props and /metrics.
        add("--host"); add("127.0.0.1")
        add("--port"); add(settings.backendPort.toString())
        add("--log-file"); add(logFile.absolutePath)
        add("-lm"); add(settings.loadMode)
        // No --api-key on the backend: argv is world-readable on Android, so the
        // key would appear in `ps` for any shell user. The proxy enforces auth
        // and the backend only listens on loopback.
    }

    private fun waitForExit(gen: Int, p: Process) {
        val code = try {
            p.waitFor()
        } catch (_: InterruptedException) {
            return
        }
        synchronized(lock) {
            if (process !== p) return
            process = null
            pid = -1
            spawnAtMs = 0
            if (generation != gen) return
            if (!isDesired) {
                setState(ServerState.STOPPED)
                return
            }
            if (thermalPaused) {
                setState(ServerState.THERMAL_PAUSE)
                return
            }
            lastError = "llama-server exited with code $code"
            restartCount++
            ringLog.append(lastError!!)
            setState(ServerState.RESTARTING)
            scheduleRetryLocked(gen)
        }
    }

    private fun scheduleRetryLocked(gen: Int) {
        if (!isDesired || thermalPaused) return
        val delay = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        ringLog.append("restart in ${delay}ms (backoff)")
        scheduler.schedule({
            synchronized(lock) {
                if (!isDesired || thermalPaused || generation != gen || process != null) return@schedule
                launchLocked(gen)
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun pollReady(gen: Int, p: Process) {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) return
            synchronized(lock) {
                if (generation != gen || process !== p || !isDesired || thermalPaused) return
            }
            if (probeBackend()) {
                synchronized(lock) {
                    if (generation == gen && process === p && isDesired && !thermalPaused) {
                        backoffMs = 1_000
                        lastError = null
                        val seconds = ((System.currentTimeMillis() - spawnAtMs) / 1_000L).coerceAtLeast(0L)
                        ringLog.append("ready after ${seconds}s, pid=$pid")
                        Log.i(TAG, "ready after ${seconds}s, pid=$pid")
                        setState(ServerState.READY)
                    }
                }
                return
            }
            try {
                Thread.sleep(READY_POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
        synchronized(lock) {
            if (generation == gen && process === p && isDesired && !thermalPaused &&
                state != ServerState.READY
            ) {
                lastError = "server did not answer /health within ${READY_TIMEOUT_MS / 1_000}s"
                ringLog.append(lastError!!)
                setState(ServerState.ERROR)
                // A load that never finishes is a failure; let supervision retry.
                p.destroy()
            }
        }
    }

    private fun probeBackend(): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("http://${settings.backendHost()}:${settings.backendPort}/health").openConnection()
                as HttpURLConnection
            conn.connectTimeout = 1_000
            conn.readTimeout = 1_500
            conn.requestMethod = "GET"
            conn.responseCode == 200
        } catch (_: Throwable) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun processPid(p: Process): Int = try {
        val method = p.javaClass.getMethod("pid")
        (method.invoke(p) as Long).toInt()
    } catch (_: Throwable) {
        findServerPids().firstOrNull() ?: -1
    }

    private fun killLocked() {
        val p = process ?: return
        process = null
        pid = -1
        spawnAtMs = 0
        try {
            p.destroy()
        } catch (_: Throwable) {
        }
        val exited = try {
            p.waitFor(4, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!exited) {
            try {
                p.destroyForcibly()
            } catch (_: Throwable) {
            }
            try {
                p.waitFor(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
            }
        }
        ringLog.append("stopped pid=${processPid(p)}")
    }

    private fun setState(s: ServerState) {
        state = s
        listener?.onServerChanged()
    }

    private fun pump(input: InputStream, tag: String) {
        try {
            BufferedInputStream(input).bufferedReader().useLines { lines ->
                for (line in lines) {
                    recordLine("[$tag] $line")
                    Log.i(TAG, "[$tag] $line")
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Record a server log line and notice an mlock refusal. The kernel answer is
     * the only trustworthy signal: the app must not report mlock as working just
     * because -lm mlock was requested.
     */
    private fun recordLine(text: String) {
        ringLog.append(text)
        if (!mlockRefused && text.contains("failed to mlock", ignoreCase = true)) {
            mlockRefused = true
            ringLog.append("load mode mlock refused by kernel: ${text.trim()}")
            Log.w(TAG, "mlock refused: ${text.trim()}")
        }
    }

    /** Follow --log-file so the ring buffer sees what llama.cpp writes there. */
    private fun tailLog(gen: Int, file: File) {
        var pos = 0L
        val pending = StringBuilder()
        while (true) {
            synchronized(lock) {
                if (generation != gen) return
            }
            try {
                if (file.isFile) {
                    if (file.length() < pos) {
                        pos = 0
                        pending.clear()
                    }
                    if (file.length() > pos) {
                        FileInputStream(file).use { fis ->
                            fis.channel.position(pos)
                            val buf = ByteArray(64 * 1024)
                            var n: Int
                            while (true) {
                                n = fis.read(buf)
                                if (n <= 0) break
                                pending.append(String(buf, 0, n, Charsets.UTF_8))
                                pos += n
                                while (true) {
                                    val idx = pending.indexOf("\n")
                                    if (idx < 0) break
                                    val line = pending.substring(0, idx).trimEnd('\r')
                                    pending.delete(0, idx + 1)
                                    if (line.isNotEmpty()) {
                                        recordLine("[log] $line")
                                        Log.i(TAG, "[log] $line")
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            try {
                Thread.sleep(1_000)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun findServerPids(): List<Int> {
        val binPath = File(context.applicationInfo.nativeLibraryDir, BIN_NAME).absolutePath
        val uid = context.applicationInfo.uid
        val result = mutableListOf<Int>()
        val entries = File("/proc").list() ?: return result
        for (entry in entries) {
            val candidate = entry.toIntOrNull() ?: continue
            try {
                val cmdline = File("/proc/$entry/cmdline").readText()
                if (!cmdline.contains(binPath)) continue
                val status = File("/proc/$entry/status").readText()
                val uidLine = status.lineSequence().firstOrNull { it.startsWith("Uid:") } ?: continue
                val procUid = uidLine.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: continue
                if (procUid != uid) continue
                result.add(candidate)
            } catch (_: Throwable) {
                // Process disappeared or is not visible (different uid).
            }
        }
        return result
    }

    private companion object {
        /** Upper bound on how long a thermal pause waits for in-flight work. */
        private const val DRAIN_MAX_MS = 120_000L
        const val TAG = "Phonon"
        const val BIN_NAME = "libllama-server.so"
        const val READY_TIMEOUT_MS = 120_000L
        const val READY_POLL_MS = 500L
        const val MAX_BACKOFF_MS = 30_000L
        const val DEFAULT_TOKEN_NANOS = 200_000_000L
        val DECODE_RE = Regex("\"predicted_per_second\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)")
        val PROMPT_RE = Regex("\"prompt_per_second\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)")
    }
}

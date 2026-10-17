package org.phononai.app

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Shared view of client traffic through the /v1 proxy. KeepWarm uses it to
 * decide whether a beat is safe; ApiServer updates it around every proxied
 * request.
 */
class RequestTracker {
    private val lock = Any()
    private var inFlight = 0
    private var beatInFlight = false
    private var lastClientAtMs = 0L

    val inFlightCount: Int
        get() = synchronized(lock) { inFlight }

    val lastClientRequestAtMs: Long
        get() = synchronized(lock) { lastClientAtMs }

    fun beginClient() = synchronized(lock) {
        inFlight++
        lastClientAtMs = System.currentTimeMillis()
    }

    fun endClient() = synchronized(lock) {
        if (inFlight > 0) inFlight--
        lastClientAtMs = System.currentTimeMillis()
    }

    /**
     * Reserve a beat only if no client request is in flight and none arrived
     * within [intervalMs] of [sinceMs] (the later of "ready" and the previous
     * beat).
     */
    fun tryStartBeat(nowMs: Long, intervalMs: Long, sinceMs: Long): Boolean =
        synchronized(lock) {
            if (inFlight > 0 || beatInFlight) return false
            val base = maxOf(lastClientAtMs, sinceMs)
            if (nowMs - base < intervalMs) return false
            beatInFlight = true
            true
        }

    fun endBeat() = synchronized(lock) { beatInFlight = false }
}

/**
 * Periodic one-token completion against the backend.
 *
 * The model is 2.84 GB of mmap'd weights on a 7.65 GB phone. After a few idle
 * minutes Android reclaims those file-backed pages and the next request pays
 * tens of seconds of page faults. mlock is refused for an unprivileged app, so
 * the only lever left is to touch the pages with a real (tiny) generation
 * before the kernel takes them.
 *
 * A beat is sent only when:
 *  - keep_warm is enabled;
 *  - the supervisor state is exactly `ready`;
 *  - no client request is in flight;
 *  - no client request (and no beat) happened in the last interval.
 *
 * Failures are counted and never change /health state.
 */
class KeepWarm(
    private val settings: Settings,
    private val server: ServerManager,
    private val requests: RequestTracker,
) {
    @Volatile private var lastKeepaliveAtMs = 0L
    @Volatile private var count = 0
    @Volatile private var failures = 0
    @Volatile private var lastFailure: String? = null
    @Volatile private var readyAtMs = 0L
    @Volatile private var stopped = false

    val lastKeepaliveAtMsValue: Long get() = lastKeepaliveAtMs
    val keepaliveCount: Int get() = count
    val keepaliveFailures: Int get() = failures

    fun start() {
        thread(name = "phonon-keepwarm", isDaemon = true) {
            var wasReady = false
            while (!stopped) {
                try {
                    val ready = server.state == ServerState.READY
                    if (ready && !wasReady) readyAtMs = System.currentTimeMillis()
                    if (!ready) readyAtMs = 0L
                    wasReady = ready

                    if (ready && settings.keepWarm) maybeBeat()
                } catch (t: Throwable) {
                    // The loop must survive anything the beat path throws.
                    Log.d(TAG, "keepalive loop: ${t.javaClass.simpleName}: ${t.message}")
                }
                try {
                    Thread.sleep(TICK_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    fun stop() {
        stopped = true
    }

    fun healthFields(json: JSONObject) {
        json.put("keep_warm", settings.keepWarm)
        json.put("keep_warm_interval_s", settings.keepWarmIntervalS)
        json.put("last_keepalive_at", if (lastKeepaliveAtMs > 0) lastKeepaliveAtMs else JSONObject.NULL)
        json.put("keepalive_count", count)
        json.put("keepalive_failures", failures)
        if (lastFailure != null) json.put("keepalive_last_error", lastFailure)
    }

    private fun maybeBeat() {
        val intervalMs = settings.keepWarmIntervalS.coerceAtLeast(
            Settings.MIN_KEEP_WARM_INTERVAL_S,
        ) * 1_000L
        val now = System.currentTimeMillis()
        // The first beat lands one interval after `ready`; later beats are one
        // interval after the previous beat or the last client request.
        val since = maxOf(readyAtMs, lastKeepaliveAtMs)
        if (!requests.tryStartBeat(now, intervalMs, since)) return

        val started = System.currentTimeMillis()
        try {
            lastKeepaliveAtMs = started
            count++
            val code = postBeat()
            if (code in 200..299) {
                Log.d(TAG, "keepalive #$count ok: 1 token in ${System.currentTimeMillis() - started} ms")
            } else {
                failures++
                lastFailure = "HTTP $code"
                Log.d(TAG, "keepalive #$count failed: HTTP $code")
            }
        } catch (t: Throwable) {
            failures++
            lastFailure = "${t.javaClass.simpleName}: ${t.message}"
            Log.d(TAG, "keepalive #$count failed: $lastFailure")
        } finally {
            requests.endBeat()
        }
    }

    private fun postBeat(): Int {
        val body = JSONObject().apply {
            put("model", settings.alias)
            put(
                "messages",
                org.json.JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", BEAT_PROMPT)
                    },
                ),
            )
            put("max_tokens", 1)
            put("temperature", 0)
            put("stream", false)
        }.toString()
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("http://127.0.0.1:${settings.backendPort}/v1/chat/completions")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = BEAT_TIMEOUT_MS
            conn.readTimeout = BEAT_TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (settings.apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            // Discard the body; the point is the page touch, not the tokens.
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.use { s -> s.readBytes() }
            code
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "Phonon"
        private const val TICK_MS = 1_000L
        private const val BEAT_TIMEOUT_MS = 10_000
        private const val BEAT_PROMPT = "hi"
    }
}

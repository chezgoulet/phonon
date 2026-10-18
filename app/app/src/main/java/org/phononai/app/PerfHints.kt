package org.phononai.app

import android.content.Context
import android.os.PerformanceHintManager
import android.util.Log
import org.json.JSONObject

/**
 * ADPF hint session for inference.
 *
 * A session is opened for the `llama-server` process while a completion is in
 * flight and closed when it finishes. The target work duration is one token at
 * the server's last observed decode rate; until a rate exists, a conservative
 * 200 ms is used.
 *
 * Everything here degrades silently. A device without PerformanceHintManager,
 * a refused session (some vendors restrict hint sessions to the calling
 * process's own threads) or a dead child must never break serving; `/health`
 * reports what actually happened through `perf_hint_active` and
 * `perf_hint_detail`.
 */
class PerfHints(private val context: Context) {

    private val lock = Any()
    private var session: PerformanceHintManager.Session? = null
    private var depth = 0
    private var opened = 0

    @Volatile private var active = false
    @Volatile private var targetNanos = 0L
    @Volatile private var detail = "idle"

    val isActive: Boolean get() = active

    /** Start (or join) a hint session for the server's main thread. */
    fun begin(serverPid: Int, perTokenNanos: Long) {
        synchronized(lock) {
            depth++
            if (depth > 1) return
            if (serverPid <= 0) {
                detail = "no server pid"
                return
            }
            val target = perTokenNanos.coerceIn(MIN_TARGET_NS, MAX_TARGET_NS)
            val manager = try {
                context.getSystemService(PerformanceHintManager::class.java)
            } catch (t: Throwable) {
                detail = "unavailable: ${t.javaClass.simpleName}"
                Log.i(TAG, "ADPF unavailable: ${t.javaClass.simpleName}: ${t.message}")
                return
            }
            if (manager == null) {
                detail = "unavailable: no manager"
                return
            }
            try {
                // The child runs under this app's uid, so its main tid is a
                // valid work thread for the session. If the platform insists
                // on threads of the calling process this call throws and the
                // hint stays off rather than pointing at the wrong thread.
                val s = manager.createHintSession(intArrayOf(serverPid), target)
                    ?: run {
                        detail = "unavailable: no session"
                        return
                    }
                s.updateTargetWorkDuration(target)
                session = s
                targetNanos = target
                active = true
                opened++
                detail = "active"
                Log.i(
                    TAG,
                    "ADPF hint session open: tid=$serverPid target=${target / 1_000_000}ms",
                )
            } catch (t: Throwable) {
                detail = "unavailable: ${t.javaClass.simpleName}"
                Log.i(
                    TAG,
                    "ADPF hint session refused: ${t.javaClass.simpleName}: ${t.message}",
                )
            }
        }
    }

    /** Finish the request; [actualNanos] is the observed per-token time, if any. */
    fun end(actualNanos: Long?) {
        synchronized(lock) {
            if (depth > 0) depth--
            if (depth > 0) return
            val s = session ?: run {
                if (active) {
                    active = false
                    detail = "idle"
                }
                return
            }
            session = null
            active = false
            try {
                actualNanos?.takeIf { it > 0 }?.let {
                    @Suppress("DEPRECATION")
                    s.reportActualWorkDuration(it.coerceIn(MIN_TARGET_NS, MAX_TARGET_NS))
                }
                s.close()
                detail = "idle"
                Log.i(TAG, "ADPF hint session closed after $opened session(s)")
            } catch (t: Throwable) {
                detail = "close failed: ${t.javaClass.simpleName}"
                Log.i(TAG, "ADPF hint close failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    fun healthFields(json: JSONObject) {
        json.put("perf_hint_active", active)
        json.put(
            "perf_hint_target_ms",
            if (targetNanos > 0) targetNanos / 1_000_000.0 else JSONObject.NULL,
        )
        json.put("perf_hint_detail", detail)
    }

    private companion object {
        const val TAG = "Phonon"
        const val MIN_TARGET_NS = 2_000_000L        // 2 ms
        const val MAX_TARGET_NS = 2_000_000_000L    // 2 s
    }
}

package org.phononai.app

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlin.concurrent.thread

/**
 * One fixed request through the app's own /v1/chat/completions, measured from
 * this process. The numbers are the product's numbers: they include the app
 * proxy, the child process and whatever cgroup Android put it in.
 *
 * Results are appended to the settings store so the Serve screen and the
 * device report see the same history after a restart.
 */
class BenchmarkRunner(
    private val settings: Settings,
    private val server: ServerManager,
    private val batteryTemp: () -> Float?,
) {
    @Volatile var running: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set

    @Volatile private var cancelled = false
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var lastLoadAtMs = 0L

    /** Start on a background thread; returns immediately. */
    fun start(): Boolean {
        synchronized(this) {
            if (running) return false
            running = true
            cancelled = false
            lastError = null
        }
        thread(name = "phonon-benchmark", isDaemon = true) {
            try {
                runOnce()
            } catch (t: Throwable) {
                if (!cancelled) {
                    lastError = "${t.javaClass.simpleName}: ${t.message}"
                    Log.w(TAG, "benchmark failed: $lastError", t)
                }
            } finally {
                connection = null
                running = false
            }
        }
        return true
    }

    fun cancel() {
        cancelled = true
        try {
            connection?.disconnect()
        } catch (_: Throwable) {
        }
    }

    /** State for the activity. History is newest first. */
    fun stateJson(): String {
        val history = settings.benchmarkHistory
        return JSONObject().apply {
            put("running", running)
            put("error", lastError ?: JSONObject.NULL)
            put("last", history.firstOrNull() ?: JSONObject.NULL)
            put("history", org.json.JSONArray().apply {
                history.forEach { run -> put(run as Any) }
            })
        }.toString()
    }

    private fun runOnce() {
        val pid = server.pid
        if (server.state != ServerState.READY || pid <= 0) {
            lastError = "server is not ready"
            return
        }
        val loadAt = server.loadedAtMs
        val loadKind = if (lastLoadAtMs != 0L && lastLoadAtMs == loadAt) "warm" else "cold"
        val startedAtMs = System.currentTimeMillis()
        val startNs = System.nanoTime()
        var firstTokenNs = 0L
        var lastTokenNs = 0L
        var streamedChunks = 0
        var promptTokens: Int? = null
        var completionTokens: Int? = null
        var timings: JSONObject? = null

        // Sample the child's resident set while tokens arrive. VmHWM alone
        // would include the model-load peak, which is not what a request costs.
        var peakRssKb = readRssKb(pid)
        var sampling = true
        val sampler = thread(name = "phonon-benchmark-rss", isDaemon = true) {
            while (sampling) {
                val rss = readRssKb(pid)
                if (rss > peakRssKb) peakRssKb = rss
                try {
                    Thread.sleep(RSS_SAMPLE_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }

        val url = URL("http://${settings.apiHost()}:${settings.port}/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        connection = conn
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 600_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            if (settings.apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            }
            val body = JSONObject().apply {
                put("model", settings.alias)
                put("messages", org.json.JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", PROMPT)
                }))
                put("max_tokens", MAX_TOKENS)
                put("temperature", 0)
                put("stream", true)
                put("stream_options", JSONObject().put("include_usage", true))
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != 200) {
                val text = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Throwable) {
                    ""
                }
                lastError = "HTTP $code${if (text.isBlank()) "" else ": ${text.take(200)}"}"
                return
            }

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    if (cancelled) return
                    if (server.state != ServerState.READY || server.pid != pid) {
                        cancelled = true
                        lastError = "cancelled: server stopped"
                        return
                    }
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val chunk = try {
                        JSONObject(payload)
                    } catch (_: Throwable) {
                        continue
                    }
                    chunk.optJSONObject("usage")?.let { usage ->
                        if (!usage.isNull("prompt_tokens")) {
                            promptTokens = usage.optInt("prompt_tokens", 0)
                        }
                        if (!usage.isNull("completion_tokens")) {
                            completionTokens = usage.optInt("completion_tokens", 0)
                        }
                    }
                    chunk.optJSONObject("timings")?.let { timings = it }
                    val choices = chunk.optJSONArray("choices") ?: continue
                    for (i in 0 until choices.length()) {
                        val delta = choices.optJSONObject(i)?.optJSONObject("delta") ?: continue
                        val content = delta.optString("content", "")
                        if (content.isNotEmpty()) {
                            val now = System.nanoTime()
                            if (firstTokenNs == 0L) firstTokenNs = now
                            lastTokenNs = now
                            streamedChunks++
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            if (!cancelled) lastError = "${t.javaClass.simpleName}: ${t.message}"
            return
        } finally {
            sampling = false
            sampler.join(1_000)
            try {
                conn.disconnect()
            } catch (_: Throwable) {
            }
        }

        // A stream can end (EOF or a short body) because the server was
        // stopped, not because the request finished. Never store a partial
        // result as a benchmark.
        if (!cancelled && (server.state != ServerState.READY || server.pid != pid)) {
            cancelled = true
            lastError = "cancelled: server stopped"
        }
        if (cancelled) {
            if (lastError == null) lastError = "cancelled"
            return
        }
        val totalMs = (System.nanoTime() - startNs) / 1_000_000.0
        if (firstTokenNs == 0L) {
            lastError = "no tokens received"
            return
        }
        val ttftMs = (firstTokenNs - startNs) / 1_000_000.0
        val decodeWindowS = if (lastTokenNs > firstTokenNs) {
            (lastTokenNs - firstTokenNs) / 1_000_000_000.0
        } else {
            0.0
        }
        val t = timings
        val promptN = promptTokens ?: t?.optInt("prompt_n", 0) ?: 0
        val predictedN = completionTokens ?: t?.optInt("predicted_n", 0) ?: streamedChunks
        val prefillTps = t?.optDouble("prompt_per_second", 0.0)?.takeIf { it > 0.0 }
            ?: if (ttftMs > 0) promptN / (ttftMs / 1_000.0) else null
        val decodeTps = t?.optDouble("predicted_per_second", 0.0)?.takeIf { it > 0.0 }
            ?: if (decodeWindowS > 0 && predictedN > 1) {
                (predictedN - 1) / decodeWindowS
            } else {
                null
            }
        if (decodeTps == null) {
            lastError = "no decode timing in response"
            return
        }

        val result = JSONObject().apply {
            put("timestamp", Instant.ofEpochMilli(startedAtMs).toString())
            put("model", File(settings.modelPath).name)
            put("alias", settings.alias)
            put("load", loadKind)
            put("prompt_tokens", promptN)
            put("completion_tokens", predictedN)
            put("prefill_tps", round2(prefillTps ?: 0.0))
            put("decode_tps", round2(decodeTps))
            put("ttft_ms", Math.round(ttftMs))
            put("total_ms", Math.round(totalMs))
            put("peak_rss_mb", Math.round(peakRssKb / 1024.0))
            put("battery_temp_c", batteryTemp()?.toDouble() ?: JSONObject.NULL)
            put("context", settings.contextSize)
            put("threads", settings.threads)
            put("load_mode", settings.loadMode)
            put("server_pid", pid)
            put("app_version", BuildConfig.VERSION_NAME)
        }
        settings.appendBenchmark(result)
        lastLoadAtMs = loadAt
        lastError = null
        Log.i(TAG, "benchmark: ${result.optDouble("decode_tps")} tok/s ${loadKind}")
    }

    private fun readRssKb(pid: Int): Long = try {
        File("/proc/$pid/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull() ?: 0L
        }
    } catch (_: Throwable) {
        0L
    }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

    private companion object {
        const val TAG = "Phonon"
        const val MAX_TOKENS = 128
        const val RSS_SAMPLE_MS = 200L
        const val PROMPT =
            "You are a concise technical writer. Explain what a KV cache is in a " +
                "local language model, why it makes long conversations cheaper, and " +
                "name one tradeoff. Answer in plain prose, without headings or lists."
    }
}

package com.chezgoulet.phonon.inference

import android.content.Context
import android.util.Log
import com.chezgoulet.phonon.model.ModelManager
import com.chezgoulet.phonon.models.InferenceRequest
import com.chezgoulet.phonon.models.InferenceResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Local HTTP inference server running on port 9876.
 *
 * Exposes an OpenAI-compatible /v1/chat/completions endpoint backed by
 * LiteRT-LM engine (via ModelManager). Each request creates a fresh
 * conversation on the shared Engine instance.
 *
 * Uses a simple ServerSocket-based HTTP server (no com.sun.net.httpserver,
 * which is unavailable on Android).
 */
class InferenceServer(
    private val context: Context,
    private val modelManager: ModelManager,
    /**
     * Returns the pairing auth token, or null if the device is not yet
     * paired. Inference requests must carry this token as
     * "Authorization: Bearer <token>"; while unpaired (null), ALL
     * inference is refused — the phone only takes inference orders from
     * its paired coordinator.
     */
    private val tokenProvider: () -> String? = { null }
) {
    private val tag = "InferenceServer"
    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null

    /**
     * Listen port. Defaults to 9876; can be overridden with the
     * PHONON_INFERENCE_PORT environment variable (e.g. set via
     * `adb shell setprop` wrapper scripts or an instrumented launch).
     * Must match the coordinator's `cluster.inference_port` setting.
     */
    private val listenPort: Int =
        System.getenv(ENV_INFERENCE_PORT)?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: DEFAULT_PORT

    // Max request body in bytes — 1 MB is generous for any chat prompt
    private val maxBodyBytes = 1_048_576

    // Serialize access to the shared LiteRT-LM Engine
    private val engineMutex = Mutex()

    // Secondary constructor for backward compatibility
    constructor(context: Context) : this(context, ModelManager(context))

    /**
     * Starts the HTTP server on port 9876.
     */
    fun start() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope?.launch {
            try {
                val port = listenPort
                serverSocket = ServerSocket()
                serverSocket?.reuseAddress = true
                serverSocket?.bind(InetSocketAddress(port))
                Log.i(tag, "Inference server listening on port $port")

                while (isActive) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        scope?.launch {
                            handleConnection(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.w(tag, "Accept error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to start inference server: ${e.message}")
            }
        }
    }

    /**
     * Stops the HTTP server.
     */
    fun stop() {
        scope?.cancel()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        Log.i(tag, "Inference server stopped")
    }

    /**
     * Port being listened on.
     */
    val port: Int get() = serverSocket?.localPort ?: listenPort

    companion object {
        const val DEFAULT_PORT = 9876
        const val ENV_INFERENCE_PORT = "PHONON_INFERENCE_PORT"
    }

    private suspend fun handleConnection(clientSocket: Socket) {
        try {
            clientSocket.use { socket ->
                socket.soTimeout = 15_000 // read timeout — prevents slowloris

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = socket.getOutputStream()

                // Read request line
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 3) return
                val method = parts[0]
                val path = parts[1]

                // Read headers
                val headers = mutableMapOf<String, String>()
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val key = line.substring(0, colon).trim().lowercase()
                        val value = line.substring(colon + 1).trim()
                        headers[key] = value
                        if (key == "content-length") {
                            contentLength = value.toIntOrNull() ?: 0
                        }
                    }
                }

                // Reject oversized bodies
                if (contentLength > maxBodyBytes) {
                    sendResponse(writer, 413, "application/json",
                        """{"error":"Request too large"}""")
                    return
                }

                // Read body (Content-Length is bytes — read as bytes, not chars)
                val body = if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var total = 0
                    while (total < contentLength) {
                        val n = socket.getInputStream().read(buf, total, contentLength - total)
                        if (n < 0) break
                        total += n
                    }
                    String(buf, 0, total, Charsets.UTF_8)
                } else ""

                when {
                    path == "/v1/chat/completions" && method == "POST" -> {
                        if (!authorize(headers, writer)) return
                        handleInference(socket, writer, body)
                    }
                    path == "/infer" && method == "POST" -> {
                        if (!authorize(headers, writer)) return
                        handleInference(socket, writer, body)
                    }
                    path == "/health" && method == "GET" -> {
                        // Probed by the coordinator's health monitor every
                        // check cycle: proves the inference server itself is
                        // serving, not merely that PhononService heartbeats.
                        val model = modelManager.currentModelName()
                        val backend = modelManager.currentBackend()
                        val body = JSONObject().apply {
                            put("status", "ok")
                            put("model_loaded", modelManager.isRunning())
                            put("model", model ?: JSONObject.NULL)
                            put("backend", backend ?: JSONObject.NULL)
                        }
                        sendResponse(writer, 200, "application/json", body.toString())
                    }
                    else ->
                        sendResponse(writer, 404, "application/json", """{"error":"Not found"}""")
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(tag, "Connection timed out")
        } catch (e: Exception) {
            Log.w(tag, "Connection error: ${e.message}")
        }
    }

    /**
     * Authenticates an inference request against the pairing token.
     *
     * Fail-closed rules:
     *  - Not paired yet (no token) → 403. The phone never serves
     *    inference until pairing completes.
     *  - Paired but missing/wrong "Authorization: Bearer <token>" → 401.
     *    Only the paired coordinator knows the token, so only it can
     *    submit inference.
     *
     * Comparison is constant-time (MessageDigest.isEqual).
     */
    private fun authorize(headers: Map<String, String>, writer: java.io.OutputStream): Boolean {
        val token = tokenProvider()
        if (token.isNullOrEmpty()) {
            sendResponse(writer, 403, "application/json",
                """{"error":"not_paired","message":"this phone only accepts inference from its paired coordinator — complete pairing first"}""")
            return false
        }

        val auth = headers["authorization"] ?: ""
        val presented = if (auth.startsWith("Bearer ", ignoreCase = true)) {
            auth.substring(7).trim()
        } else ""

        val ok = presented.isNotEmpty() && java.security.MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            token.toByteArray(Charsets.UTF_8)
        )
        if (!ok) {
            Log.w(tag, "Rejected inference request with missing/invalid coordinator token")
            sendResponse(writer, 401, "application/json",
                """{"error":"unauthorized","message":"missing or invalid coordinator token"}""")
            return false
        }
        return true
    }

    private suspend fun handleInference(socket: Socket, writer: java.io.OutputStream, body: String) {
        val startTime = System.currentTimeMillis()
        try {
            val request = InferenceRequest.fromJson(JSONObject(body))

            // Streaming path — SSE over chunked transfer encoding.
            if (request.stream) {
                handleStreamingInference(writer, request)
                return
            }

            // Build the prompt from the message history
            val prompt = buildPrompt(request)

            // Serialize access to the shared LiteRT-LM Engine
            val text = engineMutex.withLock {
                modelManager.generate(prompt)
            }

            val elapsed = (System.currentTimeMillis() - startTime).toInt()
            val estimatedTokens = text.length / 4 // rough estimate

            val response = InferenceResponse(
                text = text,
                tokens = estimatedTokens,
                durationMs = elapsed
            )

            // Build OpenAI-compatible response
            val responseJson = JSONObject().apply {
                put("id", "chatcmpl-${System.currentTimeMillis()}")
                put("object", "chat.completion")
                put("created", System.currentTimeMillis() / 1000)
                put("model", request.model)
                put("choices", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("index", 0)
                        put("message", JSONObject().apply {
                            put("role", "assistant")
                            put("content", text)
                        })
                        put("finish_reason", "stop")
                    })
                })
                put("usage", JSONObject().apply {
                    put("prompt_tokens", request.messages.sumOf { it.content.length / 4 })
                    put("completion_tokens", estimatedTokens)
                    put("total_tokens", (request.messages.sumOf { it.content.length / 4 }) + estimatedTokens)
                })
            }

            sendResponse(writer, 200, "application/json", responseJson.toString())
            Log.i(tag, "Inference completed in ${elapsed}ms")
        } catch (e: IllegalStateException) {
            Log.w(tag, "No model loaded: ${e.message}")
            sendResponse(writer, 502, "application/json",
                """{"error":"No model loaded","code":"no_model"}""")
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.w(tag, "Inference timed out")
            sendResponse(writer, 504, "application/json",
                """{"error":"Inference timed out"}""")
        } catch (e: Exception) {
            Log.w(tag, "Inference error: ${e.message}")
            // Strip error details to avoid information disclosure
            sendResponse(writer, 502, "application/json",
                """{"error":"Inference failed"}""")
        }
    }

    /**
     * Streaming inference: responds with SSE events over chunked transfer
     * encoding, in the OpenAI chat.completion.chunk delta format:
     *
     *   data: {"choices":[{"delta":{"content":"Hello"},"index":0}]}
     *   data: {"choices":[{"delta":{"content":" world"},"index":0}]}
     *   data: [DONE]
     *
     * The LiteRT-LM Kotlin Conversation API in use here is synchronous —
     * Conversation.sendMessage() returns only the completed message and
     * exposes no incremental token callback. Streaming is therefore
     * approximated: the full generation runs first (with SSE keepalive
     * comments every 2s so the coordinator's per-chunk stall detector
     * doesn't fire), then the text is emitted in word-boundary chunks at
     * ~10ms intervals.
     *
     * TODO(litert-lm): switch to the SDK's incremental generation API
     * (token callback / async stream) as soon as one ships, and hold
     * engineMutex across the whole generation instead of pre-generating.
     */
    private suspend fun handleStreamingInference(writer: java.io.OutputStream, request: InferenceRequest) = coroutineScope {
        val prompt = buildPrompt(request)

        // Commit to SSE before generation so the coordinator sees bytes
        // (headers + keepalives) instead of silence while the model runs.
        sendChunkedHeader(writer, "text/event-stream")

        val generation = async(Dispatchers.Default) {
            engineMutex.withLock {
                modelManager.generate(prompt)
            }
        }

        try {
            // Await generation, emitting an SSE comment every 2s as a
            // keepalive. Comments (lines starting with ':') are ignored
            // by SSE parsers but reset the coordinator's stall timer.
            var text: String? = null
            while (text == null) {
                text = withTimeoutOrNull(2_000) { generation.await() }
                if (text == null) {
                    sendChunk(writer, ": keepalive\n\n")
                }
            }

            // Emit word-boundary deltas at ~10ms intervals.
            for (piece in splitDeltas(text)) {
                val event = JSONObject().put(
                    "choices",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("delta", JSONObject().put("content", piece))
                            .put("index", 0)
                    )
                )
                sendChunk(writer, "data: $event\n\n")
                delay(10)
            }
            sendChunk(writer, "data: [DONE]\n\n")
            endChunks(writer)
            Log.i(tag, "Streaming inference completed (${text.length} chars)")
        } catch (e: IllegalStateException) {
            // No model loaded — headers already sent, so report in-band.
            Log.w(tag, "No model loaded for streaming: ${e.message}")
            sendStreamError(writer, "No model loaded")
        } catch (e: Exception) {
            Log.w(tag, "Streaming inference error: ${e.message}")
            generation.cancel()
            // Strip error details to avoid information disclosure
            sendStreamError(writer, "Inference failed")
        }
    }

    /** Splits generated text into word-boundary chunks (word + trailing whitespace). */
    private fun splitDeltas(text: String): List<String> =
        Regex("""\S+\s*|\s+""").findAll(text).map { it.value }.toList()

    /** Emits an SSE error event followed by [DONE] and closes the chunk stream. */
    private fun sendStreamError(writer: java.io.OutputStream, message: String) {
        try {
            val err = JSONObject().put(
                "error",
                JSONObject().put("message", message).put("type", "inference_error")
            )
            sendChunk(writer, "data: $err\n\n")
            sendChunk(writer, "data: [DONE]\n\n")
            endChunks(writer)
        } catch (_: Exception) {
            // Socket already gone — nothing to report to.
        }
    }

    /** Writes chunked-transfer response headers for a streaming reply. */
    private fun sendChunkedHeader(writer: java.io.OutputStream, contentType: String) {
        val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        writer.write(head.toByteArray())
        writer.flush()
    }

    /** Writes one HTTP/1.1 chunk (hex size + CRLF-framed payload). */
    private fun sendChunk(writer: java.io.OutputStream, data: String) {
        val bytes = data.toByteArray(Charsets.UTF_8)
        writer.write("%x\r\n".format(bytes.size).toByteArray())
        writer.write(bytes)
        writer.write("\r\n".toByteArray())
        writer.flush()
    }

    /** Terminates the chunked response. */
    private fun endChunks(writer: java.io.OutputStream) {
        writer.write("0\r\n\r\n".toByteArray())
        writer.flush()
    }

    /**
     * Builds a consolidated prompt from the message history.
     *
     * Since the LiteRT-LM Kotlin API's Conversation.sendMessage() takes
     * a single text input (not a message list), we concatenate the history
     * into a single prompt string using a standard chat format.
     */
    private fun buildPrompt(request: InferenceRequest): String {
        return request.messages.joinToString("\n") { msg ->
            when (msg.role) {
                "user" -> "<|user|>\n${msg.content}\n<|end|>"
                "assistant" -> "<|assistant|>\n${msg.content}\n<|end|>"
                "system" -> "<|system|>\n${msg.content}\n<|end|>"
                else -> "${msg.role}: ${msg.content}"
            }
        } + "\n<|assistant|>\n"
    }

    private fun sendResponse(writer: java.io.OutputStream, status: Int, contentType: String, body: String) {
        val statusText = when (status) {
            200 -> "OK"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            502 -> "Bad Gateway"
            else -> "Unknown"
        }
        val response = "HTTP/1.1 $status $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body
        writer.write(response.toByteArray())
        writer.flush()
    }

    private val CoroutineScope.isActive: Boolean
        get() = coroutineContext[Job]?.isActive != false
}

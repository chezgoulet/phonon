package com.chezgoulet.phonon.inference

import android.content.Context
import android.util.Log
import com.chezgoulet.phonon.model.ModelManager
import com.chezgoulet.phonon.models.InferenceRequest
import com.chezgoulet.phonon.models.InferenceResponse
import kotlinx.coroutines.*
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
    private val modelManager: ModelManager
) {
    private val tag = "InferenceServer"
    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null

    // Secondary constructor for backward compatibility
    constructor(context: Context) : this(context, ModelManager(context))

    /**
     * Starts the HTTP server on port 9876.
     */
    fun start() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope?.launch {
            try {
                val port = 9876
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
    val port: Int get() = serverSocket?.localPort ?: 9876

    private suspend fun handleConnection(clientSocket: Socket) {
        try {
            clientSocket.use { socket ->
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

                // Read body
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var total = 0
                    while (total < contentLength) {
                        val n = reader.read(buf, total, contentLength - total)
                        if (n < 0) break
                        total += n
                    }
                    String(buf, 0, total)
                } else ""

                when {
                    path == "/v1/chat/completions" && method == "POST" ->
                        handleInference(socket, writer, body)
                    path == "/infer" && method == "POST" ->
                        handleInference(socket, writer, body)
                    path == "/health" && method == "GET" ->
                        sendResponse(writer, 200, "application/json", """{"status":"ok"}""")
                    else ->
                        sendResponse(writer, 404, "application/json", """{"error":"Not found"}""")
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Connection error: ${e.message}")
        }
    }

    private suspend fun handleInference(socket: Socket, writer: java.io.OutputStream, body: String) {
        val startTime = System.currentTimeMillis()
        try {
            val request = InferenceRequest.fromJson(JSONObject(body))

            // Build the prompt from the message history
            val prompt = buildPrompt(request)

            // Run inference via LiteRT-LM
            val text = modelManager.generate(prompt)

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
        } catch (e: Exception) {
            Log.w(tag, "Inference error: ${e.message}")
            sendResponse(writer, 502, "application/json",
                """{"error":"Inference failed: ${e.message}"}""")
        }
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

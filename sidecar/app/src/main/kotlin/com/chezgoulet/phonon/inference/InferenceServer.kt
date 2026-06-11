package com.chezgoulet.phonon.inference

import android.content.Context
import android.util.Log
import com.chezgoulet.phonon.models.InferenceRequest
import com.chezgoulet.phonon.models.InferenceResponse
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Local HTTP inference server running on port 9876.
 *
 * Proxies inference requests to OlliteRT's OpenAI-compatible endpoint
 * running on localhost:8085. If OlliteRT is not running, returns an
 * error response.
 *
 * Uses a simple ServerSocket-based HTTP server (no com.sun.net.httpserver,
 * which is unavailable on Android).
 */
class InferenceServer(private val context: Context) {
    private val tag = "InferenceServer"
    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json".toMediaType()

    private val olliteUrl = "http://127.0.0.1:8085/v1/chat/completions"

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
        try {
            val request = InferenceRequest.fromJson(org.json.JSONObject(body))

            // Build inference request to OlliteRT
            val olliteBodyJson = buildOlliteRequest(request)
            val olliteRequest = Request.Builder()
                .url(olliteUrl)
                .post(olliteBodyJson.toRequestBody(jsonMediaType))
                .build()

            val olliteResponse = withContext(Dispatchers.IO) {
                client.newCall(olliteRequest).execute()
            }
            val olliteBodyStr = olliteResponse.body?.string() ?: "{}"

            if (!olliteResponse.isSuccessful) {
                sendResponse(writer, 502, "application/json",
                    """{"error":"Inference engine error: ${olliteResponse.code}"}""")
                return
            }

            // Parse inference engine response
            val engineJson = org.json.JSONObject(olliteBodyStr)
            val choices = engineJson.optJSONArray("choices")
            val text = if (choices != null && choices.length() > 0) {
                choices.getJSONObject(0)
                    .optJSONObject("message")
                    ?.optString("content", "") ?: ""
            } else ""

            val usage = engineJson.optJSONObject("usage")
            val totalTokens = usage?.optInt("total_tokens", text.length / 4) ?: (text.length / 4)
            val completionTokens = usage?.optInt("completion_tokens", totalTokens) ?: totalTokens

            val response = InferenceResponse(
                text = text,
                tokens = completionTokens,
                durationMs = 0
            )

            sendResponse(writer, 200, "application/json", response.toJson().toString())
        } catch (e: Exception) {
            Log.w(tag, "Inference error: ${e.message}")
            sendResponse(writer, 502, "application/json",
                """{"error":"Inference failed: ${e.message}"}""")
        }
    }

    private fun buildOlliteRequest(request: InferenceRequest): String {
        val messages = org.json.JSONArray()
        for (msg in request.messages) {
            messages.put(org.json.JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }

        return org.json.JSONObject().apply {
            put("model", request.model)
            put("messages", messages)
            put("temperature", request.temperature)
            put("max_tokens", request.maxTokens)
            put("stream", false)
        }.toString()
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

    private fun sendError(writer: java.io.OutputStream, status: Int, message: String) {
        val body = """{"error":"$message"}"""
        sendResponse(writer, status, "application/json", body)
    }

    private val CoroutineScope.isActive: Boolean
        get() = coroutineContext[Job]?.isActive != false
}

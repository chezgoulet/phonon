package com.chezgoulet.phonon.inference

import android.content.Context
import android.util.Log
import com.chezgoulet.phonon.models.InferenceRequest
import com.chezgoulet.phonon.models.InferenceResponse
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Local HTTP inference server running on port 9876.
 *
 * Proxies inference requests to OlliteRT's OpenAI-compatible endpoint
 * running on localhost:8085. If OlliteRT is not running, returns an
 * error response.
 *
 * The coordinator sends PhoneInferenceRequest via this endpoint.
 */
class InferenceServer(private val context: Context) {
    private val tag = "InferenceServer"
    private var httpServer: HttpServer? = null
    private var scope: CoroutineScope? = null
    private val client = OkHttpClient()

    private val olliteUrl = "http://127.0.0.1:8085/v1/chat/completions"

    /**
     * Starts the HTTP server on port 9876.
     */
    fun start() {
        try {
            val port = 9876
            httpServer = HttpServer.create(InetSocketAddress(port), 0)
            httpServer?.executor = Executors.newFixedThreadPool(4)

            httpServer?.createContext("/infer") { exchange ->
                handleInference(exchange)
            }

            httpServer?.createContext("/health") { exchange ->
                val response = """{"status":"ok","ollite_running":false}"""
                exchange.sendResponseHeaders(200, response.length.toLong())
                exchange.responseBody.write(response.toByteArray())
                exchange.responseBody.close()
            }

            httpServer?.start()
            Log.i(tag, "Inference server listening on port $port")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start inference server: ${e.message}")
        }
    }

    /**
     * Stops the HTTP server.
     */
    fun stop() {
        scope?.cancel()
        httpServer?.stop(1)
        httpServer = null
        Log.i(tag, "Inference server stopped")
    }

    /**
     * Port being listened on.
     */
    val port: Int get() = 9876

    private fun handleInference(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                sendError(exchange, 405, "Method not allowed")
                return
            }

            val body = exchange.requestBody.readBytes().decodeToString()
            val request = InferenceRequest.fromJson(
                org.json.JSONObject(body)
            )

            // Proxy to OlliteRT
            val olliteBody = buildOlliteRequest(request)
            val olliteRequest = Request.Builder()
                .url(olliteUrl)
                .post(olliteBody.toRequestBody("application/json".toMediaType()))
                .build()

            val olliteResponse = client.newCall(olliteRequest).execute()
            val olliteBodyStr = olliteResponse.body?.string() ?: "{}"

            if (!olliteResponse.isSuccessful) {
                sendError(exchange, 502, "OlliteRT error: ${olliteResponse.code}")
                return
            }

            // Parse OlliteRT response and extract text
            val olliteJson = org.json.JSONObject(olliteBodyStr)
            val choices = olliteJson.optJSONArray("choices")
            val text = if (choices != null && choices.length() > 0) {
                choices.getJSONObject(0)
                    .optJSONObject("message")
                    ?.optString("content", "") ?: ""
            } else {
                ""
            }

            val usage = olliteJson.optJSONObject("usage")
            val totalTokens = usage?.optInt("total_tokens", text.length / 4) ?: (text.length / 4)
            val completionTokens = usage?.optInt("completion_tokens", totalTokens) ?: totalTokens

            val response = InferenceResponse(
                text = text,
                tokens = completionTokens,
                durationMs = 0
            )

            sendJson(exchange, 200, response.toJson().toString())
        } catch (e: Exception) {
            Log.w(tag, "Inference error: ${e.message}")
            sendError(exchange, 502, "Inference failed: ${e.message}")
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

    private fun sendJson(exchange: HttpExchange, status: Int, body: String) {
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, body.length.toLong())
        exchange.responseBody.write(body.toByteArray())
        exchange.responseBody.close()
    }

    private fun sendError(exchange: HttpExchange, status: Int, message: String) {
        val body = """{"error":"$message"}"""
        sendJson(exchange, status, body)
    }
}

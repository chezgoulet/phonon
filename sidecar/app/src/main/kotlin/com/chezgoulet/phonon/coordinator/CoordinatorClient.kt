package com.chezgoulet.phonon.coordinator

import android.content.Context
import android.util.Log
import com.chezgoulet.phonon.PhononApplication
import com.chezgoulet.phonon.models.*
import kotlinx.coroutines.*
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Manages REST registration and WebSocket command channel with the coordinator.
 *
 * Flow:
 * 1. POST /api/v1/sidecar/register → get node name + assigned group
 * 2. POST /api/v1/sidecar/pair → if token is provided
 * 3. Open WebSocket → receive commands, send acks
 * 4. On disconnect: exponential backoff reconnect
 */
class CoordinatorClient(
    private val context: Context,
    private var host: String,
    private var port: Int,
    private val app: PhononApplication,
    private val onStatusChange: (String) -> Unit,
    private val onModelLoad: (modelName: String, modelUrl: String, engine: String) -> Unit,
    private val onModelUnload: () -> Unit,
    private val onShutdown: () -> Unit
) {
    private val tag = "CoordinatorClient"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    var registered: Boolean = false
        private set

    @Volatile
    var nodeName: String? = null
        private set

    @Volatile
    var pairId: String? = null
        private set

    private var webSocket: WebSocket? = null
    private var reconnectAttempt = 0
    private var stopped = false
    private val pendingCommands = ConcurrentLinkedQueue<WSMessage>()

    private val baseUrl: String get() = "http://$host:$port"
    private val wsUrl: String get() = "ws://$host:$port/api/v1/sidecar/ws"

    fun connect() {
        stopped = false
        scope.launch {
            registerWithCoordinator()
            connectWebSocket()
        }
    }

    fun stop() {
        stopped = true
        scope.cancel()
        webSocket?.close(1000, "shutdown")
        client.dispatcher.executorService.shutdown()
    }

    // ─── REST Registration ───

    private suspend fun registerWithCoordinator() {
        val body = RegisterRequest(
            deviceId = app.deviceId,
            deviceModel = app.deviceModel,
            androidVersion = app.androidVersion,
            ipAddress = app.ipAddress,
            networkInterface = app.networkInterface
        ).toJson().toString()

        val request = Request.Builder()
            .url("$baseUrl/api/v1/sidecar/register")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            val responseBody = response.body?.string() ?: "{}"
            val json = JSONObject(responseBody)

            if (response.isSuccessful) {
                val regResp = RegisterResponse.fromJson(json)
                nodeName = regResp.nodeName
                registered = true
                Log.i(tag, "Registered as ${regResp.nodeName} (status=${regResp.status})")

                // If coordinator assigns us to a group, pair
                if (regResp.assignedTo != null) {
                    pairWithCoordinator()
                }
                onStatusChange("connected")
            } else {
                Log.w(tag, "Registration failed: HTTP ${response.code} — $responseBody")
                onStatusChange("disconnected")
            }
        } catch (e: Exception) {
            Log.w(tag, "Registration error: ${e.message}")
            onStatusChange("disconnected")
        }
    }

    private suspend fun pairWithCoordinator() {
        val pairBody = PairRequest(
            deviceId = app.deviceId,
            token = "", // TODO: get pairing token from config or notification
            audit = AuditInfo(
                packagesInstalled = 0,
                rootDetected = false,
                bootloaderLocked = true,
                androidVersion = app.androidVersion
            )
        ).toJson().toString()

        val request = Request.Builder()
            .url("$baseUrl/api/v1/sidecar/pair")
            .post(pairBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            val responseBody = response.body?.string() ?: "{}"
            val json = JSONObject(responseBody)

            if (response.isSuccessful) {
                val pairResp = PairResponse.fromJson(json)
                pairId = pairResp.pairId
                Log.i(tag, "Paired with coordinator, pairId=${pairResp.pairId}")
            } else {
                Log.w(tag, "Pairing failed: HTTP ${response.code}")
            }
        } catch (e: Exception) {
            Log.w(tag, "Pairing error: ${e.message}")
        }
    }

    // ─── WebSocket ───

    private fun connectWebSocket() {
        if (stopped) return

        val request = Request.Builder()
            .url("$wsUrl?device_id=${app.deviceId}")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(tag, "WebSocket connected")
                reconnectAttempt = 0
                onStatusChange("connected")

                // Resend any pending commands
                while (true) {
                    val pending = pendingCommands.poll() ?: break
                    sendCommandAck(pending, AckStatus.Accepted)
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleCommand(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.w(tag, "WebSocket closing: $code $reason")
                ws.close(code, reason)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.w(tag, "WebSocket closed: $code $reason")
                onStatusChange("disconnected")
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(tag, "WebSocket failure: ${t.message}")
                onStatusChange("disconnected")
                scheduleReconnect()
            }
        })
    }

    private fun handleCommand(text: String) {
        try {
            val json = JSONObject(text)
            val msg = WSMessage.fromJson(json)
            if (msg == null) {
                Log.w(tag, "Unknown command type, ignoring")
                return
            }

            when (msg.type) {
                CommandType.ModelPush -> {
                    // Acknowledge and download with checksum verification
                    sendCommandAck(msg, AckStatus.Accepted)
                    val modelName = msg.payload?.optString("model", "")
                    val modelUrl = msg.payload?.optString("url", "")
                    val expectedChecksum = msg.payload?.optString("checksum", "") ?: ""
                    if (modelName.isNullOrEmpty() || modelUrl.isNullOrEmpty()) {
                        sendCommandAck(msg, AckStatus.Failed, "missing model or url in push command")
                        return
                    }
                    scope.launch {
                        val success = downloadModelVerify(modelName, modelUrl, expectedChecksum)
                        if (success) {
                            sendCommandAck(msg, AckStatus.Completed)
                        } else {
                            sendCommandAck(msg, AckStatus.Failed, "download failed or checksum mismatch")
                        }
                    }
                }
                CommandType.ModelLoad -> {
                    sendCommandAck(msg, AckStatus.Accepted)
                    val modelName = msg.payload?.optString("model", "")
                    val modelUrl = msg.payload?.optString("url", "")
                    val engine = msg.payload?.optString("engine", "prima")
                    if (modelName.isNullOrEmpty()) {
                        sendCommandAck(msg, AckStatus.Failed, "missing model in load command")
                        return
                    }
                    onModelLoad(modelName, modelUrl ?: "", engine ?: "prima")
                }
                CommandType.ModelUnload -> {
                    sendCommandAck(msg, AckStatus.Accepted)
                    onModelUnload()
                    sendCommandAck(msg, AckStatus.Completed)
                }
                CommandType.ModeChange -> {
                    sendCommandAck(msg, AckStatus.Accepted)
                    val mode = msg.payload?.optString("mode", "pool")
                    Log.i(tag, "Mode change to: $mode")
                    sendCommandAck(msg, AckStatus.Completed)
                }
                CommandType.StandbyPromote -> {
                    sendCommandAck(msg, AckStatus.Accepted)
                    Log.i(tag, "Promoted from standby")
                    sendCommandAck(msg, AckStatus.Completed)
                }
                CommandType.Shutdown -> {
                    sendCommandAck(msg, AckStatus.Accepted)
                    onShutdown()
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to handle command: $text — ${e.message}")
        }
    }

    private fun sendCommandAck(msg: WSMessage, status: AckStatus, error: String? = null) {
        val ack = WSAck(
            ackType = "ack",
            commandId = msg.commandId ?: return,
            status = status,
            error = error
        )
        webSocket?.send(ack.toJson().toString())
    }

    /**
     * Downloads a model file and verifies its SHA-256 checksum.
     * @return true if the download succeeded and the checksum matches.
     */
    private suspend fun downloadModelVerify(modelName: String, url: String, expectedChecksum: String): Boolean {
        val cacheDir = File(context.cacheDir, "models")
        cacheDir.mkdirs()
        val modelFile = File(cacheDir, modelName.replace("/", "_").replace(":", "_"))

        // Remove any previous partial download — we always write to a fresh temp
        // and verify against the full checksum. Requesting a suffix Range and then
        // writing to a new temp guarantees checksum mismatch on retry.
        if (modelFile.exists()) {
            modelFile.delete()
        }

        val request = Request.Builder()
            .url(url)
            .build()

        return try {
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }

            if (!response.isSuccessful) {
                Log.w(tag, "Model download failed: HTTP ${response.code}")
                return false
            }

            val body = response.body ?: run {
                Log.w(tag, "Model download: empty response body")
                return false
            }

            val sourceFile = File.createTempFile("download_", ".tmp", cacheDir)

            var actualSha256 = withContext(Dispatchers.IO) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                sourceFile.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            digest.update(buffer, 0, bytesRead)
                        }
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }

            // Verify checksum if the coordinator provided one
            if (expectedChecksum.isNotEmpty()) {
                if (actualSha256 != expectedChecksum) {
                    Log.w(tag, "Checksum mismatch for $modelName: expected $expectedChecksum, got $actualSha256")
                    sourceFile.delete()
                    return false
                }
                Log.i(tag, "Model verified: $modelName (SHA-256 matches)")
            } else {
                Log.i(tag, "Model downloaded: $modelName (no checksum to verify)")
            }

            // Atomic rename
            modelFile.delete()
            sourceFile.renameTo(modelFile)
            Log.i(tag, "Model cached: $modelName (${modelFile.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(tag, "Model download error: ${e.message}")
            false
        }
    }

    private fun scheduleReconnect() {
        if (stopped) return
        val delay = listOf(1000, 2000, 5000, 10000, 30000)
            .getOrElse(reconnectAttempt) { 60000 }
        reconnectAttempt++
        Log.i(tag, "Reconnecting in ${delay}ms (attempt $reconnectAttempt)")
        scope.launch {
            delay(delay.toLong())
            registerWithCoordinator()
            connectWebSocket()
        }
    }

    fun sendHeartbeat(heartbeat: HeartbeatRequest) {
        if (!registered) return
        scope.launch {
            val body = heartbeat.toJson().toString()
            val request = Request.Builder()
                .url("$baseUrl/api/v1/sidecar/heartbeat")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            try {
                withContext(Dispatchers.IO) {
                    client.newCall(request).execute().close()
                }
            } catch (_: Exception) {
                // Heartbeat failures are non-fatal
            }
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

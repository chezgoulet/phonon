package com.chezgoulet.phonon.models

import org.json.JSONObject
import org.json.JSONArray

// ─── REST API types (mirrors coordinator's sidecar_api.go) ───

data class RegisterRequest(
    val deviceId: String,
    val deviceModel: String,
    val androidVersion: String,
    val ipAddress: String,
    val networkInterface: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("device_id", deviceId)
        put("device_model", deviceModel)
        put("android_version", androidVersion)
        put("ip_address", ipAddress)
        put("network_interface", networkInterface)
    }
}

data class RegisterResponse(
    val status: String,
    val nodeName: String,
    val assignedTo: String?
) {
    companion object {
        fun fromJson(json: JSONObject) = RegisterResponse(
            status = json.optString("status", ""),
            nodeName = json.optString("node_name", ""),
            assignedTo = json.optString("assigned_to", null)
        )
    }
}

data class HeartbeatRequest(
    val deviceId: String,
    val batteryLevel: Double,
    val batteryCharging: Boolean,
    val batteryCapacityPct: Double,
    val thermalTempC: Double,
    val storageTotalGb: Double,
    val storageFreeGb: Double,
    val modelLoaded: String?,
    val queueDepth: Int,
    val network: String,
    val timestamp: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("device_id", deviceId)
        put("battery", JSONObject().apply {
            put("level", batteryLevel)
            put("charging", batteryCharging)
            put("capacity_pct", batteryCapacityPct)
        })
        put("thermal", JSONObject().apply {
            put("soc_temp_c", thermalTempC)
        })
        put("storage", JSONObject().apply {
            put("total_gb", storageTotalGb)
            put("free_gb", storageFreeGb)
        })
        if (modelLoaded != null) {
            put("model", JSONObject().apply {
                put("loaded", modelLoaded)
            })
        }
        put("queue_depth", queueDepth)
        put("network", network)
        put("timestamp", timestamp)
    }
}

data class PairRequest(
    val deviceId: String,
    val token: String,
    val audit: AuditInfo
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("device_id", deviceId)
        put("token", token)
        put("audit", JSONObject().apply {
            put("packages_installed", audit.packagesInstalled)
            put("root_detected", audit.rootDetected)
            put("bootloader_locked", audit.bootloaderLocked)
            put("android_version", audit.androidVersion)
        })
    }
}

data class AuditInfo(
    val packagesInstalled: Int,
    val rootDetected: Boolean,
    val bootloaderLocked: Boolean,
    val androidVersion: String
)

data class PairResponse(
    val status: String,
    val nodeName: String,
    val pairId: String
) {
    companion object {
        fun fromJson(json: JSONObject) = PairResponse(
            status = json.optString("status", ""),
            nodeName = json.optString("node_name", ""),
            pairId = json.optString("pair_id", "")
        )
    }
}

// ─── WebSocket command types (mirrors coordinator's ws.go) ───

data class WSMessage(
    val type: String,
    val commandId: String?,
    val payload: JSONObject?
) {
    companion object {
        fun fromJson(json: JSONObject) = WSMessage(
            type = json.getString("type"),
            commandId = json.optString("command_id", null),
            payload = json.optJSONObject("payload")
        )
    }
}

data class WSAck(
    val ackType: String,
    val commandId: String,
    val status: String,
    val error: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ack_type", ackType)
        put("command_id", commandId)
        put("status", status)
        if (error != null) put("error", error)
    }
}

// Command types
const val CMD_MODEL_PUSH = "model_push"
const val CMD_MODEL_LOAD = "model_load"
const val CMD_MODEL_UNLOAD = "model_unload"
const val CMD_MODE_CHANGE = "mode_change"
const val CMD_STANDBY_PROMOTE = "standby_promote"
const val CMD_SHUTDOWN = "shutdown"

const val ACK_ACCEPTED = "accepted"
const val ACK_COMPLETED = "completed"
const val ACK_FAILED = "failed"

// ─── Inference request/response (local HTTP server) ───

data class InferenceRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Double,
    val maxTokens: Int
) {
    companion object {
        fun fromJson(json: JSONObject) = InferenceRequest(
            model = json.getString("model"),
            messages = json.getJSONArray("messages").let { arr ->
                (0 until arr.length()).map { i ->
                    val msg = arr.getJSONObject(i)
                    Message(
                        role = msg.getString("role"),
                        content = msg.getString("content")
                    )
                }
            },
            temperature = json.optDouble("temperature", 0.7),
            maxTokens = json.optInt("max_tokens", 2048)
        )
    }
}

data class Message(val role: String, val content: String)

data class InferenceResponse(
    val text: String,
    val tokens: Int,
    val durationMs: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("text", text)
        put("tokens", tokens)
        put("duration_ms", durationMs)
    }
}

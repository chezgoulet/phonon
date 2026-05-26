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
//
// Wire format documented in SPEC.md §5.0. The Go coordinator produces
// JSON with the field names in this section's spec tables. When changing
// these types, update the schema doc and the companion Go types in
// internal/api/ws.go. The Go test TestWS_WireFormat validates exact wire
// format output.

sealed class CommandType(val value: String) {
    object ModelPush : CommandType("model_push")
    object ModelLoad : CommandType("model_load")
    object ModelUnload : CommandType("model_unload")
    object ModeChange : CommandType("mode_change")
    object StandbyPromote : CommandType("standby_promote")
    object Shutdown : CommandType("shutdown")

    override fun toString(): String = value

    companion object {
        private val map = entries().associateBy { it.value }

        fun fromString(s: String): CommandType? = map[s]

        fun entries(): List<CommandType> = listOf(
            ModelPush, ModelLoad, ModelUnload,
            ModeChange, StandbyPromote, Shutdown
        )
    }
}

sealed class AckStatus(val value: String) {
    object Accepted : AckStatus("accepted")
    object Completed : AckStatus("completed")
    object Failed : AckStatus("failed")

    override fun toString(): String = value

    companion object {
        private val map = entries().associateBy { it.value }

        fun fromString(s: String): AckStatus? = map[s]

        fun entries(): List<AckStatus> = listOf(Accepted, Completed, Failed)
    }
}

data class WSMessage(
    val type: CommandType,
    val commandId: String?,
    val payload: JSONObject?
) {
    // JSON keys must match Go WSCommand's json tags in internal/api/ws.go.
    // See SPEC.md §5.0 for the canonical wire format schema.
    companion object {
        fun fromJson(json: JSONObject): WSMessage? {
            val rawType = json.getString("type")
            return CommandType.fromString(rawType)?.let { type ->
                WSMessage(
                    type = type,
                    commandId = json.optString("command_id", null),
                    payload = json.optJSONObject("payload")
                )
            }
        }
    }
}

data class WSAck(
    val ackType: String,
    val commandId: String,
    val status: AckStatus,
    val error: String?
) {
    // JSON keys must match Go WSAck's json tags in internal/api/ws.go.
    // See SPEC.md §5.0 for the canonical wire format schema.
    fun toJson(): JSONObject = JSONObject().apply {
        put("ack_type", ackType)
        put("command_id", commandId)
        put("status", status.value)
        if (error != null) put("error", error)
    }
}

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

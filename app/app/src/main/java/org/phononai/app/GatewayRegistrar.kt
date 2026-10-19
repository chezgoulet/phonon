package org.phononai.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** The outcome of one gateway call, in words the operator can act on. */
sealed class GatewayResult {
    object Ok : GatewayResult()

    /** [message] is the gateway's own sentence, when it gave one. */
    data class Failure(val message: String, val hint: String? = null) : GatewayResult()
}

/**
 * One small interface, as the issue asks: register this node, and deregister it.
 *
 * LiteLLM is the only implementation, because it is the router this project
 * recommends and the only one that takes runtime model management. A second
 * implementation would not touch the service.
 */
interface GatewayRegistrar {
    /** `phone-<shortid>`: the name the gateway exposes. */
    val modelName: String

    /** Idempotent: exactly one entry for [modelName] afterwards. */
    fun register(): GatewayResult

    /** Best-effort: an unreachable gateway is not an error worth blocking on. */
    fun deregister(): GatewayResult

    /** The Test button: does this URL answer, and does this key authorise it? */
    fun check(): GatewayResult
}

/**
 * LiteLLM's model-management endpoints, as the proxy actually behaves.
 *
 * Measured against LiteLLM `main-latest` with `store_model_in_db: true`:
 *
 *  - `POST /model/new` does **not** reject a duplicate `model_name`; it inserts
 *    another row with a new uuid. So "register, and on 409 update" would leak a
 *    row per restart. Idempotency here is: list, then update by `model_info.id`.
 *  - `POST /model/update` requires `model_info.id`; with a `model_name` alone it
 *    answers 400 `Authentication Error, model_info not provided`.
 *  - `POST /model/delete` takes `{"id": ...}`.
 *  - A proxy without a database answers 500 `No DB Connected...` to `/model/new`
 *    (this build does not name `STORE_MODEL_IN_DB`; the older wording is still
 *    matched, because both mean the same thing to the operator).
 */
class LiteLlmRegistrar(
    baseUrl: String,
    private val masterKey: String,
    override val modelName: String,
    private val servedModel: String,
    private val apiBase: String,
    private val nodeKey: String,
    private val timeoutMs: Int = 15_000,
) : GatewayRegistrar {

    private val base = baseUrl.trim().trimEnd('/')

    override fun register(): GatewayResult {
        val existing = when (val listed = existingIds()) {
            is Listing.Failed -> return listed.result
            is Listing.Found -> listed.ids
        }
        return when {
            existing.size == 1 -> update(existing.first())
            existing.size > 1 -> {
                // Older builds could leave duplicates behind; converge on one
                // rather than reporting success over a growing mess.
                for (extra in existing.drop(1)) delete(extra)
                update(existing.first())
            }
            else -> create()
        }
    }

    override fun deregister(): GatewayResult {
        val existing = when (val listed = existingIds()) {
            is Listing.Failed -> return listed.result
            is Listing.Found -> listed.ids
        }
        var last: GatewayResult = GatewayResult.Ok
        for (id in existing) {
            val result = delete(id)
            if (result !is GatewayResult.Ok) last = result
        }
        return last
    }

    override fun check(): GatewayResult {
        val response = try {
            request("GET", "/v1/models", null)
        } catch (t: Throwable) {
            return unreachable(t)
        }
        return if (response.ok) GatewayResult.Ok else failure(response.status, response.body)
    }

    private fun create(): GatewayResult {
        val response = try {
            request("POST", "/model/new", payload().toString())
        } catch (t: Throwable) {
            return unreachable(t)
        }
        if (response.ok) return GatewayResult.Ok
        // Not what this LiteLLM does, but other builds do answer 409 or say
        // "already exists"; treat it as "look it up and update".
        if (response.status == 409 || response.body.contains("already exists", true)) {
            val listed = existingIds()
            if (listed is Listing.Found && listed.ids.isNotEmpty()) {
                return update(listed.ids.first())
            }
        }
        return failure(response.status, response.body)
    }

    private fun update(id: String): GatewayResult {
        val body = payload().apply {
            put("model_info", JSONObject().put("id", id))
        }
        val response = try {
            request("POST", "/model/update", body.toString())
        } catch (t: Throwable) {
            return unreachable(t)
        }
        return if (response.ok) GatewayResult.Ok else failure(response.status, response.body)
    }

    private fun delete(id: String): GatewayResult {
        val response = try {
            request("POST", "/model/delete", JSONObject().put("id", id).toString())
        } catch (t: Throwable) {
            return unreachable(t)
        }
        return if (response.ok) GatewayResult.Ok else failure(response.status, response.body)
    }

    private sealed class Listing {
        data class Found(val ids: List<String>) : Listing()
        data class Failed(val result: GatewayResult) : Listing()
    }

    /** Every entry whose `model_name` is this node's, newest last. */
    private fun existingIds(): Listing {
        val response = try {
            request("GET", "/model/info", null)
        } catch (t: Throwable) {
            return Listing.Failed(unreachable(t))
        }
        if (!response.ok) return Listing.Failed(failure(response.status, response.body))
        val ids = ArrayList<String>()
        try {
            val data = JSONObject(response.body).optJSONArray("data") ?: return Listing.Found(ids)
            for (i in 0 until data.length()) {
                val entry = data.optJSONObject(i) ?: continue
                if (entry.optString("model_name") != modelName) continue
                val id = entry.optJSONObject("model_info")?.optString("id").orEmpty()
                if (id.isNotBlank()) ids.add(id)
                if (ids.size >= MAX_ENTRIES) break
            }
        } catch (t: Throwable) {
            return Listing.Failed(
                GatewayResult.Failure(
                    "the gateway answered /model/info with something that is not a model list",
                    "check that the URL is a LiteLLM proxy, not another service",
                ),
            )
        }
        return Listing.Found(ids)
    }

    private fun payload(): JSONObject = JSONObject().apply {
        put("model_name", modelName)
        put(
            "litellm_params",
            JSONObject().apply {
                put("model", "openai/$servedModel")
                put("api_base", apiBase)
                put("api_key", nodeKey)
                // A phone can take minutes to first token; the router's defaults
                // would time out healthy requests and cool the node down.
                put("timeout", 1800)
                put("stream_timeout", 1800)
                put("max_retries", 0)
            },
        )
    }

    private data class Response(val status: Int, val body: String) {
        val ok: Boolean get() = status in 200..299
    }

    private fun request(method: String, path: String, body: String?): Response {
        val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Authorization", "Bearer $masterKey")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Response(status, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun unreachable(t: Throwable): GatewayResult = GatewayResult.Failure(
        sanitize("${t.javaClass.simpleName}: ${t.message.orEmpty()}"),
        "check that the gateway address answers on this network; registration is for a " +
            "gateway on the same LAN or tailnet as the phone",
    )

    /**
     * The gateway's own sentence, sanitised: a proxy that echoes the key back in
     * an error must not put it on screen or in a log.
     */
    private fun failure(status: Int, body: String): GatewayResult {
        val raw = gatewayMessage(body) ?: body.take(300).ifBlank { "HTTP $status" }
        val lower = raw.lowercase()
        val databaseMissing = lower.contains("store_model_in_db") ||
            lower.contains("no connected db") ||
            lower.contains("no db connected") ||
            lower.contains("no database") ||
            lower.contains("db connection")
        val hint = when {
            databaseMissing ->
                "the gateway must run with store_model_in_db: true and a DATABASE_URL"
            status == 401 || status == 403 ->
                "the gateway rejected the key; it must be a proxy key allowed to manage models"
            status == 404 ->
                "the gateway has no model-management API at this URL; check the address and port"
            else -> null
        }
        return GatewayResult.Failure(sanitize(raw), hint)
    }

    private fun gatewayMessage(body: String): String? = try {
        val json = JSONObject(body)
        json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json.optString("detail").takeIf { it.isNotBlank() }
            ?: json.optString("message").takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    private fun sanitize(text: String): String {
        var out = text
        for (secret in listOf(masterKey, nodeKey)) {
            if (secret.length >= 8) out = out.replace(secret, "[REDACTED]")
        }
        return out
    }

    private companion object {
        /** Enough to notice duplicates without walking a huge model list. */
        const val MAX_ENTRIES = 20
    }
}

/**
 * Whether a gateway address is one the phone can actually be reached from.
 *
 * Registration writes an `api_base` pointing back at this phone, so a gateway
 * in a cloud cannot route to it. Saying so at save time is better than a
 * registration that succeeds and a completion that times out.
 */
object GatewayAddress {

    fun violation(url: String): String? {
        val host = hostOf(url) ?: return "not a valid http://host:port URL"
        if (isPrivateHost(host)) return null
        return "$host is not a private address; the gateway must be on the same LAN or " +
            "tailnet as the phone, or it cannot reach the node"
    }

    fun hostOf(url: String): String? {
        return try {
            val parsed = URL(url.trim())
            if (parsed.protocol != "http" && parsed.protocol != "https") {
                null
            } else {
                parsed.host?.takeIf { it.isNotBlank() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun isPrivateHost(host: String): Boolean {
        val name = host.trim().lowercase()
        if (name == "localhost" || name.endsWith(".local") || name.endsWith(".lan") ||
            name.endsWith(".internal") || name.endsWith(".ts.net")
        ) {
            return true
        }
        val address = try {
            InetAddress.getByName(name)
        } catch (_: IOException) {
            // A name that does not resolve is not evidence of a public address;
            // the Test button is what establishes reachability.
            return false
        }
        return address.isLoopbackAddress || address.isSiteLocalAddress ||
            address.isLinkLocalAddress
    }
}

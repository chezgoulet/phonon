package com.chezgoulet.phonon.pairing

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Drives the device side of the pairing flow and holds the resulting
 * auth token.
 *
 * Flow:
 *  1. POST /api/v1/sidecar/pair/request with the device's public key →
 *     coordinator returns a 6-digit code, shown on the phone screen.
 *  2. Operator types the code into the coordinator UI (or auto-approves
 *     a headless phone).
 *  3. Phone polls GET /api/v1/sidecar/pair/status with an Ed25519
 *     signature; once paired, the response includes the device auth
 *     token, which is persisted.
 *
 * The token is then attached to every heartbeat / model-status /
 * WebSocket request, and required from the coordinator on inference
 * requests (see InferenceServer).
 */
class PairingClient(
    context: Context,
    private val identity: DeviceIdentity,
    private val client: OkHttpClient,
) {
    private val tag = "PairingClient"
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The device auth token, or null if not yet paired. */
    @Volatile
    var authToken: String? = prefs.getString(KEY_AUTH_TOKEN, null)
        private set

    val isPaired: Boolean get() = authToken != null

    /** Clears the stored token (e.g. after the coordinator unpairs us). */
    fun clearToken() {
        authToken = null
        prefs.edit().remove(KEY_AUTH_TOKEN).apply()
    }

    /**
     * Ensures the device is paired and the auth token is available.
     * Blocks (suspending) until pairing completes or [maxAttempts]
     * status polls are exhausted.
     *
     * @param onPairingCode called with the 6-digit code when a new
     *        pairing request is created, so the UI can display it.
     * @return the auth token, or null if pairing did not complete.
     */
    suspend fun ensurePaired(
        baseUrl: String,
        deviceId: String,
        deviceModel: String,
        onPairingCode: (String) -> Unit,
        maxAttempts: Int = 60,
        pollIntervalMs: Long = 5_000,
    ): String? {
        authToken?.let { return it }

        // Already paired on the coordinator side (e.g. token lost locally,
        // or paired before tokens existed)? A signed status poll recovers
        // the token without re-pairing.
        fetchToken(baseUrl, deviceId)?.let { return it }

        // Not paired — start a pairing request and surface the code.
        val code = requestPairing(baseUrl, deviceId, deviceModel) ?: return null
        onPairingCode(code)
        Log.i(tag, "Pairing requested — confirm code on the coordinator UI")

        repeat(maxAttempts) {
            delay(pollIntervalMs)
            fetchToken(baseUrl, deviceId)?.let { return it }
        }
        Log.w(tag, "Pairing not confirmed after $maxAttempts polls")
        return null
    }

    /** POSTs a pairing request; returns the 6-digit code or null. */
    private suspend fun requestPairing(baseUrl: String, deviceId: String, deviceModel: String): String? {
        val body = JSONObject().apply {
            put("device_id", deviceId)
            put("device_model", deviceModel)
            put("device_pubkey", identity.publicKeyHex)
        }.toString()

        val request = Request.Builder()
            .url("$baseUrl/api/v1/sidecar/pair/request")
            .post(body.toRequestBody(JSON))
            .build()

        return try {
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            response.use {
                val json = JSONObject(it.body?.string() ?: "{}")
                when {
                    !it.isSuccessful -> {
                        Log.w(tag, "Pair request failed: HTTP ${it.code}")
                        null
                    }
                    json.optString("status") == "already_paired" -> {
                        // Token retrieval happens via fetchToken.
                        ""
                    }
                    else -> json.optString("code", "").ifEmpty { null }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Pair request error: ${e.message}")
            null
        }
    }

    /**
     * Polls pair/status with an Ed25519-signed query. If the coordinator
     * reports "paired", stores and returns the auth token.
     */
    suspend fun fetchToken(baseUrl: String, deviceId: String): String? {
        val ts = System.currentTimeMillis() / 1000
        val sig = identity.signPairStatus(deviceId, ts)
        val request = Request.Builder()
            .url("$baseUrl/api/v1/sidecar/pair/status?device_id=$deviceId&ts=$ts&sig=$sig")
            .get()
            .build()

        return try {
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            response.use {
                if (!it.isSuccessful) return null
                val json = JSONObject(it.body?.string() ?: "{}")
                if (json.optString("status") != "paired") return null
                val token = json.optString("token", "")
                if (token.isEmpty()) {
                    // Paired, but the coordinator didn't release the token —
                    // signature rejected (clock skew? key mismatch).
                    Log.w(tag, "Paired but no token released — check device clock / re-pair if persistent")
                    return null
                }
                authToken = token
                prefs.edit().putString(KEY_AUTH_TOKEN, token).apply()
                Log.i(tag, "Device auth token received and stored")
                token
            }
        } catch (e: Exception) {
            Log.w(tag, "Pair status error: ${e.message}")
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "phonon_pairing"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private val JSON = "application/json".toMediaType()
    }
}

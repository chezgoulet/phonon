package com.chezgoulet.phonon.pairing

import android.content.Context
import android.util.Log
import com.google.crypto.tink.subtle.Ed25519Sign
import java.io.File

/**
 * The phone's long-lived Ed25519 identity key.
 *
 * The 32-byte private key seed is generated on first use and persisted in
 * app-private storage (files/phonon_device.key). The public key is sent to
 * the coordinator at registration and pinned there at pairing time; the
 * private key signs the pair/status poll so the coordinator only releases
 * the device auth token to the holder of this key.
 *
 * The signed message format must match the coordinator's
 * internal/pair/deviceauth.go:
 *
 *     "phonon-pair-status|" + deviceId + "|" + unixSeconds
 */
class DeviceIdentity(context: Context) {
    private val tag = "DeviceIdentity"
    private val keyFile = File(context.filesDir, KEY_FILE_NAME)

    private val privateKey: ByteArray
    val publicKey: ByteArray

    init {
        privateKey = loadOrGenerateSeed()
        // Tink derives the public key from the private key seed.
        publicKey = Ed25519Sign.KeyPair.newKeyPairFromSeed(privateKey).publicKey
    }

    /** Hex-encoded public key, as expected by the coordinator. */
    val publicKeyHex: String get() = publicKey.toHex()

    /** Signs the pair/status poll for the given timestamp (unix seconds). */
    fun signPairStatus(deviceId: String, unixSeconds: Long): String {
        val msg = "$PAIR_STATUS_PREFIX|$deviceId|$unixSeconds".toByteArray(Charsets.UTF_8)
        val signer = Ed25519Sign(privateKey)
        return signer.sign(msg).toHex()
    }

    private fun loadOrGenerateSeed(): ByteArray {
        try {
            if (keyFile.exists()) {
                val seed = keyFile.readBytes()
                if (seed.size == SEED_BYTES) return seed
                Log.w(tag, "Device key file has wrong size (${seed.size}); regenerating")
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to read device key: ${e.message}; regenerating")
        }

        val seed = ByteArray(SEED_BYTES)
        java.security.SecureRandom().nextBytes(seed)
        try {
            keyFile.writeBytes(seed)
        } catch (e: Exception) {
            // Without persistence the device gets a new identity each boot
            // and must re-pair. Loud log, but keep running.
            Log.e(tag, "Failed to persist device key: ${e.message}")
        }
        Log.i(tag, "Generated new device identity key")
        return seed
    }

    companion object {
        private const val KEY_FILE_NAME = "phonon_device.key"
        private const val SEED_BYTES = 32
        private const val PAIR_STATUS_PREFIX = "phonon-pair-status"
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

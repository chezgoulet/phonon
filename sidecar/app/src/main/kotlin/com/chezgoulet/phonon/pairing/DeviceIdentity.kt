package com.chezgoulet.phonon.pairing

import android.content.Context
import android.util.Log
import com.google.crypto.tink.subtle.Ed25519Sign

/**
 * The phone's long-lived Ed25519 identity key (issue C-06).
 *
 * Storage model: the 32-byte private seed never touches disk in plaintext.
 * It is sealed by [KeystoreSeedCipher] under an AES-256-GCM key held inside
 * Android Keystore (StrongBox when available) and persisted only as an
 * authenticated ciphertext blob in files/phonon_device.key.enc; the seed is
 * decrypted transiently into memory for each signature operation.
 *
 * Why the seed is not a native Keystore key: AndroidKeyStore exposes no
 * stable Ed25519 keygen/sign algorithm across our supported range (minSdk
 * 29 → 35; KeyMint Ed25519 support is device-dependent), so the Ed25519
 * math remains Tink ([Ed25519Sign]) and Keystore provides the non-exportable
 * hardware-backed wrapping key. The seed is therefore protected against
 * plaintext-at-rest exfiltration (rooted device, custom ROM) even though it
 * still exists transiently in RAM to sign.
 *
 * Migration: a pre-existing plaintext files/phonon_device.key is re-sealed
 * into wrapped storage on first run and zero-wiped, preserving the paired
 * identity — no re-pairing needed. Unreadable or corrupt material is
 * invalidated and regenerated instead; the loud log below marks that case,
 * which requires re-pairing with the coordinator.
 *
 * The signed message format must match the coordinator's
 * internal/pair/deviceauth.go:
 *
 *     "phonon-pair-status|" + deviceId + "|" + unixSeconds
 */
class DeviceIdentity(context: Context) {
    private val tag = "DeviceIdentity"

    private val privateKey: ByteArray
    val publicKey: ByteArray

    init {
        val result = IdentitySeedStore(context.filesDir, KeystoreSeedCipher()).loadOrGenerate()
        privateKey = result.seed
        // Tink derives the public key from the private key seed.
        publicKey = ed25519PublicKeyFromSeed(privateKey)
        when (result.origin) {
            SeedOrigin.LOADED_WRAPPED ->
                Log.d(tag, "Loaded device identity key (Keystore-wrapped)")
            SeedOrigin.MIGRATED_LEGACY ->
                Log.i(tag, "Migrated device identity key into Keystore-wrapped storage; plaintext copy wiped")
            SeedOrigin.GENERATED_FRESH ->
                Log.i(tag, "Generated new device identity key")
            SeedOrigin.GENERATED_AFTER_INVALIDATION ->
                Log.e(
                    tag,
                    "Stored device identity key was unreadable/corrupt and has been regenerated; " +
                        "this device must re-pair with the coordinator",
                )
        }
    }

    /** Hex-encoded public key, as expected by the coordinator. */
    val publicKeyHex: String get() = publicKey.toHex()

    /** Signs the pair/status poll for the given timestamp (unix seconds). */
    fun signPairStatus(deviceId: String, unixSeconds: Long): String =
        ed25519SignToHex(privateKey, pairStatusMessage(deviceId, unixSeconds))
}

internal const val PAIR_STATUS_PREFIX = "phonon-pair-status"

/** Domain-separated pair/status message; mirrors internal/pair/deviceauth.go. */
internal fun pairStatusMessage(deviceId: String, unixSeconds: Long): ByteArray =
    "$PAIR_STATUS_PREFIX|$deviceId|$unixSeconds".toByteArray(Charsets.UTF_8)

internal fun ed25519PublicKeyFromSeed(seed: ByteArray): ByteArray =
    Ed25519Sign.KeyPair.newKeyPairFromSeed(seed).publicKey

internal fun ed25519SignToHex(seed: ByteArray, message: ByteArray): String =
    Ed25519Sign(seed).sign(message).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

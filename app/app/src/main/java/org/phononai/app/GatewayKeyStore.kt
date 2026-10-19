package org.phononai.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The gateway registration key, encrypted with an AES-256-GCM key that lives in
 * the Android Keystore.
 *
 * Stored in its own preferences file so an operator dumping `shared_prefs` sees
 * one opaque blob and no key material. The plaintext never touches disk, is
 * never logged, is never put in an Intent extra or argv, and is never written
 * into the device report.
 *
 * If the Keystore is unavailable the store refuses and says why. There is no
 * plaintext fallback: a key that authorises writes to a shared gateway is worth
 * more than the convenience.
 */
object GatewayKeyStore {
    private const val TAG = "Phonon"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "phonon-gateway-key"
    private const val PREFS = "phonon-secrets"
    private const val KEY_CIPHER = "gateway_key_cipher"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Returns null on success, or a sentence explaining why nothing was saved. */
    fun store(context: Context, secret: String): String? {
        if (secret.isEmpty()) {
            clear(context)
            return null
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            if (iv == null || iv.size != IV_BYTES) throw IllegalStateException("unexpected IV")
            val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            // iv || ciphertext+tag, base64. The IV is not secret.
            val blob = ByteArray(iv.size + encrypted.size)
            iv.copyInto(blob)
            encrypted.copyInto(blob, iv.size)
            prefs(context).edit()
                .putString(KEY_CIPHER, Base64.encodeToString(blob, Base64.NO_WRAP))
                .apply()
            null
        } catch (t: Throwable) {
            Log.w(TAG, "gateway key not stored: ${t.javaClass.simpleName}")
            "could not store the key in the Android Keystore (${t.javaClass.simpleName}); " +
                "it was not saved, and it is never written in plain text"
        }
    }

    /** The decrypted key, or null when none is stored or it cannot be read. */
    fun load(context: Context): String? {
        val encoded = prefs(context).getString(KEY_CIPHER, null) ?: return null
        return try {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            if (blob.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES),
            )
            val plain = cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
            plain.toString(Charsets.UTF_8)
        } catch (t: Throwable) {
            // A key that will not decrypt (Keystore reset, reinstall of the
            // Keystore entry) is treated as absent: the operator re-enters it.
            Log.w(TAG, "stored gateway key could not be read: ${t.javaClass.simpleName}")
            null
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_CIPHER).apply()
    }

    fun has(context: Context): Boolean = prefs(context).contains(KEY_CIPHER)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}

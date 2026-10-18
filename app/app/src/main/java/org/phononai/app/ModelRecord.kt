package org.phononai.app

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * A sidecar record proving that this installation verified a model file.
 *
 * `<model>.phonon` holds the file name, byte count, SHA-256, source, catalog id,
 * the time of verification and the app version, signed with an HMAC key that
 * lives in the Android Keystore (StrongBox where the device offers it).
 *
 * What this proves: this installation wrote this record, for this file, at that
 * time. It does not prove the bytes came from the upstream host — that guarantee
 * is the catalog hash compiled into the signed APK, which is the anchor. The
 * record exists so a 3 GB file does not have to be re-hashed on every start, and
 * so a swapped file no longer matches its own record.
 *
 * A record that fails verification is ignored; the caller falls back to hashing
 * and, on a mismatch, deletes the file.
 */
object ModelRecord {
    private const val TAG = "Phonon"
    private const val ALIAS = "phonon-model-record"
    private const val SUFFIX = ".phonon"
    private const val KEYSTORE = "AndroidKeyStore"

    fun pathFor(model: File): File = File(model.absolutePath + SUFFIX)

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Writes the record after a download has been verified. Best effort. */
    fun write(context: Context, model: File, sha256: String, source: String?, catalogId: String?) {
        try {
            val body = JSONObject().apply {
                put("file", model.name)
                put("bytes", model.length())
                put("sha256", sha256)
                put("source", source ?: "")
                put("catalog_id", catalogId ?: "")
                put("verified_at", Instant.now().toString())
                put("app_version", BuildConfig.VERSION_NAME)
            }.toString()
            val record = JSONObject().apply {
                put("record", body)
                put("hmac", hmac(body))
            }
            pathFor(model).writeText(record.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "model record not written: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * The recorded SHA-256, but only when the record's signature is intact and
     * the recorded size equals the file's current size. Null means "trust
     * nothing about this file".
     */
    fun recordedSha(model: File): String? {
        val recordFile = pathFor(model)
        if (!recordFile.isFile) return null
        return try {
            val outer = JSONObject(recordFile.readText())
            val body = outer.optString("record")
            val mac = outer.optString("hmac")
            if (body.isBlank() || mac.isBlank()) return null
            if (!MessageDigest.isEqual(mac.toByteArray(), hmac(body).toByteArray())) {
                Log.w(TAG, "model record failed verification: ${recordFile.name}")
                return null
            }
            val rec = JSONObject(body)
            if (rec.optLong("bytes") != model.length()) {
                Log.w(TAG, "model record size disagrees with the file: ${recordFile.name}")
                return null
            }
            rec.optString("sha256").takeIf { it.length == 64 }
        } catch (t: Throwable) {
            Log.w(TAG, "model record unreadable: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun hmac(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key())
        return mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                spec.setIsStrongBoxBacked(true)
            }
            generator.init(spec.build())
            return generator.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            val fallback = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            generator.init(fallback)
            return generator.generateKey()
        }
    }
}

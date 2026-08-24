package com.chezgoulet.phonon.pairing

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [SeedCipher] backed by Android Keystore.
 *
 * Holds an AES-256-GCM key inside the AndroidKeyStore provider (TEE or
 * StrongBox when available and supported by the device). The key is
 * non-exportable: it never leaves secure hardware, and its private material
 * is unreachable even on rooted devices.
 *
 * Wire format of a sealed blob: `iv(12) || ciphertext+tag(128-bit)`. The
 * AEAD envelope (the plaintext GCM authenticates) is
 * `formatVersion(1) || seed`, so the format version is authenticated INSIDE
 * the tag, not just framing outside it. Additionally the associated data
 * (AAD) is `"alias|version|filename"`: blobs are cryptographically bound to
 * this wrapping key's alias and to the storage filename, so a blob swapped
 * in from another alias/file/location fails authentication with
 * [javax.crypto.AEADBadTagException] — tampering, truncation,
 * cross-blob-splicing, and misfiling all fail closed with
 * [GeneralSecurityException], which the caller classifies as "invalidate
 * and regenerate".
 *
 * Note the honest scope of this protection (issue C-06): Android Keystore
 * offers no stable public Ed25519 keygen/sign algorithm across our range
 * (minSdk 29 → 35; KeyMint Ed25519 support is device-dependent), so this
 * class wraps the Tink-derived Ed25519 seed instead of holding the signing
 * key itself. The seed still exists transiently in app memory to sign; what
 * it is protected from is plaintext-at-rest exfiltration of app storage.
 */
class KeystoreSeedCipher(
    private val alias: String = DEFAULT_ALIAS,
    private val preferStrongBox: Boolean = true,
) : SeedCipher {

    override fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        cipher.updateAAD(aad())
        // Format-version byte lives INSIDE the authenticated envelope.
        val ciphertext = cipher.doFinal(byteArrayOf(FORMAT_VERSION) + plaintext)
        return cipher.iv + ciphertext
    }

    override fun unseal(sealed: ByteArray): ByteArray {
        if (sealed.size <= GCM_IV_BYTES + FORMAT_VERSION_BYTES) {
            throw GeneralSecurityException("wrapped identity blob too short")
        }
        val iv = sealed.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = sealed.copyOfRange(GCM_IV_BYTES, sealed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad())
        // Wrong key, wrong alias/filename AAD, or any tampering fails HERE
        // with AEADBadTagException — never returns unauthenticated bytes.
        val envelope = cipher.doFinal(ciphertext)
        if (envelope.isEmpty() || envelope[0] != FORMAT_VERSION) {
            throw GeneralSecurityException("unexpected envelope format version")
        }
        return envelope.copyOfRange(FORMAT_VERSION_BYTES, envelope.size)
    }

    /** Domain-separation binding: blob belongs to THIS alias + version + file. */
    private fun aad(): ByteArray =
        "$alias|${FORMAT_VERSION.toInt()}|${IdentitySeedStore.WRAPPED_FILE_NAME}"
            .toByteArray(Charsets.UTF_8)

    /** Returns the wrapping key from Keystore, generating it on first use. */
    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER)
        keyStore.load(null)
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        // StrongBox requires API 28+ hardware support; fall back to the
        // TEE-backed keystore when secure-element keygen fails for ANY
        // keystore-shaped reason — StrongBoxUnavailableException, vendor
        // ProviderException (secure element absent/busy, service not yet up
        // in early boot), or other GeneralSecurityException. Non-security
        // RuntimeExceptions are deliberately NOT swallowed.
        return try {
            generateKey(strongBox = preferStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        } catch (e: StrongBoxUnavailableException) {
            generateKey(strongBox = false)
        } catch (e: ProviderException) {
            generateKey(strongBox = false)
        } catch (e: GeneralSecurityException) {
            generateKey(strongBox = false)
        }
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(WRAP_KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val DEFAULT_ALIAS = "phonon_identity_wrap_v1"
        private const val WRAP_KEY_BITS = 256
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128

        /** Envelope format version, authenticated inside the AEAD tag. */
        internal const val FORMAT_VERSION: Byte = IdentitySeedStore.BLOB_VERSION_1
        private const val FORMAT_VERSION_BYTES = 1
    }
}

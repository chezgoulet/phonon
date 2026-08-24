package com.chezgoulet.phonon.pairing

import java.io.File
import java.security.SecureRandom

/**
 * Seals/unseals the identity seed. Implementations MUST provide
 * authenticated encryption: unsealing tampered or truncated input throws
 * [java.security.GeneralSecurityException], which [IdentitySeedStore]
 * treats as corrupt state.
 */
interface SeedCipher {
    /** Returns an implementation-specific sealed blob for [plaintext]. */
    fun seal(plaintext: ByteArray): ByteArray

    /** Reverses [seal]; throws on tamper, truncation, or garbage input. */
    fun unseal(sealed: ByteArray): ByteArray
}

/** How the current identity came to exist; drives DeviceIdentity logging. */
enum class SeedOrigin {
    /** Loaded from existing Keystore-wrapped storage. */
    LOADED_WRAPPED,

    /** Migrated from the legacy plaintext file (identity preserved). */
    MIGRATED_LEGACY,

    /** First run — no prior key material existed. */
    GENERATED_FRESH,

    /**
     * Prior key material existed but was unreadable/corrupt and was
     * invalidated; a new identity was generated (device must re-pair).
     */
    GENERATED_AFTER_INVALIDATION,
}

class SeedResult(val seed: ByteArray, val origin: SeedOrigin)

/**
 * Filesystem storage for the 32-byte Ed25519 identity seed.
 *
 * On disk the seed exists ONLY in sealed form ([SeedCipher] output prefixed
 * with a one-byte format version), written atomically via a temp file +
 * rename. Deliberately framework-free (no Android imports) so the
 * migrate-or-invalidate logic is unit-testable on the JVM; the production
 * cipher is [KeystoreSeedCipher].
 *
 * Resolution order in [loadOrGenerate]:
 *  1. Wrapped blob present → unseal and use it.
 *  2. Otherwise, legacy plaintext file present with exactly [SEED_BYTES]
 *     bytes → seal it into wrapped form, wipe the plaintext copy, use it
 *     (identity preserved — no re-pairing).
 *  3. Otherwise generate a fresh random seed and persist it sealed.
 *
 * Any unreadable/corrupt/wrong-size material is invalidated (zero-wiped,
 * removed) rather than trusted, and reported via [SeedOrigin].
 */
class IdentitySeedStore(
    private val dir: File,
    private val cipher: SeedCipher,
    private val random: SecureRandom = SecureRandom(),
) {
    private var invalidatedCorruptState = false

    fun loadOrGenerate(): SeedResult {
        loadWrapped()?.let { return SeedResult(it, SeedOrigin.LOADED_WRAPPED) }

        readLegacySeed()?.let { legacy ->
            writeWrapped(legacy)
            wipe(legacyFile)
            return SeedResult(legacy, SeedOrigin.MIGRATED_LEGACY)
        }

        val seed = ByteArray(SEED_BYTES).also { random.nextBytes(it) }
        writeWrapped(seed)
        val origin = if (invalidatedCorruptState) {
            SeedOrigin.GENERATED_AFTER_INVALIDATION
        } else {
            SeedOrigin.GENERATED_FRESH
        }
        return SeedResult(seed, origin)
    }

    /** Removes all stored key material for this store. */
    fun invalidate() {
        wipe(wrappedFile)
        wipe(legacyFile)
    }

    private fun loadWrapped(): ByteArray? {
        if (!wrappedFile.isFile) return null
        val blob = try {
            wrappedFile.readBytes()
        } catch (_: Exception) {
            return failCorrupt()
        }
        // Sanity floor: version byte + IV + auth tag for an AEAD blob.
        if (blob.size < MIN_BLOB_BYTES || blob[0] != BLOB_VERSION_1) return failCorrupt()
        val seed = try {
            cipher.unseal(blob.copyOfRange(1, blob.size))
        } catch (_: Exception) {
            return failCorrupt()
        }
        if (seed.size != SEED_BYTES) return failCorrupt()
        return seed
    }

    private fun failCorrupt(): ByteArray? {
        invalidatedCorruptState = true
        wipe(wrappedFile)
        return null
    }

    private fun readLegacySeed(): ByteArray? {
        if (!legacyFile.isFile) return null
        val bytes = try {
            legacyFile.readBytes()
        } catch (_: Exception) {
            return null
        }
        if (bytes.size != SEED_BYTES) {
            // Not a plausible identity key — treat as garbage, not identity.
            invalidatedCorruptState = true
            wipe(legacyFile)
            return null
        }
        return bytes
    }

    private fun writeWrapped(seed: ByteArray) {
        val blob = byteArrayOf(BLOB_VERSION_1) + cipher.seal(seed)
        val tmp = File(dir, WRAPPED_FILE_NAME + ".tmp")
        tmp.writeBytes(blob)
        if (wrappedFile.exists()) wrappedFile.delete()
        if (!tmp.renameTo(wrappedFile)) {
            tmp.copyTo(wrappedFile, overwrite = true)
            tmp.delete()
        }
    }

    /** Overwrites with zeros before deleting; best effort on failure. */
    private fun wipe(file: File) {
        try {
            if (!file.isFile) return
            val length = file.length().toInt().coerceAtLeast(SEED_BYTES)
            file.writeBytes(ByteArray(length))
            file.delete()
        } catch (_: Exception) {
            // Best effort; app-private storage already gates access.
        }
    }

    private val wrappedFile get() = File(dir, WRAPPED_FILE_NAME)
    private val legacyFile get() = File(dir, LEGACY_FILE_NAME)

    companion object {
        const val LEGACY_FILE_NAME = "phonon_device.key"
        const val WRAPPED_FILE_NAME = "phonon_device.key.enc"

        internal const val SEED_BYTES = 32
        internal const val BLOB_VERSION_1: Byte = 0x01

        // version(1) + GCM IV(12) + tag(16); exact floor depends on cipher.
        private const val MIN_BLOB_BYTES = 24
    }
}

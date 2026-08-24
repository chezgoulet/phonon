package com.chezgoulet.phonon.pairing

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

/**
 * Thrown when the sealed identity blob exists but could not be read or
 * unsealed for a reason that is NOT evidence of corruption — Keystore not
 * ready during early boot, TEE busy after an OTA, EBUSY/EACCES on storage,
 * provider hiccups.
 *
 * Unlike corruption (GCM auth failure), a transient failure leaves the
 * sealed blob untouched on disk so the NEXT boot/startup can retry with the
 * same identity intact. Callers MUST NOT regenerate key material in
 * response; treat this as "signing unavailable this boot" and surface it
 * loudly instead.
 */
class TransientUnsealException(message: String, cause: Throwable) : Exception(message, cause)

/**
 * Seals/unseals the identity seed. Implementations MUST provide
 * authenticated encryption and MUST throw [AEADBadTagException] (possibly
 * wrapped as the cause of another exception) when authentication fails —
 * i.e. on tamper, truncation, wrong key, or wrong AAD. [IdentitySeedStore]
 * classifies that case as corrupt state; ANY other exception type is
 * treated as transient ([TransientUnsealException]) and preserves the blob.
 */
interface SeedCipher {
    /** Returns an implementation-specific sealed blob for [plaintext]. */
    fun seal(plaintext: ByteArray): ByteArray

    /** Reverses [seal]; throws [AEADBadTagException] on tamper/truncation/wrong-key input. */
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
 * with a one-byte format version), written atomically: the temp file is
 * fsynced, then renamed over the target — the target is never pre-deleted,
 * so a crash can only leave the previous good blob or the new one. Deliberately framework-free (no Android imports) so the
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
 *
 * Corruption vs transient failure: only a GCM authentication failure
 * ([AEADBadTagException], directly or as cause) proves the stored blob is
 * tampered/truncated/foreign-keyed — that path invalidates and regenerates
 * (device must re-pair). Read errors and any other unseal exception
 * (Keystore not ready at early boot, TEE busy post-OTA, EBUSY/EACCES,
 * provider failures) are NOT corruption: they throw
 * [TransientUnsealException] with the blob preserved, so the next
 * startup/boot retries and keeps the existing identity. Never fall back to
 * generation on a transient failure — that would silently replace a good
 * identity.
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
        } catch (e: Exception) {
            // File exists but is unreadable right now (EBUSY, EACCES,
            // storage not mounted yet) — no evidence about its content,
            // hence transient rather than corrupt.
            throw TransientUnsealException("failed reading ${wrappedFile.name}", e)
        }
        // Sanity floor: version byte + IV + auth tag for an AEAD blob.
        if (blob.size < MIN_BLOB_BYTES || blob[0] != BLOB_VERSION_1) return failCorrupt()
        val seed = try {
            cipher.unseal(blob.copyOfRange(1, blob.size))
        } catch (e: Exception) {
            if (!isGcmAuthFailure(e)) {
                // Keystore not ready / TEE busy / provider failure — the
                // blob may be perfectly fine. Keep it; retry next boot.
                throw TransientUnsealException(
                    "unsealing ${wrappedFile.name} failed without GCM auth failure",
                    e,
                )
            }
            return failCorrupt()
        }
        if (seed.size != SEED_BYTES) return failCorrupt()
        return seed
    }

    /**
     * True iff [e] is (or wraps, via its cause chain) an AES-GCM tag
     * verification failure — the only outcome that proves tampering,
     * truncation, or a foreign wrapping key. AndroidKeyStore sometimes
     * surfaces it nested inside [java.security.ProviderException], so the
     * whole cause chain is inspected.
     */
    private fun isGcmAuthFailure(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            if (cur is AEADBadTagException) return true
            cur = cur.cause
        }
        return false
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
        try {
            tmp.writeBytes(blob)
            fsync(tmp)

            // POSIX rename atomically REPLACES the target: readers observe
            // either the old blob or the new one, never a window where
            // neither exists. Do NOT delete the target first — a crash
            // between delete and rename used to leave no identity at all,
            // forcing a fresh generation (and re-pairing).
            if (tmp.renameTo(wrappedFile)) return

            // Fallback for filesystems that refuse replace-on-rename:
            // stage a copy through a second temp file so this path is
            // tmp+rename too, never an in-place overwrite of good state.
            val staged = File(dir, WRAPPED_FILE_NAME + ".tmp2")
            try {
                tmp.copyTo(staged, overwrite = true)
                fsync(staged)
                if (!staged.renameTo(wrappedFile)) {
                    // Both renames refused replacement. Throwing (loud,
                    // retry next boot) is safer than overwriting a possibly
                    // intact blob in place.
                    throw IOException("cannot replace $WRAPPED_FILE_NAME via rename")
                }
            } finally {
                staged.delete()
            }
        } finally {
            tmp.delete()
        }
    }

    /**
     * Best-effort durability barrier: flush the temp file's data to stable
     * storage before it is renamed into place, so a power cut cannot leave
     * the renamed target with zero-length or unwritten content.
     */
    private fun fsync(file: File) {
        try {
            FileInputStream(file).use { it.fd.sync() }
        } catch (_: Exception) {
            // Best effort: a missed fsync degrades crash-durability, not
            // secrecy, and some mounts simply do not support it.
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

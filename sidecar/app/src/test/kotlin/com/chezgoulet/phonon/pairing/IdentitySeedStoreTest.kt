package com.chezgoulet.phonon.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

/**
 * JVM tests for the migrate-or-invalidate logic and the corrupt-vs-transient
 * classification. The cipher is a deterministic stand-in ([FakeCipher]) whose
 * tamper detection mimics GCM: modified input raises [AEADBadTagException].
 * The production cipher is [KeystoreSeedCipher], exercised only on a device
 * (see the androidTest source set).
 */
class IdentitySeedStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * XOR-pad stand-in — NOT real AEAD; plumbing tests only. Appends a
     * one-byte checksum over the padded body so any modification of the
     * sealed bytes is DETECTED exactly like GCM would (via
     * [AEADBadTagException]), letting the store's corrupt-vs-transient
     * split be exercised without Android Keystore.
     */
    private class FakeCipher : SeedCipher {
        private val pad = ByteArray(48) { ((it * 37 + 11) and 0xFF).toByte() }

        override fun seal(plaintext: ByteArray): ByteArray {
            val xored = ByteArray(plaintext.size) { (plaintext[it].toInt() xor pad[it % pad.size].toInt()).toByte() }
            return xored + byteArrayOf(checksum(xored))
        }

        override fun unseal(sealed: ByteArray): ByteArray {
            if (sealed.size < 2) throw GeneralSecurityException("blob too short")
            val body = sealed.copyOfRange(0, sealed.size - 1)
            if (checksum(body) != sealed[sealed.size - 1]) {
                throw AEADBadTagException("fake tag mismatch")
            }
            return ByteArray(body.size) { (body[it].toInt() xor pad[it % pad.size].toInt()).toByte() }
        }

        private fun checksum(b: ByteArray): Byte {
            var acc = 0x5A
            for (x in b) acc = ((acc * 31) + x.toInt()) and 0xFF
            return acc.toByte()
        }
    }

    /** Cipher whose unseal ALWAYS fails with [error]; sealing works so an
     *  invalidate-and-regenerate flow can complete inside the same store. */
    private class ThrowingCipher(private val error: Exception) : SeedCipher {
        override fun seal(plaintext: ByteArray): ByteArray =
            ByteArray(plaintext.size) { (plaintext[it] + 1).toByte() }

        override fun unseal(sealed: ByteArray): ByteArray = throw error
    }

    private fun newStore(dir: File = tmp.root): IdentitySeedStore =
        IdentitySeedStore(dir, FakeCipher())

    private val legacyFile get() = File(tmp.root, IdentitySeedStore.LEGACY_FILE_NAME)
    private val wrappedFile get() = File(tmp.root, IdentitySeedStore.WRAPPED_FILE_NAME)

    // ── fresh generation ─────────────────────────────────────────────

    @Test
    fun `first run generates a fresh seed`() {
        val result = newStore().loadOrGenerate()
        assertEquals(SeedOrigin.GENERATED_FRESH, result.origin)
        assertEquals(32, result.seed.size)
    }

    @Test
    fun `reload yields the same seed and loaded origin`() {
        val first = newStore().loadOrGenerate()
        val second = newStore().loadOrGenerate()
        assertEquals(SeedOrigin.LOADED_WRAPPED, second.origin)
        assertTrue(first.seed.contentEquals(second.seed))
    }

    @Test
    fun `seed never touches disk in plaintext`() {
        val seed = newStore().loadOrGenerate().seed
        val files = tmp.root.listFiles().orEmpty()
        assertTrue("expected stored artifacts", files.isNotEmpty())
        for (f in files.filter { it.isFile }) {
            val bytes = f.readBytes()
            assertFalse(
                "${f.name} contains the raw seed",
                indexOf(bytes, seed) >= 0,
            )
            assertFalse(
                "${f.name} IS the raw seed",
                bytes.contentEquals(seed),
            )
        }
    }

    @Test
    fun `wrapped blob is written atomically without temp leftovers`() {
        newStore().loadOrGenerate()
        assertTrue(wrappedFile.isFile)
        assertFalse(File(tmp.root, IdentitySeedStore.WRAPPED_FILE_NAME + ".tmp").exists())
        assertEquals(IdentitySeedStore.BLOB_VERSION_1, wrappedFile.readBytes()[0])
    }

    // ── legacy migration ─────────────────────────────────────────────

    @Test
    fun `legacy plaintext key migrates preserving identity`() {
        val legacySeed = ByteArray(32) { it.toByte() }
        legacyFile.writeBytes(legacySeed)

        val result = newStore().loadOrGenerate()

        assertEquals(SeedOrigin.MIGRATED_LEGACY, result.origin)
        assertTrue(result.seed.contentEquals(legacySeed))
        assertTrue(result.legacyWipeSucceeded)
        assertFalse("plaintext copy must be removed", legacyFile.exists())
        assertTrue("sealed copy must exist", wrappedFile.isFile)

        val reloaded = newStore().loadOrGenerate()
        assertTrue(reloaded.seed.contentEquals(legacySeed))
    }

    @Test
    fun `migrated identity keeps the coordinator-visible public key stable`() {
        val legacySeed = ByteArray(32) { (it * 5 + 1).toByte() }
        legacyFile.writeBytes(legacySeed)
        val expectedPubHex = ed25519PublicKeyFromSeed(legacySeed).toHexString()

        val migrated = newStore().loadOrGenerate()

        assertEquals(expectedPubHex, ed25519PublicKeyFromSeed(migrated.seed).toHexString())
    }

    @Test
    fun `legacy file with wrong size is invalidated not adopted`() {
        legacyFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))

        val result = newStore().loadOrGenerate()

        assertEquals(SeedOrigin.GENERATED_AFTER_INVALIDATION, result.origin)
        assertFalse(legacyFile.exists())
        assertTrue(wrappedFile.isFile)
    }

    // ── invalidate-on-corruption ─────────────────────────────────────

    @Test
    fun `corrupt wrapped blob invalidates cleanly and regenerates`() {
        val original = newStore().loadOrGenerate()
        val blob = wrappedFile.readBytes()
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()
        wrappedFile.writeBytes(blob)

        val result = newStore().loadOrGenerate()

        assertEquals(SeedOrigin.GENERATED_AFTER_INVALIDATION, result.origin)
        assertNotEquals(0, result.seed.size)
        assertFalse(original.seed.contentEquals(result.seed))
        // The regenerated state must itself be loadable.
        val again = newStore().loadOrGenerate()
        assertTrue(result.seed.contentEquals(again.seed))
    }

    @Test
    fun `unknown blob version invalidates cleanly`() {
        newStore().loadOrGenerate()
        val blob = wrappedFile.readBytes()
        blob[0] = 0x02
        wrappedFile.writeBytes(blob)

        val result = newStore().loadOrGenerate()

        assertEquals(SeedOrigin.GENERATED_AFTER_INVALIDATION, result.origin)
        assertEquals(IdentitySeedStore.BLOB_VERSION_1, wrappedFile.readBytes()[0])
    }

    @Test
    fun `truncated wrapped blob invalidates cleanly`() {
        newStore().loadOrGenerate()
        val blob = wrappedFile.readBytes()
        wrappedFile.writeBytes(blob.copyOfRange(0, blob.size / 2))

        val result = newStore().loadOrGenerate()

        assertEquals(SeedOrigin.GENERATED_AFTER_INVALIDATION, result.origin)
        assertEquals(32, result.seed.size)
    }

    @Test
    fun `valid legacy key survives a corrupt wrapped blob`() {
        val legacySeed = ByteArray(32) { (it * 3).toByte() }
        legacyFile.writeBytes(legacySeed)
        val migrated = newStore().loadOrGenerate()
        assertEquals(SeedOrigin.MIGRATED_LEGACY, migrated.origin)

        // Corrupt the wrapped copy while a stray legacy file reappears.
        wrappedFile.writeBytes(ByteArray(40) { 0x55 })
        legacyFile.writeBytes(legacySeed)

        val result = newStore().loadOrGenerate()

        assertEquals(SeedOrigin.MIGRATED_LEGACY, result.origin)
        assertTrue(result.seed.contentEquals(legacySeed))
        assertFalse(legacyFile.exists())
    }

    @Test
    fun `generated seeds are random across stores`() {
        val a = newStore().loadOrGenerate().seed
        val b = IdentitySeedStore(tmp.newFolder(), FakeCipher(), SecureRandom()).loadOrGenerate().seed
        assertFalse(a.contentEquals(b))
    }

    // ── transient vs corrupt classification ──────────────────────────

    @Test
    fun `transient unseal failure keeps the blob and throws the typed error`() {
        val original = newStore().loadOrGenerate()
        val blobBefore = wrappedFile.readBytes()

        // Simulates early-boot Keystore-not-ready / storage EBUSY: an
        // IOException-shaped failure with NO GCM verdict.
        val store = IdentitySeedStore(tmp.root, ThrowingCipher(IOException("simulated EBUSY")))

        val thrown = assertThrows(TransientUnsealException::class.java) {
            store.loadOrGenerate()
        }
        assertEquals("simulated EBUSY", thrown.cause?.message)

        // The sealed blob must survive untouched for retry-on-next-boot.
        assertTrue("blob must survive a transient failure", wrappedFile.isFile)
        assertTrue(blobBefore.contentEquals(wrappedFile.readBytes()))

        // Once the transient condition clears, the SAME identity loads.
        val recovered = newStore().loadOrGenerate()
        assertEquals(SeedOrigin.LOADED_WRAPPED, recovered.origin)
        assertTrue(original.seed.contentEquals(recovered.seed))
    }

    @Test
    fun `keystore-shaped key failures are transient not corrupt`() {
        newStore().loadOrGenerate()
        val blobBefore = wrappedFile.readBytes()

        assertThrows(TransientUnsealException::class.java) {
            IdentitySeedStore(tmp.root, ThrowingCipher(InvalidKeyException("keystore not ready")))
                .loadOrGenerate()
        }
        assertTrue(blobBefore.contentEquals(wrappedFile.readBytes()))
    }

    @Test
    fun `gcm auth failure wipes the blob and regenerates`() {
        newStore().loadOrGenerate()

        val result = IdentitySeedStore(tmp.root, ThrowingCipher(AEADBadTagException("tag mismatch")))
            .loadOrGenerate()

        assertEquals(SeedOrigin.GENERATED_AFTER_INVALIDATION, result.origin)
        // The wiped state was replaced by a fresh, loadable identity.
        assertTrue(wrappedFile.isFile)
        assertEquals(IdentitySeedStore.BLOB_VERSION_1, wrappedFile.readBytes()[0])
    }

    @Test
    fun `gcm auth failure wrapped in a provider exception still counts as corrupt`() {
        // AndroidKeyStore frequently nests AEADBadTagException inside
        // ProviderException — the cause chain must be walked.
        newStore().loadOrGenerate()
        val wrapped = ProviderException("keystore operation failed")
        wrapped.initCause(AEADBadTagException("tag"))

        val result = IdentitySeedStore(tmp.root, ThrowingCipher(wrapped)).loadOrGenerate()

        assertEquals(SeedOrigin.GENERATED_AFTER_INVALIDATION, result.origin)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}

internal fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

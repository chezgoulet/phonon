package com.chezgoulet.phonon.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * JVM tests for the migrate-or-invalidate storage logic. The cipher is a
 * deterministic stand-in ([FakeCipher]); the production cipher is
 * [KeystoreSeedCipher], exercised only on a device (see KeystoreSeedCipherTest).
 */
class IdentitySeedStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeCipher : SeedCipher {
        // XOR stand-in — NOT AEAD; plumbing tests only. Pad is non-trivial so
        // sealed bytes never equal plaintext bytes.
        private val pad = ByteArray(48) { ((it * 37 + 11) and 0xFF).toByte() }

        override fun seal(plaintext: ByteArray): ByteArray =
            ByteArray(plaintext.size) { (plaintext[it] xor pad[it % pad.size]) }

        override fun unseal(sealed: ByteArray): ByteArray {
            if (sealed.size < 8) throw GeneralSecurityException("blob too short")
            return ByteArray(sealed.size) { (sealed[it] xor pad[it % pad.size]) }
        }
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

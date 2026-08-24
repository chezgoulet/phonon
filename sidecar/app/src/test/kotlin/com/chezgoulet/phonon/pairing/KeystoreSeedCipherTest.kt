package com.chezgoulet.phonon.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.IOException
import javax.crypto.AEADBadTagException

/**
 * JVM-side contract tests for the [SeedCipher] abstraction, using a
 * deterministic fake — NOT the real AndroidKeyStore.
 *
 * The real-Keystore behavior (round trip, key persistence across instances,
 * tamper rejection) is exercised by the instrumented test of the same name
 * in src/androidTest/ and runs via :connectedDebugAndroidTest on a device
 * runner; it cannot run in :testDebugUnitTest because AndroidKeyStore does
 * not exist on the JVM.
 *
 * What this file locks down is the CONTRACT [IdentitySeedStore] relies on:
 * seal/unseal round trip, and authentication failures surfaced as
 * [AEADBadTagException] (the only exception type the store classifies as
 * corruption; everything else is treated as transient).
 */
class KeystoreSeedCipherTest {

    /**
     * Stand-in AEAD: XOR-pad body plus a one-byte checksum "tag". Tampering
     * raises AEADBadTagException like real GCM would.
     */
    private class FakeCipher : SeedCipher {
        private val pad = ByteArray(48) { ((it * 37 + 11) and 0xFF).toByte() }

        override fun seal(plaintext: ByteArray): ByteArray {
            val xored = ByteArray(plaintext.size) { (plaintext[it] xor pad[it % pad.size]) }
            return xored + byteArrayOf(checksum(xored))
        }

        override fun unseal(sealed: ByteArray): ByteArray {
            if (sealed.size < 2) throw AEADBadTagException("fake tag mismatch")
            val body = sealed.copyOfRange(0, sealed.size - 1)
            if (checksum(body) != sealed[sealed.size - 1]) {
                throw AEADBadTagException("fake tag mismatch")
            }
            return ByteArray(body.size) { (body[it] xor pad[it % pad.size]) }
        }

        private fun checksum(b: ByteArray): Byte {
            var acc = 0x5A
            for (x in b) acc = ((acc * 31) + x.toInt()) and 0xFF
            return acc.toByte()
        }
    }

    private val cipher = FakeCipher()

    @Test
    fun `seal unseal round trip`() {
        val seed = ByteArray(32) { it.toByte() }
        val sealed = cipher.seal(seed)
        assertNotEquals(seed.toList(), sealed.toList())
        assertEquals(seed.toList(), cipher.unseal(sealed).toList())
    }

    @Test
    fun `tampered blob raises aead bad tag`() {
        val sealed = cipher.seal(ByteArray(32))
        sealed[0] = (sealed[0] + 1).toByte()
        assertThrows(AEADBadTagException::class.java) { cipher.unseal(sealed) }
    }

    @Test
    fun `non security failures are not auth failures`() {
        // Contract: only AEADBadTagException means corruption; an
        // IOException from the cipher must stay distinguishable so the
        // store can treat it as transient instead of wiping identity.
        val transient = object : SeedCipher by cipher {
            override fun unseal(sealed: ByteArray): ByteArray =
                throw IOException("simulated EBUSY")
        }
        assertThrows(IOException::class.java) { transient.unseal(cipher.seal(ByteArray(32))) }
    }
}

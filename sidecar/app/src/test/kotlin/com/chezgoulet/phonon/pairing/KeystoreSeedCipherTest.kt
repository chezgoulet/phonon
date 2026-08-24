package com.chezgoulet.phonon.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Ignore
import org.junit.Test
import java.security.GeneralSecurityException

/**
 * Exercises [KeystoreSeedCipher] against the real AndroidKeyStore provider.
 *
 * IGNORED on the JVM by design: AndroidKeyStore does not exist outside an
 * Android device/emulator, so these cannot run in :testDebugUnitTest.
 * Run them under :connectedDebugAndroidTest (device farm covers the issue's
 * Pixel 6 / API 31 → Pixel 9 / API 35 matrix) before merging to main.
 */
@Ignore("Requires an Android device/emulator: exercises the real AndroidKeyStore provider")
class KeystoreSeedCipherTest {

    private val cipher = KeystoreSeedCipher()

    @Test
    fun `seal unseal round trip`() {
        val seed = ByteArray(32) { it.toByte() }
        val sealed = cipher.seal(seed)
        assertNotEquals(seed.toList(), sealed.toList())
        assertEquals(seed.toList(), cipher.unseal(sealed).toList())
    }

    @Test
    fun `sealed blobs are non deterministic`() {
        val seed = ByteArray(32) { (it * 7).toByte() }
        assertNotEquals(cipher.seal(seed).toList(), cipher.seal(seed).toList())
    }

    @Test(expected = GeneralSecurityException::class)
    fun `tampered blob fails authentication`() {
        val sealed = cipher.seal(ByteArray(32))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        cipher.unseal(sealed)
    }
}

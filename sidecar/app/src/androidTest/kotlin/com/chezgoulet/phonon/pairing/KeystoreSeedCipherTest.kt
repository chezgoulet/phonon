package com.chezgoulet.phonon.pairing

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException

/**
 * Exercises [KeystoreSeedCipher] against the real AndroidKeyStore provider.
 *
 * Lives in the androidTest source set BY DESIGN: AndroidKeyStore does not
 * exist outside an Android device/emulator, so these cannot run in
 * :testDebugUnitTest (the JVM-side contract is covered by the FakeCipher
 * tests in the unit source set). Run via :connectedDebugAndroidTest on a
 * registered device runner; CI wires that up in .github/workflows/ci.yml.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSeedCipherTest {

    private val cipher = KeystoreSeedCipher()

    @Test
    fun sealUnsealRoundTrip() {
        val seed = ByteArray(32) { it.toByte() }
        val sealed = cipher.seal(seed)
        assertNotEquals(seed.toList(), sealed.toList())
        assertEquals(seed.toList(), cipher.unseal(sealed).toList())
    }

    @Test
    fun keyPersistsAcrossCipherInstances() {
        // The wrapping key is non-exportable but must be stable: a blob
        // sealed by one process/instance unseals in another (reboot proxy).
        val seed = ByteArray(32) { (it * 3).toByte() }
        val sealed = KeystoreSeedCipher().seal(seed)
        assertEquals(seed.toList(), KeystoreSeedCipher().unseal(sealed).toList())
    }

    @Test
    fun sealedBlobsAreNonDeterministic() {
        val seed = ByteArray(32) { (it * 7).toByte() }
        assertNotEquals(cipher.seal(seed).toList(), cipher.seal(seed).toList())
    }

    @Test(expected = GeneralSecurityException::class)
    fun tamperedBlobFailsAuthentication() {
        val sealed = cipher.seal(ByteArray(32))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        cipher.unseal(sealed)
    }
}

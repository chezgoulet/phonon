package com.chezgoulet.phonon.pairing

import com.google.crypto.tink.subtle.Ed25519Verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.GeneralSecurityException

/**
 * Locks the coordinator-facing identity contract: public-key extraction
 * from a seed and the pair/status signature format must never drift from
 * internal/pair/deviceauth.go ("phonon-pair-status|" + deviceId + "|" + ts).
 */
class PairingIdentityContractTest {

    // RFC 8032 §7.1 TEST 1 — guards against accidental curve/derivation changes.
    private val rfc8032Seed = hex(
        "9d61b19deffd5a60ba844af492ec2cc4" +
            "4449c5697b326919703bac031cae7f60",
    )
    private val rfc8032PublicKey = hex(
        "d75a980182b10ab7d54bfed3c964073a" +
            "0ee172f3daa62325af021a68f707511a",
    )

    @Test
    fun `public key extraction matches RFC 8032 vector`() {
        assertArrayEquals(rfc8032PublicKey, ed25519PublicKeyFromSeed(rfc8032Seed))
    }

    @Test
    fun `public key extraction is deterministic across calls`() {
        val first = ed25519PublicKeyFromSeed(rfc8032Seed)
        val second = ed25519PublicKeyFromSeed(rfc8032Seed)
        assertArrayEquals(first, second)
    }

    @Test
    fun `pair status message matches coordinator wire format`() {
        val msg = pairStatusMessage("pixel-6-abc", 1_719_000_000)
        assertEquals(
            "phonon-pair-status|pixel-6-abc|1719000000",
            msg.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `signature verifies under the derived public key`() {
        val pub = ed25519PublicKeyFromSeed(rfc8032Seed)
        val sigHex = ed25519SignToHex(rfc8032Seed, pairStatusMessage("device-1", 1234567890L))

        Ed25519Verify(pub).verify(hex(sigHex), pairStatusMessage("device-1", 1234567890L))
    }

    @Test
    fun `signature is bound to device id and timestamp`() {
        val pub = ed25519PublicKeyFromSeed(rfc8032Seed)
        val sigHex = ed25519SignToHex(rfc8032Seed, pairStatusMessage("device-1", 1000L))

        assertFalse(try {
            Ed25519Verify(pub).verify(hex(sigHex), pairStatusMessage("device-2", 1000L))
            true
        } catch (_: GeneralSecurityException) {
            false
        })
        assertFalse(try {
            Ed25519Verify(pub).verify(hex(sigHex), pairStatusMessage("device-1", 2000L))
            true
        } catch (_: GeneralSecurityException) {
            false
        })
    }

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

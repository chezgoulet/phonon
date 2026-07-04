package com.chezgoulet.phonon.coordinator

import com.chezgoulet.phonon.coordinator.CoordinatorClient.Companion.RECONNECT_BASE_MS
import com.chezgoulet.phonon.coordinator.CoordinatorClient.Companion.RECONNECT_CAP_MS
import com.chezgoulet.phonon.coordinator.CoordinatorClient.Companion.RECONNECT_JITTER_FRACTION
import com.chezgoulet.phonon.coordinator.CoordinatorClient.Companion.backoffBaseMs
import com.chezgoulet.phonon.coordinator.CoordinatorClient.Companion.jitteredDelayMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the WebSocket reconnect backoff (#234). Verifies the
 * deterministic exponential curve and the jitter bounds independently.
 */
class ReconnectBackoffTest {

    @Test
    fun `backoff doubles each attempt from the base`() {
        assertEquals(1_000L, backoffBaseMs(0))
        assertEquals(2_000L, backoffBaseMs(1))
        assertEquals(4_000L, backoffBaseMs(2))
        assertEquals(8_000L, backoffBaseMs(3))
        assertEquals(16_000L, backoffBaseMs(4))
        assertEquals(32_000L, backoffBaseMs(5))
    }

    @Test
    fun `backoff is capped and never below base`() {
        // 2^6 * 1000 = 64000 > cap.
        assertEquals(RECONNECT_CAP_MS, backoffBaseMs(6))
        assertEquals(RECONNECT_CAP_MS, backoffBaseMs(100))
        // Defensive: negative attempts clamp to the base delay.
        assertEquals(RECONNECT_BASE_MS, backoffBaseMs(-1))
    }

    @Test
    fun `jitter stays within the configured fraction of the base`() {
        val base = 8_000L
        val maxSpread = (base * RECONNECT_JITTER_FRACTION).toLong()
        // Extremes.
        assertEquals(base - maxSpread, jitteredDelayMs(base, -1.0))
        assertEquals(base + maxSpread, jitteredDelayMs(base, 1.0))
        assertEquals(base, jitteredDelayMs(base, 0.0))
        // Sweep: every jittered delay is within ±fraction and non-negative.
        var r = -1.0
        while (r <= 1.0) {
            val d = jitteredDelayMs(base, r)
            assertTrue("delay $d below lower bound", d >= base - maxSpread)
            assertTrue("delay $d above upper bound", d <= base + maxSpread)
            assertTrue("delay negative", d >= 0)
            r += 0.1
        }
    }

    @Test
    fun `jitter ratio outside range is clamped`() {
        val base = 4_000L
        val maxSpread = (base * RECONNECT_JITTER_FRACTION).toLong()
        assertEquals(base + maxSpread, jitteredDelayMs(base, 5.0))
        assertEquals(base - maxSpread, jitteredDelayMs(base, -5.0))
    }
}

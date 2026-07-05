package com.chezgoulet.phonon

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [PhononService.tokensPerSecond] — the on-phone throughput
 * figure that drives the viz packs' tokens/sec readout. Previously this stayed
 * pinned at 0 because reportInference was never called; now it must reflect
 * real completed-inference throughput.
 */
class TokensPerSecondTest {

    @Test
    fun `computes tokens per second from tokens and duration`() {
        // 100 tokens in 2000ms = 50 tok/s.
        assertEquals(50f, PhononService.tokensPerSecond(100, 2_000L), 0.001f)
    }

    @Test
    fun `zero duration yields zero, not a divide-by-zero spike`() {
        assertEquals(0f, PhononService.tokensPerSecond(100, 0L), 0.001f)
        assertEquals(0f, PhononService.tokensPerSecond(100, -5L), 0.001f)
    }

    @Test
    fun `result is capped to guard against tiny-duration spikes`() {
        // 1000 tokens in 1ms would be 1_000_000 tok/s; must clamp to the cap.
        assertEquals(
            PhononService.CAP_TOKENS_PER_SEC,
            PhononService.tokensPerSecond(1_000, 1L),
            0.001f
        )
    }

    @Test
    fun `zero tokens yields zero`() {
        assertEquals(0f, PhononService.tokensPerSecond(0, 1_000L), 0.001f)
    }
}

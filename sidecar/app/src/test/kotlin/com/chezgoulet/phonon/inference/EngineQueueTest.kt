package com.chezgoulet.phonon.inference

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [EngineQueue] — the real inference queue-depth counter that
 * replaces the sidecar's historical hardcoded `queueDepth = 0`.
 */
class EngineQueueTest {

    @Test
    fun `depth is one while generating and zero after`() = runBlocking {
        val q = EngineQueue()
        assertEquals("idle depth", 0, q.depth)

        var depthInside = -1
        q.withEngine { depthInside = q.depth }

        assertEquals("depth while holding the engine", 1, depthInside)
        assertEquals("depth after completion", 0, q.depth)
    }

    @Test
    fun `depth counts waiters, not just the holder`() = runBlocking {
        val q = EngineQueue()

        val holderEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        // Holder acquires the engine and stays inside until released.
        val holder = launch(Dispatchers.Default) {
            q.withEngine {
                holderEntered.complete(Unit)
                release.await()
            }
        }
        holderEntered.await()
        assertEquals("only the holder is in flight", 1, q.depth)

        // A second request enters withEngine and blocks waiting for the lock.
        val waiter = launch(Dispatchers.Default) { q.withEngine { } }

        // The waiter increments before blocking on the lock, so depth must
        // reach 2 (holder + waiter). Poll to avoid a fixed-sleep race.
        withTimeout(2_000) {
            while (q.depth < 2) delay(5)
        }
        assertEquals("holder plus one waiter", 2, q.depth)

        release.complete(Unit)
        holder.join()
        waiter.join()
        assertEquals("all requests drained", 0, q.depth)
    }

    @Test
    fun `depth returns to zero when the block throws`() = runBlocking {
        val q = EngineQueue()
        try {
            q.withEngine { throw RuntimeException("boom") }
        } catch (_: RuntimeException) {
            // expected — the finally must still decrement.
        }
        assertEquals("depth after a failed generation", 0, q.depth)
    }
}

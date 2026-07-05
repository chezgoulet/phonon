package com.chezgoulet.phonon.inference

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serializes access to the shared LiteRT-LM engine and tracks the real
 * inference queue depth — the number of requests currently holding or waiting
 * on the engine.
 *
 * [depth] is what the sidecar reports to the coordinator in its heartbeat,
 * replacing the historical hardcoded 0. The counter is incremented *before*
 * acquiring the lock (so waiting requests are counted, not just the single
 * holder) and always decremented once generation completes — even on failure.
 *
 * Extracted from [InferenceServer] so the queue-depth semantics can be unit
 * tested without an Android Context.
 */
class EngineQueue {
    private val mutex = Mutex()
    private val inFlight = AtomicInteger(0)

    /** Requests currently holding or waiting on the engine. */
    val depth: Int get() = inFlight.get()

    /**
     * Runs [block] under the engine mutex, counting the caller as in-flight for
     * the entire time it is queued or generating.
     */
    suspend fun <T> withEngine(block: suspend () -> T): T {
        inFlight.incrementAndGet()
        try {
            return mutex.withLock { block() }
        } finally {
            inFlight.decrementAndGet()
        }
    }
}

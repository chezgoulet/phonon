package org.phononai.app

/**
 * The rule that turns a measured time-to-first-token into a keep-warm interval.
 *
 * The model is mmap'd weights on a phone that reclaims file-backed pages after a
 * few idle minutes. A beat that comes back fast means the pages survived and the
 * interval can be lengthened; a beat that comes back slow means the kernel
 * evicted them and the interval must be shortened so the next touch is sooner.
 * A one-token beat's wall time *is* its time-to-first-token, so the beat measures
 * its own sample.
 *
 * Starting point: the value in the stored tuning profile when present, else a
 * previously adapted value, else 45 s. Always clamped to [MIN_S, MAX_S]. Pure so
 * the adaptation is unit tested on the JVM.
 */
object KeepWarmAdaptation {

    const val MIN_S = 15
    const val MAX_S = 300
    const val DEFAULT_S = 45

    /** A beat at or below this is "the cache survived"; lengthen. */
    const val FAST_TTFT_MS = 2_000L

    /** A beat at or above this is "the cache was evicted"; shorten. */
    const val SLOW_TTFT_MS = 8_000L

    /** How much one step moves the interval, in seconds. */
    const val STEP_S = 15

    fun clamp(intervalS: Int): Int = intervalS.coerceIn(MIN_S, MAX_S)

    /**
     * The interval to start from. Profile first, then a previously adapted value,
     * then the 45 s default; each positive value is clamped into range.
     */
    fun start(profileValueS: Int?, adaptedValueS: Int?): Int {
        val raw = profileValueS?.takeIf { it > 0 }
            ?: adaptedValueS?.takeIf { it > 0 }
            ?: DEFAULT_S
        return clamp(raw)
    }

    /**
     * The next interval after a beat that took [ttftMs]. Fast lengthens, slow
     * shortens, anything in between keeps the current value; a non-positive
     * sample (a failed beat) changes nothing.
     */
    fun next(currentS: Int, ttftMs: Long): Int = when {
        ttftMs <= 0 -> clamp(currentS)
        ttftMs <= FAST_TTFT_MS -> clamp(currentS + STEP_S)
        ttftMs >= SLOW_TTFT_MS -> clamp(currentS - STEP_S)
        else -> clamp(currentS)
    }

    /** The human line for the Settings card. */
    fun describe(intervalS: Int, samples: Int, adapted: Boolean): String = when {
        samples <= 0 ->
            "no measured beats yet — using ${clamp(intervalS)} s"
        !adapted ->
            "measuring: $samples beat${if (samples == 1) "" else "s"}, still ${clamp(intervalS)} s"
        else ->
            "measured: ${clamp(intervalS)} s from $samples beat${if (samples == 1) "" else "s"}"
    }
}

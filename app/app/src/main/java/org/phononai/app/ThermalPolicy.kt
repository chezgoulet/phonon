package org.phononai.app

import android.os.PowerManager

/**
 * One reading of the device's thermal state, from every source that is
 * available. A source is null when the platform does not offer it.
 */
data class ThermalSample(
    /** Battery temperature in Celsius; always available. */
    val batteryTempC: Float?,
    /** PowerManager.getCurrentThermalStatus(); null below API 29. */
    val thermalStatus: Int?,
    /** PowerManager.getThermalHeadroom(seconds); null below API 30 or unsupported. */
    val headroom: Float?,
)

/**
 * How the pause/resume decision is made.
 *
 * The platform's own thermal signal is preferred over battery temperature: a
 * battery sensor is a proxy for SoC heat, while `getThermalHeadroom` is the
 * kernel's estimate of how much more the *SoC* can do before it throttles, and
 * the thermal status listener is the same signal Android uses for its own
 * throttling. Battery temperature remains the fallback on phones or calls where
 * neither exists, and it stays the operative signal whenever the operator has
 * set their own thresholds — those are the override.
 */
object ThermalPolicy {

    enum class Source { HEADROOM, STATUS, BATTERY }

    /** Headroom at or above this means the device is at its thermal limit. */
    const val HEADROOM_PAUSE = 1.0f

    /** Resume once headroom has fallen back well below the limit. */
    const val HEADROOM_RESUME = 0.6f

    /** Pause once the platform reports at least MODERATE throttling. */
    const val STATUS_PAUSE = PowerManager.THERMAL_STATUS_MODERATE

    /** Resume once it has settled back to LIGHT or better. */
    const val STATUS_RESUME = PowerManager.THERMAL_STATUS_LIGHT

    /** The source an un-overridden device will actually use for this sample. */
    fun source(sample: ThermalSample, override: Boolean): Source = when {
        override -> Source.BATTERY
        usableHeadroom(sample.headroom) -> Source.HEADROOM
        sample.thermalStatus != null && sample.thermalStatus >= 0 -> Source.STATUS
        else -> Source.BATTERY
    }

    fun usableHeadroom(headroom: Float?): Boolean =
        headroom != null && !headroom.isNaN() && headroom >= 0f

    /** What the sample says, in the terms shown in /health and the Settings card. */
    fun describe(sample: ThermalSample, source: Source): String = when (source) {
        Source.HEADROOM -> "headroom %.2f".format(sample.headroom ?: 0f)
        Source.STATUS -> "status ${statusName(sample.thermalStatus)}"
        Source.BATTERY -> sample.batteryTempC?.let { "battery %.1f C".format(it) } ?: "unknown"
    }

    fun statusName(status: Int?): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }

    /**
     * What the policy wants this tick. At most one of [pause] and [resume] is
     * true. [hot] is the raw "is it too warm" answer used for the notification
     * and the card's bar regardless of the current transition.
     */
    data class Decision(
        val pause: Boolean,
        val resume: Boolean,
        val hot: Boolean,
        val source: Source,
        val detail: String,
    )

    /**
     * Decide for the current [paused]/[pauseReason] state. [stopC]/[resumeC] are
     * the operator's battery-temperature thresholds, used only when [override]
     * is set or the platform offers no better signal.
     */
    fun evaluate(
        sample: ThermalSample,
        stopC: Float,
        resumeC: Float,
        override: Boolean,
        paused: Boolean,
        pauseReason: String?,
    ): Decision {
        val source = source(sample, override)
        val detail = describe(sample, source)
        val hot = when (source) {
            Source.HEADROOM -> tooHotHeadroom(sample.headroom!!, paused)
            Source.STATUS -> tooHotStatus(sample.thermalStatus!!, paused)
            Source.BATTERY -> tooHotBattery(sample.batteryTempC, stopC, resumeC, paused)
        }
        // Thermal pause/resume only ever acts on a thermal pause; a battery pause
        // is the service's own policy and is left untouched here.
        val thermalPause = paused && pauseReason == "thermal"
        return when {
            hot && !paused -> Decision(true, false, hot, source, detail)
            !hot && thermalPause -> Decision(false, true, hot, source, detail)
            else -> Decision(false, false, hot, source, detail)
        }
    }

    // Hysteresis: while running, any reading at the limit pauses; while paused,
    // the device must fall back below the resume mark before it restarts.
    private fun tooHotHeadroom(headroom: Float, paused: Boolean): Boolean =
        if (paused) headroom > HEADROOM_RESUME else headroom >= HEADROOM_PAUSE

    private fun tooHotStatus(status: Int, paused: Boolean): Boolean =
        if (paused) status > STATUS_RESUME else status >= STATUS_PAUSE

    private fun tooHotBattery(temp: Float?, stopC: Float, resumeC: Float, paused: Boolean): Boolean =
        if (temp == null) false else if (paused) temp > resumeC else temp >= stopC
}

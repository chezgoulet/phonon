package org.phononai.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The charging-limit logic, tested without a phone.
 *
 * The two rules that matter: a plateau is only a plateau after 20 minutes of
 * evidence, and a 100% charge on a phone whose limit was on is a recalibration,
 * not a disabled limit.
 */
class ChargeLimitTest {

    private val minute = 60_000L

    // --- mode mapping -------------------------------------------------------

    @Test
    fun `secure values map to the states the card reports`() {
        assertEquals(ChargeMode.LIMIT_80, ChargeMode.fromSecure(1, null))
        assertEquals(ChargeMode.OFF, ChargeMode.fromSecure(0, null))
        assertEquals(ChargeMode.ADAPTIVE, ChargeMode.fromSecure(0, true))
        // A device without the key states nothing; that is not "off".
        assertEquals(ChargeMode.UNKNOWN, ChargeMode.fromSecure(null, null))
        assertEquals(ChargeMode.LIMIT_80, ChargeMode.fromSecure(1, true))
    }

    @Test
    fun `wire names round-trip and junk is unknown`() {
        for (mode in ChargeMode.values()) {
            assertEquals(mode, ChargeMode.fromWire(mode.wire))
        }
        assertEquals(ChargeMode.UNKNOWN, ChargeMode.fromWire("bogus"))
        assertEquals(ChargeMode.UNKNOWN, ChargeMode.fromWire(null))
    }

    // --- plateau detector ---------------------------------------------------

    @Test
    fun `flat at 80 on a charger for twenty minutes is a plateau`() {
        val detector = PlateauDetector()
        var verdict = detector.observe(0L, 80, true)
        var t = 0L
        while (t < 25 * minute) {
            t += 30_000L
            verdict = detector.observe(t, 80, true)
        }
        assertTrue(verdict.plateau)
        assertTrue(verdict.spanMs >= 19 * minute)
    }

    @Test
    fun `a short sit at 80 is not a plateau`() {
        val detector = PlateauDetector()
        var verdict = detector.observe(0L, 80, true)
        for (i in 1..10) {
            verdict = detector.observe(i * 30_000L, 80, true)
        }
        assertFalse(verdict.plateau)
    }

    @Test
    fun `one reading at 79 proves nothing`() {
        val detector = PlateauDetector()
        val verdict = detector.observe(0L, 79, true)
        assertFalse(verdict.plateau)
        assertEquals(1, verdict.samples)
    }

    @Test
    fun `levelling off at 79 on a charger is inside the tolerance`() {
        // The issue defines the band as 80 ± 1; "sitting at 79%" on its own is
        // not evidence, but twenty minutes pinned there is.
        val detector = PlateauDetector()
        var t = 0L
        var verdict = detector.observe(t, 79, true)
        while (t < 25 * minute) {
            t += 30_000L
            verdict = detector.observe(t, 79, true)
        }
        assertTrue(verdict.plateau)
    }

    @Test
    fun `rising through the band is not a plateau`() {
        val detector = PlateauDetector()
        var t = 0L
        var verdict = detector.observe(t, 70, true)
        var level = 70
        while (t < 25 * minute) {
            t += 30_000L
            if (level < 80) level++
            verdict = detector.observe(t, level, true)
        }
        assertFalse(verdict.plateau)
    }

    @Test
    fun `unplugging clears the history`() {
        val detector = PlateauDetector()
        var t = 0L
        repeat(50) {
            t += 30_000L
            detector.observe(t, 80, true)
        }
        detector.observe(t + 30_000L, 80, false)
        val after = detector.observe(t + 60_000L, 80, true)
        assertFalse(after.plateau)
        assertEquals(1, after.samples)
    }

    // --- tracker ------------------------------------------------------------

    private fun plateauedTracker(): ChargeLimitTracker {
        val tracker = ChargeLimitTracker()
        var t = 0L
        repeat(50) {
            t += 30_000L
            tracker.observe(t, 80, true, null)
        }
        return tracker
    }

    @Test
    fun `a readable setting outranks inference`() {
        val tracker = ChargeLimitTracker()
        val reading = tracker.observe(0L, 55, false, ChargeMode.OFF)
        assertEquals(ChargeMode.OFF, reading.mode)
        assertEquals("secure", reading.source)
        assertTrue(reading.confirmed)
    }

    @Test
    fun `a plateau infers limit-80 as confirmed`() {
        val tracker = ChargeLimitTracker()
        var t = 0L
        var reading = tracker.observe(t, 80, true, null)
        repeat(50) {
            t += 30_000L
            reading = tracker.observe(t, 80, true, null)
        }
        assertEquals(ChargeMode.LIMIT_80, reading.mode)
        assertEquals("plateau", reading.source)
        assertTrue(reading.confirmed)
    }

    @Test
    fun `a 100 percent charge after a plateau is a recalibration, not a disabled limit`() {
        val tracker = plateauedTracker()
        val now = 50 * 30_000L
        val reading = tracker.observe(now + 5 * minute, 100, true, null)
        assertEquals(ChargeMode.LIMIT_80, reading.mode)
        assertEquals("recalibration", reading.source)
        assertFalse(reading.confirmed)
    }

    @Test
    fun `a 100 percent charge with no history does not guess`() {
        val tracker = ChargeLimitTracker()
        val reading = tracker.observe(0L, 100, true, null)
        assertEquals(ChargeMode.UNKNOWN, reading.mode)
        assertEquals("none", reading.source)
        assertFalse(reading.confirmed)
    }

    @Test
    fun `the recalibration hold expires`() {
        val tracker = plateauedTracker()
        val lastPlateau = 50 * 30_000L
        // Just past the 12 h hold.
        val reading = tracker.observe(lastPlateau + 13 * 60 * minute, 100, true, null)
        assertEquals(ChargeMode.UNKNOWN, reading.mode)
    }

    @Test
    fun `an unplugged phone cannot be inferred`() {
        val tracker = ChargeLimitTracker()
        val reading = tracker.observe(0L, 62, false, null)
        assertEquals(ChargeMode.UNKNOWN, reading.mode)
        assertTrue(reading.detail.contains("not charging"))
    }

    // --- weekly exposure ----------------------------------------------------

    @Test
    fun `hours above eighty sums hourly intervals`() {
        val history = SocHistory()
        val hour = 3_600_000L
        for (i in 0..8) history.record(i * hour, if (i < 6) 85 else 70)
        // Samples 0..5 are at 85: six one-hour intervals above the threshold.
        assertEquals(6, history.hoursAbove(80, 8 * hour))
    }

    @Test
    fun `samples inside the same hour are not double counted`() {
        val history = SocHistory()
        val hour = 3_600_000L
        assertTrue(history.record(0L, 85))
        assertFalse(history.record(5 * minute, 85))
        assertTrue(history.record(hour, 85))
        assertEquals(1, history.hoursAbove(80, hour))
    }

    @Test
    fun `the window drops last week's exposure`() {
        val history = SocHistory()
        val hour = 3_600_000L
        val day = 24 * hour
        for (i in 0..9) history.record(i * day, 90)
        // Only the last 7 days count.
        assertEquals(7, history.hoursAbove(80, 9 * day))
    }

    @Test
    fun `the ring survives a round trip and a corrupt ring is empty`() {
        val history = SocHistory()
        val hour = 3_600_000L
        for (i in 0..3) history.record(i * hour, 85)
        val restored = SocHistory.fromJson(history.toJson())
        assertEquals(4, restored.size())
        assertEquals(3, restored.hoursAbove(80, 3 * hour))

        assertEquals(0, SocHistory.fromJson("{not json").size())
        assertEquals(0, SocHistory.fromJson(null).size())
    }

    @Test
    fun `the ring is bounded`() {
        val history = SocHistory(capacity = 5)
        val hour = 3_600_000L
        for (i in 0..19) history.record(i * hour, 90)
        assertEquals(5, history.size())
    }

    // --- reporting ----------------------------------------------------------

    @Test
    fun `health carries mode source and hours and nothing secret`() {
        val reading = ChargeReading(ChargeMode.LIMIT_80, "secure", true, "read from Settings.Secure")
        val json = ChargeLimit.healthJson(reading, 12, canToggle = true)
        assertEquals("limit-80", json.getString("mode"))
        assertEquals("secure", json.getString("source"))
        assertEquals(12, json.getInt("hours_above_80"))
        assertTrue(json.getBoolean("can_toggle"))
        assertTrue(json.getBoolean("confirmed"))
        // No key material, no serial: the block is three facts and a detail string.
        val text = json.toString()
        assertFalse(text.contains("serial", ignoreCase = true))
        assertFalse(text.contains("key", ignoreCase = true))
    }

    @Test
    fun `the card line says what was established`() {
        val line = ChargeLimit.cardLine(
            ChargeReading(ChargeMode.LIMIT_80, "plateau", true, "plugged and flat at 80% for 21 min"),
            9,
        )
        assertTrue(line.contains("Limit to 80% is on"))
        assertTrue(line.contains("21 min"))
        assertTrue(line.contains("9 h this week"))

        val unconfirmed = ChargeLimit.cardLine(
            ChargeReading(ChargeMode.UNKNOWN, "none", false, "not charging; the limit cannot be inferred"),
            0,
        )
        assertTrue(unconfirmed.contains("Couldn't confirm"))
        assertFalse(unconfirmed.contains("is on"))
    }

    @Test
    fun `health json parses back into the UI model`() {
        // The card reads /health through parseHealth; the block must survive it.
        val reading = ChargeReading(ChargeMode.LIMIT_80, "secure", true, "read from Settings.Secure")
        val health = JSONObject()
            .put("state", "ready")
            .put("model", "qwen3.5-2b")
            .put("charge", ChargeLimit.healthJson(reading, 4, false))
        val parsed = org.phononai.app.ui.parseHealth(health.toString())
        assertEquals("limit-80", parsed.chargeMode)
        assertEquals("secure", parsed.chargeSource)
        assertEquals(4, parsed.chargeHoursAbove80)
        assertFalse(parsed.chargeCanToggle)
        assertTrue(parsed.chargeConfirmed)
    }
}

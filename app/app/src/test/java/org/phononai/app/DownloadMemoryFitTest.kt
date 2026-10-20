package org.phononai.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download route's memory rule, checked on the JVM.
 *
 * Defect it pins down: a phone under transient pressure used to refuse the
 * download outright, with `fit.message` as an `error`, because the check
 * compared against `availMem` at that instant. Now only a model that cannot fit
 * even on an idle phone is refused; a transient shortfall is a warning the
 * client must accept with `force`.
 *
 * [MemoryFit.assessDownload] and [MemoryFit.outcome] are pure by construction,
 * so nothing here needs an Android runtime.
 */
class DownloadMemoryFitTest {

    private val gb = 1_000_000_000L

    /** The reference phone: 8 GB nominal. */
    private val total = 8 * gb

    private fun warning(required: Long = 3_400_000_000L, available: Long = 2_100_000_000L) =
        MemoryFit.assessDownload(required, available, total)

    @Test
    fun transientPressureWarnsInsteadOfRefusing() {
        val assessment = warning()
        assertEquals(MemoryFit.DownloadDecision.WARN, assessment.decision)
        // Nothing was accepted yet, but nothing failed either.
        assertEquals(MemoryFit.DownloadOutcome.NEEDS_CONFIRMATION, MemoryFit.outcome(assessment, false))
        val body = JSONObject(MemoryFit.warningJson(assessment))
        assertFalse("a warning must not carry an error", body.has("error"))
        assertTrue(body.has("warning"))
        assertEquals("memory_fit", body.getString("code"))
    }

    @Test
    fun hardRefusalWhenRequiredExceedsTheTotalMemoryBudget() {
        // 6 GB is free right now, but the phone could never give 5.4 GB to one
        // process: 60% of 8 GB is the ceiling. Availability must not mask it.
        val assessment = MemoryFit.assessDownload(5_400_000_000L, 6 * gb, total)
        assertEquals(MemoryFit.DownloadDecision.REFUSE, assessment.decision)
        assertEquals(MemoryFit.DownloadOutcome.REFUSED, MemoryFit.outcome(assessment, true))
        val body = JSONObject(MemoryFit.refusalJson(assessment))
        assertTrue(body.has("error"))
        assertEquals("memory_fit_hard", body.getString("code"))
        assertFalse("a refusal is not a warning", body.has("warning"))
    }

    @Test
    fun aModelWithinTheBudgetAndAvailableNowProceeds() {
        val assessment = MemoryFit.assessDownload(2 * gb, 2_500_000_000L, total)
        assertEquals(MemoryFit.DownloadDecision.PROCEED, assessment.decision)
        assertEquals(MemoryFit.DownloadOutcome.START, MemoryFit.outcome(assessment, false))
    }

    @Test
    fun unknownAvailabilityNeverBlocksADownload() {
        // availMem can read as 0 when the platform cannot answer; that is not
        // evidence of pressure, so it must not warn.
        val assessment = MemoryFit.assessDownload(2 * gb, 0L, total)
        assertEquals(MemoryFit.DownloadDecision.PROCEED, assessment.decision)
    }

    @Test
    fun forceStartsAPreviouslyWarnedDownload() {
        val assessment = warning()
        assertEquals(MemoryFit.DownloadOutcome.NEEDS_CONFIRMATION, MemoryFit.outcome(assessment, false))
        assertEquals(MemoryFit.DownloadOutcome.START, MemoryFit.outcome(assessment, true))
    }

    @Test
    fun forceDoesNotOverrideTheHardRefusal() {
        val assessment = MemoryFit.assessDownload(5_400_000_000L, 6 * gb, total)
        assertEquals(MemoryFit.DownloadOutcome.REFUSED, MemoryFit.outcome(assessment, true))
    }

    @Test
    fun totalBudgetIsSixtyPercentOfRam() {
        assertEquals(4_800_000_000L, MemoryFit.budgetBytes(total))
        assertEquals(0L, MemoryFit.budgetBytes(0L))
    }

    @Test
    fun warningJsonCarriesTheNumbers() {
        val body = JSONObject(MemoryFit.warningJson(warning()))
        assertEquals(3.40, body.getDouble("required_gb"), 0.001)
        assertEquals(2.10, body.getDouble("available_gb"), 0.001)
        assertEquals(4.80, body.getDouble("budget_gb"), 0.001)
        assertEquals(8.00, body.getDouble("total_gb"), 0.001)
        assertTrue(body.getBoolean("requires_confirmation"))
    }

    @Test
    fun refusalJsonCarriesTheNumbers() {
        val body = JSONObject(MemoryFit.refusalJson(MemoryFit.assessDownload(5_400_000_000L, 6 * gb, total)))
        assertEquals(5.40, body.getDouble("required_gb"), 0.001)
        assertEquals(6.00, body.getDouble("available_gb"), 0.001)
        assertEquals(4.80, body.getDouble("budget_gb"), 0.001)
    }

    @Test
    fun warningMessageIsCompleteAndNamesBothFigures() {
        val assessment = warning()
        val message = assessment.warningMessage
        // The whole text, not a clipped prefix: the defect was an unreadable
        // message, so completeness is asserted rather than assumed.
        assertEquals(message, JSONObject(MemoryFit.warningJson(assessment)).getString("warning"))
        assertTrue("message is too short to be the full text: $message", message.length > 180)
        assertFalse("message looks truncated: $message", message.contains("…") || message.contains("..."))
        assertTrue(message.contains(MemoryFit.gb(3_400_000_000L)))
        assertTrue(message.contains(MemoryFit.gb(2_100_000_000L)))
        assertTrue("message must say serving may fail later: $message", message.contains("may fail"))
        assertTrue(message.endsWith("."))
    }

    @Test
    fun refusalMessageIsCompleteAndNamesTheBudget() {
        val assessment = MemoryFit.assessDownload(5_400_000_000L, 6 * gb, total)
        val message = assessment.refusalMessage
        assertEquals(message, JSONObject(MemoryFit.refusalJson(assessment)).getString("error"))
        assertTrue("message is too short to be the full text: $message", message.length > 180)
        assertFalse("message looks truncated: $message", message.contains("…") || message.contains("..."))
        assertTrue(message.contains(MemoryFit.gb(5_400_000_000L)))
        assertTrue(message.contains(MemoryFit.gb(4_800_000_000L)))
        assertTrue(message.endsWith("."))
    }

    /**
     * The old service path returned `{"error": fit.message}` for any failed
     * fit. A verdict that fails on current availability must not serialize as
     * an error at download time, whatever the numbers are.
     */
    @Test
    fun aFailedLoadVerdictIsOnlyAWarningAtDownloadTime() {
        val verdict = MemoryFit.Verdict(
            fits = false,
            requiredBytes = 3_400_000_000L,
            availBytes = 2_100_000_000L,
            kvBytes = 900_000_000L,
            modelBytes = 1_280_000_000L,
            totalBytes = 4_500_000_000L,
        )
        val assessment = MemoryFit.assessDownload(verdict.requiredBytes, verdict.availBytes, total)
        assertEquals(MemoryFit.DownloadDecision.WARN, assessment.decision)
        assertFalse(JSONObject(MemoryFit.warningJson(assessment)).has("error"))
    }
}

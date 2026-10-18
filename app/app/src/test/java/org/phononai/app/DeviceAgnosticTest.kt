package org.phononai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-agnostic logic that does not touch the Android framework, checked on
 * the JVM. The parts that read /proc, MemoryInfo, or PowerManager need a real
 * device and are exercised by the on-device steps in the branch report.
 */
class DeviceAgnosticTest {

    // --- CPU feature detection (item 2) -----------------------------------

    private val pixel7Cpuinfo = """
        processor	: 0
        BogoMIPS	: 38.40
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp ssbs
        CPU implementer	: 0x41
        processor	: 1
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp ssbs
    """.trimIndent()

    private val oldCpuinfo = """
        processor	: 0
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32
    """.trimIndent()

    @Test
    fun parsesDotprodAndFp16FromArm64Features() {
        val f = CpuFeatures.parse(pixel7Cpuinfo)
        assertTrue(f.known)
        assertTrue(f.dotprod)
        assertTrue(f.fp16)
        assertFalse(f.i8mm)
    }

    @Test
    fun oldCoreHasNoDotprod() {
        val f = CpuFeatures.parse(oldCpuinfo)
        assertTrue(f.known)
        assertFalse(f.dotprod)
        assertEquals("android_armv8.0_1", f.expectedBackend)
    }

    @Test
    fun unreadableCpuinfoIsUnknownNotAbsent() {
        val f = CpuFeatures.parse(null)
        assertFalse(f.known)
        assertFalse(f.dotprod)
        assertEquals("unknown", f.expectedBackend)
    }

    @Test
    fun i8mmSelectsTheHigherBackend() {
        val f = CpuFeatures.parse("Features	: fp asimd asimddp asimdhp i8mm")
        assertTrue(f.i8mm)
        assertEquals("android_armv8.6_1", f.expectedBackend)
    }

    // --- native requirement guard (item 2) --------------------------------

    @Test
    fun allVariantsBuildRequiresNothing() {
        // Default BuildConfig carries no required features: the all-variants
        // build ships a backend for every ARMv8 level.
        assertTrue(NativeRequirements.required.isEmpty())
        assertNull(NativeRequirements.refusal(CpuFeatures.parse(oldCpuinfo)))
    }

    @Test
    fun missingDotprodProducesTheExactMessage() {
        // The mechanism a single-ISA build would use: an operator-facing refusal
        // instead of a SIGILL in the child.
        assertEquals(
            "this phone is missing the dot-product instructions this build needs",
            NativeRequirements.refusalFor(setOf("dotprod"), CpuFeatures.parse(oldCpuinfo)),
        )
        // A phone that has the feature is not refused.
        assertNull(
            NativeRequirements.refusalFor(setOf("dotprod"), CpuFeatures.parse(pixel7Cpuinfo)),
        )
        // A CPU we could not read is never refused.
        assertNull(
            NativeRequirements.refusalFor(setOf("dotprod"), CpuFeatures.parse(null)),
        )
    }

    // --- memory fit (item 5) ----------------------------------------------

    @Test
    fun kvEstimateScalesWithContextAndFileSize() {
        val model = 1_280_835_840L
        val at32k = MemoryFit.estimateKvCacheBytes(model, 32_768)
        val at16k = MemoryFit.estimateKvCacheBytes(model, 16_384)
        assertEquals(at32k / 2, at16k)
        // ~28 KB/token for a 1.28 GB Q4 2B model, so ~0.9 GB at 32K.
        assertTrue(at32k in 800_000_000L..1_000_000_000L)
    }

    @Test
    fun kvEstimateIsClampedForTinyAndHugeModels() {
        assertEquals(12_288L, MemoryFit.kvBytesPerToken(1L))
        assertEquals(131_072L, MemoryFit.kvBytesPerToken(Long.MAX_VALUE / 4))
    }

    @Test
    fun sigkillLooksLikeAMemoryKill() {
        assertTrue(MemoryFit.isKilled(137))
        assertFalse(MemoryFit.isKilled(1))
        assertFalse(MemoryFit.isKilled(0))
    }

    // --- thermal policy (item 6) ------------------------------------------

    @Test
    fun headroomAtTheLimitPauses() {
        val sample = ThermalSample(batteryTempC = 30f, thermalStatus = 0, headroom = 1.1f)
        val d = ThermalPolicy.evaluate(sample, 45f, 40f, override = false, paused = false, pauseReason = null)
        assertTrue(d.pause)
        assertEquals(ThermalPolicy.Source.HEADROOM, d.source)
    }

    @Test
    fun headroomBelowTheLimitDoesNotPause() {
        val sample = ThermalSample(batteryTempC = 30f, thermalStatus = 0, headroom = 0.7f)
        val d = ThermalPolicy.evaluate(sample, 45f, 40f, override = false, paused = false, pauseReason = null)
        assertFalse(d.pause)
    }

    @Test
    fun resumeOnlyWhenHeadroomHasFallenBack() {
        val hotPaused = ThermalSample(30f, 0, 0.8f)
        assertTrue(
            ThermalPolicy.evaluate(hotPaused, 45f, 40f, false, true, "thermal").resume.not(),
        )
        val cool = ThermalSample(30f, 0, 0.3f)
        assertTrue(ThermalPolicy.evaluate(cool, 45f, 40f, false, true, "thermal").resume)
    }

    @Test
    fun fallsBackToBatteryTemperatureWhenNoPlatformSignal() {
        val sample = ThermalSample(batteryTempC = 46f, thermalStatus = null, headroom = null)
        val d = ThermalPolicy.evaluate(sample, 45f, 40f, override = false, paused = false, pauseReason = null)
        assertEquals(ThermalPolicy.Source.BATTERY, d.source)
        assertTrue(d.pause)
    }

    @Test
    fun operatorOverrideForcesBatteryThresholds() {
        // Headroom says fine, but the operator set their own thresholds.
        val sample = ThermalSample(batteryTempC = 46f, thermalStatus = 0, headroom = 0.2f)
        val d = ThermalPolicy.evaluate(sample, 45f, 40f, override = true, paused = false, pauseReason = null)
        assertEquals(ThermalPolicy.Source.BATTERY, d.source)
        assertTrue(d.pause)
    }

    @Test
    fun statusListenerSignalIsUsedWhenHeadroomIsAbsent() {
        val sample = ThermalSample(30f, thermalStatus = 3, headroom = null)
        val d = ThermalPolicy.evaluate(sample, 45f, 40f, false, false, null)
        assertEquals(ThermalPolicy.Source.STATUS, d.source)
        assertTrue(d.pause)
    }

    // --- per-SoC tuning (item 7) ------------------------------------------

    @Test
    fun bestProfilePicksTheFastestConfigOfEach() {
        val p = SocTuning.best(
            soc = "tensor",
            cpuRange = "6-7",
            context = 32768,
            keepWarmIntervalS = 45,
            decode = listOf(2 to 6.1, 4 to 7.4, 6 to 5.9),
            prefill = listOf(2 to 40.0, 4 to 61.0),
        )
        assertNotNull(p)
        assertEquals(4, p!!.decodeThreads)
        assertEquals(4, p.prefillThreads)
        assertEquals(7.4, p.decodeTps, 0.001)
        assertEquals(61.0, p.prefillTps, 0.001)
    }

    @Test
    fun bestProfileIsNullWithoutDecodeMeasurements() {
        assertNull(SocTuning.best("tensor", "6-7", 8192, 45, emptyList(), listOf(2 to 40.0)))
    }

    // --- generic perf text (item 3) ---------------------------------------

    @Test
    fun cpuListParserHandlesRangesAndSparseSets() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5), PerfProfile.parseCpuList("0-5"))
        assertEquals(listOf(0, 2, 3, 7), PerfProfile.parseCpuList("0,2-3,7"))
    }
}

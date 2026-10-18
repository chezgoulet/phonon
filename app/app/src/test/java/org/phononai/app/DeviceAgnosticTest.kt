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
            decodeCpuRange = "6-7",
            contextSize = 32768,
            keepWarmIntervalS = 45,
            decode = listOf(2 to 6.1, 4 to 7.4, 6 to 5.9),
            prefill = listOf(2 to 40.0, 4 to 61.0),
        )
        assertNotNull(p)
        assertEquals(4, p!!.decodeThreads)
        assertEquals(4, p.prefillThreads)
        assertEquals(7.4, p.decodeTps, 0.001)
        assertEquals(61.0, p.prefillTps, 0.001)
        // The profile carries the engine it was measured against.
        assertEquals(SocTuning.ENGINE_REV, p.engineRev)
        assertEquals("6-7", p.decodeCpuRange)
        assertEquals(32768, p.contextSize)
    }

    @Test
    fun bestProfileIsNullWithoutDecodeMeasurements() {
        assertNull(
            SocTuning.best(
                "tensor", "6-7", 8192, 45, emptyList(), listOf(2 to 40.0),
            ),
        )
    }

    // --- generic perf text (item 3) ---------------------------------------

    @Test
    fun cpuListParserHandlesRangesAndSparseSets() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5), PerfProfile.parseCpuList("0-5"))
        assertEquals(listOf(0, 2, 3, 7), PerfProfile.parseCpuList("0,2-3,7"))
    }

    // --- SoC key resolution (item 1) --------------------------------------

    @Test
    fun socKeyUsesSocModelOnApi31AndHardwareBelow() {
        assertEquals("Tensor G2", SocTuning.resolveSoc(34, "Tensor G2", "gs201"))
        // Below API 31 SOC_MODEL does not exist, so HARDWARE is the key.
        assertEquals("gs201", SocTuning.resolveSoc(30, "Tensor G2", "gs201"))
        assertEquals("", SocTuning.resolveSoc(34, null, null))
    }

    // --- profile lookup and engine-revision invalidation (item 1) ---------

    private fun profileJson(engineRev: String): String {
        val p = TuningProfile(
            soc = "Tensor G2",
            decodeThreads = 4,
            decodeCpuRange = "6-7",
            prefillThreads = 4,
            contextSize = 32768,
            keepWarmIntervalS = 45,
            engineRev = engineRev,
            measuredAt = "2026-10-18T00:00:00Z",
            decodeTps = 14.4,
            prefillTps = 79.0,
        )
        return org.json.JSONObject().put("Tensor G2", p.toJson()).toString()
    }

    @Test
    fun profileRoundTripsThroughJson() {
        val json = profileJson(SocTuning.ENGINE_REV)
        val p = SocTuning.lookup(json, "Tensor G2", SocTuning.ENGINE_REV)
        assertNotNull(p)
        assertEquals(4, p!!.decodeThreads)
        assertEquals("6-7", p.decodeCpuRange)
        assertEquals(4, p.prefillThreads)
        assertEquals(32768, p.contextSize)
        assertEquals(SocTuning.ENGINE_REV, p.engineRev)
        assertEquals(14.4, p.decodeTps, 0.001)
        assertEquals(79.0, p.prefillTps, 0.001)
    }

    @Test
    fun aNewEngineRevisionInvalidatesTheStoredProfile() {
        val json = profileJson("797de63")
        // Same revision: applied. A rebuild: not applied, and the stale revision
        // is reported so the UI can ask for a re-run.
        assertNotNull(SocTuning.lookup(json, "Tensor G2", "797de63"))
        assertNull(SocTuning.lookup(json, "Tensor G2", "abcdef0"))
        assertEquals("797de63", SocTuning.staleEngineRev(json, "Tensor G2", "abcdef0"))
        assertNull(SocTuning.staleEngineRev(json, "Tensor G2", "797de63"))
    }

    @Test
    fun unknownSocOrBlankJsonYieldsNoProfile() {
        val json = profileJson(SocTuning.ENGINE_REV)
        assertNull(SocTuning.lookup(json, "Other SoC", SocTuning.ENGINE_REV))
        assertNull(SocTuning.lookup("", "Tensor G2", SocTuning.ENGINE_REV))
        assertNull(SocTuning.lookup("not json", "Tensor G2", SocTuning.ENGINE_REV))
    }

    // --- effective perf precedence (item 1) -------------------------------

    private val measured = TuningProfile(
        soc = "Tensor G2",
        decodeThreads = 4,
        decodeCpuRange = "6-7",
        prefillThreads = 4,
        contextSize = 32768,
        keepWarmIntervalS = 45,
        engineRev = SocTuning.ENGINE_REV,
        measuredAt = "2026-10-18T00:00:00Z",
        decodeTps = 14.4,
        prefillTps = 79.0,
    )

    @Test
    fun heuristicIsUsedWhenThereIsNoProfileOrOverride() {
        val e = SocTuning.decideEffective(
            userThreads = null,
            userCpuRange = null,
            userPrefillThreads = null,
            profile = null,
            autoThreads = 2,
            autoCpuRange = "6-7",
        )
        assertEquals(2, e.threads)
        assertEquals("6-7", e.cpuRange)
        assertEquals(0, e.prefillThreads)
    }

    @Test
    fun storedProfileBeatsTheHeuristicButNotAnOverride() {
        val adopted = SocTuning.decideEffective(
            userThreads = null,
            userCpuRange = null,
            userPrefillThreads = null,
            profile = measured,
            autoThreads = 2,
            autoCpuRange = "0-3",
        )
        assertEquals(4, adopted.threads)
        assertEquals("6-7", adopted.cpuRange)
        assertEquals(4, adopted.prefillThreads)

        val overridden = SocTuning.decideEffective(
            userThreads = 8,
            userCpuRange = "4-5",
            userPrefillThreads = 6,
            profile = measured,
            autoThreads = 2,
            autoCpuRange = "0-3",
        )
        assertEquals(8, overridden.threads)
        assertEquals("4-5", overridden.cpuRange)
        assertEquals(6, overridden.prefillThreads)
    }

    @Test
    fun anInvalidStoredRangeFallsBackToTheHeuristic() {
        val odd = measured.copy(decodeCpuRange = "not-a-range")
        val e = SocTuning.decideEffective(
            userThreads = null,
            userCpuRange = null,
            userPrefillThreads = null,
            profile = odd,
            autoThreads = 2,
            autoCpuRange = "0-5",
        )
        assertEquals("0-5", e.cpuRange)
        // The thread count is still the measured one.
        assertEquals(4, e.threads)
    }

    // --- sweep configuration (item 1) -------------------------------------

    @Test
    fun decodeSweepTriesTwoPinnedAndOneUnpinned() {
        val configs = SocTuning.decodeConfigs("6-7")
        assertEquals(3, configs.size)
        assertEquals(2, configs[0].threads)
        assertEquals("6-7", configs[0].cpuRange)
        assertEquals(4, configs[1].threads)
        assertEquals("6-7", configs[1].cpuRange)
        assertEquals(2, configs[2].threads)
        assertEquals("", configs[2].cpuRange)
    }

    @Test
    fun decodeSweepDegradesWhenThereIsNoClusterToPin() {
        val configs = SocTuning.decodeConfigs("")
        assertEquals(3, configs.size)
        assertTrue(configs.all { it.cpuRange.isEmpty() })
    }

    @Test
    fun prefillSweepUsesTwoAndTheFastCoreCount() {
        assertEquals(listOf(2, 5), SocTuning.prefillConfigs(5))
        // A two-core fast cluster would give two identical values, so use four.
        assertEquals(listOf(2, 4), SocTuning.prefillConfigs(2))
        assertEquals(listOf(2, 4), SocTuning.prefillConfigs(0))
    }

    // --- OEM guidance mapping (item 2) ------------------------------------

    @Test
    fun knownVendorsGetAnIntentAndAnUnknownVendorDoesNot() {
        for (m in listOf("samsung", "Xiaomi", "OnePlus", "HUAWEI", "OPPO", "vivo")) {
            val g = OemGuidance.forManufacturer(m)
            assertNotNull("$m should deep-link", OemGuidance.componentOf(g.settingsComponent))
            assertTrue(g.dontKillUrl.startsWith("https://dontkillmyapp.com/"))
        }
        // An unknown vendor must not be handed a wrong intent.
        val unknown = OemGuidance.forManufacturer("SomePhone Co")
        assertNull(OemGuidance.componentOf(unknown.settingsComponent))
        assertEquals("https://dontkillmyapp.com/", unknown.dontKillUrl)
    }

    @Test
    fun malformedComponentsAreRejected() {
        assertNull(OemGuidance.componentOf(null))
        assertNull(OemGuidance.componentOf(""))
        assertNull(OemGuidance.componentOf("com.vendor.app"))
        assertNull(OemGuidance.componentOf("/Activity"))
        assertNull(OemGuidance.componentOf("com.vendor.app/"))
        assertEquals("com.a" to "com.a.B", OemGuidance.componentOf("com.a/com.a.B"))
    }

    // --- charge-held-at-100% duration maths (item 2) ----------------------

    @Test
    fun fullHoursIsNullUntilTheThresholdIsCrossed() {
        val now = 10_000_000_000L
        assertNull(OemGuidance.fullHours(0L, now))
        assertNull(OemGuidance.fullHours(now + 1, now))
        assertNull(OemGuidance.fullHours(now - OemGuidance.FULL_CHARGE_WARN_MS + 1, now))
        assertEquals(4, OemGuidance.fullHours(now - OemGuidance.FULL_CHARGE_WARN_MS, now))
        assertEquals(5, OemGuidance.fullHours(now - 5 * 3_600_000L, now))
    }

    @Test
    fun sittingFullNeedsChargingAtOneHundredForFourHours() {
        val full = BatterySnapshot(levelPct = 100, charging = true, tempC = 30f, chargeCounterUah = null)
        val now = 20_000_000_000L
        assertTrue(OemGuidance.sittingFull(full, now - OemGuidance.FULL_CHARGE_WARN_MS, now))
        assertFalse(OemGuidance.sittingFull(full, now - 60_000L, now))
        // Unplugged, or not full: never a warning.
        assertFalse(
            OemGuidance.sittingFull(full.copy(charging = false), now - OemGuidance.FULL_CHARGE_WARN_MS, now),
        )
        assertFalse(
            OemGuidance.sittingFull(full.copy(levelPct = 99), now - OemGuidance.FULL_CHARGE_WARN_MS, now),
        )
    }

    // --- devices/<soc>.yaml rendering (field reporting) -------------------

    @Test
    fun yamlMatchesThePantherShapeExactly() {
        val report = DeviceYaml.Report(
            device = "Google Pixel 7",
            codename = "panther",
            soc = "Google Tensor G2",
            ramGb = 8,
            android = 17,
            rooted = false,
            runtimeBuild = null,
            model = "gemma-4-E2B-it-Q4_K_M",
            context = 8192,
            threads = 4,
            mtp = true,
            status = DeviceYaml.STATUS_CANDIDATE,
            units = 2,
            measured = DeviceYaml.Measured(
                prefillTps = 17.86,
                decodeTps = 10.04,
                batteryTempC = 26.2,
            ),
            notes = "hello",
        )
        val expected = """
            device: Google Pixel 7
            codename: panther
            soc: Google Tensor G2
            ram_gb: 8
            android: 17
            rooted: false
            runtime: llama.cpp
            runtime_build: null
            model: gemma-4-E2B-it-Q4_K_M
            context: 8192
            threads: 4
            mtp: true
            status: candidate
            units: 2
            measured:
              prefill_tps: 17.86
              decode_tps: 10.04
              ttft_s: null
              after_5min_tps: null
              after_15min_tps: null
              peak_mem_mb: null
              battery_temp_c: 26.2
            notes: >-
              hello
        """.trimIndent() + "\n"
        assertEquals(expected, DeviceYaml.render(report))
    }

    @Test
    fun yamlLeavesUnmeasuredFiguresNull() {
        val yaml = DeviceYaml.render(
            DeviceYaml.Report(
                device = "d", codename = "c", soc = "Some SoC",
                ramGb = 8, android = 15, rooted = false,
                model = "m", context = 4096, threads = 4,
            ),
        )
        // No estimates: every measured field is null until measured.
        assertTrue(yaml.contains("  prefill_tps: null\n"))
        assertTrue(yaml.contains("  decode_tps: null\n"))
        assertTrue(yaml.contains("  battery_temp_c: null\n"))
        assertTrue(yaml.contains("runtime_build: null\n"))
        assertTrue(yaml.endsWith("notes: ''\n"))
    }

    @Test
    fun yamlFoldsAMultiLineNoteWithIndentation() {
        val yaml = DeviceYaml.render(
            DeviceYaml.Report(
                device = "d", codename = "c", soc = "SoC",
                ramGb = 8, android = 15, rooted = false,
                model = "m", context = 4096, threads = 4,
                notes = "first line\n\nsecond line",
            ),
        )
        assertTrue(yaml.endsWith("notes: >-\n  first line\n\n  second line\n"))
    }

    @Test
    fun yamlScalarQuotesOnlyWhenItMust() {
        assertEquals("Google Pixel 7", DeviceYaml.scalar("Google Pixel 7"))
        assertEquals("''", DeviceYaml.scalar(""))
        assertEquals("'a: b'", DeviceYaml.scalar("a: b"))
        assertEquals("'#lead'", DeviceYaml.scalar("#lead"))
        assertEquals("'  true story'", DeviceYaml.scalar("  true story"))
        assertEquals("'it''s'", DeviceYaml.scalar("it's"))
    }

    @Test
    fun yamlSlugIsFilesystemSafe() {
        assertEquals("google-tensor-g2", DeviceYaml.slug("Google Tensor G2"))
        assertEquals("gs201", DeviceYaml.slug("gs201"))
        assertEquals("unknown", DeviceYaml.slug(""))
    }

    // --- measured keep-warm adaptation ------------------------------------

    @Test
    fun keepWarmStartPrefersProfileThenAdaptedThenDefault() {
        assertEquals(45, KeepWarmAdaptation.start(null, null))
        assertEquals(120, KeepWarmAdaptation.start(120, null))
        assertEquals(30, KeepWarmAdaptation.start(null, 30))
        assertEquals(300, KeepWarmAdaptation.start(400, null))
        assertEquals(15, KeepWarmAdaptation.start(5, null))
        // A zero means "unset", never a real value.
        assertEquals(45, KeepWarmAdaptation.start(0, 0))
    }

    @Test
    fun keepWarmAdaptsOnFastAndSlowBeats() {
        // Fast: cache survived, lengthen.
        assertEquals(60, KeepWarmAdaptation.next(45, 1_000))
        // Slow: cache evicted, shorten.
        assertEquals(30, KeepWarmAdaptation.next(45, 20_000))
        // In between: unchanged.
        assertEquals(45, KeepWarmAdaptation.next(45, 5_000))
        // A failed beat (no sample) changes nothing.
        assertEquals(45, KeepWarmAdaptation.next(45, 0))
        // Clamped at both ends.
        assertEquals(300, KeepWarmAdaptation.next(295, 500))
        assertEquals(15, KeepWarmAdaptation.next(20, 40_000))
    }

    @Test
    fun keepWarmSaysWhenThereIsNoDataYet() {
        assertTrue(KeepWarmAdaptation.describe(45, 0, false).contains("no measured beats"))
        assertTrue(KeepWarmAdaptation.describe(45, 1, false).contains("measuring"))
        assertTrue(KeepWarmAdaptation.describe(60, 3, true).contains("60 s"))
    }

    // --- persistence helpers for the adapted values -----------------------

    @Test
    fun withKeepWarmIntervalUpdatesOnlyTheMatchingProfile() {
        val json = profileJson(SocTuning.ENGINE_REV)
        val updated = SocTuning.withKeepWarmInterval(json, "Tensor G2", 90)
        assertEquals(90, SocTuning.lookup(updated, "Tensor G2", SocTuning.ENGINE_REV)!!.keepWarmIntervalS)
        // The decode figures are untouched.
        assertEquals(14.4, SocTuning.lookup(updated, "Tensor G2", SocTuning.ENGINE_REV)!!.decodeTps, 0.001)
        // An unknown SoC leaves the JSON alone.
        assertEquals(json, SocTuning.withKeepWarmInterval(json, "Other", 90))
    }

    @Test
    fun withContextRecordsTheSizedContext() {
        val json = profileJson(SocTuning.ENGINE_REV)
        val updated = SocTuning.withContext(json, "Tensor G2", 16_384)
        assertEquals(16_384, SocTuning.lookup(updated, "Tensor G2", SocTuning.ENGINE_REV)!!.contextSize)
    }

    // --- context sized to the phone ---------------------------------------

    private val twoBModel = 1_280_835_840L

    @Test
    fun contextChoosesTheLargestSizeThatFitsWithHeadroom() {
        val choice = ContextSizing.choose(twoBModel, 0L, 4_000_000_000L, 32_768)!!
        assertEquals(32_768, choice.context)
        assertTrue(choice.fits)
        assertFalse(choice.tight)
    }

    @Test
    fun contextFallsToTheFloorWhenNothingFits() {
        // 0.5 GB available: even 4K cannot fit with 20% headroom held back.
        val choice = ContextSizing.choose(twoBModel, 0L, 500_000_000L, 8192)!!
        assertEquals(ContextSizing.MIN_CONTEXT, choice.context)
        assertFalse(choice.fits)
        assertTrue(choice.tight)
        assertEquals("does not fit", choice.label)
    }

    @Test
    fun contextIsTightWhenItSitsAgainstTheCeiling() {
        val choice = ContextSizing.choose(twoBModel, 0L, 875_000_000L, 8192)!!
        assertEquals(4096, choice.context)
        assertTrue(choice.fits)
        assertTrue(choice.tight)
        assertEquals("tight", choice.label)
    }

    @Test
    fun contextIsNeverChosenWithoutAnHonestMemoryFigure() {
        assertNull(ContextSizing.choose(twoBModel, 0L, 0L, 32_768))
        assertNull(ContextSizing.choose(0L, 0L, 4_000_000_000L, 32_768))
        assertNull(ContextSizing.choose(twoBModel, 0L, 4_000_000_000L, 2048))
    }

    @Test
    fun contextHeadroomCostsExactlyTheMargin() {
        val req = ContextSizing.requiredBytes(1_000_000_000L, 0L, 8192)
        // KV (memory model) + margin + the dirty eighth of the weights.
        assertEquals(
            MemoryFit.estimateKvCacheBytes(1_000_000_000L, 8192) +
                MemoryFit.WORKING_MARGIN_BYTES + 125_000_000L,
            req,
        )
        assertEquals(800_000_000L, ContextSizing.budgetBytes(1_000_000_000L))
        // required at 8K for a 1 GB model is ~710 MB: fits a 1 GB avail, not 0.8 GB.
        assertTrue(ContextSizing.fits(1_000_000_000L, 0L, 8192, 1_000_000_000L))
        assertFalse(ContextSizing.fits(1_000_000_000L, 0L, 8192, 800_000_000L))
    }

    @Test
    fun contextVerdictLabelCoversEveryCase() {
        assertEquals("unknown", ContextSizing.verdictLabel(twoBModel, 0L, 8192, 0L))
        assertEquals("fits", ContextSizing.verdictLabel(twoBModel, 0L, 4096, 4_000_000_000L))
        assertEquals("does not fit", ContextSizing.verdictLabel(twoBModel, 0L, 65_536, 1_000_000_000L))
    }

    // --- optional OpenCL prefill backend ----------------------------------

    @Test
    fun gpuPrefillReportsPresenceHonestly() {
        assertFalse(GpuPrefill.present(null))
        assertFalse(GpuPrefill.present("/no/such/dir/phonon-xyz"))
        assertEquals(GpuPrefill.NOT_INCLUDED, GpuPrefill.statusLine(false))
        assertEquals(GpuPrefill.INCLUDED_UNTESTED, GpuPrefill.statusLine(true))

        val dir = java.nio.file.Files.createTempDirectory("phonon-opencl").toFile()
        try {
            assertFalse(GpuPrefill.present(dir.absolutePath))
            java.io.File(dir, GpuPrefill.LIB_NAME).writeText("x")
            assertTrue(GpuPrefill.present(dir.absolutePath))
        } finally {
            dir.deleteRecursively()
        }
    }
}


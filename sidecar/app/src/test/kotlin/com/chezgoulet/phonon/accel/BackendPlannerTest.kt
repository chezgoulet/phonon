package com.chezgoulet.phonon.accel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendPlannerTest {

    private val pixel8 = BackendPlanner.DeviceInfo(socModel = "Tensor G3", hardware = "zuma", apiLevel = 34)
    private val galaxyS23 = BackendPlanner.DeviceInfo(socModel = "SM8550", hardware = "kalama", apiLevel = 34)
    private val motoG = BackendPlanner.DeviceInfo(socModel = "MT6855", hardware = "mt6855", apiLevel = 33)
    private val oldPixel6NoSocModel = BackendPlanner.DeviceInfo(socModel = "", hardware = "oriole", apiLevel = 30)

    @Test
    fun `auto on Tensor device attempts NPU first`() {
        assertEquals(listOf("npu", "gpu", "cpu"), BackendPlanner.candidates("auto", pixel8))
    }

    @Test
    fun `auto on Snapdragon 8-series attempts NPU first`() {
        assertEquals(listOf("npu", "gpu", "cpu"), BackendPlanner.candidates("auto", galaxyS23))
    }

    @Test
    fun `auto on NPU-less SoC skips NPU`() {
        assertEquals(listOf("gpu", "cpu"), BackendPlanner.candidates("auto", motoG))
    }

    @Test
    fun `Tensor detected from board name when SOC_MODEL is unavailable`() {
        assertTrue(BackendPlanner.hasNpuPath(oldPixel6NoSocModel))
        assertEquals(listOf("npu", "gpu", "cpu"), BackendPlanner.candidates("auto", oldPixel6NoSocModel))
    }

    @Test
    fun `explicit cpu pins to cpu only`() {
        assertEquals(listOf("cpu"), BackendPlanner.candidates("cpu", pixel8))
    }

    @Test
    fun `explicit gpu still falls back to cpu`() {
        assertEquals(listOf("gpu", "cpu"), BackendPlanner.candidates("gpu", motoG))
    }

    @Test
    fun `explicit npu is honored even on unknown hardware`() {
        // Operator override: they may know the SoC better than our list.
        // Init failure falls through the chain at runtime anyway.
        assertEquals(listOf("npu", "gpu", "cpu"), BackendPlanner.candidates("npu", motoG))
    }

    @Test
    fun `null empty and unknown values are treated as auto`() {
        val expected = BackendPlanner.candidates("auto", pixel8)
        assertEquals(expected, BackendPlanner.candidates(null, pixel8))
        assertEquals(expected, BackendPlanner.candidates("", pixel8))
        assertEquals(expected, BackendPlanner.candidates("turbo", pixel8))
    }

    @Test
    fun `requested value is case and whitespace insensitive`() {
        assertEquals(listOf("cpu"), BackendPlanner.candidates(" CPU ", pixel8))
    }

    @Test
    fun `chain always ends with cpu and has no duplicates`() {
        for (req in listOf(null, "auto", "npu", "gpu", "cpu", "garbage")) {
            for (dev in listOf(pixel8, galaxyS23, motoG, oldPixel6NoSocModel)) {
                val chain = BackendPlanner.candidates(req, dev)
                assertEquals("chain must end with cpu: $chain", "cpu", chain.last())
                assertEquals("chain must be duplicate-free: $chain", chain.size, chain.distinct().size)
                assertFalse("chain must not be empty", chain.isEmpty())
            }
        }
    }
}

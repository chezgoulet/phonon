package org.phononai.app.ui.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mark's motion rules, tested without a phone.
 *
 * What matters for cost: settled states stop scheduling, nothing schedules
 * faster than the 30 fps clock, and the generating beat follows decode rate.
 * What matters for the identity: the mark is always there, HEAT is stepped,
 * and the compact cut carries no traces.
 */
class MarkMotionTest {

    private val f = MarkFrame()

    private fun frame(
        style: MotionStyle,
        state: MarkState,
        t: Long,
        tps: Float = 12f,
        compact: Boolean = false,
        still: Boolean = false,
    ): MarkFrame {
        MarkMotion.fill(f, style, state, t, tps, compact, still)
        return f
    }

    private val times = (0L..6000L step 17L).toList()

    @Test
    fun `stopped draws once in every style and size`() {
        for (style in MotionStyle.values()) for (compact in listOf(false, true)) for (t in times) {
            assertEquals(MarkMotion.NEVER, frame(style, MarkState.Stopped, t, compact = compact).holdMs)
        }
    }

    @Test
    fun `reduced motion never schedules a frame`() {
        for (style in MotionStyle.values()) for (state in MarkState.values()) for (t in times) {
            assertEquals("$style $state", MarkMotion.NEVER, frame(style, state, t, still = true).holdMs)
        }
    }

    @Test
    fun `no frame is scheduled faster than 30 fps`() {
        for (style in MotionStyle.values()) for (state in MarkState.values()) for (tps in listOf(0f, 3f, 20f, 500f)) {
            for (t in times) {
                val hold = frame(style, state, t, tps).holdMs
                assertTrue("$style $state $tps $t: $hold", hold == MarkMotion.NEVER || hold >= MarkMotion.FRAME_MS)
            }
        }
    }

    @Test
    fun `paused and a settled error stop scheduling`() {
        for (style in MotionStyle.values()) {
            for (t in times) assertEquals(MarkMotion.NEVER, frame(style, MarkState.Paused, t).holdMs)
            for (t in 200L..6000L step 50L) assertEquals(MarkMotion.NEVER, frame(style, MarkState.Error, t).holdMs)
        }
    }

    @Test
    fun `every frame shows the mark`() {
        for (style in MotionStyle.values()) for (state in MarkState.values()) for (compact in listOf(false, true)) {
            for (t in times) {
                val fr = frame(style, state, t, compact = compact)
                val squares = fr.squareEdges.sum()
                assertTrue("$style $state $t", squares > 0f || fr.ghost != Role.NONE)
            }
        }
    }

    @Test
    fun `bus lights exactly one segment, paced by decode rate`() {
        val beat = MarkMotion.busBeatMs(20f)
        assertEquals(200L, beat)
        for (k in 0 until 10) {
            val fr = frame(MotionStyle.BUS, MarkState.Generating, k * beat + 5, tps = 20f)
            val lit = (0 until 5).filter { fr.traceRole[it] == Role.PRIMARY }
            assertEquals(listOf(k % 5), lit)
            assertEquals(beat - 5, fr.holdMs)
        }
        // Clamped at both ends, with a fallback before the first rate arrives.
        assertEquals(133L, MarkMotion.busBeatMs(1000f))
        assertEquals(800L, MarkMotion.busBeatMs(1f))
        assertEquals(400L, MarkMotion.busBeatMs(0f))
    }

    @Test
    fun `bus error snaps the path and holds`() {
        val fr = frame(MotionStyle.BUS, MarkState.Error, 1000L)
        assertTrue(fr.traceGap.all { it > 0.4f })
        assertTrue(fr.traceRole.all { it == Role.DANGER })
    }

    @Test
    fun `build sketches every stroke during loading`() {
        val done = frame(MotionStyle.BUILD, MarkState.Loading, MarkMotion.BUILD_STROKES * MarkMotion.BUILD_STROKE_MS + 10)
        assertEquals(17f, done.drawnStrokes(), 1e-4f)
        assertEquals(1f, done.dotScale, 1e-4f)
        val early = frame(MotionStyle.BUILD, MarkState.Loading, 0L)
        assertTrue(early.drawnStrokes() in 0.01f..1f)
    }

    @Test
    fun `build pauses frozen halfway`() {
        val fr = frame(MotionStyle.BUILD, MarkState.Paused, 5000L)
        assertEquals(MarkMotion.BUILD_STROKES / 2f, fr.drawnStrokes(), 1e-4f)
        assertEquals(Role.RULE, fr.ghost)
    }

    @Test
    fun `build fills the squares in sequence`() {
        val beat = MarkMotion.buildBeatMs(12f)
        val counts = (0 until 4).map { k ->
            frame(MotionStyle.BUILD, MarkState.Generating, k * beat + 1).squareFill.count { it != Role.NONE }
        }
        assertEquals(listOf(0, 1, 2, 3), counts)
    }

    @Test
    fun `heat is four discrete steps`() {
        for (state in MarkState.values()) for (tps in listOf(0f, 5f, 15f)) for (t in times) {
            val fr = frame(MotionStyle.HEAT, state, t, tps)
            assertTrue(MarkMotion.HEAT_DOT.any { it == fr.dotScale })
            assertTrue(MarkMotion.HEAT_TRACE.any { it == fr.traceWeight })
            assertTrue(fr.band in 0..4)
        }
        assertEquals(2, MarkMotion.heatLevel(MarkState.Generating, 0, 5f, false))
        assertEquals(3, MarkMotion.heatLevel(MarkState.Generating, 0, 15f, false))
    }

    @Test
    fun `heat colours appear only in the strip`() {
        val heatRoles = Role.HEAT0..Role.HEAT0 + 3
        for (style in MotionStyle.values()) for (state in MarkState.values()) for (t in times) {
            val fr = frame(style, state, t)
            assertTrue(fr.squareRole.none { it in heatRoles } && fr.squareFill.none { it in heatRoles })
            assertTrue(fr.traceRole.none { it in heatRoles } && fr.dotRole !in heatRoles)
        }
    }

    @Test
    fun `compact drops traces and strip but keeps the signal`() {
        for (style in MotionStyle.values()) for (state in MarkState.values()) for (t in times) {
            val fr = frame(style, state, t, compact = true)
            assertTrue(fr.traceDrawn.all { it == 0f })
            assertEquals(-1, fr.band)
        }
        val beat = MarkMotion.busBeatMs(12f)
        for (k in 0 until 5) {
            val fr = frame(MotionStyle.BUS, MarkState.Generating, k * beat + 1, compact = true)
            assertEquals(1, fr.squareFill.count { it == Role.PRIMARY })
            assertEquals(MarkMotion.TRACE_OWNER[k], fr.squareFill.indexOf(Role.PRIMARY))
        }
    }

    @Test
    fun `palette resolves every role`() {
        val p = MarkPalette(1, 2, 3, 4, 5, 6, intArrayOf(7, 8, 9, 10))
        val roles = listOf(Role.INK, Role.DIM, Role.RULE, Role.PRIMARY, Role.ACCENT, Role.DANGER) +
            (Role.HEAT0..Role.HEAT0 + 3)
        assertEquals((1..10).toList(), roles.map { p.color(it) })
    }
}

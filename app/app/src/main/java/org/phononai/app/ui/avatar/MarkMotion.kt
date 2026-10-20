package org.phononai.app.ui.avatar

import kotlin.math.max
import kotlin.math.min

/**
 * Motion for the BENCH mark, as a pure function of time.
 *
 * Nothing here touches Android: [MarkMotion.fill] writes one [MarkFrame] for a
 * (style, state, elapsed time) and says how long that frame stays valid. The
 * view draws the frame and sleeps until it changes; the unit tests and the
 * HTML preview (preview/mark-motion.html) run the same rules.
 *
 * Every animation is stepped, not continuous. A frame holds for a whole step
 * (66 ms, 300 ms, or one decode beat), so the view invalidates a handful of
 * times a second instead of 30, and never at all once a state has settled.
 *
 * Geometry is the 256 grid of res/drawable/ic_bench_mark.xml.
 */
enum class MotionStyle { BUS, BUILD, HEAT }

/** Mirrors AvatarKind in PhononApp.kt, which is private to that file. */
enum class MarkState { Stopped, Loading, Ready, Generating, Paused, Error }

/** Colour roles. The view resolves them against a [MarkPalette]. */
object Role {
    const val NONE = -1
    const val INK = 0
    const val DIM = 1
    const val RULE = 2
    const val PRIMARY = 3
    const val ACCENT = 4
    const val DANGER = 5
    const val HEAT0 = 6 // HEAT0..HEAT0 + 3: the four bands, cold to hot
}

/** The CORE colours the mark may use, as ARGB ints. Supplied by the caller. */
class MarkPalette(
    val ink: Int,
    val dim: Int,
    val rule: Int,
    val primary: Int,
    val accent: Int,
    val danger: Int,
    /** Cold to hot, four bands. Only ever drawn as the thin edge strip. */
    val heat: IntArray,
) {
    init {
        require(heat.size == 4) { "heat needs four bands" }
    }

    fun color(role: Int): Int = when (role) {
        Role.INK -> ink
        Role.DIM -> dim
        Role.RULE -> rule
        Role.PRIMARY -> primary
        Role.ACCENT -> accent
        Role.DANGER -> danger
        in Role.HEAT0..Role.HEAT0 + 3 -> heat[role - Role.HEAT0]
        else -> 0
    }
}

/**
 * One drawable frame. Mutable and reused, so drawing allocates nothing.
 *
 * Squares are A (top), B (left), C (right). Trace segments run in bus order:
 * B up, B across into the hub, hub up to A, hub across to C, down into C.
 */
class MarkFrame {
    /** Edges drawn per square, 0..4, clockwise from the top-left corner. */
    val squareEdges = FloatArray(3)
    val squareRole = IntArray(3)
    /** Fill role per square, or [Role.NONE] for the open outline. */
    val squareFill = IntArray(3)
    /** Fraction of each trace segment drawn from its start, 0..1. */
    val traceDrawn = FloatArray(5)
    val traceRole = IntArray(5)
    /** Fraction cut out of the middle of each segment: the snapped bus. */
    val traceGap = FloatArray(5)
    /** Multiplier on the 9-unit trace stroke. */
    var traceWeight = 1f
    /** Multiplier on the 24-unit dot; 0 hides it. */
    var dotScale = 1f
    var dotRole = Role.PRIMARY
    /** Undrawn parts of the mark are drawn first in this role (a ghost), or NONE. */
    var ghost = Role.NONE
    /** Heat strip: -1 hidden, else how many of the four bands are lit. */
    var band = -1
    /** Milliseconds this frame stays correct, or [MarkMotion.NEVER]. */
    var holdMs = MarkMotion.NEVER

    fun drawnStrokes(): Float = squareEdges.sum() + traceDrawn.sum()
}

object MarkMotion {
    /** The frame clock: no frame is ever scheduled sooner than this (30 fps). */
    const val FRAME_MS = 33L
    const val NEVER = -1L

    /** Step for one-shot transitions and the BUILD sketch. */
    const val STEP_MS = 66L

    // --- geometry: res/drawable/ic_bench_mark.xml, 256 grid ------------------

    /** Square corners: left, top, right, bottom. */
    val SQUARES = arrayOf(
        floatArrayOf(98f, 24f, 158f, 84f),
        floatArrayOf(22f, 172f, 82f, 232f),
        floatArrayOf(174f, 172f, 234f, 232f),
    )
    const val SQUARE_STROKE = 13f

    /** Trace segments in bus order: x0, y0, x1, y1. */
    val TRACES = arrayOf(
        floatArrayOf(52f, 172f, 52f, 134f),
        floatArrayOf(52f, 134f, 116f, 134f),
        floatArrayOf(128f, 116f, 128f, 84f),
        floatArrayOf(140f, 122f, 204f, 122f),
        floatArrayOf(204f, 122f, 204f, 172f),
    )
    const val TRACE_STROKE = 9f

    /**
     * Trace ends that sit on a right-angle corner. Traces use butt caps (as in
     * the drawable), so these ends are pushed out by half the stroke to close
     * the corner; the ends at the squares and the hub stay flush.
     */
    val TRACE_CORNER_START = booleanArrayOf(false, true, false, false, true)
    val TRACE_CORNER_END = booleanArrayOf(true, false, false, true, false)

    /** Which square each trace segment belongs to (for the compact cut). */
    val TRACE_OWNER = intArrayOf(1, 1, 0, 2, 2)

    /** Dot: centre and half-size at scale 1. */
    const val DOT_CX = 128f
    const val DOT_CY = 128f
    const val DOT_HALF = 12f

    /** Heat strip under the mark: top, height, horizontal extent, gap. */
    const val BAND_TOP = 246f
    const val BAND_HEIGHT = 4f
    const val BAND_LEFT = 15.5f
    const val BAND_RIGHT = 240.5f
    const val BAND_GAP = 4f

    /** Below this size (dp) the compact cut is drawn: no traces, filled squares. */
    const val COMPACT_DP = 48f

    // --- timing ---------------------------------------------------------------

    /** BUS idle pulse: eight 300 ms steps, a 2.4 s breath. */
    const val PULSE_STEP_MS = 300L
    val PULSE = floatArrayOf(1f, 1f, 1f, 1.1f, 1.2f, 1.3f, 1.2f, 1.1f)

    /** BUS loading: a probe walks the bus, one segment per step. */
    const val PROBE_STEP_MS = 300L

    /** BUILD loading: 18 strokes (12 edges, 5 traces, the dot), then a hold. */
    const val BUILD_STROKES = 18
    const val BUILD_STROKE_MS = 198L // three 66 ms steps
    const val BUILD_HOLD_MS = 792L
    const val BUILD_CYCLE_MS = BUILD_STROKES * BUILD_STROKE_MS + BUILD_HOLD_MS

    /** HEAT: four discrete levels. */
    val HEAT_DOT = floatArrayOf(1f, 1.25f, 1.5f, 1.75f)
    val HEAT_TRACE = floatArrayOf(0.67f, 1f, 1.5f, 2f)
    const val HEAT_RAMP_STEP_MS = 500L

    /** Decode rate at which HEAT reads the top level. */
    const val HEAT_HOT_TPS = 10f

    /**
     * One decode beat: [tokens] tokens at [tps], clamped. With no rate yet
     * (tps <= 0) the beat is [fallback].
     */
    fun beatMs(tps: Float, tokens: Float, minMs: Long, maxMs: Long, fallback: Long): Long =
        if (tps <= 0f) fallback else (tokens * 1000f / tps).toLong().coerceIn(minMs, maxMs)

    /** BUS: one trace segment per four tokens; 20 tok/s is a 1 s lap of the bus. */
    fun busBeatMs(tps: Float) = beatMs(tps, 4f, 133L, 800L, 400L)

    /** BUILD: one square filled per six tokens. */
    fun buildBeatMs(tps: Float) = beatMs(tps, 6f, 200L, 1200L, 500L)

    /** HEAT: the needle drops a step on every other beat of four tokens. */
    fun heatBeatMs(tps: Float) = beatMs(tps, 4f, 133L, 800L, 400L)

    fun beatFor(style: MotionStyle, tps: Float): Long = when (style) {
        MotionStyle.BUS -> busBeatMs(tps)
        MotionStyle.BUILD -> buildBeatMs(tps)
        MotionStyle.HEAT -> heatBeatMs(tps)
    }

    /**
     * Fill [f] for [style] in [state], [t] ms after the state began.
     *
     * [still] is reduced motion: a single representative frame, never
     * rescheduled. [compact] is the cut for header sizes.
     */
    fun fill(
        f: MarkFrame,
        style: MotionStyle,
        state: MarkState,
        t: Long,
        tps: Float,
        compact: Boolean,
        still: Boolean,
    ) {
        reset(f)
        val time = max(0L, t)
        when (style) {
            MotionStyle.BUS -> bus(f, state, time, tps, still)
            MotionStyle.BUILD -> build(f, state, time, tps, still)
            MotionStyle.HEAT -> heat(f, state, time, tps, still)
        }
        if (compact) toCompact(f)
        if (still || state == MarkState.Stopped) f.holdMs = NEVER
        if (f.holdMs != NEVER) f.holdMs = max(FRAME_MS, f.holdMs)
    }

    private fun reset(f: MarkFrame) {
        f.squareEdges.fill(4f)
        f.squareRole.fill(Role.INK)
        f.squareFill.fill(Role.NONE)
        f.traceDrawn.fill(1f)
        f.traceRole.fill(Role.INK)
        f.traceGap.fill(0f)
        f.traceWeight = 1f
        f.dotScale = 1f
        f.dotRole = Role.PRIMARY
        f.ghost = Role.NONE
        f.band = -1
        f.holdMs = NEVER
    }

    /** Time left in the current [step]. */
    private fun rest(t: Long, step: Long) = step - t % step

    private fun allRoles(f: MarkFrame, role: Int) {
        f.squareRole.fill(role)
        f.traceRole.fill(role)
        f.dotRole = role
    }

    // --- BUS: the traces are the subject --------------------------------------

    private fun bus(f: MarkFrame, state: MarkState, t: Long, tps: Float, still: Boolean) {
        when (state) {
            MarkState.Stopped -> allRoles(f, Role.DIM)
            MarkState.Loading -> {
                f.traceRole.fill(Role.DIM)
                f.dotRole = Role.ACCENT
                if (!still) {
                    f.traceRole[((t / PROBE_STEP_MS) % 5).toInt()] = Role.ACCENT
                    f.holdMs = rest(t, PROBE_STEP_MS)
                }
            }
            MarkState.Ready -> if (!still) {
                f.dotScale = PULSE[((t / PULSE_STEP_MS) % PULSE.size).toInt()]
                f.holdMs = rest(t, PULSE_STEP_MS)
            }
            MarkState.Generating -> {
                if (still) {
                    f.traceRole.fill(Role.PRIMARY)
                } else {
                    val beat = busBeatMs(tps)
                    f.traceRole.fill(Role.DIM)
                    f.traceRole[((t / beat) % 5).toInt()] = Role.PRIMARY
                    f.holdMs = rest(t, beat)
                }
            }
            MarkState.Paused -> {
                f.squareRole.fill(Role.DIM)
                f.traceRole.fill(Role.DIM)
                f.dotRole = Role.ACCENT
            }
            MarkState.Error -> {
                f.traceRole.fill(Role.DANGER)
                f.dotRole = Role.DANGER
                // The bus snaps open in three frames, then holds.
                val step = if (still) 2 else (t / STEP_MS).toInt()
                f.traceGap.fill(SNAP[min(step, 2)])
                if (step < 2) f.holdMs = rest(t, STEP_MS)
            }
        }
    }

    private val SNAP = floatArrayOf(0.2f, 0.35f, 0.45f)

    // --- BUILD: the mark draws itself -----------------------------------------

    /** Lay down the first [strokes] of the 18-stroke sketch (fractional). */
    private fun sketch(f: MarkFrame, strokes: Float) {
        for (i in 0 until 3) f.squareEdges[i] = (strokes - i * 4).coerceIn(0f, 4f)
        for (i in 0 until 5) f.traceDrawn[i] = (strokes - 12 - i).coerceIn(0f, 1f)
        f.dotScale = (strokes - 17).coerceIn(0f, 1f)
        f.ghost = Role.RULE
    }

    private fun build(f: MarkFrame, state: MarkState, t: Long, tps: Float, still: Boolean) {
        when (state) {
            MarkState.Stopped -> allRoles(f, Role.DIM)
            MarkState.Loading -> {
                f.dotRole = Role.ACCENT
                if (still) {
                    sketch(f, BUILD_STROKES / 2f)
                    f.dotScale = 1f
                } else {
                    // Quantised to 66 ms steps: each stroke lands in thirds.
                    val c = t % BUILD_CYCLE_MS
                    val stepped = c / STEP_MS * STEP_MS
                    sketch(f, min(BUILD_STROKES.toFloat(), (stepped + STEP_MS).toFloat() / BUILD_STROKE_MS))
                    f.holdMs = rest(t, STEP_MS)
                }
            }
            MarkState.Ready -> {
                // Settle: the lamp lands a size large and steps down, once.
                val step = if (still) 2 else (t / STEP_MS).toInt()
                f.dotScale = SETTLE[min(step, 2)]
                if (step < 2) f.holdMs = rest(t, STEP_MS)
            }
            MarkState.Generating -> {
                val filled = if (still) 3 else ((t / buildBeatMs(tps)) % 4).toInt()
                for (k in 0 until filled) f.squareFill[FILL_ORDER[k]] = Role.PRIMARY
                if (!still) f.holdMs = rest(t, buildBeatMs(tps))
            }
            MarkState.Paused -> {
                // Frozen halfway through the sketch.
                sketch(f, BUILD_STROKES / 2f)
                f.squareRole.fill(Role.DIM)
                f.traceRole.fill(Role.DIM)
                f.dotScale = 1f
                f.dotRole = Role.ACCENT
            }
            MarkState.Error -> {
                // Each square loses its last edge; the traces are gone.
                f.squareEdges.fill(3f)
                f.traceDrawn.fill(0f)
                f.squareRole.fill(Role.DANGER)
                f.dotRole = Role.DANGER
                f.ghost = Role.RULE
            }
        }
    }

    private val SETTLE = floatArrayOf(1.25f, 1.12f, 1f)

    /** Squares in bus order: left, top, right. */
    private val FILL_ORDER = intArrayOf(1, 0, 2)

    // --- HEAT: the mark as a gauge --------------------------------------------

    /** Level 0..3 for a running state; the needle may drop one below this. */
    fun heatLevel(state: MarkState, t: Long, tps: Float, still: Boolean): Int = when (state) {
        MarkState.Stopped, MarkState.Error -> 0
        MarkState.Loading -> if (still) 2 else ((t / HEAT_RAMP_STEP_MS) % 4).toInt()
        MarkState.Ready -> 1
        MarkState.Generating -> if (tps >= HEAT_HOT_TPS) 3 else 2
        MarkState.Paused -> 3
    }

    private fun heat(f: MarkFrame, state: MarkState, t: Long, tps: Float, still: Boolean) {
        val level = heatLevel(state, t, tps, still)
        f.traceWeight = HEAT_TRACE[level]
        f.dotScale = HEAT_DOT[level]
        f.band = level + 1
        when (state) {
            MarkState.Stopped -> {
                allRoles(f, Role.DIM)
                f.band = 0
            }
            MarkState.Loading -> {
                f.dotRole = Role.ACCENT
                if (!still) f.holdMs = rest(t, HEAT_RAMP_STEP_MS)
            }
            MarkState.Ready -> Unit
            MarkState.Generating -> if (!still) {
                val beat = heatBeatMs(tps)
                if ((t / beat) % 2 == 1L) f.dotScale = HEAT_DOT[level - 1]
                f.holdMs = rest(t, beat)
            }
            MarkState.Paused -> {
                f.squareRole.fill(Role.DIM)
                f.traceRole.fill(Role.DIM)
                f.dotRole = Role.ACCENT
            }
            MarkState.Error -> {
                f.traceRole.fill(Role.DANGER)
                f.dotRole = Role.DANGER
                f.band = 0
            }
        }
    }

    // --- compact cut ------------------------------------------------------------

    /**
     * At header size the traces and the strip would be sub-pixel. Drop them
     * and move what they were saying onto the filled squares: a lit trace
     * lights its square, a snapped bus turns its squares to danger.
     */
    private fun toCompact(f: MarkFrame) {
        for (i in 0 until 3) {
            if (f.squareFill[i] == Role.NONE) f.squareFill[i] = f.squareRole[i]
            // A filled square has no edges to sketch: it lands once mostly drawn.
            f.squareEdges[i] = if (f.squareEdges[i] >= 3f) 4f else 0f
        }
        for (s in 0 until 5) {
            val role = f.traceRole[s]
            if (role == Role.PRIMARY || role == Role.ACCENT || role == Role.DANGER) {
                f.squareFill[TRACE_OWNER[s]] = role
            }
        }
        f.traceDrawn.fill(0f)
        f.band = -1
    }
}

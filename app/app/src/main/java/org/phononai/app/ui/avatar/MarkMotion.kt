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
    /** Running well: the only green in the mark. */
    const val OK = 10
}

/** The CORE colours the mark may use, as ARGB ints. Supplied by the caller. */
class MarkPalette(
    val ink: Int,
    val dim: Int,
    val rule: Int,
    val primary: Int,
    val accent: Int,
    val danger: Int,
    /** Good/running green. */
    val ok: Int,
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
        Role.OK -> ok
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
    /** A packet travelling the bus: trace index (-1 none), fraction along it. */
    var packetTrack = -1
    var packetPos = 0f
    var packetRole = Role.OK

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

    /**
     * Canonical BENCH geometry, taken verbatim from the site header and scaled
     * from its 118-unit box into this 256-unit one (k = 256/118):
     *   squares (12,12), (78,12), (78,78) at 28; traces M40,26h38 and
     *   M26,40v52h52; dot (59,59) r5; stroke 5.
     * Top-left, top-right, bottom-right — open at the bottom-left. Do not
     * rearrange these; the arrangement is the logo.
     */
    val SQUARES = arrayOf(
        floatArrayOf(26.0f, 26.0f, 86.8f, 86.8f),
        floatArrayOf(169.2f, 26.0f, 230.0f, 86.8f),
        floatArrayOf(169.2f, 169.2f, 230.0f, 230.0f),
    )
    const val SQUARE_STROKE = 10.85f

    /** Traces: the top bar, then the L (down the left, along the bottom). */
    val TRACES = arrayOf(
        floatArrayOf(86.8f, 56.4f, 169.2f, 56.4f),
        floatArrayOf(56.4f, 86.8f, 56.4f, 199.6f),
        floatArrayOf(56.4f, 199.6f, 169.2f, 199.6f),
        floatArrayOf(0f, 0f, 0f, 0f),
        floatArrayOf(0f, 0f, 0f, 0f),
    )
    const val TRACE_STROKE = 10.85f

    /**
     * Trace ends that sit on a right-angle corner. The vertical meets the
     * bottom run at (56.4, 199.6); both are pushed out by half the stroke.
     */
    val TRACE_CORNER_START = booleanArrayOf(false, false, true, false, false)
    val TRACE_CORNER_END = booleanArrayOf(false, true, false, false, false)

    /** Which square each trace segment belongs to (for the compact cut). */
    val TRACE_OWNER = intArrayOf(0, 0, 2, 0, 0)

    /** Dot: circle at the centre of the implied rectangle, radius as the logo. */
    const val DOT_CX = 128f
    const val DOT_CY = 128f
    const val DOT_HALF = 10.85f

    const val BAND_TOP = 246f
    const val BAND_HEIGHT = 4f
    const val BAND_LEFT = 15.5f
    const val BAND_RIGHT = 240.5f
    const val BAND_GAP = 4f

    /** Packet: half-size on the 256 grid, small enough to stay inside the trace. */
    const val PACKET_HALF = 7f

    /** Segment lengths of the real path, in grid units (308 in total). */
    private val SEG_LEN = floatArrayOf(82.4f, 112.8f, 112.8f)
    private const val PATH_LEN = 308f

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
        f.packetTrack = -1
        f.packetPos = 0f
        f.packetRole = Role.OK
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

    /**
     * Place a packet at [u] of the bus path (0..1). The path is axis-aligned, so
     * the packet can only ever move along one axis at a time and turns the
     * corners rather than cutting them.
     */
    private fun packetOnPath(f: MarkFrame, u: Float, role: Int) {
        var d = u.coerceIn(0f, 0.999f) * PATH_LEN
        for (i in 0 until 3) {
            if (d <= SEG_LEN[i] || i == 2) {
                f.packetTrack = i
                f.packetPos = (d / SEG_LEN[i]).coerceIn(0f, 1f)
                f.packetRole = role
                return
            }
            d -= SEG_LEN[i]
        }
    }


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
            MarkState.Ready -> {
                f.dotRole = Role.OK
                if (still) {
                    f.dotScale = 1f
                } else {
                    // One lap in eight 300 ms hops; the corners are turns, not arcs.
                    val step = (t / PULSE_STEP_MS).toInt()
                    packetOnPath(f, (step % 8) / 8f, Role.OK)
                    f.dotScale = PULSE[step % PULSE.size]
                    f.holdMs = rest(t, PULSE_STEP_MS)
                }
            }
            MarkState.Generating -> {
                f.dotRole = Role.OK
                if (still) {
                    packetOnPath(f, 0.5f, Role.OK)
                } else {
                    val beat = busBeatMs(tps)
                    // The packet runs the path a beat per hop; the squares tick
                    // in sequence as tokens land.
                    packetOnPath(f, ((t / beat) % 8) / 8f, Role.OK)
                    f.squareFill[((t / beat) % 3).toInt()] = Role.OK
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
                f.dotRole = Role.OK
                val step = if (still) 2 else (t / STEP_MS).toInt()
                f.dotScale = SETTLE[min(step, 2)]
                if (step < 2) f.holdMs = rest(t, STEP_MS)
            }
            MarkState.Generating -> {
                val filled = if (still) 3 else ((t / buildBeatMs(tps)) % 4).toInt()
                for (k in 0 until filled) f.squareFill[FILL_ORDER[k]] = Role.OK
                f.dotRole = Role.OK
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
            MarkState.Ready -> f.dotRole = Role.OK
            MarkState.Generating -> if (!still) {
                f.dotRole = Role.OK
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

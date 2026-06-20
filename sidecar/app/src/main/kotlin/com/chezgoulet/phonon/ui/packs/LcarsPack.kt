package com.chezgoulet.phonon.ui.packs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.chezgoulet.phonon.ui.VisualizationPack
import com.chezgoulet.phonon.ui.VizState
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * LCARS — Library Computer Access/Retrieval System.
 *
 * A structurally faithful Star Trek: TNG Okudagram console, built on the
 * actual LCARS layout grammar rather than just its color palette:
 *
 *  - a strict UNIT GRID (bar thickness = unit/3, consistent black gaps);
 *  - ELBOWS that join the wide vertical side panel to the horizontal bars,
 *    each with a large OUTER corner radius and a smaller concave INNER cut;
 *  - horizontal bars terminating in characteristic half-pill END-CAPS;
 *  - a side panel that is a stack of rectangular ELEMENTS (buttons /
 *    indicators), each carrying a short right-aligned Okudagram serial;
 *  - right-aligned text labels throughout;
 *  - a decorative BRACKET grouping the telemetry readout;
 *  - CALM presentation (Roddenberry wanted minimal panel activity): motion
 *    is reserved for meaningful state changes, never idle decoration.
 *
 * Telemetry maps onto the console: two side-panel elements are live
 * indicators (status + power), the interior carries a labeled bar-graph
 * band over a bracketed readout, and alert conditions recolor the frame
 * (amber → yellow caution → red alert) and gently blink the relevant block.
 *
 * Low-power mode drops the animated bar-graph band and presents a calm,
 * static, text-only interior to spare the battery.
 *
 * This is an original interpretation of the LCARS visual language drawn
 * with standard Compose primitives, not a copy of any screen-used asset.
 */
object LcarsPack : VisualizationPack {

    override val id = "lcars"
    override val name = "LCARS"
    override val description =
        "A structurally faithful Star Trek: TNG Okudagram console — unit-grid elbows, half-pill end-caps, a stacked panel of numbered elements, and bracket-grouped readouts. Calm by design; only alert states animate."
    override val author = "chezgoulet"
    override val version = "2.0.0"

    override val defaultConfig = mapOf(
        "color_primary" to "#FF9966",    // amber / orange (frame spine)
        "color_secondary" to "#CC99CC",  // lavender
        "color_tertiary" to "#9999FF",   // periwinkle
        "color_quaternary" to "#FFCC99", // peach
        "bg_color" to "#000000",         // LCARS lives on pure black
    )

    // Reserved alert hues (LCARS keeps red/yellow for alert conditions only).
    private val RED = Color(0xFFCC3333)
    private val YELLOW = Color(0xFFFFCC00)

    // Okudagram-style serials for the inert decorative elements.
    private val serials = listOf("4754", "1701-D", "021", "47", "M113", "5537", "33-A")

    // ── Program cycle ──
    // During heavy inference the console runs a sequence of subroutines,
    // each for ~10–15s, returning to HOME BASE between them. Every change
    // triggers an LCARS reconfigure: the side-panel elements re-flow into a
    // new arrangement behind a bright sweep that repaints the interior.
    private val PROGRAMS = listOf("home", "engineering", "home", "planet", "home", "subspace")
    private val HOLD = mapOf("home" to 3.2f, "engineering" to 13f, "planet" to 13f, "subspace" to 13f)
    private val TRANS = 1.0f   // reconfigure duration, seconds

    // Persistent state — kept minimal, in the LCARS spirit.
    private var bar = 0f
    private var progIdx = 0
    private var progClock = 0f
    private var transClock = -1f
    private var prevIdx = 0
    private var wasInferring = false

    override fun onActivate() {
        bar = 0f; progIdx = 0; progClock = 0f; transClock = -1f; prevIdx = 0; wasInferring = false
    }

    /** Per-program layout: side-panel rhythm, palette slots, labels, title, interior id. */
    private data class Layout(
        val title: String,
        val weights: FloatArray,
        val colors: List<Color>,
        val labels: List<String>,
        val interior: String,
    )

    private fun layoutFor(name: String, pal: Palette, state: VizState): Layout = when (name) {
        "engineering" -> Layout(
            "ENGINEERING \u00B7 DIAG",
            floatArrayOf(1.0f, 1.8f, 0.8f, 1.2f, 0.9f, 2.0f, 0.7f),
            listOf(pal.a, pal.c, pal.d, pal.b, pal.d, pal.a, pal.c),
            listOf("DIAG", "WARP", "EPS", "SIF", "47", "PLASMA", "9-T"),
            "engineering",
        )
        "planet" -> Layout(
            "SENSORS \u00B7 GEOSCAN",
            floatArrayOf(1.3f, 0.8f, 1.1f, 1.6f, 1.0f, 0.9f, 1.5f),
            listOf(pal.c, pal.b, pal.d, pal.a, pal.c, pal.d, pal.b),
            listOf("SCAN", "CLASS-M", "BIO", "ATMOS", "GEO", "021", "LAT"),
            "planet",
        )
        "subspace" -> Layout(
            "SUBSPACE \u00B7 ANALYSIS",
            floatArrayOf(0.9f, 1.2f, 1.7f, 0.8f, 1.4f, 1.0f, 1.1f),
            listOf(pal.b, pal.d, pal.a, pal.c, pal.b, pal.a, pal.d),
            listOf("FREQ", "TACHYON", "FLUX", "BAND", "PHASE", "5537", "Q-7"),
            "subspace",
        )
        else -> Layout(
            "LCARS \u00B7 " + state.coordinatorMode.uppercase(),
            floatArrayOf(1.6f, 0.9f, 0.9f, 2.2f, 1.1f, 1.4f, 0.8f),
            listOf(pal.b, pal.c, pal.d, pal.a, pal.c, pal.b, pal.d),
            serials.take(7),
            "home",
        )
    }

    private data class Palette(val spine: Color, val a: Color, val b: Color, val c: Color, val d: Color)

    private fun palette(cfg: Map<String, String>, critical: Boolean, caution: Boolean): Palette {
        val amber = parseHexColor(cfg["color_primary"] ?: "#FF9966")
        val lavender = parseHexColor(cfg["color_secondary"] ?: "#CC99CC")
        val periwinkle = parseHexColor(cfg["color_tertiary"] ?: "#9999FF")
        val peach = parseHexColor(cfg["color_quaternary"] ?: "#FFCC99")
        return when {
            critical -> Palette(RED, RED, amber, peach, YELLOW)
            caution -> Palette(YELLOW, YELLOW, amber, peach, lavender)
            else -> Palette(amber, amber, lavender, periwinkle, peach)
        }
    }

    @Composable
    override fun Render(state: VizState, modifier: Modifier) {
        val measurer = rememberTextMeasurer()

        var tSec by remember { mutableStateOf(0f) }
        var lastFrame by remember { mutableStateOf(0f) }
        var dtSec by remember { mutableStateOf(0.016f) }
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val tn = (now - start) / 1_000_000_000f
                dtSec = (tn - lastFrame).coerceIn(0f, 0.05f)
                lastFrame = tn
                tSec = tn
            }
        }

        Canvas(modifier = modifier.fillMaxSize()) {
            val t = tSec
            val dt = dtSec
            val w = size.width
            val h = size.height
            val cfg = state.themeConfig
            val bg = parseHexColor(cfg["bg_color"] ?: "#000000")

            drawRect(bg)

            val overheating = state.batteryTemperature > 42f
            val critical = !state.isHealthy || overheating
            val caution = state.batteryLevel < 20 && !state.isCharging
            val pal = palette(cfg, critical, caution)

            // ── Drive the program state machine ──
            val lowPower = state.lowPowerMode
            val heavy = state.isProcessing && state.inferenceLoad > 0.55f && !lowPower && !critical
            if (!wasInferring && heavy) { progIdx = 0; progClock = 0f; transClock = -1f }
            wasInferring = heavy

            if (heavy) {
                progClock += dt
                if (transClock >= 0f) {
                    transClock += dt
                    if (transClock >= TRANS) transClock = -1f
                }
                val curName0 = PROGRAMS[progIdx]
                if (transClock < 0f && progClock >= (HOLD[curName0] ?: 12f)) {
                    prevIdx = progIdx
                    progIdx = (progIdx + 1) % PROGRAMS.size
                    progClock = 0f
                    transClock = 0f
                }
            } else {
                if (progIdx != 0 || transClock >= 0f) {
                    prevIdx = progIdx
                    if (progIdx != 0 && transClock < 0f) transClock = 0f
                    progIdx = 0
                    progClock = 0f
                }
                if (transClock >= 0f) {
                    transClock += dt
                    if (transClock >= TRANS) transClock = -1f
                }
            }

            val forcedHome = lowPower || critical || caution
            val curName = if (forcedHome) "home" else PROGRAMS[progIdx]
            val prevName = if (forcedHome) "home" else PROGRAMS[prevIdx]
            val transP = if (transClock >= 0f) (transClock / TRANS).coerceIn(0f, 1f) else 1f

            // ── Unit grid ──
            val u = (w * 0.30f).coerceIn(90f, 240f)
            val barT = u / 3f
            val gap = (w * 0.018f).coerceIn(5f, 14f)
            val m = (w * 0.03f).coerceIn(8f, 22f)
            val outerR = u * 0.7f
            val innerR = barT * 0.8f
            val panelX = m
            val panelW = u
            val topY = m
            val bottomY = h - m
            val inX = panelX + panelW + gap
            val rightX = w - m
            val elbowH = barT + outerR

            val blink = when {
                critical -> 0.55f + 0.45f * (0.5f + 0.5f * sin(t * 5f))
                caution -> 0.7f + 0.3f * (0.5f + 0.5f * sin(t * 2.2f))
                else -> 1f
            }

            // Build current (and, mid-transition, blended) layout.
            val curLayout = layoutFor(curName, pal, state)
            val reconfig = transP < 1f && prevName != curName
            val layout: Layout = if (reconfig) {
                val prevLayout = layoutFor(prevName, pal, state)
                val e = if (transP < 0.5f) 2f * transP * transP
                    else 1f - Math.pow((-2f * transP + 2f).toDouble(), 2.0).toFloat() / 2f
                val blended = FloatArray(curLayout.weights.size) { i ->
                    val a = prevLayout.weights.getOrElse(i) { curLayout.weights[i] }
                    a + (curLayout.weights[i] - a) * e
                }
                val swapped = transP > 0.5f
                Layout(
                    if (swapped) curLayout.title else prevLayout.title,
                    blended,
                    if (swapped) curLayout.colors else prevLayout.colors,
                    if (swapped) curLayout.labels else prevLayout.labels,
                    curLayout.interior,
                )
            } else curLayout

            var title = layout.title
            if (critical && overheating) title = "ALERT \u00B7 THERMAL"
            else if (critical) title = "ALERT \u00B7 SYSTEM"
            else if (caution) title = "PWR RESERVE"

            // ── Frame (elbows, bars, side-panel elements) ──
            // Top elbow + bar.
            drawElbow(panelX, topY, panelW, elbowH, barT, outerR, innerR, true, bg, pal.spine.copy(alpha = blink))
            drawBarWithCap(inX, topY, rightX - inX, barT, pal.b)
            drawLabel(measurer, title, rightX - barT * 0.5f, topY + barT * 0.5f, bg,
                barT * 0.5f, alignRight = true, middle = true, bold = true, maxW = (rightX - inX) - barT)
            // Bottom elbow + bar.
            drawElbow(panelX, bottomY - elbowH, panelW, elbowH, barT, outerR, innerR, false, bg, pal.spine.copy(alpha = blink))
            drawBarWithCap(inX, bottomY - barT, rightX - inX, barT, pal.c)
            drawLabel(measurer, state.deviceId.uppercase(), rightX - barT * 0.5f, bottomY - barT * 0.5f, bg,
                barT * 0.42f, alignRight = true, middle = true, bold = true, maxW = (rightX - inX) - barT)

            // Side-panel elements from the layout descriptor.
            val panelTop = topY + elbowH + gap
            val panelBot = bottomY - elbowH - gap
            val panelH = panelBot - panelTop
            val weights = layout.weights
            val wSum = weights.sum()
            var ey = panelTop
            for (i in weights.indices) {
                val eh = (panelH - gap * (weights.size - 1)) * (weights[i] / wSum)
                var ec = layout.colors[i % layout.colors.size]
                var label = layout.labels[i % layout.labels.size]
                var alpha = 1f
                if (layout.interior == "home" && i == 0) {
                    ec = if (critical) RED else pal.a
                    label = if (critical) "ALERT" else "NOMINAL"
                    if (critical) alpha = blink
                } else if (layout.interior == "home" && i == 3) {
                    ec = if (state.isCharging) YELLOW else if (state.batteryLevel < 20) RED else pal.d
                    label = if (state.isCharging) "CHRG" else "${state.batteryLevel}%"
                    if (state.batteryLevel < 20 && !state.isCharging) alpha = blink
                }
                drawRect(ec.copy(alpha = alpha), topLeft = Offset(panelX, ey), size = Size(panelW, eh))
                drawLabel(measurer, label, panelX + panelW - barT * 0.4f, ey + eh - barT * 0.32f, bg,
                    min(barT * 0.42f, eh * 0.4f), alignRight = true, middle = false, bold = true,
                    maxW = panelW - barT * 0.8f)
                ey += eh + gap
            }

            // ── Interior region ──
            val cTop = topY + elbowH + gap
            val cBot = bottomY - elbowH - gap
            val cLeft = inX
            val cW = rightX - cLeft
            val regH = cBot - cTop

            if (lowPower) {
                drawReadout(measurer, cLeft, cTop, cW, regH, state, pal, barT)
                return@Canvas
            }

            if (reconfig) {
                // LCARS reconfigure: incoming program on the left of the sweep
                // edge, outgoing program on the right — the panel repaints L→R.
                val edge = cLeft + cW * transP
                val prevInterior = layoutFor(prevName, pal, state).interior
                clipRect(left = edge, top = cTop - barT, right = rightX + barT, bottom = cBot + barT) {
                    drawInterior(measurer, prevInterior, cLeft, cTop, cW, regH, state, pal, barT, dt, t)
                }
                clipRect(left = cLeft - barT, top = cTop - barT, right = edge, bottom = cBot + barT) {
                    drawInterior(measurer, curLayout.interior, cLeft, cTop, cW, regH, state, pal, barT, dt, t)
                }
                // Bright sweep bar at the reveal edge.
                drawSweep(edge, topY, bottomY - topY, barT, pal)
            } else {
                drawInterior(measurer, curLayout.interior, cLeft, cTop, cW, regH, state, pal, barT, dt, t)
            }
        }
    }

    // ── Dispatch to the active interior program renderer. ──
    private fun DrawScope.drawInterior(
        measurer: TextMeasurer, name: String,
        x: Float, y: Float, w: Float, h: Float,
        state: VizState, pal: Palette, barT: Float, dt: Float, t: Float,
    ) {
        when (name) {
            "engineering" -> progEngineering(measurer, x, y, w, h, state, pal, barT, t)
            "planet" -> progPlanetScan(measurer, x, y, w, h, state, pal, barT, t)
            "subspace" -> progSubspace(measurer, x, y, w, h, state, pal, barT, t)
            else -> {
                val bandH = (h * 0.34f).coerceIn(barT * 2f, 220f)
                drawBarGraph(measurer, x, y, w, bandH, state, pal, barT, dt)
                val readTop = y + bandH + barT
                drawReadout(measurer, x, readTop, w, h - bandH - barT, state, pal, barT)
            }
        }
    }

    // ── Reconfigure sweep bar at the reveal edge. ──
    private fun DrawScope.drawSweep(edge: Float, y: Float, h: Float, barT: Float, pal: Palette) {
        val bw = barT * 0.7f
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(pal.a.copy(alpha = 0f), pal.a.copy(alpha = 0.22f)),
                startX = edge - bw * 5f, endX = edge,
            ),
            topLeft = Offset(edge - bw * 5f, y), size = Size(bw * 5f, h),
        )
        drawRect(pal.a, topLeft = Offset(edge - bw, y), size = Size(bw, h))
        drawRect(Color.White.copy(alpha = 0.5f), topLeft = Offset(edge - bw * 0.4f, y), size = Size(bw * 0.25f, h))
        drawRect(YELLOW, topLeft = Offset(edge - bw, y + h * 0.20f), size = Size(bw, h * 0.05f))
        drawRect(YELLOW, topLeft = Offset(edge - bw, y + h * 0.72f), size = Size(bw, h * 0.05f))
    }

    // ════════════════════════════════════════════════════════════════
    //  PROGRAM: ENGINEERING DIAGNOSTIC — warp-core intermix MSD.
    // ════════════════════════════════════════════════════════════════
    private fun DrawScope.progEngineering(
        measurer: TextMeasurer, x: Float, y: Float, w: Float, h: Float,
        state: VizState, pal: Palette, barT: Float, t: Float,
    ) {
        drawLabel(measurer, "WARP CORE \u00B7 INTERMIX", x, y, pal.a, barT * 0.4f,
            alignRight = false, middle = false, bold = true, maxW = w)
        val top = y + barT * 0.9f
        val hh = h - barT * 0.9f
        val coreX = x + w * 0.20f
        val coreW = (w * 0.08f).coerceAtLeast(barT * 0.7f)
        val coreTop = top + hh * 0.06f
        val coreBot = top + hh * 0.94f
        val coreH = coreBot - coreTop
        val chamberR = coreW * 0.95f
        val load = 0.5f + 0.5f * state.inferenceLoad

        drawRect(pal.d.copy(alpha = 0.25f), topLeft = Offset(coreX - coreW / 2f, coreTop), size = Size(coreW, coreH))
        val segs = 9
        for (i in 0 until segs) {
            val phase = ((t * (1.2f + load * 1.8f) + i / segs.toFloat()) % 1f)
            val yTop = lerpF(coreTop, (coreTop + coreBot) / 2f, phase)
            val yBot = lerpF(coreBot, (coreTop + coreBot) / 2f, phase)
            val a = 0.15f + 0.6f * (1f - phase)
            val segH = coreH * 0.05f
            drawRect(YELLOW.copy(alpha = a), topLeft = Offset(coreX - coreW / 2f, yTop), size = Size(coreW, segH))
            drawRect(pal.a.copy(alpha = a), topLeft = Offset(coreX - coreW / 2f, yBot - segH), size = Size(coreW, segH))
        }
        // chambers
        for (cy in listOf(coreTop, coreBot)) {
            drawCircle(pal.b.copy(alpha = 0.9f), chamberR, Offset(coreX, cy))
            drawCircle(Color.Black, chamberR * 0.5f, Offset(coreX, cy))
        }
        // dilithium articulation diamond
        val midY = (coreTop + coreBot) / 2f
        val pulse = 0.6f + 0.4f * (0.5f + 0.5f * sin(t * (3f + load * 4f)))
        val dia = chamberR * 1.1f
        rotateRad(Math.PI.toFloat() / 4f, pivot = Offset(coreX, midY)) {
            drawRect(YELLOW.copy(alpha = pulse), topLeft = Offset(coreX - dia / 2f, midY - dia / 2f), size = Size(dia, dia))
        }
        // EPS conduit gauges
        val gx = x + w * 0.42f
        val gw = w - (gx - x)
        data class G(val k: String, val v: Float, val c: Color)
        val gauges = listOf(
            G("EPS", 0.4f + 0.5f * state.inferenceLoad, pal.c),
            G("SIF", if (state.isHealthy) 0.92f else 0.4f, if (state.isHealthy) pal.c else RED),
            G("PLASMA", (0.55f + 0.4f * sin(t * 0.8f) * 0.5f + 0.2f).coerceIn(0f, 1f), pal.a),
            G("DRIVE", (state.tokensPerSecond / 60f).coerceIn(0.05f, 1f), pal.d),
        )
        val rowH = coreH / gauges.size
        var gy = coreTop
        for (g in gauges) {
            val barY = gy + rowH * 0.30f
            val barH2 = rowH * 0.34f
            drawRect(g.c.copy(alpha = 0.16f), topLeft = Offset(gx, barY), size = Size(gw, barH2))
            val cells = 14; val cg = gw * 0.02f; val cw = (gw - cg * (cells - 1)) / cells
            val lit = (g.v.coerceIn(0f, 1f) * cells).roundToInt()
            for (i in 0 until lit) drawRect(g.c, topLeft = Offset(gx + i * (cw + cg), barY), size = Size(cw, barH2))
            drawLabel(measurer, g.k, gx, gy + rowH * 0.16f, pal.b, min(barT * 0.34f, rowH * 0.22f),
                alignRight = false, middle = false, bold = false, maxW = gw * 0.6f)
            drawLabel(measurer, "${(g.v * 100).roundToInt()}%", gx + gw, gy + rowH * 0.16f, g.c,
                min(barT * 0.34f, rowH * 0.22f), alignRight = true, middle = false, bold = true, maxW = gw * 0.35f)
            gy += rowH
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  PROGRAM: PLANETARY GEOSCAN — rotating planet, sweep, lifeform lock.
    // ════════════════════════════════════════════════════════════════
    private fun DrawScope.progPlanetScan(
        measurer: TextMeasurer, x: Float, y: Float, w: Float, h: Float,
        state: VizState, pal: Palette, barT: Float, t: Float,
    ) {
        drawLabel(measurer, "GEOSCAN \u00B7 ACTIVE", x, y, pal.a, barT * 0.4f,
            alignRight = false, middle = false, bold = true, maxW = w)
        val cx = x + w * 0.32f
        val cy = y + h * 0.42f
        val r = min(w * 0.26f, h * 0.32f)

        clipPath(Path().apply { addOval(Rect(cx - r, cy - r, cx + r, cy + r)) }) {
            drawRect(pal.b.copy(alpha = 0.9f), topLeft = Offset(cx - r, cy - r), size = Size(r * 2f, r * 2f))
            val rot = t * 0.25f
            for (i in -4..4) {
                val yy = cy + (i / 5f) * r + sin(rot + i) * 2f
                drawRect(pal.d.copy(alpha = 0.18f + 0.08f * ((i + 4) % 2)),
                    topLeft = Offset(cx - r, yy - r * 0.06f), size = Size(r * 2f, r * 0.12f))
            }
            val shadeX = cx + sin(t * 0.3f) * r * 0.3f
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0f), Color.Black.copy(alpha = 0.55f)),
                    startX = shadeX, endX = cx + r,
                ),
                topLeft = Offset(cx - r, cy - r), size = Size(r * 2f, r * 2f),
            )
        }
        drawCircle(pal.a.copy(alpha = 0.8f), r, Offset(cx, cy), style = Stroke(width = barT * 0.1f))

        val scanProg = (progClock / 3f) % 1f
        val scanY = cy - r + scanProg * r * 2f
        drawLine(YELLOW.copy(alpha = 0.85f), Offset(cx - r, scanY), Offset(cx + r, scanY), strokeWidth = barT * 0.08f)
        drawRect(YELLOW.copy(alpha = 0.10f), topLeft = Offset(cx - r, scanY), size = Size(r * 2f, r * 0.5f))

        val detected = progClock > 5f
        if (detected) {
            val bx = cx + r * 0.35f; val by = cy - r * 0.2f
            val flash = 0.5f + 0.5f * sin(t * 6f)
            drawCircle(RED.copy(alpha = 0.5f + 0.5f * flash), r * 0.10f, Offset(bx, by))
            val st = Stroke(width = barT * 0.07f)
            drawCircle(RED.copy(alpha = 0.9f), r * 0.20f, Offset(bx, by), style = st)
            drawLine(RED.copy(alpha = 0.9f), Offset(bx - r * 0.28f, by), Offset(bx - r * 0.12f, by), strokeWidth = barT * 0.07f)
            drawLine(RED.copy(alpha = 0.9f), Offset(bx + r * 0.12f, by), Offset(bx + r * 0.28f, by), strokeWidth = barT * 0.07f)
            drawLine(RED.copy(alpha = 0.9f), Offset(bx, by - r * 0.28f), Offset(bx, by - r * 0.12f), strokeWidth = barT * 0.07f)
            drawLine(RED.copy(alpha = 0.9f), Offset(bx, by + r * 0.12f), Offset(bx, by + r * 0.28f), strokeWidth = barT * 0.07f)
        }

        val tx = x + w * 0.62f
        val tw = x + w - tx
        val rows = listOf(
            Triple("CLASS", "M", pal.c),
            Triple("ATMOS", "N2-O2", pal.d),
            Triple("HYDRO", "71%", pal.d),
            Triple("GEO", "STABLE", pal.c),
            Triple("LIFE", if (detected) "DETECTED" else "SCAN...", if (detected) RED else pal.d),
            Triple("FORM", if (detected) "UNKNOWN" else "---", if (detected) RED else pal.b),
        )
        val lh = (h / (rows.size + 1)).coerceIn(barT * 0.7f, barT * 1.5f)
        val fs = min(lh * 0.42f, tw * 0.16f).coerceIn(9f, 26f)
        var yy = y + h * 0.12f
        for ((k, v, c) in rows) {
            drawLabel(measurer, k, tx, yy + lh / 2f, pal.b, fs, alignRight = false, middle = true, bold = false, maxW = tw * 0.5f)
            drawLabel(measurer, v, x + w, yy + lh / 2f, c, fs, alignRight = true, middle = true, bold = true, maxW = tw * 0.5f)
            yy += lh
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  PROGRAM: SUBSPACE ANOMALY — waveform / spectrum with roving spike.
    // ════════════════════════════════════════════════════════════════
    private fun DrawScope.progSubspace(
        measurer: TextMeasurer, x: Float, y: Float, w: Float, h: Float,
        state: VizState, pal: Palette, barT: Float, t: Float,
    ) {
        drawLabel(measurer, "SUBSPACE FLUX \u00B7 SCAN", x, y, pal.a, barT * 0.4f,
            alignRight = false, middle = false, bold = true, maxW = w)
        val gx = x; val gy = y + barT; val gw = w; val gh = h * 0.52f
        val midY = gy + gh / 2f

        for (i in 0..8) {
            val xx = gx + gw * i / 8f
            drawLine(pal.d.copy(alpha = 0.16f), Offset(xx, gy), Offset(xx, gy + gh), strokeWidth = 1f)
        }
        for (i in 0..4) {
            val yy = gy + gh * i / 4f
            drawLine(pal.d.copy(alpha = 0.16f), Offset(gx, yy), Offset(gx + gw, yy), strokeWidth = 1f)
        }
        drawLine(pal.b.copy(alpha = 0.3f), Offset(gx, midY), Offset(gx + gw, midY), strokeWidth = 1f)

        val amp = gh * 0.40f * (0.5f + 0.5f * state.inferenceLoad)
        val spikePos = (sin(t * 0.5f) * 0.5f + 0.5f)
        val path = Path()
        val n = 120
        for (i in 0..n) {
            val fx = i / n.toFloat()
            val xx = gx + fx * gw
            var yv = sin(fx * 18f + t * 3f) * 0.5f + sin(fx * 7f - t * 2f) * 0.3f + sin(fx * 31f + t * 5f) * 0.18f
            val d = fx - spikePos
            val spike = Math.exp((-(d * d) / 0.0016f).toDouble()).toFloat() * (1.2f + 0.6f * sin(t * 8f))
            yv += spike
            val yy = midY - yv * amp * 0.5f
            if (i == 0) path.moveTo(xx, yy) else path.lineTo(xx, yy)
        }
        drawPath(path, YELLOW.copy(alpha = 0.95f),
            style = Stroke(width = barT * 0.09f, join = StrokeJoin.Round))
        val sx = gx + spikePos * gw
        drawLine(RED.copy(alpha = 0.7f), Offset(sx, gy), Offset(sx, gy + gh), strokeWidth = barT * 0.05f)

        val ry0 = gy + gh + barT * 0.6f
        val tw = gw
        val peak = (40f + spikePos * 1200f).roundToInt()
        val rows = listOf(
            Triple("FREQ", "$peak THz", pal.c),
            Triple("FLUX", String.format("%.1f cochrane", 1.5f + state.inferenceLoad * 4f), pal.d),
            Triple("BAND", "THETA-9", pal.d),
            Triple("ANOMALY", "TYPE-IV", RED),
        )
        val lh = ((y + h - ry0) / rows.size).coerceIn(barT * 0.6f, barT * 1.3f)
        val fs = min(lh * 0.46f, tw * 0.5f * 0.14f).coerceIn(9f, 24f)
        var yy = ry0
        for ((k, v, c) in rows) {
            drawLabel(measurer, k, gx, yy + lh / 2f, pal.b, fs, alignRight = false, middle = true, bold = false, maxW = tw * 0.42f)
            drawLabel(measurer, v, gx + gw, yy + lh / 2f, c, fs, alignRight = true, middle = true, bold = true, maxW = tw * 0.5f)
            yy += lh
        }
    }

    // ── Elbow: filled L with large OUTER radius + concave INNER cut ──
    private fun DrawScope.drawElbow(
        x: Float, y: Float, w: Float, h: Float, barT: Float,
        outerR: Float, innerR: Float, top: Boolean, bg: Color, fill: Color,
    ) {
        // Solid body with one rounded outer corner.
        val body = if (top)
            RoundRect(Rect(x, y, x + w, y + h), topLeft = CornerRadius(outerR, outerR),
                topRight = CornerRadius.Zero, bottomRight = CornerRadius.Zero, bottomLeft = CornerRadius.Zero)
        else
            RoundRect(Rect(x, y, x + w, y + h), topLeft = CornerRadius.Zero,
                topRight = CornerRadius.Zero, bottomRight = CornerRadius.Zero, bottomLeft = CornerRadius(outerR, outerR))
        drawPath(Path().apply { addRoundRect(body) }, fill)

        // Concave interior cut, painted in the background color.
        val cut = if (top)
            RoundRect(Rect(x + barT, y + barT, x + w + 2f, y + h + 2f),
                topLeft = CornerRadius(innerR, innerR), topRight = CornerRadius.Zero,
                bottomRight = CornerRadius.Zero, bottomLeft = CornerRadius.Zero)
        else
            RoundRect(Rect(x + barT, y - 2f, x + w + 2f, y + h - barT),
                topLeft = CornerRadius.Zero, topRight = CornerRadius.Zero,
                bottomRight = CornerRadius.Zero, bottomLeft = CornerRadius(innerR, innerR))
        drawPath(Path().apply { addRoundRect(cut) }, bg)
    }

    // ── Horizontal bar terminating in a right-side half-pill cap ──
    private fun DrawScope.drawBarWithCap(x: Float, y: Float, w: Float, h: Float, color: Color) {
        val rr = RoundRect(
            Rect(x, y, x + w, y + h),
            topLeft = CornerRadius.Zero,
            topRight = CornerRadius(h / 2f, h / 2f),
            bottomRight = CornerRadius(h / 2f, h / 2f),
            bottomLeft = CornerRadius.Zero,
        )
        drawPath(Path().apply { addRoundRect(rr) }, color)
    }

    // ── Labeled bar-graph band: vertical cells rising with inference load ──
    private fun DrawScope.drawBarGraph(
        measurer: TextMeasurer, x: Float, y: Float, w: Float, h: Float,
        state: VizState, pal: Palette, barT: Float, dt: Float,
    ) {
        drawLabel(measurer, "INFERENCE LOAD", x, y, pal.b, barT * 0.42f,
            alignRight = false, middle = false, bold = true, maxW = w * 0.6f)
        val gy = y + barT * 0.7f
        val gh = h - barT * 0.7f
        bar += (state.inferenceLoad - bar) * (1f - Math.pow(0.05, dt.coerceAtMost(0.05f).toDouble()).toFloat())
        val cells = 16
        val cgap = w * 0.012f
        val cw = (w - cgap * (cells - 1)) / cells
        for (i in 0 until cells) {
            val frac = (i + 1) / cells.toFloat()
            val lit = frac <= bar + 1e-3f
            val stepCol = when {
                frac > 0.8f -> YELLOW
                frac > 0.5f -> pal.a
                else -> pal.c
            }
            val cx = x + i * (cw + cgap)
            drawRect(stepCol.copy(alpha = if (lit) 1f else 0.14f), topLeft = Offset(cx, gy), size = Size(cw, gh))
        }
        drawLabel(measurer, (bar * 100).roundToInt().toString().padStart(3, '0'), x + w, y, pal.d,
            barT * 0.42f, alignRight = true, middle = false, bold = true, maxW = w * 0.35f)
    }

    // ── Bracket-grouped readout rows (the data area) ──
    private fun DrawScope.drawReadout(
        measurer: TextMeasurer, x: Float, y: Float, w: Float, h: Float,
        state: VizState, pal: Palette, barT: Float,
    ) {
        val brW = barT * 0.32f
        val armLen = barT * 1.1f
        drawRect(pal.b, topLeft = Offset(x, y), size = Size(brW, h))                 // spine
        drawRect(pal.b, topLeft = Offset(x, y), size = Size(armLen, brW))            // top arm
        drawRect(pal.b, topLeft = Offset(x, y + h - brW), size = Size(armLen, brW))  // bottom arm

        val padL = x + brW + barT * 0.5f
        val right = x + w
        val innerW = right - padL
        val valW = innerW * 0.42f
        val keyRight = right - valW - barT * 0.4f

        val tempCol = when {
            state.batteryTemperature > 42f -> RED
            state.batteryTemperature > 35f -> YELLOW
            else -> pal.d
        }
        val rows = listOf(
            Triple("MODE", state.coordinatorMode.uppercase(), pal.c),
            Triple("TOK/S", state.tokensPerSecond.roundToInt().toString(), pal.d),
            Triple("QUEUE", state.queueDepth.toString(), pal.d),
            Triple("TEMP", "${state.batteryTemperature.roundToInt()}C", tempCol),
            Triple("POWER", "${state.batteryLevel}%" + (if (state.isCharging) "+" else ""),
                if (state.batteryLevel < 20 && !state.isCharging) RED else pal.d),
            Triple("NODES", state.peerCount.toString(), pal.c),
            Triple("STATUS", if (state.isHealthy) "NOMINAL" else "FAULT", if (state.isHealthy) pal.c else RED),
        )
        val lineH = (h / (rows.size + 0.5f)).coerceIn(barT * 0.55f, barT * 1.4f)
        val fs = min(min(lineH * 0.46f, innerW * 0.5f * 0.16f), barT * 0.42f).coerceIn(9f, 34f)
        var ry = y + lineH * 0.4f
        for ((k, v, c) in rows) {
            drawLabel(measurer, k, padL, ry + lineH / 2f, pal.b, fs, alignRight = false, middle = true, bold = false, maxW = keyRight - padL)
            drawLabel(measurer, v, right, ry + lineH / 2f, c, fs, alignRight = true, middle = true, bold = true, maxW = valW)
            ry += lineH
        }
    }

    /**
     * LCARS text: upper-case, lightly tracked, optionally right-aligned and
     * vertically centered. When [maxW] is given, the font shrinks so the text
     * never overflows its column (the bench-validated fit-to-width behavior).
     */
    private fun DrawScope.drawLabel(
        measurer: TextMeasurer, text: String, x: Float, y: Float, color: Color,
        sizePx: Float, alignRight: Boolean, middle: Boolean, bold: Boolean, maxW: Float = 0f,
    ) {
        val upper = text.uppercase()
        var fs = sizePx
        val weight = if (bold) FontWeight.Bold else FontWeight.Normal
        fun measure(px: Float) = measurer.measure(
            AnnotatedString(upper),
            TextStyle(color = color, fontSize = px.sp, fontFamily = FontFamily.SansSerif,
                fontWeight = weight, letterSpacing = (px * 0.04f).sp),
        )
        var laid = measure(fs)
        if (maxW > 0f) {
            var guard = 0
            while (laid.size.width > maxW && fs > 7f && guard++ < 24) {
                fs *= 0.92f
                laid = measure(fs)
            }
        }
        val tx = if (alignRight) x - laid.size.width else x
        val ty = if (middle) y - laid.size.height / 2f else y - laid.size.height
        drawText(laid, topLeft = Offset(tx, ty))
    }
}

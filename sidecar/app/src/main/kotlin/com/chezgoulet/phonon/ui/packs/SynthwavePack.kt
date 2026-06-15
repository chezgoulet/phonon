package com.chezgoulet.phonon.ui.packs

import androidx.compose.animation.core.withFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthwave / Outrun — retro-futurist neon visualization pack.
 * Pink sun, purple mountains, perspective grid, orbit rings, comet trails,
 * border glow, neon-tube display number. Energy-driven from VizState.
 */

object SynthwavePack : VisualizationPack {

    override val id = "synthwave"
    override val name = "Synthwave"
    override val description = "Retro-futurist neon: pink sun, purple mountains, perspective grid, orbit rings, and cassette-tape glow"
    override val author = "chezgoulet"
    override val version = "1.0.0"

    override val defaultConfig = mapOf(
        "sun_color" to "#FF007F", "grid_color" to "#00E5FF", "accent_color" to "#7C3AED",
        "rotation_speed" to "1.0", "star_count" to "45", "pulse_intensity" to "1.0",
    )

    // Scene state
    private var energy = 0f
    private var flashPhase = 0f
    private val trails = ArrayDeque<Trail>()
    private val starField = mutableListOf<Star>()
    private var starSeed = false
    private data class Star(val x: Float, val y: Float, val ba: Float, val sp: Float, val ph: Float)
    private data class Trail(val x: Float, val y: Float, val born: Float, val sx: Float, val sy: Float)

    override fun onActivate() { energy = 0f; flashPhase = 0f; trails.clear(); starSeed = false }

    @Composable
    override fun Render(state: VizState, modifier: Modifier) {
        val sunCol = parseHexColor(state.themeConfig["sun_color"] ?: "#FF007F")
        val gridCol = parseHexColor(state.themeConfig["grid_color"] ?: "#00E5FF")
        val accentCol = parseHexColor(state.themeConfig["accent_color"] ?: "#7C3AED")
        val speedMul = (state.themeConfig["rotation_speed"] ?: "1.0").toFloatOrNull() ?: 1f
        val pulseMul = (state.themeConfig["pulse_intensity"] ?: "1.0").toFloatOrNull() ?: 1f
        val starCount = ((state.themeConfig["star_count"] ?: "45").toFloatOrNull() ?: 45f).toInt().coerceIn(20, 80)
        val lowPower = state.lowPowerMode
        val measurer = rememberTextMeasurer()

        var tSec by remember { mutableFloatStateOf(0f) }
        var dtSec by remember { mutableFloatStateOf(0.016f) }
        var lastFrame by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) {
            while (true) {
                val now = withFrameNanos { it } / 1_000_000_000f
                dtSec = (now - lastFrame).coerceIn(0f, 0.05f); lastFrame = now; tSec = now
            }
        }

        Canvas(modifier = modifier.fillMaxSize()) {
            val t = tSec; val dt = dtSec
            val w = size.width; val h = size.height
            val horizonY = h * 0.20f; val sunCx = w / 2f

            // Energy smoothing across 0..1 from inferenceLoad, isProcessing, etc.
            val procBoost = if (state.isProcessing) 0.35f else 0f
            val target = if (lowPower) 0f else (procBoost + state.inferenceLoad * 0.5f + (state.queueDepth / 20f).coerceIn(0f, 1f) * 0.15f).coerceIn(0f, 1f)
            val k = 1f - 0.002f.pow(dt.coerceAtMost(0.05f))
            energy += (target - energy) * k; val e = energy

            // Flash at peak
            if (e > 0.95f && flashPhase < 1.5f) flashPhase += dt else if (e < 0.8f) flashPhase = 0f
            val flashT = flashPhase.coerceAtMost(0.5f)

            // Health, thermal, battery, charging modifiers
            val battDim = (state.batteryLevel / 100f).coerceIn(0.3f, 1f)
            val heatShift = when { state.batteryTemperature > 42f -> 0.6f; state.batteryTemperature > 35f -> 0.25f; else -> 0f }
            val heatSun = if (heatShift > 0f) blend(sunCol, Color(0xFFFF4500), heatShift * 0.5f) else sunCol
            val accentHeat = if (heatShift > 0f) blend(accentCol, Color(0xFFEF4444), heatShift * 0.5f) else accentCol
            val unhealthyWash = if (!state.isHealthy) 0.2f else 0f
            val chargingTint = if (state.isCharging) 0.12f else 0f
            val activeSun = if (e > 0.15f && heatShift <= 0f) blend(heatSun, Color(0xFFFF6B9D), (e - 0.15f) * 0.6f) else heatSun
            val gridActive = gridCol.copy(alpha = (0.35f + e * 0.45f) * battDim * if (unhealthyWash > 0f) 0.5f else 1f)

            drawGradient(w, h, horizonY, e, activeSun, lowPower, unhealthyWash)
            if (!lowPower) drawMountains(w, horizonY, unhealthyWash)
            val sunCy = horizonY + h * 0.08f
            drawSun(sunCx, sunCy, w, t, e, activeSun, lowPower, pulseMul, heatShift)
            drawGrid(w, h, horizonY, gridActive, e, lowPower, battDim)
            if (!starSeed) { seedStars(w, h, starCount); starSeed = true }
            drawStars(t, e, lowPower, battDim)
            drawTrails(e, ((e - 0.3f) / 0.3f * 0.8f).coerceIn(0f, 1f), t, dt, activeSun, w, h)
            if (!lowPower) drawOrbitRings(w, h, t, e, activeSun, gridCol, speedMul)
            drawBorderGlow(w, h, e, accentHeat, lowPower, pulseMul, unhealthyWash)

            val dn = state.displayNumber
            if (dn != null) drawDisplayNumber(measurer, dn, w, h, e, gridCol, state.displayNumberFlash)

            // Full-screen flash
            if (flashT > 0f) {
                val fa = if (flashT < 0.2f) flashT / 0.2f else (1f - (flashT - 0.2f) / 0.3f).coerceIn(0f, 1f)
                drawRect(blend(Color.White, heatSun, 0.3f).copy(alpha = fa * 0.7f))
            }
            // Unhealthy red wash
            if (unhealthyWash > 0f) drawRect(Color(0xFF400000).copy(alpha = unhealthyWash * (0.5f + 0.5f * sin(t * 4f))))
            // Charging horizon glow
            if (chargingTint > 0f) drawRect(
                brush = Brush.verticalGradient(listOf(Color(0xFF9ACD32).copy(alpha = 0f), Color(0xFF9ACD32).copy(alpha = chargingTint * 0.3f)), startY = horizonY, endY = horizonY + h * 0.08f),
                topLeft = Offset(0f, horizonY), size = Size(w, h * 0.08f))
        }
    }

    private fun DrawScope.drawGradient(w: Float, h: Float, hy: Float, e: Float, sun: Color, lp: Boolean, uw: Float) {
        val night = Color(0xFF0B0B2B); val horizon = Color(0xFF2D1B69); val hotBase = Color(0xFF26082E)
        val base = if (uw > 0f) Color(0xFF1E060A) else blend(night, hotBase, e * 0.4f)
        drawRect(brush = Brush.verticalGradient(listOf(base, blend(base, horizon, 0.6f), blend(horizon, Color.Black, 0.3f)), startY = 0f, endY = h))
    }

    private fun DrawScope.drawMountains(w: Float, hy: Float, uw: Float) {
        val baseMtn = if (uw > 0f) Color(0xFF2A0A1E) else Color(0xFF1A0A3E)
        data class Peak(val cx: Float, val peak: Float, val hw: Float)
        val peaks = listOf(Peak(0.15f, 0.45f, 0.14f), Peak(0.40f, 0.65f, 0.18f), Peak(0.72f, 0.50f, 0.16f), Peak(0.90f, 0.30f, 0.10f))
        for ((cxp, ph, hwp) in peaks) {
            val px = cxp * w; val py = hy; val peakY = py - ph * w * 0.22f; val hw = hwp * w
            val path = Path().apply { moveTo(px - hw, py); lineTo(px, peakY); lineTo(px + hw, py); close() }
            drawPath(path, color = blend(baseMtn, Color(0xFF2D1560), cxp * 0.15f))
        }
    }

    private fun DrawScope.drawSun(cx: Float, cy: Float, w: Float, t: Float, e: Float, sun: Color, lp: Boolean, pm: Float, hs: Float) {
        val sunW = w * 0.15f; val baseH = w * 0.22f
        val pulseH = baseH * (1f + (if (e > 0.3f) (e - 0.3f) / 0.7f else 0f) * 0.10f * pm)
        val height = pulseH * (1f + sin(t * (2f + e * 3f)) * 0.02f * pm); val steps = 24
        for (i in 0 until steps) {
            val frac = i / steps.toFloat(); val yOff = (frac - 0.5f) * height
            val bf = 1f - kotlin.math.abs(frac - 0.5f) * 1.4f; val bw = sunW * (0.5f + bf * 0.5f)
            val lum = when { frac < 0.35f -> 0.85f - frac * 0.4f; frac < 0.65f -> lerpF(0.7f, 0.4f, (frac - 0.35f) / 0.3f); else -> lerpF(0.4f, 0.1f, (frac - 0.55f).coerceIn(0f, 1f)) }
            val col = if (e > 0.7f && frac in 0.35f..0.65f) blend(sun, Color.White, (e - 0.7f) / 0.3f * 0.3f) else sun
            val alpha = (lum * (1f - hs * 0.3f) * if (lp) 0.4f else 1f).coerceIn(0.05f, 1f)
            drawLine(col.copy(alpha = alpha), Offset(cx - bw / 2, cy + yOff), Offset(cx + bw / 2, cy + yOff), strokeWidth = height / steps + 1f)
        }
        if (!lp) for (gf in listOf(0.30f, 0.42f, 0.55f, 0.68f)) {
            val bandY = cy + (gf - 0.5f) * height; val bw = sunW * (0.75f + (0.5f - kotlin.math.abs(gf - 0.5f)) * 0.3f)
            val ba = if (e > 0.3f) 0.4f + (e - 0.3f) / 0.7f * 0.35f else 0.2f
            drawLine(Color.White.copy(alpha = ba * pm), Offset(cx - bw / 2, bandY), Offset(cx + bw / 2, bandY), strokeWidth = 2f + e * 1.5f)
        }
    }

    private fun DrawScope.drawGrid(w: Float, h: Float, hy: Float, col: Color, e: Float, lp: Boolean, bd: Float) {
        val vpX = w / 2f; val vpY = hy + 2f; val hz = (if (lp) 6 else 12) + (e * 4).toInt()
        for (i in 1..hz) {
            val frac = i / hz.toFloat(); val y = vpY + frac * (h - vpY)
            val a2 = (1f - frac) * col.alpha * (1f + e * 0.3f)
            drawLine(col.copy(alpha = a2.coerceIn(0.01f, 1f)), Offset(0f, y), Offset(w, y), strokeWidth = 1f + e * 0.5f)
        }
        for (i in -5..5) {
            val a2 = (1f - kotlin.math.abs(i) * 0.12f) * col.alpha
            val angle = i * 0.12f; val dx = sin(angle) * w * 0.55f
            drawLine(col.copy(alpha = a2.coerceIn(0.01f, 1f)), Offset(vpX + dx, vpY), Offset(vpX + dx * 3f, h), strokeWidth = 0.8f)
        }
        if (!lp) { var sy = hy; while (sy < h) { drawLine(Color.White.copy(alpha = 0.06f * bd), Offset(0f, sy), Offset(w, sy), 1f); sy += 4f } }
    }

    private fun seedStars(w: Float, h: Float, count: Int) {
        val rng = Random(42); starField.clear()
        repeat(count) { starField.add(Star(rng.nextFloat() * w, rng.nextFloat() * h * 0.6f, 0.2f + rng.nextFloat() * 0.8f, 0.5f + rng.nextFloat() * 1.5f, rng.nextFloat() * PI.toFloat() * 2f)) }
    }

    private fun DrawScope.drawStars(t: Float, e: Float, lp: Boolean, bd: Float) {
        val visCount = if (lp) (starField.size * 0.5f).toInt() else starField.size
        for (i in 0 until visCount) {
            val st = starField[i]; val tw = 0.5f + 0.5f * sin(t * st.sp + st.ph)
            val bright = st.ba * (0.4f + 0.6f * tw) * bd
            drawCircle(Color(0xFFFFD700).copy(alpha = bright), 1.2f + st.ba * 0.8f, Offset(st.x, st.y))
            if (e > 0.3f && i < (e * 8).toInt()) {
                val ct = (e - 0.3f) / 0.7f * 0.5f; val tail = 12f * ct
                drawLine(Color(0xFFFFD700).copy(alpha = bright * ct * 0.5f), Offset(st.x, st.y),
                    Offset(st.x - tail * cos(t * 0.5f + st.ph), st.y + tail * sin(t * 0.7f + i * 0.5f)), strokeWidth = 1.2f)
            }
        }
    }

    private fun DrawScope.drawTrails(e: Float, ts: Float, t: Float, dt: Float, sun: Color, w: Float, h: Float) {
        if (e < 0.3f) { trails.clear(); return }
        val rate = (ts * 12).toInt().coerceAtLeast(1)
        if (Random.nextFloat() < rate * dt * 3f) trails.addLast(Trail(Random.nextFloat() * w, Random.nextFloat() * h * 0.5f, t, (Random.nextFloat() - 0.5f) * 60f, -40f - Random.nextFloat() * 80f))
        while (trails.isNotEmpty() && t - trails.first().born > 0.5f) trails.removeFirst()
        for (tr in trails) {
            val age = (t - tr.born) / 0.5f; val bx = tr.x + tr.sx * age; val by = tr.y + tr.sy * age; val a = 1f - age
            drawLine(sun.copy(alpha = a * 0.4f), Offset(bx, by), Offset(bx - tr.sx * age * 0.3f, by - tr.sy * age * 0.3f), strokeWidth = 30f * a * 0.15f)
            drawCircle(sun.copy(alpha = a * 0.5f), 1.5f * a + 0.3f, Offset(bx, by))
        }
    }

    private fun DrawScope.drawOrbitRings(w: Float, h: Float, t: Float, e: Float, sun: Color, grid: Color, sm: Float) {
        val cx = w / 2f; val cy = h * 0.60f; val tilt = 0.35f; val tc = cos(tilt); val ts = sin(tilt)
        val speed = 0.2f + e * 0.5f * sm
        val rings = listOf(Triple(0.40f * w, 0.20f * tc * w, 1f) to sun, Triple(0.28f * w, 0.14f * tc * w, -0.7f) to grid, Triple(0.52f * w, 0.26f * tc * w, -0.5f) to sun.copy(alpha = 0.6f))
        for ((spec, col) in rings) {
            val (rx, ry, dir) = spec; val phase = t * speed * dir * sm; val segs = 36; val path = Path()
            for (si in 0..segs) if (si % 3 != 0) {
                val a = (si / segs.toFloat()) * PI.toFloat() * 2f + phase
                val px = cx + cos(a) * rx; val py = cy + sin(a) * ry
                if (si == 0 || (si - 1) % 3 == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, col.copy(alpha = (0.25f + e * 0.35f).coerceAtMost(0.7f)), style = Stroke(width = (1.5f + e).coerceAtMost(3f), cap = StrokeCap.Round))
        }
    }

    private fun DrawScope.drawBorderGlow(w: Float, h: Float, e: Float, accent: Color, lp: Boolean, pm: Float, uw: Float) {
        val blen = if (lp) 30f else 40f; val th = 8f
        val pulse = if (e > 0.3f) 0.3f + (e - 0.3f) / 0.7f * 0.4f else 0f
        val a = (0.25f + pulse * pm + 0.15f * (0.5f + 0.5f * sin(System.currentTimeMillis() / (1000f / (if (uw > 0f) 8f else 5f))))).coerceIn(0f, 0.75f)
        val col = accent.copy(alpha = a); data class Br(val x: Float, val y: Float, val sx: Int, val sy: Int)
        for (cc in listOf(Br(0f, 0f, 1, 1), Br(w, 0f, -1, 1), Br(0f, h, 1, -1), Br(w, h, -1, -1))) {
            drawLine(col, Offset(cc.x + cc.sx * blen, cc.y), Offset(cc.x, cc.y), strokeWidth = th.toFloat())
            drawLine(col, Offset(cc.x, cc.y), Offset(cc.x, cc.y + cc.sy * blen), strokeWidth = th.toFloat())
        }
    }

    private fun DrawScope.drawDisplayNumber(m: TextMeasurer, num: Int, w: Float, h: Float, e: Float, grid: Color, flash: Boolean) {
        val s = num.toString().padStart(2, '0'); val px = w * 0.14f; val y = h * 0.82f; val glowR = px * 1.5f; val alpha = if (flash) 1f else 0.6f
        drawCircle(grid.copy(alpha = 0.08f * alpha), glowR + glowR * 0.6f, Offset(w / 2f, y))
        drawCircle(grid.copy(alpha = 0.15f * alpha), glowR + glowR * 0.3f, Offset(w / 2f, y))
        drawCircle(grid.copy(alpha = 0.25f * alpha), glowR, Offset(w / 2f, y))
        val result = m.measure(AnnotatedString(s), TextStyle(color = Color.White.copy(alpha = 0.9f * alpha), fontSize = px.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 8.sp))
        val tx = w / 2f - result.size.width / 2f; val ty = y - result.size.height / 2f
        drawText(result, topLeft = Offset(tx, ty))
        if (e > 0.3f) drawCircle(grid.copy(alpha = (e - 0.3f) / 0.7f * 0.2f * alpha), glowR * (0.5f + 0.5f * sin(System.currentTimeMillis() / 300.0).toFloat()), Offset(w / 2f, y), style = Stroke(width = 0.8f))
    }
}

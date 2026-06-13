package com.chezgoulet.phonon.ui.packs

import androidx.compose.animation.core.withFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.chezgoulet.phonon.ui.VisualizationPack
import com.chezgoulet.phonon.ui.VizState
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Bioluminescent Dreamscape — organic tidepool visualization pack.
 *
 * A calm, soothing alternative to the existing packs. Drifting motes of light,
 * kelp-like swaying tendrils, and concentric pulse waves evoke a moonlit
 * tidepool. Under load, the bioluminescence blooms — motes cluster and
 * brighten, tendrils sway faster, and the pool briefly flashes white-hot
 * during heavy inference.
 *
 * Low power mode: background + dim motes (no clustering) + 1 faint tendril.
 */
object BioluminescentPack : VisualizationPack {

    override val id = "bioluminescent"
    override val name = "Bioluminescent Dreamscape"
    override val description = "Drifting motes, swaying tendrils, and pulse waves that bloom with device activity — organic and calming"
    override val author = "chezgoulet"
    override val version = "0.1.0"

    override val defaultConfig = mapOf(
        "mote_count" to "30",
        "tendril_count" to "5",
        "glow_intensity" to "1.0",
        "pulse_frequency" to "1.0",
    )

    // ── Mutable scene state (object-scoped; pack is a singleton) ──
    private var energy = 0f
    private val pulses = ArrayDeque<Pulse>()
    private val blooms = ArrayDeque<Bloom>()
    private var lastPulse = 0f
    private var lastCascade = 0f
    private var moteSeeds: List<MoteSeed>? = null
    private var tendrilSeeds: List<TendrilSeed>? = null

    private data class Pulse(val born: Float, val x: Float, val y: Float, val hot: Float)
    private data class Bloom(val born: Float)
    private data class MoteSeed(val ax: Float, val ay: Float, val phase: Float, val sz: Float, val dx: Float, val dy: Float)
    private data class TendrilSeed(val anchorX: Float, val phase: Float)

    private data class Palette(
        val primary: Color,
        val accent: Color,
        val bgCenter: Color,
        val bg: Color,
    )

    override fun onActivate() {
        energy = 0f
        pulses.clear()
        blooms.clear()
        moteSeeds = null
        tendrilSeeds = null
        lastPulse = 0f
        lastCascade = 0f
    }

    private fun palette(
        e: Float,
        isHealthy: Boolean,
        isCharging: Boolean,
        batteryLevel: Int,
        batteryTemperature: Float,
    ): Palette {
        val idleP = Color(0xFF38F8C8); val idleA = Color(0xFF60F0FF)
        val hotP = Color(0xFF22E9A8); val hotA = Color(0xFFFFE066)
        val idleBgC = Color(0xFF0A1820); val idleBg = Color(0xFF040B0F)
        val hotBgC = Color(0xFF0F1E1A); val hotBg = Color(0xFF091514)

        // overheating → shift warm
        val tempT = ((batteryTemperature - 35f) / 10f).coerceIn(0f, 1f)
        val warmP = blend(idleP, Color(0xFFEAB308), tempT)
        val warmA = blend(idleA, Color(0xFFFF5A5A), tempT)
        val realP = if (batteryTemperature > 42f) blend(warmP, Color(0xFFEF4444), ((batteryTemperature - 42f) / 6f).coerceIn(0f, 1f)) else warmP
        val realA = if (batteryTemperature > 42f) blend(warmA, Color(0xFFEF4444), ((batteryTemperature - 42f) / 6f).coerceIn(0f, 1f)) else warmA

        // low battery → dim blue
        val battP: Color
        val battA: Color
        val battBg: Color
        val battBgC: Color
        if (batteryLevel < 20 && !isCharging) {
            val dim = 1f - (20f - batteryLevel) / 20f * 0.7f
            battP = Color(
                (0x1A / 255f) * dim, (0x4C / 255f) * dim, (0x78 / 255f) * dim, 1f,
            )
            battA = Color(
                (0x1A / 255f) * dim, (0x3A / 255f) * dim, (0x5C / 255f) * dim, 1f,
            )
            battBg = Color(0xFF020608)
            battBgC = Color(0xFF040810)
        } else {
            battP = realP; battA = realA; battBg = hotBg; battBgC = hotBgC
        }

        if (!isHealthy) {
            return Palette(
                primary = blend(battP, Color(0xFFEF4444), 0.6f),
                accent = Color(0xFFEF4444),
                bgCenter = Color(0xFF140608),
                bg = Color(0xFF0A0304),
            )
        }

        return Palette(
            primary = blend(battP, hotP, e),
            accent = blend(battA, hotA, e),
            bgCenter = blend(battBgC, hotBgC, e),
            bg = battBg,
        )
    }

    @Composable
    override fun Render(state: VizState, modifier: Modifier) {
        val moteCount = (state.themeConfig["mote_count"] ?: "30").toFloatOrNull()?.toInt()?.coerceIn(10, 60) ?: 30
        val tendrilCount = (state.themeConfig["tendril_count"] ?: "5").toFloatOrNull()?.toInt()?.coerceIn(1, 8) ?: 5
        val glowMod = (state.themeConfig["glow_intensity"] ?: "1.0").toFloatOrNull() ?: 1.0f
        val pulseFreq = (state.themeConfig["pulse_frequency"] ?: "1.0").toFloatOrNull() ?: 1.0f
        val lowPower = state.lowPowerMode

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
            val cx = w / 2f
            val cy = h / 2f

            // ── Energy smoothing ──
            val target = if (lowPower) 0f else (
                (if (state.isProcessing) 0.35f else 0f) +
                    state.inferenceLoad * 0.55f +
                    (state.queueDepth / 15f).coerceIn(0f, 1f) * 0.2f
                ).coerceIn(0f, 1f)
            val k = 1f - 0.001f.pow(dt.coerceAtMost(0.05f))
            energy += (target - energy) * k
            val e = energy

            val pal = palette(e, state.isHealthy, state.isCharging, state.batteryLevel, state.batteryTemperature)

            // ── Background (radial abyss gradient) ──
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(pal.bgCenter, pal.bg, blend(pal.bg, Color.Black, 0.5f)),
                    center = Offset(cx, cy),
                    radius = hypot(w, h) * 0.7f,
                )
            )

            // Charging edge glow
            if (state.isCharging && !lowPower) {
                val chgAlpha = 0.06f + 0.08f * (0.5f + 0.5f * sin(t * 2f))
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF22C55E).copy(alpha = 0f), Color(0xFF22C55E).copy(alpha = chgAlpha)),
                        center = Offset(cx, cy),
                        radius = hypot(w, h) * 0.9f,
                    ),
                )
            }

            // ── Pulse waves ──
            if (!lowPower) {
                val emitGap = when {
                    e > 0.7f -> lerpF(0.6f, 0.3f, pulseFreq)
                    e > 0.3f -> lerpF(3f, 1.5f, (e - 0.3f) / 0.4f) / pulseFreq
                    state.isProcessing -> lerpF(5f, 3f, e * 3f) / pulseFreq
                    else -> 8f
                }
                if (t - lastPulse > emitGap) {
                    val px = 0.15f + Random.nextFloat() * 0.7f
                    val py = 0.15f + Random.nextFloat() * 0.6f
                    pulses.addLast(Pulse(t, px * w, py * h, e))
                    lastPulse = t

                    // Cascade: second pulse from a different origin when energy is high
                    if (e > 0.6f && t - lastCascade > 0.5f) {
                        val cpx = 0.15f + Random.nextFloat() * 0.7f
                        val cpy = 0.15f + Random.nextFloat() * 0.6f
                        pulses.addLast(Pulse(t, cpx * w, cpy * h, e * 0.8f))
                        lastCascade = t
                    }
                }
                while (pulses.isNotEmpty() && t - pulses.first().born >= 1.2f) pulses.removeFirst()
                for (p in pulses) {
                    val age = (t - p.born) / 1.2f
                    val pr = lerpF(0f, w * 0.35f, age)
                    val pCol = if (e > 0.6f) blend(pal.accent, Color.White, 0.4f) else pal.accent
                    drawCircle(
                        pCol.copy(alpha = (1f - age) * (0.3f + 0.3f * p.hot) * glowMod),
                        pr,
                        Offset(p.x, p.y),
                        style = Stroke(width = lerpF(3f, 0.5f, age) * (0.6f + p.hot)),
                    )
                }

                // Deep queue sustained bloom ring
                if (state.queueDepth > 10) {
                    val qbAlpha = (state.queueDepth / 20f).coerceIn(0f, 1f) * 0.12f * glowMod
                    drawCircle(pal.accent.copy(alpha = qbAlpha), w * 0.25f, Offset(cx, cy), style = Stroke(width = 4f))
                }
            }

            // ── Tendrils ──
            val localTendrils = tendrilSeeds ?: run {
                val rng = Random(77)
                val count = if (lowPower) 1 else tendrilCount
                (0 until count).map {
                    TendrilSeed(0.1f + (it.toFloat() + 0.5f) / count * 0.8f, rng.nextFloat() * 6.28f)
                }.also { tendrilSeeds = it }
            }

            val tendrilAlpha = if (lowPower) 0.12f else lerpF(0.25f, 0.7f, e) * glowMod
            val swaySpeed = if (lowPower) 0.3f else lerpF(1f, 3f, e)
            val swayAmp = if (lowPower) 0.02f else lerpF(0.04f, 0.12f, e)
            val tendrilW = lerpF(2.5f, 6f, e) * (0.5f + 0.5f * glowMod)
            val segs = 12

            for (td in localTendrils) {
                val ax = td.anchorX * w
                val ay = h * 0.98f

                val tendrilPath = Path()
                for (si in 0..segs) {
                    val f = si / segs.toFloat()
                    val yy = ay - f * h * 0.7f
                    val sway1 = sin(t * swaySpeed * 0.6f + td.phase + f * 2.1f) * swayAmp * w
                    val sway2 = sin(t * swaySpeed * 1.4f + td.phase * 1.7f + f * 3.3f) * swayAmp * w * 0.5f
                    val xx = ax + sway1 + sway2
                    if (si == 0) tendrilPath.moveTo(xx, yy) else tendrilPath.lineTo(xx, yy)
                }

                // Main tendril line
                drawPath(
                    tendrilPath,
                    color = pal.primary.copy(alpha = tendrilAlpha),
                    style = Stroke(width = tendrilW, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                // Outer glow
                drawPath(
                    tendrilPath,
                    color = pal.primary.copy(alpha = tendrilAlpha * 0.25f),
                    style = Stroke(width = tendrilW + 6f * glowMod, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }

            // ── Motes (drifting glowing particles computed from seeds + time) ──
            val localSeeds = moteSeeds ?: run {
                val rng = Random(42)
                val count = if (lowPower) 10.coerceAtMost(moteCount) else moteCount
                List(count) {
                    MoteSeed(
                        ax = rng.nextFloat() * w,
                        ay = rng.nextFloat() * h,
                        phase = rng.nextFloat() * 6.28f,
                        sz = if (lowPower) 1.2f + rng.nextFloat() else 1.5f + rng.nextFloat() * 4f,
                        dx = rng.nextFloat() * w * 0.15f,
                        dy = rng.nextFloat() * h * 0.15f,
                    )
                }.also { moteSeeds = it }
            }

            val moteSpeed = if (lowPower) 0.3f else lerpF(0.3f, 1.2f, e)
            val moteMaxAlpha = if (lowPower) 0.4f else lerpF(0.5f, 0.9f, e) * glowMod
            val doClustering = e > 0.15f && !lowPower

            // Pre-compute mote positions for this frame
            val motePositions = localSeeds.map { seed ->
                val mx = seed.ax + sin(t * moteSpeed * 0.5f + seed.phase * 1.3f) * seed.dx
                val my = seed.ay + sin(t * moteSpeed * 0.4f + seed.phase * 0.7f) * seed.dy
                Offset(mx, my)
            }

            for (i in localSeeds.indices) {
                val seed = localSeeds[i]
                val pos = motePositions[i]
                val breath = 0.4f + 0.6f * (0.5f + 0.5f * sin(t * 1.2f + seed.phase))

                // Proximity clustering: mutual brightness from nearby motes
                var clusterBonus = 0f
                if (doClustering) {
                    for (q in motePositions.indices) {
                        if (q == i) continue
                        val dist = hypot(pos.x - motePositions[q].x, pos.y - motePositions[q].y)
                        if (dist < w * 0.12f) {
                            clusterBonus += (1f - dist / (w * 0.12f)) * 0.15f
                        }
                    }
                }

                val alpha = (moteMaxAlpha * breath + clusterBonus).coerceIn(0f, 0.95f)
                val sizeMult = if (lowPower) 1f else 1f + e * 0.5f + clusterBonus * 0.5f
                val drawSz = seed.sz * sizeMult

                if (alpha > 0.02f) {
                    val glowR = drawSz * 3f * glowMod
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(pal.primary.copy(alpha = alpha * 0.3f), pal.primary.copy(alpha = 0f)),
                            center = Offset(pos.x, pos.y),
                            radius = glowR,
                        ),
                        radius = glowR,
                        center = Offset(pos.x, pos.y),
                    )
                    drawCircle(pal.primary.copy(alpha = alpha), drawSz, Offset(pos.x, pos.y))
                }
            }

            // ── Core bloom (white-blue flash under heavy load) ──
            if (e > 0.6f && state.isProcessing && !lowPower) {
                if (blooms.isEmpty() || t - blooms.last().born > 0.5f) {
                    blooms.addLast(Bloom(t))
                }
            }
            while (blooms.isNotEmpty() && t - blooms.first().born >= 0.5f) blooms.removeFirst()
            for (bl in blooms) {
                val age = (t - bl.born) / 0.4f
                if (age > 1f) continue
                val br = w * 0.2f * age
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            blend(pal.accent, Color.White, 0.7f).copy(alpha = (1f - age) * 0.5f * glowMod),
                            Color.White.copy(alpha = (1f - age) * 0.3f * glowMod),
                            Color.White.copy(alpha = 0f),
                        ),
                        center = Offset(cx, cy),
                        radius = br,
                    ),
                    radius = br,
                    center = Offset(cx, cy),
                )
                drawCircle(Color.White.copy(alpha = (1f - age) * 0.7f), lerpF(2f, br * 0.3f, age), Offset(cx, cy))
            }

            // ── Unhealthy red wash ──
            if (!state.isHealthy) {
                val pulseAlpha = 0.08f + 0.12f * (0.5f + 0.5f * sin(t * 3f))
                drawRect(Color(0xFFFF4646).copy(alpha = pulseAlpha))
            }
        }
    }
}

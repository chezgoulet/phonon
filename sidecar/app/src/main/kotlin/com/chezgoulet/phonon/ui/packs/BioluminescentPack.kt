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
 * Bioluminescent Dreamscape — organic underwater ecosystem visualization pack.
 *
 * An abyssal tidepool: kelp fronds sway in a deep current, caustic light filters
 * through the depths, three classes of plankton drift and pulse, and bioluminescent
 * ripples respond to device activity — all within a living, submerged ecosystem.
 *
 * Three plankton tiers: nano (distant tiny dots), micro (soft glowing motes with
 * trailing light), and jellyfish (large complex glows with trailing tentacles).
 * Each has different drift behavior and rendering depth.
 *
 * Kelp fronds use bezier-approximated paths with multi-frequency sway, side branches,
 * and bioluminescent spore glows clustered along the frond for an organic, "alive" feel.
 *
 * Pulse ripples use gradient-based soft rings instead of hard stroke arcs, with
 * trailing micro-ripples for a liquid, blurry effect.
 *
 * Low power mode: background + dim nano plankton + 1 faint kelp frond.
 */
object BioluminescentPack : VisualizationPack {

    override val id = "bioluminescent"
    override val name = "Bioluminescent Dreamscape"
    override val description = "An abyssal tidepool responding to device activity — organic and submerged"
    override val author = "chezgoulet"
    override val version = "0.2.0"

    override val defaultConfig = mapOf(
        "tendril_count" to "6",
        "glow_intensity" to "1.0",
        "pulse_frequency" to "1.0",
    )

    // ── Mutable scene state (object-scoped; pack is a singleton) ──
    private var energy = 0f
    private val pulses = ArrayDeque<Pulse>()
    private val blooms = ArrayDeque<Bloom>()
    private var lastPulse = 0f
    private var lastCascade = 0f
    private var plankton: List<PlanktonSeed>? = null
    private var kelp: List<KelpSeed>? = null
    private var caustics: List<CausticSeed>? = null
    private var depthDust: List<DustSeed>? = null

    private data class Pulse(val born: Float, val x: Float, val y: Float, val hot: Float)
    private data class Bloom(val born: Float)
    private data class CausticSeed(val x: Float, val y: Float, val phase: Float, val speed: Float, val sz: Float, val wobble: Float, val wobbleSpeed: Float)
    private data class DustSeed(val x: Float, val y: Float, val phase: Float, val sz: Float, val depth: Float, val dx: Float, val dy: Float)
    private data class PlanktonSeed(
        val type: String,
        val ax: Float,
        val ay: Float,
        val phase: Float,
        val sz: Float,
        val depth: Float,
        val driftX: Float,
        val driftY: Float,
        val trailLen: Float = 0f,
        val pulsePhase: Float = 0f,
        val tentacleCount: Int = 0,
    )
    private data class KelpSeed(
        val anchorX: Float,
        val phase: Float,
        val restTipX: Float,
        val restMidX: Float,
        val freq1: Float,
        val freq2: Float,
        val freq3: Float,
        val amp1: Float,
        val amp2: Float,
        val amp3: Float,
        val phase1: Float,
        val phase2: Float,
        val phase3: Float,
        val hasBranch: Boolean,
        val branchSide: Int,
        val branchH: Float,
        val branchLen: Float,
        val sporeH: List<Float>,
    )

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
        plankton = null
        kelp = null
        caustics = null
        depthDust = null
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

        val tempT = ((batteryTemperature - 35f) / 10f).coerceIn(0f, 1f)
        val warmP = blend(idleP, Color(0xFFEAB308), tempT)
        val warmA = blend(idleA, Color(0xFFFF5A5A), tempT)
        val realP = if (batteryTemperature > 42f) blend(warmP, Color(0xFFEF4444), ((batteryTemperature - 42f) / 6f).coerceIn(0f, 1f)) else warmP
        val realA = if (batteryTemperature > 42f) blend(warmA, Color(0xFFEF4444), ((batteryTemperature - 42f) / 6f).coerceIn(0f, 1f)) else warmA

        var battP = realP; var battA = realA; var battBg = hotBg; var battBgC = hotBgC
        if (batteryLevel < 20 && !isCharging) {
            val dim = 1f - (20f - batteryLevel) / 20f * 0.7f
            battP = Color(0x1A * dim, 0x4C * dim, 0x78 * dim, 1f)
            battA = Color(0x1A * dim, 0x3A * dim, 0x5C * dim, 1f)
            battBg = Color(0xFF020608); battBgC = Color(0xFF040810)
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
        val tendrilCount = (state.themeConfig["tendril_count"] ?: "6").toFloatOrNull()?.toInt()?.coerceIn(1, 8) ?: 6
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
            val rMax = hypot(w, h)

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

            // ═══ LAYER 1: THE ABYSS ═══
            // 1a. Deep radial gradient
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(pal.bgCenter, pal.bg, blend(pal.bg, Color.Black, 0.3f), Color.Black),
                    center = Offset(cx, cy * 0.9f),
                    radius = rMax * 0.8f,
                )
            )

            // 1b. Surface shimmer
            if (!lowPower) {
                val sf = listOf(
                    blend(pal.primary, Color(0xFF82FFF0), 0.3f).copy(alpha = 0.03f + 0.03f * (0.5f + 0.5f * sin(t * 0.7f))),
                    pal.bgCenter.copy(alpha = 0.02f),
                    pal.bgCenter.copy(alpha = 0f),
                )
                drawRect(brush = Brush.verticalGradient(sf), topLeft = Offset.Zero, size = size.copy(height = h * 0.35f))
            }

            // 1c. Caustic patches
            val localCaustics = caustics ?: run {
                val rng = Random(133)
                List(6) {
                    CausticSeed(
                        rng.nextFloat() * w, 0.1f + rng.nextFloat() * 0.6f * h,
                        rng.nextFloat() * 6.28f, 0.3f + rng.nextFloat() * 0.5f,
                        0.2f + rng.nextFloat() * 0.3f,
                        rng.nextFloat() * 6.28f, 0.2f + rng.nextFloat() * 0.4f,
                    )
                }.also { caustics = it }
            }
            if (!lowPower) {
                for (c in localCaustics) {
                    val cx2 = c.x + sin(t * c.speed * 0.15f + c.phase) * w * 0.15f
                    val cy2 = c.y + sin(t * c.speed * 0.1f + c.phase * 1.3f) * h * 0.1f
                    val r = rMax * c.sz * (1f + 0.1f * sin(t * c.wobbleSpeed + c.wobble))
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(
                                blend(pal.primary, Color(0xFFC8FFFF), 0.5f).copy(alpha = 0.03f * glowMod),
                                pal.primary.copy(alpha = 0.015f * glowMod),
                                pal.primary.copy(alpha = 0f),
                            ),
                            center = Offset(cx2, cy2),
                            radius = r,
                        ),
                        radius = r,
                        center = Offset(cx2, cy2),
                    )
                }
            }

            // 1d. Depth dust
            val localDust = depthDust ?: run {
                val rng = Random(211)
                List(45) {
                    DustSeed(
                        rng.nextFloat() * w, rng.nextFloat() * h,
                        rng.nextFloat() * 6.28f,
                        0.6f + rng.nextFloat() * 1.3f,
                        rng.nextFloat(),
                        rng.nextFloat() * w * 0.08f,
                        rng.nextFloat() * h * 0.06f,
                    )
                }.also { depthDust = it }
            }
            for (d in localDust) {
                val dx = d.x + sin(t * (0.12f + d.depth * 0.2f) + d.phase * 1.1f) * d.dx
                val dy = d.y + sin(t * (0.1f + d.depth * 0.15f) + d.phase * 0.8f) * d.dy
                val dustAlpha = (0.2f + 0.3f * (1f - d.depth)) * glowMod
                drawCircle(pal.primary.copy(alpha = dustAlpha), d.sz, Offset(dx, dy))
            }

            // ═══ LAYER 2: RIPPLES (soft pulse waves) ═══
            if (!lowPower) {
                val emitGap = when {
                    e > 0.7f -> lerp(0.6f, 0.3f, pulseFreq)
                    e > 0.3f -> lerp(3f, 1.5f, (e - 0.3f) / 0.4f) / pulseFreq
                    state.isProcessing -> lerp(5f, 3f, e * 3f) / pulseFreq
                    else -> 8f
                }
                if (t - lastPulse > emitGap) {
                    val px = 0.15f + Random.nextFloat() * 0.7f
                    val py = 0.1f + Random.nextFloat() * 0.5f
                    pulses.addLast(Pulse(t, px * w, py * h, e))
                    lastPulse = t
                    if (e > 0.6f && t - lastCascade > 0.5f) {
                        pulses.addLast(
                            Pulse(
                                t, (0.15f + Random.nextFloat() * 0.7f) * w,
                                (0.1f + Random.nextFloat() * 0.5f) * h, e * 0.7f
                            )
                        )
                        lastCascade = t
                    }
                }
                while (pulses.isNotEmpty() && t - pulses.first().born >= 1.8f) pulses.removeFirst()
                for (p in pulses) {
                    val age = (t - p.born) / 1.8f
                    val rad = lerp(w * 0.02f, w * 0.4f, age)
                    val a = (1f - age) * (0.2f + 0.25f * p.hot) * glowMod
                    val col = blend(pal.primary, pal.accent, p.hot)
                    // 3-layer gradient ring for blur
                    for (ri in 0..2) {
                        val rr = rad + ri * w * 0.015f
                        val ra = a * (1f - ri * 0.3f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(col.copy(alpha = 0f), col.copy(alpha = ra), col.copy(alpha = ra), col.copy(alpha = 0f)),
                                center = Offset(p.x, p.y),
                                radius = rr + w * 0.025f,
                            ),
                            radius = rr + w * 0.025f,
                            center = Offset(p.x, p.y),
                        )
                    }
                    // Micro-ripple tail
                    if (age > 0.4f && age < 0.9f && p.hot > 0.3f) {
                        val mAge = (age - 0.4f) / 0.5f
                        val mRad = rad + w * 0.04f
                        val mAlpha = (1f - mAge) * 0.08f * p.hot * glowMod
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(col.copy(alpha = 0f), col.copy(alpha = mAlpha), col.copy(alpha = mAlpha), col.copy(alpha = 0f)),
                                center = Offset(p.x, p.y),
                                radius = mRad + w * 0.008f,
                            ),
                            radius = mRad + w * 0.008f,
                            center = Offset(p.x, p.y),
                        )
                    }
                }
                // Deep queue sustained bloom ring
                if (state.queueDepth > 10) {
                    val qbAlpha = (state.queueDepth / 20f).coerceIn(0f, 1f) * 0.08f * glowMod
                    val qbRad = w * 0.22f
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(pal.accent.copy(alpha = 0f), pal.accent.copy(alpha = qbAlpha), pal.accent.copy(alpha = qbAlpha), pal.accent.copy(alpha = 0f)),
                            center = Offset(cx, cy),
                            radius = qbRad + w * 0.02f,
                        ),
                        radius = qbRad + w * 0.02f,
                        center = Offset(cx, cy),
                    )
                }
            }

            // ═══ LAYER 3: KELP FRONDS ═══
            val localKelp = kelp ?: run {
                val rng = Random(77)
                val count = if (lowPower) 1 else tendrilCount
                List(count) {
                    KelpSeed(
                        anchorX = 0.06f + (it + 0.5f) / count * 0.88f,
                        phase = rng.nextFloat() * 6.28f,
                        restTipX = (rng.nextFloat() - 0.5f) * 0.12f,
                        restMidX = (rng.nextFloat() - 0.5f) * 0.08f,
                        freq1 = 0.5f + rng.nextFloat() * 0.3f,
                        freq2 = 0.8f + rng.nextFloat() * 0.4f,
                        freq3 = 1.2f + rng.nextFloat() * 0.6f,
                        amp1 = 1f,
                        amp2 = 0.5f + rng.nextFloat() * 0.3f,
                        amp3 = 0.2f + rng.nextFloat() * 0.2f,
                        phase1 = rng.nextFloat() * 6.28f,
                        phase2 = rng.nextFloat() * 6.28f,
                        phase3 = rng.nextFloat() * 6.28f,
                        hasBranch = rng.nextFloat() > 0.3f,
                        branchSide = if (rng.nextFloat() > 0.5f) 1 else -1,
                        branchH = 0.35f + rng.nextFloat() * 0.2f,
                        branchLen = 0.15f + rng.nextFloat() * 0.2f,
                        sporeH = List(3 + rng.nextInt(4)) { 0.1f + rng.nextFloat() * 0.75f },
                    )
                }.also { kelp = it }
            }
            if (!lowPower) {
                val swaySpeed = lerp(0.5f, 2.5f, e)
                val swayAmp = lerp(0.03f, 0.08f, e)
                val frondAlpha = lerp(0.25f, 0.6f, e) * glowMod
                val segs = 20

                for (k in localKelp) {
                    val ax = k.anchorX * w
                    val ay = h + 5f
                    val points = mutableListOf<Offset>()

                    for (si in 0..segs) {
                        val f = si / segs.toFloat()
                        val yy = ay - f.pow(0.85f) * h * 0.72f
                        val s1 = sin(t * swaySpeed * k.freq1 + k.phase1 + f * 1.7f) * swayAmp * w * k.amp1
                        val s2 = sin(t * swaySpeed * k.freq2 + k.phase2 + f * 3.1f) * swayAmp * w * k.amp2
                        val s3 = sin(t * swaySpeed * k.freq3 + k.phase3 + f * 4.7f) * swayAmp * w * k.amp3
                        val restDrift = if (f < 0.3f) 0f else
                            lerp(0f, k.restMidX, ((f - 0.3f) / 0.2f).coerceIn(0f, 1f)) * w +
                                if (f > 0.5f) lerp(0f, k.restTipX, ((f - 0.5f) / 0.3f).coerceIn(0f, 1f)) * w else 0f
                        val xx = ax + restDrift + s1 + s2 + s3
                        points.add(Offset(xx, yy))
                    }

                    // 3-layer soft rendering
                    val path = Path().apply {
                        for (pi in points.indices) {
                            if (pi == 0) moveTo(points[pi].x, points[pi].y)
                            else lineTo(points[pi].x, points[pi].y)
                        }
                    }
                    val baseW = lerp(12f, 2f, 1f) * (1f + e * 0.5f)
                    drawPath(path, pal.primary.copy(alpha = frondAlpha * 0.12f),
                        style = Stroke(width = baseW, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path, pal.primary.copy(alpha = frondAlpha * 0.35f),
                        style = Stroke(width = lerp(6f, 1.5f, 1f) * (1f + e * 0.3f), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path, blend(pal.primary, pal.accent, 0.2f).copy(alpha = frondAlpha * 0.85f),
                        style = Stroke(width = lerp(2.5f, 0.8f, 1f) * (1f + e * 0.2f), cap = StrokeCap.Round, join = StrokeJoin.Round))

                    // Branch frond
                    if (k.hasBranch && points.isNotEmpty()) {
                        val bi = (k.branchH * segs).toInt().coerceAtMost(points.lastIndex)
                        val bp = points[bi]
                        val bSegs = 8
                        val branchPath = Path()
                        for (si in 0..bSegs) {
                            val f = si / bSegs.toFloat()
                            val yy = bp.y - f.pow(0.8f) * k.branchLen * h * 0.6f
                            val s1b = sin(t * swaySpeed * k.freq1 * 0.8f + k.phase1 + f * 1.7f) * swayAmp * w * k.amp1 * 0.7f
                            val s2b = sin(t * swaySpeed * k.freq2 * 1.1f + k.phase2 + f * 2.5f) * swayAmp * w * k.amp2 * 0.5f
                            val xx = bp.x + k.branchSide * f * w * 0.06f + s1b + s2b
                            if (si == 0) branchPath.moveTo(xx, yy) else branchPath.lineTo(xx, yy)
                        }
                        drawPath(branchPath, pal.primary.copy(alpha = frondAlpha * 0.25f),
                            style = Stroke(width = lerp(4f, 1f, 1f) * (1f + e * 0.2f)))
                        drawPath(branchPath, pal.primary.copy(alpha = frondAlpha * 0.6f),
                            style = Stroke(width = lerp(1.5f, 0.5f, 1f)))
                    }

                    // Spore glow clusters along the frond
                    for (sh in k.sporeH) {
                        val pi = (sh * segs).toInt().coerceAtMost(points.lastIndex)
                        val sp = points[pi]
                        val sBreathe = 0.3f + 0.7f * (0.5f + 0.5f * sin(t * 1.8f + k.phase + sp.y * 0.1f))
                        val sGlowR = 3f + 4f * sBreathe
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(pal.accent.copy(alpha = 0.5f * sBreathe * glowMod), pal.accent.copy(alpha = 0f)),
                                center = Offset(sp.x, sp.y),
                                radius = sGlowR,
                            ),
                            radius = sGlowR,
                            center = Offset(sp.x, sp.y),
                        )
                        drawCircle(blend(pal.accent, Color.White, 0.5f).copy(alpha = 0.7f * sBreathe),
                            0.8f + 0.5f * sBreathe, Offset(sp.x, sp.y))
                    }
                }
            }

            // ═══ LAYER 4: PLANKTON (three classes) ═══
            val localPlankton = plankton ?: run {
                val rng = Random(42)
                val p = mutableListOf<PlanktonSeed>()
                // Nano (distant)
                for (i in 0 until 25) {
                    p.add(PlanktonSeed("nano", rng.nextFloat() * w, rng.nextFloat() * h,
                        rng.nextFloat() * 6.28f, 0.7f + rng.nextFloat() * 1f,
                        depth = 0.1f + rng.nextFloat() * 0.2f,
                        driftX = (rng.nextFloat() - 0.5f) * 0.4f, driftY = (rng.nextFloat() - 0.5f) * 0.3f))
                }
                // Micro (mid)
                for (i in 0 until 14) {
                    p.add(PlanktonSeed("micro", rng.nextFloat() * w, rng.nextFloat() * h,
                        rng.nextFloat() * 6.28f, 1.5f + rng.nextFloat() * 2.5f,
                        depth = 0.3f + rng.nextFloat() * 0.3f,
                        driftX = (rng.nextFloat() - 0.3f) * 0.5f, driftY = (rng.nextFloat() - 0.5f) * 0.4f,
                        trailLen = 0.3f + rng.nextFloat() * 0.4f))
                }
                // Macro (jellyfish)
                for (i in 0 until 4) {
                    p.add(PlanktonSeed("jelly", 0.15f + rng.nextFloat() * 0.7f * w, 0.2f + rng.nextFloat() * 0.6f * h,
                        rng.nextFloat() * 6.28f, 6f + rng.nextFloat() * 8f,
                        depth = 0.5f + rng.nextFloat() * 0.2f,
                        driftX = (rng.nextFloat() - 0.2f) * 0.3f, driftY = -0.2f - rng.nextFloat() * 0.3f,
                        pulsePhase = rng.nextFloat() * 6.28f,
                        tentacleCount = 4 + rng.nextInt(3)))
                }
                p.also { plankton = it }
            }

            val currentDirX = 0.3f
            val currentDirY = -0.05f
            val plankSpeed = if (lowPower) 0.2f else lerp(0.3f, 1.2f, e)

            val plankPos = localPlankton.map { p ->
                Offset(
                    p.ax + sin(t * plankSpeed * 0.4f + p.phase * 1.3f) * p.driftX * w * 0.1f + t * currentDirX * w * 0.04f,
                    p.ay + sin(t * plankSpeed * 0.35f + p.phase * 0.9f) * p.driftY * h * 0.1f + t * currentDirY * h * 0.02f,
                )
            }

            for (i in localPlankton.indices) {
                val p = localPlankton[i]
                val pos = plankPos[i]
                val breathe = 0.3f + 0.7f * (0.5f + 0.5f * sin(t * 1.5f + p.phase))

                when (p.type) {
                    "nano" -> {
                        val na = 0.06f * breathe * glowMod * (1f + e * 0.5f)
                        drawCircle(pal.primary.copy(alpha = na), p.sz, pos)
                    }
                    "micro" -> {
                        val ma = (0.4f * breathe * glowMod * (0.4f + e * 0.6f)).coerceIn(0f, 0.8f)
                        val szM = p.sz * (1f + e * 0.3f)
                        val glowR = szM * 3.5f * glowMod
                        // Trailing light
                        val tDirX = cos(t * plankSpeed * 0.4f + p.phase * 1.3f) * p.driftX
                        val tDirY = cos(t * plankSpeed * 0.35f + p.phase * 0.9f) * p.driftY
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(pal.primary.copy(alpha = ma * 0.3f), pal.primary.copy(alpha = 0f)),
                                center = pos - Offset(tDirX * p.trailLen * 60f, tDirY * p.trailLen * 60f),
                                radius = szM * 0.8f,
                            ),
                            radius = szM * 0.8f,
                            center = pos - Offset(tDirX * p.trailLen * 60f, tDirY * p.trailLen * 60f),
                        )
                        // Soft glow
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(pal.primary.copy(alpha = ma * 0.25f), pal.primary.copy(alpha = 0f)),
                                center = pos,
                                radius = glowR,
                            ),
                            radius = glowR,
                            center = pos,
                        )
                        // Core
                        drawCircle(
                            blend(pal.primary, Color(0xFFC8FFF0), 0.3f).copy(alpha = ma * 0.8f),
                            szM, pos,
                        )
                    }
                    "jelly" -> if (!lowPower) {
                        val pulse = 1f + 0.15f * sin(t * 2.5f + p.pulsePhase)
                        val ja = (0.3f * breathe * glowMod * (0.3f + e * 0.7f)).coerceIn(0f, 0.6f)
                        val szJ = p.sz * pulse
                        // Outer glow
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(pal.primary.copy(alpha = ja * 0.3f), pal.accent.copy(alpha = ja * 0.12f), pal.accent.copy(alpha = 0f)),
                                center = pos,
                                radius = szJ * 2f,
                            ),
                            radius = szJ * 2f,
                            center = pos,
                        )
                        // Body dome
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(
                                    blend(pal.primary, Color.White, 0.4f).copy(alpha = ja * 0.5f),
                                    pal.primary.copy(alpha = ja * 0.15f),
                                    pal.primary.copy(alpha = 0f),
                                ),
                                center = pos - Offset(0f, szJ * 0.2f),
                                radius = szJ,
                            ),
                            radius = szJ,
                            center = pos,
                        )
                        // Tentacles
                        val tentLen = szJ * (2f + 0.5f * sin(t * 1.8f + p.pulsePhase))
                        for (ti in 0 until p.tentacleCount) {
                            val ta = (ti.toFloat() / p.tentacleCount) * 6.28f + sin(t * 0.6f + p.phase + ti) * 0.5f
                            val tx = cos(ta) * szJ * 0.5f
                            val ty = sin(ta) * szJ * 0.5f
                            val tentPath = Path()
                            for (tsi in 0..6) {
                                val f = tsi / 6f
                                val yt = pos.y + kotlin.math.abs(ty) + f * tentLen
                                val xt = pos.x + tx + sin(t * 2f + p.phase + ti + f * 2.5f) * szJ * 0.15f * (1f - f)
                                if (tsi == 0) tentPath.moveTo(xt, pos.y + kotlin.math.abs(ty))
                                else tentPath.lineTo(xt, yt)
                            }
                            drawPath(tentPath, pal.primary.copy(alpha = ja * 0.2f),
                                style = Stroke(width = 0.8f, cap = StrokeCap.Round))
                        }
                    }
                }
            }

            // Proximity glow enhancement
            if (e > 0.2f && !lowPower) {
                val microIndices = localPlankton.indices.filter { localPlankton[it].type != "nano" }
                for (mi in microIndices.indices) {
                    for (mj in mi + 1 until microIndices.size) {
                        val ii = microIndices[mi]; val jj = microIndices[mj]
                        val dist = hypot(plankPos[ii].x - plankPos[jj].x, plankPos[ii].y - plankPos[jj].y)
                        if (dist < w * 0.15f) {
                            val bAlpha = (1f - dist / (w * 0.15f)) * 0.15f * glowMod
                            val midX = (plankPos[ii].x + plankPos[jj].x) / 2f
                            val midY = (plankPos[ii].y + plankPos[jj].y) / 2f
                            drawCircle(
                                brush = Brush.radialGradient(
                                    listOf(pal.accent.copy(alpha = bAlpha), pal.accent.copy(alpha = 0f)),
                                    center = Offset(midX, midY),
                                    radius = w * 0.04f,
                                ),
                                radius = w * 0.04f,
                                center = Offset(midX, midY),
                            )
                        }
                    }
                }
            }

            // ═══ LAYER 5: CORE BLOOM (jellyfish flash) ═══
            if (e > 0.6f && state.isProcessing && !lowPower) {
                if (blooms.isEmpty() || t - blooms.last().born > 0.8f) {
                    blooms.addLast(Bloom(t))
                }
            }
            while (blooms.isNotEmpty() && t - blooms.first().born >= 0.8f) blooms.removeFirst()
            for (bl in blooms) {
                val age = (t - bl.born) / 0.6f
                if (age > 1f) continue
                val br = w * 0.08f + w * 0.22f * age
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            blend(pal.accent, Color.White, 0.6f).copy(alpha = (1f - age) * 0.3f * glowMod),
                            pal.accent.copy(alpha = (1f - age) * 0.08f * glowMod),
                            pal.accent.copy(alpha = 0f),
                        ),
                        center = Offset(cx, cy),
                        radius = br,
                    ),
                    radius = br,
                    center = Offset(cx, cy),
                )
                // Radiating tentacle rays
                for (ri in 0 until 10) {
                    val ra = (ri / 10f) * 6.28f + sin(bl.born * 3f + ri * 0.7f) * 0.3f
                    val rayPath = Path().apply {
                        moveTo(cx + cos(ra) * w * 0.01f, cy + sin(ra) * h * 0.01f)
                        lineTo(
                            cx + cos(ra) * br * (0.5f + 0.5f * sin(t * 3f + ri)),
                            cy + sin(ra) * br * (0.5f + 0.5f * sin(t * 3f + ri)),
                        )
                    }
                    drawPath(rayPath, pal.accent.copy(alpha = (1f - age) * 0.06f * glowMod),
                        style = Stroke(width = 0.6f))
                }
                drawCircle(Color.White.copy(alpha = (1f - age) * 0.6f),
                    lerp(4f, br * 0.2f, age), Offset(cx, cy))
            }

            // ═══ LAYER 6: OVERLAYS ═══
            // Charging
            if (state.isCharging && !lowPower) {
                val chgAlpha = 0.04f + 0.06f * (0.5f + 0.5f * sin(t * 2f))
                drawRect(
                    brush = Brush.radialGradient(
                        listOf(Color(0xFF22C55E).copy(alpha = 0f), Color(0xFF22C55E).copy(alpha = chgAlpha)),
                        center = Offset(cx, cy),
                        radius = rMax * 0.9f,
                    ),
                )
            }
            // Unhealthy
            if (!state.isHealthy) {
                val pulseAlpha = 0.08f + 0.12f * (0.5f + 0.5f * sin(t * 3f))
                drawRect(Color(0xFFFF4646).copy(alpha = pulseAlpha))
            }
        }
    }

    // ── Utility helpers ──

    private fun DrawScope.lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun blend(a: Color, b: Color, t: Float): Color {
        return Color(
            a.red + (b.red - a.red) * t,
            a.green + (b.green - a.green) * t,
            a.blue + (b.blue - a.blue) * t,
            a.alpha + (b.alpha - a.alpha) * t,
        )
    }
}

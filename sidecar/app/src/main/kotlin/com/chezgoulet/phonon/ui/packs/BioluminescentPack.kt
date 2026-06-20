package com.chezgoulet.phonon.ui.packs

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
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Bioluminescent Dreamscape — organic underwater ecosystem visualization pack.
 *
 * Free-swimming bioluminescent organisms drift through the abyss, their luminous
 * bodies tracing sinuous paths in the dark. Caustic light shimmers across the
 * depths, shifting with each pulse of device activity.
 *
 * Three layers: abyss (background + caustic patches + depth dust), free-swimmers
 * (sinuous creatures that wander and interact), and plankton (nano + micro motes
 * with comet trails). Caustic shimmer bursts replace traditional ripple pulses.
 */
object BioluminescentPack : VisualizationPack {

    override val id = "bioluminescent"
    override val name = "Bioluminescent Dreamscape"
    override val description = "Free-swimming organisms trace sinuous paths through the abyss — organic and submerged"
    override val author = "chezgoulet"
    override val version = "0.3.0"

    override val defaultConfig = mapOf(
        "tendril_count" to "5",
        "glow_intensity" to "1.0",
        "pulse_frequency" to "1.0",
    )

    // ── Mutable scene state (object-scoped; pack is a singleton) ──
    private var energy = 0f
    private val shimmers = ArrayDeque<Shimmer>()
    private val blooms = ArrayDeque<Bloom>()
    private var lastShimmer = 0f
    private var lastCascade = 0f
    private var plankton: List<PlanktonSeed>? = null
    private var swimmers: List<SwimmerSeed>? = null
    private var caustics: List<CausticSeed>? = null
    private var depthDust: List<DustSeed>? = null

    private data class Shimmer(val born: Float, val x: Float, val y: Float, val hot: Float, val phase: Float)
    private data class Bloom(val born: Float)
    private data class CausticSeed(
        var x: Float, var y: Float,
        val phase: Float, val speed: Float, val sz: Float,
        val driftAngle: Float, val driftSpeed: Float,
        val wobble: Float, val wobbleSpeed: Float,
    )
    private data class DustSeed(
        val x: Float, val y: Float, val phase: Float, val sz: Float, val depth: Float,
        val dx: Float, val dy: Float,
    )
    private data class PlanktonSeed(
        val type: String,
        val ax: Float, val ay: Float,
        val phase: Float, val sz: Float, val depth: Float,
        val driftX: Float, val driftY: Float,
        val trailLen: Float = 0f,
    )
    private data class SwimmerSeed(
        var x: Float, var y: Float,
        var angle: Float,
        var phase: Float, var driftPhase: Float,
        val size: Float, val speed: Float, val swimFreq: Float,
        val bodyLen: Float, var hueOff: Float, var colorIdx: Int,
        var targetX: Float, var targetY: Float,
        var turnTimer: Float,
        var age: Float, var lifeSpan: Float, var fadeAlpha: Float,
        val trail: MutableList<Offset>,
    )

    private data class Palette(
        val primary: Color,
        val accent: Color,
        val bgCenter: Color,
        val bg: Color,
    )

    override fun onActivate() {
        energy = 0f
        shimmers.clear()
        blooms.clear()
        plankton = null
        swimmers = null
        caustics = null
        depthDust = null
        lastShimmer = 0f
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
        val tendrilCount = (state.themeConfig["tendril_count"] ?: "5").toFloatOrNull()?.toInt()?.coerceIn(3, 8) ?: 5
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
            val panic = if (state.batteryTemperature > 38f) ((state.batteryTemperature - 38f) / 10f).coerceIn(0f, 1f) else 0f

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

            // 1b. Surface shimmer band
            if (!lowPower) {
                val sf = listOf(
                    blend(pal.primary, Color(0xFF82FFF0), 0.3f).copy(alpha = 0.03f + 0.03f * (0.5f + 0.5f * sin(t * 0.7f))),
                    pal.bgCenter.copy(alpha = 0.02f),
                    pal.bgCenter.copy(alpha = 0f),
                )
                drawRect(brush = Brush.verticalGradient(sf), topLeft = Offset.Zero, size = size.copy(height = h * 0.35f))
            }

            // 1c. Enhanced caustic patches (12 drifting, more prominent)
            val localCaustics = caustics ?: run {
                val rng = Random(133)
                List(12) {
                    CausticSeed(
                        x = rng.nextFloat() * w,
                        y = 0.1f + rng.nextFloat() * 0.7f * h,
                        phase = rng.nextFloat() * 6.28f,
                        speed = 0.3f + rng.nextFloat() * 0.5f,
                        sz = 0.15f + rng.nextFloat() * 0.25f,
                        driftAngle = rng.nextFloat() * 6.28f,
                        driftSpeed = 0.2f + rng.nextFloat() * 0.4f,
                        wobble = rng.nextFloat() * 6.28f,
                        wobbleSpeed = 0.2f + rng.nextFloat() * 0.4f,
                    )
                }.also { caustics = it }
            }
            if (!lowPower) {
                val margin = w * 0.2f
                for (c in localCaustics) {
                    c.x += cos(c.driftAngle) * c.driftSpeed * 60f * dt
                    c.y += sin(c.driftAngle) * c.driftSpeed * 60f * dt
                    if (c.x < -margin) c.x = w + margin
                    if (c.x > w + margin) c.x = -margin
                    if (c.y < -margin) c.y = h + margin
                    if (c.y > h + margin) c.y = -margin

                    val cx2 = c.x + sin(t * c.speed * 0.15f + c.phase) * w * 0.1f
                    val cy2 = c.y + sin(t * c.speed * 0.1f + c.phase * 1.3f) * h * 0.08f
                    val r = rMax * c.sz * (1f + 0.12f * sin(t * c.wobbleSpeed + c.wobble))
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(
                                blend(pal.primary, Color(0xFFC8FFFF), 0.5f).copy(alpha = 0.045f * glowMod * (0.5f + 0.5f * e)),
                                pal.primary.copy(alpha = 0.025f * glowMod * (0.5f + 0.5f * e)),
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

                        // ═══ LAYER 2: FREE-SWIMMERS — trail-based slithering ═══
            val MAX_TRAIL = 120
            val maxSwimmers = if (lowPower) 2 else (3 + (e * 8f).roundToInt()).coerceIn(3, 11)
            if (swimmers == null) {
                val rng = Random(88); val pool = mutableListOf<SwimmerSeed>()
                for (i in 0 until 14) {
                    val sx = rng.nextFloat() * w; val sy = rng.nextFloat() * h; val sa = rng.nextFloat() * 6.28f
                    val sSpeed = 0.2f + rng.nextFloat() * 0.3f; val sBodyLen = 50f + rng.nextFloat() * 20f
                    val tr = mutableListOf<Offset>()
                    for (j in 0 until MAX_TRAIL) {
                        tr.add(Offset(
                            sx - cos(sa) * j.toFloat() * (sSpeed * 60f / MAX_TRAIL),
                            sy - sin(sa) * j.toFloat() * (sSpeed * 60f / MAX_TRAIL),
                        ))
                    }
                    val hueOff = rng.nextFloat()
                    pool.add(SwimmerSeed(
                        x = sx, y = sy, angle = sa,
                        phase = rng.nextFloat() * 6.28f, driftPhase = rng.nextFloat() * 6.28f,
                        size = 0.7f + rng.nextFloat() * 0.6f, speed = sSpeed, swimFreq = 3f + rng.nextFloat() * 3f,
                        bodyLen = sBodyLen, hueOff = hueOff,
                        colorIdx = (hueOff * 6f).toInt().coerceIn(0, 5),
                        targetX = rng.nextFloat() * w, targetY = rng.nextFloat() * h,
                        turnTimer = 2f + rng.nextFloat() * 4f,
                        age = 0f, lifeSpan = 15f + rng.nextFloat() * 15f, fadeAlpha = 1f,
                        trail = tr,
                    ))
                }
                swimmers = pool
            }

            val localSwimmers = swimmers!!
            val swimCount = maxSwimmers.coerceAtMost(localSwimmers.size)

            // Update — lifecycle, navigation, trail
            for (i in 0 until swimCount) {
                val cr = localSwimmers[i]
                cr.age += dt
                cr.fadeAlpha = 1f
                if (cr.age < 2f) cr.fadeAlpha = cr.age / 2f
                val lifeRemaining = cr.lifeSpan - cr.age
                if (lifeRemaining < 3f && lifeRemaining > 0f) cr.fadeAlpha = lifeRemaining / 3f
                if (lifeRemaining <= 0f) {
                    val rng2 = Random((t * 1000 + i * 7).toInt())
                    cr.x = rng2.nextFloat() * w; cr.y = rng2.nextFloat() * h
                    cr.angle = rng2.nextFloat() * 6.28f
                    cr.targetX = rng2.nextFloat() * w; cr.targetY = rng2.nextFloat() * h
                    cr.age = 0f; cr.lifeSpan = 15f + rng2.nextFloat() * 15f; cr.fadeAlpha = 0f
                    cr.hueOff = rng2.nextFloat(); cr.colorIdx = (cr.hueOff * 6f).toInt().coerceIn(0, 5)
                    cr.trail.clear()
                    for (j in 0 until MAX_TRAIL) {
                        cr.trail.add(Offset(
                            cr.x - cos(cr.angle) * j.toFloat() * (cr.speed * 60f / MAX_TRAIL),
                            cr.y - sin(cr.angle) * j.toFloat() * (cr.speed * 60f / MAX_TRAIL),
                        ))
                    }
                    continue
                }
                val tdx = cr.targetX - cr.x; val tdy = cr.targetY - cr.y
                val tDist = hypot(tdx, tdy)
                cr.turnTimer -= dt
                if (panic > 0f) cr.turnTimer -= panic * dt * 2f
                if (cr.turnTimer <= 0f || tDist < 40f) {
                    cr.targetX = cx + (Random.nextFloat() * 2f - 1f) * w * 0.38f
                    cr.targetY = cy + (Random.nextFloat() * 2f - 1f) * h * 0.38f
                    cr.turnTimer = 2.5f + Random.nextFloat() * 3f
                }
                val tAngle = kotlin.math.atan2(tdy, tdx)
                var angleDiff = tAngle - cr.angle
                while (angleDiff > 3.14159f) angleDiff -= 6.28318f
                while (angleDiff < -3.14159f) angleDiff += 6.28318f
                val turnRate = (1.8f + e * 2f + panic * 3f) * dt
                cr.angle += angleDiff.coerceIn(-turnRate, turnRate)
                if (panic > 0f && Random.nextFloat() < 0.02f) cr.angle += (Random.nextFloat() - 0.5f) * panic * 0.5f
                val speedMul = if (lowPower) 0.4f else lerp(0.6f, 3.0f, e) * (1f + panic * Random.nextFloat() * 0.3f)
                cr.x += cos(cr.angle) * cr.speed * speedMul * dt * 60f
                cr.y += sin(cr.angle) * cr.speed * speedMul * dt * 60f
                cr.driftPhase += dt * 0.3f
                val m = cr.bodyLen * 0.5f
                if (cr.x < -m) cr.x = w + m; if (cr.x > w + m) cr.x = -m
                if (cr.y < -m) cr.y = h + m; if (cr.y > h + m) cr.y = -m
                cr.trail.add(0, Offset(cr.x, cr.y))
                if (cr.trail.size > MAX_TRAIL) cr.trail.removeAt(cr.trail.lastIndex)
            }

            // Interaction: stronger proximity glow + mutual steering
            for (i in 0 until swimCount) {
                for (j in i + 1 until swimCount) {
                    val a = localSwimmers[i]; val b = localSwimmers[j]
                    val dx = a.x - b.x; val dy = a.y - b.y
                    val dist = hypot(dx, dy)
                    if (dist < 140f) {
                        val prox = 1f - dist / 140f
                        val nearGlow = prox * (0.12f + prox * 0.2f) * glowMod * (0.5f + 0.5f * e)
                        val connPath = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y) }
                        drawPath(connPath, pal.accent.copy(alpha = nearGlow * 0.3f),
                            style = Stroke(width = 1.5f + prox * 3f))
                        val amidX = (a.x + b.x) / 2f; val amidY = (a.y + b.y) / 2f
                        val connR = 20f + prox * 20f
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(pal.accent.copy(alpha = nearGlow), pal.accent.copy(alpha = 0f)),
                                center = Offset(amidX, amidY), radius = connR,
                            ), radius = connR, center = Offset(amidX, amidY),
                        )
                        val steerA = kotlin.math.atan2(b.y - a.y, b.x - a.x)
                        var diffA = steerA - a.angle
                        while (diffA > 3.14159f) diffA -= 6.28318f
                        while (diffA < -3.14159f) diffA += 6.28318f
                        a.angle += diffA * dt * 0.7f * prox
                        val steerB = kotlin.math.atan2(a.y - b.y, a.x - b.x)
                        var diffB = steerB - b.angle
                        while (diffB > 3.14159f) diffB -= 6.28318f
                        while (diffB < -3.14159f) diffB += 6.28318f
                        b.angle += diffB * dt * 0.7f * prox
                    }
                }
            }

            // Swimmer colors — wider palette (blues, violets, pinks under heavy load)
            val swimmerColors = arrayOf(
                listOf(Color(0xFF38F8C8), Color(0xFF60F0FF)),
                listOf(Color(0xFF22E9A8), Color(0xFFFFE066)),
                listOf(Color(0xFF30B8FF), Color(0xFF80D0FF)),
                listOf(Color(0xFF7864FF), Color(0xFFB090FF)),
                listOf(Color(0xFFB060FF), Color(0xFFD090FF)),
                listOf(Color(0xFFFF70C0), Color(0xFFFFA0D8)),
            )
            val numColors = if (e > 0.5f) 6 else 3

            // Render swimmers as trail-following sinuous bodies
            for (i in 0 until swimCount) {
                val cr = localSwimmers[i]
                val fade = cr.fadeAlpha
                val trail = cr.trail
                val trailLen = trail.size
                if (trailLen < 3) continue

                val colIdx = cr.colorIdx % numColors
                val baseCol = swimmerColors[colIdx][0]; val accentCol = swimmerColors[colIdx][1]
                val activityCol = blend(baseCol, accentCol, e * 0.4f)
                val vibrancy = if (e > 0.5f) ((e - 0.5f) * 2f).coerceIn(0f, 1f) else 0f
                val vividCol = if (vibrancy > 0.01f) {
                    val flashCol = blend(Color(0xFFFF80FF), Color(0xFFFF6080),
                        (sin(cr.phase + t * 0.3f) * 0.5f + 0.5f))
                    blend(activityCol, flashCol, vibrancy * 0.4f)
                } else activityCol

                // Build body from trail samples (path-following, no mechanical pivot)
                val segs = 20
                val pts = mutableListOf<Offset>()
                val step = maxOf(1, (trailLen - 1) / (segs - 1))
                for (si in 0 until segs) {
                    val idx = (si * step).toFloat().roundToInt().coerceAtMost(trailLen - 1)
                    val pt = trail[idx]
                    val prevIdx = maxOf(0, idx - 1); val nextIdx = minOf(trailLen - 1, idx + 1)
                    val dirX = trail[nextIdx].x - trail[prevIdx].x
                    val dirY = trail[nextIdx].y - trail[prevIdx].y
                    val dirLen = maxOf(hypot(dirX, dirY), 0.001f)
                    val perpX = -dirY / dirLen; val perpY = dirX / dirLen
                    val waveAmp = cr.size * 5f * cr.speed * (si.toFloat() / (segs - 1))
                    val wave = sin(t * cr.swimFreq * 3f - si * 0.7f + cr.phase + cr.driftPhase * 2f) * waveAmp
                    pts.add(Offset(pt.x + perpX * wave, pt.y + perpY * wave))
                }

                // Wake (fading trail glow)
                val wakeStep = maxOf(1, trailLen / 30)
                for (wi in (trailLen - 1) downTo 0 step wakeStep) {
                    val f = wi.toFloat() / trailLen
                    val wakeA = fade * (1f - f) * 0.04f * (0.5f + 0.5f * e)
                    if (wakeA < 0.001f) continue
                    val wakeR = cr.size * lerp(0.5f, 1.5f, f) * (0.5f + 0.5f * e)
                    drawCircle(Color(vividCol.red, vividCol.green, vividCol.blue, wakeA), wakeR, trail[wi])
                }

                // Body: smooth bezier path
                val bodyPath = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (k in 1 until pts.size) {
                        val p0 = pts[k - 1]; val p1 = pts[k]
                        quadraticBezierTo(p0.x, p0.y, (p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
                    }
                    lineTo(pts.last().x, pts.last().y)
                }
                val panicFlicker = if (panic > 0f) (0.7f + 0.3f * sin(t * 12f + i * 3f)) else 1f
                val bodyAlpha = fade * lerp(0.5f, 0.9f, e) * (0.6f + 0.4f * (1f - e * state.inferenceLoad)) * panicFlicker
                val activity = if (state.isProcessing) (1f + e * 0.5f) else 1f
                drawPath(bodyPath, vividCol.copy(alpha = bodyAlpha * 0.1f),
                    style = Stroke(width = cr.size * 10f * activity, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(bodyPath, vividCol.copy(alpha = bodyAlpha * 0.25f),
                    style = Stroke(width = cr.size * 5f * activity, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(bodyPath, blend(vividCol, Color.White, 0.25f).copy(alpha = bodyAlpha * 0.85f),
                    style = Stroke(width = (cr.size * 2.2f * activity).coerceAtLeast(1.2f), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val headSz = 3.5f * cr.size * activity
                val headR = headSz * 4f
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            blend(pal.accent, Color.White, 0.5f).copy(alpha = bodyAlpha * 0.9f * fade),
                            pal.accent.copy(alpha = bodyAlpha * 0.2f * fade),
                            pal.accent.copy(alpha = 0f),
                        ),
                        center = pts[0], radius = headR,
                    ), radius = headR, center = pts[0],
                )
            }// ═══ LAYER 3: PLANKTON (nano + micro) ═══
            val localPlankton = plankton ?: run {
                val rng = Random(42)
                val p = mutableListOf<PlanktonSeed>()
                for (i in 0 until 25) {
                    p.add(PlanktonSeed(
                        type = "nano",
                        ax = rng.nextFloat() * w, ay = rng.nextFloat() * h,
                        phase = rng.nextFloat() * 6.28f,
                        sz = 0.7f + rng.nextFloat() * 1f,
                        depth = 0.1f + rng.nextFloat() * 0.2f,
                        driftX = (rng.nextFloat() - 0.5f) * 0.4f,
                        driftY = (rng.nextFloat() - 0.5f) * 0.3f,
                    ))
                }
                for (i in 0 until 16) {
                    p.add(PlanktonSeed(
                        type = "micro",
                        ax = rng.nextFloat() * w, ay = rng.nextFloat() * h,
                        phase = rng.nextFloat() * 6.28f,
                        sz = 1.5f + rng.nextFloat() * 2.5f,
                        depth = 0.3f + rng.nextFloat() * 0.3f,
                        driftX = (rng.nextFloat() - 0.3f) * 0.5f,
                        driftY = (rng.nextFloat() - 0.5f) * 0.4f,
                        trailLen = 0.3f + rng.nextFloat() * 0.4f,
                    ))
                }
                p.also { plankton = it }
            }

            val plankSpeed = if (lowPower) 0.2f else lerp(0.3f, 1.2f, e)
            val currentDirX = 0.3f; val currentDirY = -0.05f

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
                        val tDirX = cos(t * plankSpeed * 0.4f + p.phase * 1.3f) * p.driftX
                        val tDirY = cos(t * plankSpeed * 0.35f + p.phase * 0.9f) * p.driftY
                        val tailLen = p.trailLen * 30f
                        // Comet trail
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(
                                    pal.primary.copy(alpha = ma * 0.3f),
                                    pal.primary.copy(alpha = 0f),
                                ),
                                center = pos - Offset(tDirX * tailLen * 2f, tDirY * tailLen * 2f),
                                radius = szM * 0.8f,
                            ),
                            radius = szM * 0.8f,
                            center = pos - Offset(tDirX * tailLen * 2f, tDirY * tailLen * 2f),
                        )
                        // Outer glow
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
                }
            }

            // ═══ LAYER 4: CAUSTIC SHIMMER BURST (replaces ripple pulses) ═══
            if (!lowPower) {
                val emitGap = when {
                    e > 0.7f -> lerp(0.6f, 0.3f, pulseFreq)
                    e > 0.3f -> lerp(3f, 1.5f, (e - 0.3f) / 0.4f) / pulseFreq
                    state.isProcessing -> lerp(5f, 3f, e * 3f) / pulseFreq
                    else -> 8f
                }
                if (t - lastShimmer > emitGap) {
                    val burstCount = 2 + (e * 3f).toInt()
                    for (i in 0 until burstCount) {
                        shimmers.addLast(Shimmer(
                            born = t,
                            x = (0.1f + Random.nextFloat() * 0.8f) * w,
                            y = (0.1f + Random.nextFloat() * 0.7f) * h,
                            hot = e * (0.6f + Random.nextFloat() * 0.4f),
                            phase = Random.nextFloat() * 6.28f,
                        ))
                    }
                    lastShimmer = t
                    if (e > 0.6f && t - lastCascade > 0.5f) {
                        for (i in 0 until 2) {
                            shimmers.addLast(Shimmer(
                                born = t,
                                x = (0.1f + Random.nextFloat() * 0.8f) * w,
                                y = (0.1f + Random.nextFloat() * 0.5f) * h,
                                hot = e * 0.5f,
                                phase = Random.nextFloat() * 6.28f,
                            ))
                        }
                        lastCascade = t
                    }
                }
                while (shimmers.isNotEmpty() && t - shimmers.first().born >= 1.2f) shimmers.removeFirst()
                for (s in shimmers) {
                    val age = (t - s.born) / 1.2f
                    val rad = lerp(w * 0.03f, w * 0.25f, age) * (0.7f + s.hot * 0.3f)
                    val alpha = (1f - age) * 0.15f * s.hot * glowMod
                    // Soft caustic patch (filled glow, not a ring)
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(
                                blend(pal.accent, Color(0xFFFFF8F0), 0.5f).copy(alpha = alpha),
                                pal.primary.copy(alpha = alpha * 0.4f),
                                pal.primary.copy(alpha = 0f),
                            ),
                            center = Offset(s.x, s.y),
                            radius = rad,
                        ),
                        radius = rad,
                        center = Offset(s.x, s.y),
                    )
                    // Secondary shifted glow (caustic shimmer)
                    val offX = cos(s.phase + t * 0.6f) * rad * 0.25f
                    val offY = sin(s.phase + t * 1.2f) * rad * 0.2f
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(pal.accent.copy(alpha = alpha * 0.35f), pal.accent.copy(alpha = 0f)),
                            center = Offset(s.x + offX, s.y + offY),
                            radius = rad * 0.4f,
                        ),
                        radius = rad * 0.4f,
                        center = Offset(s.x + offX, s.y + offY),
                    )
                }

                // Deep queue: sustained shimmer field
                if (state.queueDepth > 10) {
                    val qbAlpha = (state.queueDepth / 20f).coerceIn(0f, 1f) * 0.06f * glowMod
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(pal.accent.copy(alpha = qbAlpha), pal.accent.copy(alpha = 0f)),
                            center = Offset(cx, cy),
                            radius = rMax * 0.35f,
                        ),
                        radius = rMax * 0.35f,
                        center = Offset(cx, cy),
                    )
                }
            }

            // ═══ LAYER 5: CORE BLOOM ═══
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
                // Soft burst
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
                // Radiating rays
                for (ri in 0 until 10) {
                    val ra = (ri / 10f) * 6.28318f + sin(bl.born * 3f + ri * 0.7f) * 0.3f
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

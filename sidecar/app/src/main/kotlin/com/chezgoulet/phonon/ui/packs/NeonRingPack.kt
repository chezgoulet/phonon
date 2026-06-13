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
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Neon Ring — flagship reactive visualization pack.
 *
 * A living reactor core whose entire visual language shifts smoothly with
 * activity, so device state reads at a glance with no text:
 *
 *  - Idle: cool cyan/indigo palette, near-circular rings breathing slowly,
 *    faint drifting motes, rare gentle shockwaves — a calm, low-key scene.
 *  - Heavy inference: palette heats toward spring-green/magenta, ring paths
 *    squiggle turbulently, orbit nodes multiply and collide (spark bursts),
 *    energy arcs leap between nodes, shockwaves fire rapidly, and the core
 *    blooms white-hot.
 *
 * The activity level is a smoothed "energy" value so transitions glide rather
 * than snap. Low power mode falls back to two calm breathing rings.
 */
object NeonRingPack : VisualizationPack {

    override val id = "neon-ring"
    override val name = "Neon Ring"
    override val description = "A living reactor core whose palette, turbulence, orbital collisions and energy arcs shift smoothly from calm idle to roiling inference — readable at a glance"
    override val author = "chezgoulet"
    override val version = "1.2.1"

    override val defaultConfig = mapOf(
        "rotation_speed" to "0.8",
        "glow_intensity" to "1.0",
    )

    // ── mutable scene state (object-scoped; pack is a singleton rendered once) ──
    private var energy = 0f
    private val pulses = ArrayDeque<Pulse>()
    private var lastEmit = 0f
    private val sparks = ArrayDeque<Spark>()
    private val arcs = ArrayDeque<Arc>()
    private var lastArc = 0f
    private var motes: List<Mote>? = null

    private data class Pulse(val born: Float, val hot: Float)
    private data class Spark(val x: Float, val y: Float, val vx: Float, val vy: Float, val born: Float, val col: Color)
    private data class Arc(val born: Float, val ax: Float, val ay: Float, val bx: Float, val by: Float, val col: Color, val seed: Float)
    private data class Mote(val a: Float, val r: Float, val sp: Float, val ph: Float, val sz: Float)
    private data class Node(val x: Float, val y: Float, val ring: Int, val col: Color)

    private data class Palette(val a: Color, val b: Color, val bg: Color, val accent: Color)

    override fun onActivate() {
        energy = 0f; pulses.clear(); sparks.clear(); arcs.clear(); motes = null; lastEmit = 0f; lastArc = 0f
    }

    private fun palette(e: Float, healthy: Boolean): Palette {
        val idleA = Color(0xFF38BDF8); val idleB = Color(0xFF6366F1); val idleBg = Color(0xFF0C0C26)
        val hotA = Color(0xFF22E9A8); val hotB = Color(0xFFD946EF); val hotBg = Color(0xFF26082E)
        if (!healthy) {
            return Palette(
                a = blend(idleA, Color(0xFFEF4444), 0.7f),
                b = blend(idleB, Color(0xFFF43F5E), 0.7f),
                bg = Color(0xFF1E060A),
                accent = Color(0xFFFF5A5A),
            )
        }
        return Palette(
            a = blend(idleA, hotA, e),
            b = blend(idleB, hotB, e),
            bg = blend(idleBg, hotBg, e),
            accent = blend(Color(0xFFB4DCFF), Color(0xFFFFF0B4), e),
        )
    }

    @Composable
    override fun Render(state: VizState, modifier: Modifier) {
        val glowMod = (state.themeConfig["glow_intensity"] ?: "1.0").toFloatOrNull() ?: 1.0f
        val speedMul = (state.themeConfig["rotation_speed"] ?: "0.8").toFloatOrNull() ?: 0.8f
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
            val cx = size.width / 2f
            val cy = size.height / 2f
            val maxR = minOf(cx, cy)

            // ── target energy, smoothed ──
            val target = if (lowPower) 0f else (
                (if (state.isProcessing) 0.45f else 0f) +
                    state.inferenceLoad * 0.5f +
                    (state.queueDepth / 20f).coerceIn(0f, 1f) * 0.15f
                ).coerceIn(0f, 1f)
            val k = 1f - 0.001f.pow(dt.coerceAtMost(0.05f))
            energy += (target - energy) * k
            val e = energy

            val pal = palette(e, state.isHealthy)
            val spin = t * speedMul * (0.35f + e * 1.6f)
            val phaseDeg = (spin * 60f) % 360f

            // ── background ──
            run {
                val core = if (lowPower) Color(0xFF0A0A1A) else blend(pal.bg, pal.a, 0.10f * e)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(core, pal.bg, blend(pal.bg, Color.Black, 0.6f)),
                        center = Offset(cx, cy), radius = maxR * 1.25f,
                    )
                )
            }

            if (lowPower) { drawIdleLowPower(state, cx, cy, maxR, pal, t); return@Canvas }

            // ── idle motes (fade out as energy rises) ──
            val localMotes = motes ?: run {
                val rng = Random(99)
                List(30) { Mote(rng.nextFloat() * 6.28f, 0.2f + rng.nextFloat() * 0.75f, 0.05f + rng.nextFloat() * 0.12f, rng.nextFloat() * 6.28f, 0.6f + rng.nextFloat() * 1.4f) }
                    .also { motes = it }
            }
            val idleVis = 1f - e
            if (idleVis > 0.02f) {
                for (m in localMotes) {
                    val a = m.a + t * m.sp * 0.3f
                    val rr = m.r + sin(t * 0.4f + m.ph) * 0.04f
                    val mx = cx + cos(a) * rr * maxR
                    val my = cy + sin(a) * rr * maxR
                    val tw = 0.5f + 0.5f * sin(t * 1.5f + m.ph)
                    drawCircle(pal.a.copy(alpha = 0.12f * idleVis * tw), m.sz, Offset(mx, my))
                }
            }

            // ── shockwave pulses ──
            val emitGap = lerpF(3.2f, 0.55f, e)
            if (t - lastEmit > emitGap) { pulses.addLast(Pulse(t, e)); lastEmit = t }
            while (pulses.isNotEmpty() && t - pulses.first().born >= 2.4f) pulses.removeFirst()
            for (p in pulses) {
                val age = (t - p.born) / 2.4f
                val r = maxR * (0.1f + age * 1.05f)
                val col = blend(pal.a, pal.b, p.hot)
                drawCircle(col.copy(alpha = (1f - age) * (0.18f + 0.42f * p.hot) * glowMod), r, Offset(cx, cy), style = Stroke(width = (2.5f * (1f - age) + 0.4f) * (0.6f + p.hot)))
            }

            // ── squiggling rings + collect orbit nodes ──
            val ringCount = 4
            val baseR = maxR * 0.16f
            val breathe = 1f + sin(t * (1.2f + e * 2.5f)) * (0.02f + e * 0.05f)
            val wobAmp = e * e * maxR * 0.045f
            val nodes = ArrayList<Node>(8)

            for (i in 0 until ringCount) {
                val dir = if (i % 2 == 0) 1f else -1f
                val ringPhase = spin * dir * (1f + i * 0.15f) + i * 1.7f
                val radius = baseR * (1f + i * 0.42f) * breathe
                val ringColor = blend(if (i % 2 == 0) pal.a else pal.b, pal.accent, e * 0.25f)
                val alpha = (0.6f - i * 0.08f).coerceIn(0.2f, 0.62f)
                val baseW = (3.4f - i * 0.4f).coerceAtLeast(1.4f) * (1f + e * 0.7f)
                val wobFreq = (3 + i * 2 + (e * 5).toInt()).toFloat()
                val wobSpeed = (1.4f + i * 0.7f + e * 4f) * dir
                val wobDrift = t * (0.3f + i * 0.4f)

                val path = Path()
                val segs = 80
                for (sgi in 0..segs) {
                    val a = (sgi / segs.toFloat()) * (2f * Math.PI.toFloat())
                    val wob = wobAmp * sin(a * wobFreq + t * wobSpeed + wobDrift + i * 1.3f) * (1f - i * 0.12f)
                    val rr = radius + wob
                    val x = cx + cos(a + ringPhase) * rr
                    val y = cy + sin(a + ringPhase) * rr
                    if (sgi == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, color = ringColor.copy(alpha = alpha * (0.22f + 0.25f * e) * glowMod), style = Stroke(width = baseW + 7f + e * 5f))
                drawPath(path, color = ringColor.copy(alpha = alpha), style = Stroke(width = baseW))

                val nodeCount = if (e > 0.45f) 2 else 1
                for (n in 0 until nodeCount) {
                    val na = ringPhase + n * Math.PI.toFloat()
                    val wob = wobAmp * sin(na * wobFreq + t * wobSpeed + wobDrift + i * 1.3f)
                    val nr = radius + wob
                    val nx = cx + cos(na) * nr
                    val ny = cy + sin(na) * nr
                    val orbR = (3.6f - i * 0.4f).coerceAtLeast(2f) * (1f + e * 0.5f)
                    drawCircle(ringColor.copy(alpha = 0.28f * glowMod), orbR * 2.6f, Offset(nx, ny))
                    drawCircle(blend(ringColor, Color.White, 0.5f).copy(alpha = 0.95f), orbR, Offset(nx, ny))
                    nodes.add(Node(nx, ny, i, ringColor))
                }
            }

            // ── collisions → sparks ──
            for (p in nodes.indices) {
                for (q in p + 1 until nodes.size) {
                    val a = nodes[p]; val b = nodes[q]
                    if (kotlin.math.abs(a.ring - b.ring) != 1) continue
                    if (hypot(a.x - b.x, a.y - b.y) < maxR * 0.06f && Random.nextFloat() < 0.4f) {
                        val mx = (a.x + b.x) / 2f; val my = (a.y + b.y) / 2f
                        val burst = 4 + (e * 6).toInt()
                        repeat(burst) {
                            val ang = Random.nextFloat() * 6.28f
                            val spd = (0.4f + Random.nextFloat() * 1.2f) * (1f + e)
                            sparks.addLast(Spark(mx, my, cos(ang) * spd, sin(ang) * spd, t, blend(a.col, b.col, 0.5f)))
                        }
                    }
                }
            }
            while (sparks.isNotEmpty() && t - sparks.first().born >= 0.8f) sparks.removeFirst()
            for (sp in sparks) {
                val age = (t - sp.born) / 0.8f
                drawCircle(blend(sp.col, Color.White, 0.4f).copy(alpha = (1f - age) * 0.9f), (1f - age) * 2.4f + 0.3f, Offset(sp.x + sp.vx * age * 40f, sp.y + sp.vy * age * 40f))
            }

            // ── energy arcs ──
            if (e > 0.35f && nodes.size >= 2) {
                val arcGap = lerpF(0.9f, 0.12f, e)
                if (t - lastArc > arcGap) {
                    lastArc = t
                    val a = nodes[Random.nextInt(nodes.size)]
                    val b = nodes[Random.nextInt(nodes.size)]
                    if (a !== b) arcs.addLast(Arc(t, a.x, a.y, b.x, b.y, blend(a.col, b.col, 0.5f), Random.nextFloat() * 1000f))
                }
            }
            while (arcs.isNotEmpty() && t - arcs.first().born >= 0.22f) arcs.removeFirst()
            for (arc in arcs) {
                val age = (t - arc.born) / 0.22f
                val segs = 8
                val nrmx = -(arc.by - arc.ay); val nrmy = arc.bx - arc.ax
                val nl = hypot(nrmx, nrmy).coerceAtLeast(1f)
                val path = Path()
                for (sgi in 0..segs) {
                    val f = sgi / segs.toFloat()
                    val jitter = if (sgi == 0 || sgi == segs) 0f else sin(arc.seed + sgi * 12.9f) * maxR * 0.04f
                    val x = lerpF(arc.ax, arc.bx, f) + (nrmx / nl) * jitter
                    val y = lerpF(arc.ay, arc.by, f) + (nrmy / nl) * jitter
                    if (sgi == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = blend(arc.col, Color.White, 0.5f).copy(alpha = (1f - age) * 0.85f), style = Stroke(width = 1.6f * (1f - age) + 0.5f))
            }

            // ── reactor core ──
            val coreBeat = 1f + sin(t * (3f + e * 6f)) * (0.06f + e * 0.12f)
            val centerR = (5f + e * 9f) * coreBeat
            val coreCol = blend(pal.a, pal.b, e)
            val haloR = centerR * (3.5f + e * 4f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(coreCol.copy(alpha = (0.3f + 0.45f * e) * glowMod), coreCol.copy(alpha = 0.12f * glowMod), coreCol.copy(alpha = 0f)),
                    center = Offset(cx, cy), radius = haloR,
                ),
                radius = haloR, center = Offset(cx, cy),
            )
            drawCircle(blend(coreCol, Color.White, 0.35f + 0.4f * e).copy(alpha = 0.95f), centerR, Offset(cx, cy))
            drawCircle(Color.White.copy(alpha = 0.5f + 0.4f * e), centerR * (0.35f + 0.15f * sin(t * 8f)), Offset(cx, cy))
            if (!state.isHealthy) {
                drawCircle(Color(0xFFFF4646).copy(alpha = 0.4f + 0.4f * (0.5f + 0.5f * sin(t * 6f))), centerR + 5f, Offset(cx, cy), style = Stroke(width = 2f))
            }

            // ── low-battery warning / charging ring (full circle, never a
            //    partial arc that would read as a stray artifact) ──
            if (state.batteryLevel < 20 && !state.isCharging) {
                val arcR = maxR * 0.94f
                val warn = 0.12f + 0.16f * (0.5f + 0.5f * sin(t * 4f))
                drawCircle(Color(0xFFEF4444).copy(alpha = warn), arcR, Offset(cx, cy), style = Stroke(width = 2f))
            } else if (state.isCharging) {
                val arcR = maxR * 0.94f
                val chg = 0.08f + 0.12f * (0.5f + 0.5f * sin(t * 2.5f))
                drawCircle(Color(0xFF22C55E).copy(alpha = chg), arcR, Offset(cx, cy), style = Stroke(width = 1.6f))
            }
        }
    }

    private fun DrawScope.drawIdleLowPower(state: VizState, cx: Float, cy: Float, maxR: Float, pal: Palette, t: Float) {
        val breathe = 1f + sin(t * 1.2f) * 0.04f
        for (i in 0 until 2) {
            val radius = maxR * 0.18f * (1f + i * 0.5f) * breathe
            drawCircle(pal.a.copy(alpha = 0.18f - i * 0.05f), radius, Offset(cx, cy), style = Stroke(width = 1.4f))
        }
        drawCircle(pal.a.copy(alpha = 0.4f), 3f, Offset(cx, cy))
        // dim full-circle battery indicator (low power implies low battery)
        val arcR = maxR * 0.9f
        val battColor = if (state.batteryLevel < 15) Color(0xFFEF4444) else Color(0xFFEAB308)
        drawCircle(battColor.copy(alpha = 0.16f), arcR, Offset(cx, cy), style = Stroke(width = 1.4f))
    }
}

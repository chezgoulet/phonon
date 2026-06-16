package com.chezgoulet.phonon.ui.packs

import androidx.compose.animation.core.withFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.chezgoulet.phonon.ui.VisualizationPack
import com.chezgoulet.phonon.ui.VizState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthwave / Outrun visualization pack.
 *
 * Retro-futurist neon aesthetic with pink sun, purple mountains,
 * perspective grid horizon, and cassette-tape glow.
 *
 * Palette:
 *  - BG: Deep midnight #0B0B2B with gradient-to-horizon fade
 *  - Sun: Magenta-to-pink #FF007F -> #FF6B9D with horizontal glow bands
 *  - Grid: Cyan #00E5FF, receding into horizon perspective
 *  - Accents: Yellow #FFD700, electric purple #7C3AED
 */
object SynthwavePack : VisualizationPack {

    override val id = "synthwave"
    override val name = "Synthwave"
    override val description = "Retro-futurist neon with pink sun, purple mountains, perspective grid horizon, and cassette-tape glow"
    override val author = "chezgoulet"
    override val version = "1.0.0"
    override val defaultConfig = emptyMap<String, String>()

    // ── Palette ──────────────────────────────────────────────────────
    private val midnight    = Color(0xFF0B0B2B)
    private val sunInner    = Color(0xFFFF007F)
    private val sunOuter    = Color(0xFFFF6B9D)
    private val gridCyan    = Color(0xFF00E5FF)
    private val starYellow  = Color(0xFFFFD700)
    private val borderPurple = Color(0xFF7C3AED)
    private val horizonColor = Color(0xFF2D1B69)
    private val mountainColor = Color(0xFF1A0A3E)

    // ── Pre-generated star field (deterministic per session) ─────────
    private data class Star(
        val x: Float, val y: Float,
        val baseBrightness: Float,
        val twinkleSpeed: Float,
        val twinklePhase: Float
    )

    private var stars: List<Star>? = null
    private val random = Random(42)

    private fun ensureStars(w: Float, h: Float, count: Int = 50): List<Star> {
        val existing = stars
        if (existing != null) return existing
        val s = buildList {
            repeat(count) {
                add(Star(
                    x = random.nextFloat() * w,
                    y = random.nextFloat() * h * 0.6f,
                    baseBrightness = 0.3f + random.nextFloat() * 0.7f,
                    twinkleSpeed = 0.5f + random.nextFloat() * 1.5f,
                    twinklePhase = random.nextFloat() * PI.toFloat() * 2f
                ))
            }
        }
        stars = s
        return s
    }

    override fun onActivate() {
        stars = null  // re-generate star field for new session dimensions
    }

    override fun onDeactivate() { stars = null }

    @Composable
    override fun Render(state: VizState, modifier: Modifier) {
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
            val w = size.width
            val h = size.height
            val t = tSec
            val time = t.toDouble()
            val degraded = state.lowPowerMode
            val isProcessing = state.isProcessing

            val s = ensureStars(w, h)

            val horizonY = h * 0.20f
            val sunCx = w / 2f
            val sunCy = horizonY + h * 0.08f

            // 1. Background gradient (midnight to horizon)
            drawGradientBackground(w, h, horizonY)

            if (!degraded) {
                // 2. Mountain silhouette
                drawMountains(w, horizonY)
                // 3. Retro sun
                drawSun(sunCx, sunCy, w, time, isProcessing)
            }

            // 4. Horizon grid
            drawGrid(w, h, horizonY, time, isProcessing, degraded)

            if (!degraded) {
                // 5. Stars
                drawStars(s, time)
                // 6. Orbit rings
                drawOrbitRings(w, h, time, state.inferenceLoad)
            }

            // 7. Border glow
            drawBorderGlow(w, h, isProcessing, degraded, t)
        }
    }

    // ── Drawing helpers (unchanged from original; DrawScope context) ──

    private fun DrawScope.drawGradientBackground(w: Float, h: Float, horizonY: Float) {
        val steps = 40
        for (i in 0 until steps) {
            val t = i.toFloat() / steps
            val y = t * h
            val color = when {
                y < horizonY -> {
                    val blend = y / horizonY
                    midnight.copy(
                        red   = (1f - blend) * midnight.red   + blend * horizonColor.red,
                        green = (1f - blend) * midnight.green + blend * horizonColor.green,
                        blue  = (1f - blend) * midnight.blue  + blend * horizonColor.blue,
                        alpha = 1f
                    )
                }
                else -> {
                    val blend = (y - horizonY) / (h - horizonY)
                    horizonColor.copy(
                        red   = horizonColor.red   * (1f - blend * 0.5f),
                        green = horizonColor.green * (1f - blend * 0.6f),
                        blue  = horizonColor.blue  * (1f - blend * 0.7f),
                        alpha = 1f
                    )
                }
            }
            drawLine(color, Offset(0f, y), Offset(w, y), strokeWidth = h / steps + 1f)
        }
    }

    private fun DrawScope.drawMountains(w: Float, horizonY: Float) {
        val peaks = listOf(
            Triple(0.05f, 0.35f, 0.08f),
            Triple(0.18f, 0.50f, 0.15f),
            Triple(0.35f, 0.65f, 0.12f),
            Triple(0.52f, 0.55f, 0.10f),
            Triple(0.68f, 0.45f, 0.13f),
            Triple(0.85f, 0.38f, 0.09f),
        )
        for ((cx, height, halfWidth) in peaks) {
            val px = cx * w; val py = horizonY
            val peakY = py - height * w * 0.25f; val hw = halfWidth * w
            val path = Path().apply {
                moveTo(px - hw, py); lineTo(px, peakY); lineTo(px + hw, py); close()
            }
            val elevation = (cx * 0.1f).toFloat()
            drawPath(path, color = mountainColor.copy(
                red   = (mountainColor.red   + elevation * 0.2f).coerceIn(0f, 1f),
                green = (mountainColor.green - elevation * 0.1f).coerceIn(0f, 1f),
                blue  = (mountainColor.blue  - elevation * 0.1f).coerceIn(0f, 1f),
                alpha = 1f
            ))
        }
    }

    private fun DrawScope.drawSun(cx: Float, cy: Float, w: Float, time: Double, isProcessing: Boolean) {
        val sunWidth = w * 0.15f
        val baseHeight = w * 0.22f
        val pulseHeight = if (isProcessing) baseHeight * 1.10f else baseHeight
        val steps = 24
        for (i in 0 until steps) {
            val t = i.toFloat() / steps
            val yOffset = (t - 0.5f) * pulseHeight; val yPos = cy + yOffset
            val bandFraction = 1f - abs(t - 0.5f) * 1.2f
            val bandWidth = sunWidth * (0.6f + bandFraction * 0.4f)
            val color = when {
                t < 0.4f -> sunInner.copy(alpha = 0.9f - t * 0.3f)
                t < 0.7f -> {
                    val blend = (t - 0.4f) / 0.3f
                    Color(red = (1f - blend) * sunInner.red + blend * sunOuter.red,
                        green = (1f - blend) * sunInner.green + blend * sunOuter.green,
                        blue = (1f - blend) * sunInner.blue + blend * sunOuter.blue, alpha = 0.8f - t * 0.2f)
                }
                else -> sunOuter.copy(alpha = 0.5f - (t - 0.7f) * 0.3f)
            }
            drawLine(color, Offset(cx - bandWidth / 2, yPos), Offset(cx + bandWidth / 2, yPos), strokeWidth = pulseHeight / steps + 1f)
        }
        val glowBands = listOf(0.25f, 0.40f, 0.55f, 0.70f)
        for (bandT in glowBands) {
            val bandY = cy + (bandT - 0.5f) * pulseHeight
            val bandWidth = sunWidth * (0.8f + (0.5f - abs(bandT - 0.5f)) * 0.4f)
            drawLine(Color.White.copy(alpha = if (isProcessing) 0.5f else 0.25f), Offset(cx - bandWidth / 2, bandY), Offset(cx + bandWidth / 2, bandY), 3f)
        }
    }

    private fun DrawScope.drawGrid(w: Float, h: Float, horizonY: Float, time: Double, isProcessing: Boolean, degraded: Boolean) {
        val alpha = if (isProcessing) 0.7f else 0.4f
        val vpX = w / 2f; val vpY = horizonY + 2f
        val lineCount = if (degraded) 6 else 12
        for (i in 1..lineCount) {
            val t = i.toFloat() / lineCount; val y = vpY + t * (h - vpY)
            drawLine(gridCyan.copy(alpha = (1f - t) * alpha), Offset(0f, y), Offset(w, y), 1f)
        }
        for (i in -5..5) {
            val angle = i.toFloat() * 0.15f; val y1 = vpY
            val dx = sin(angle.toDouble()).toFloat() * w * 0.6f; val tx = vpX + dx
            drawLine(gridCyan.copy(alpha = (1f - abs(i.toFloat()) * 0.12f) * alpha), Offset(tx, y1), Offset(vpX + dx * 2.5f, h), 1f)
        }
        if (!degraded) {
            var sy = 0f
            while (sy < h) { drawLine(gridCyan.copy(alpha = 0.06f), Offset(0f, sy), Offset(w, sy), 1f); sy += 4f }
        }
    }

    private fun DrawScope.drawStars(stars: List<Star>, time: Double) {
        for (star in stars) {
            val twinkle = sin(time * star.twinkleSpeed + star.twinklePhase).toFloat()
            val brightness = star.baseBrightness * (0.5f + 0.5f * twinkle)
            drawCircle(starYellow.copy(alpha = brightness), if (star.baseBrightness > 0.7f) 2.5f else 1.5f, Offset(star.x, star.y))
        }
    }

    private fun DrawScope.drawOrbitRings(w: Float, h: Float, time: Double, load: Float) {
        val cx = w / 2f; val cy = h / 2f
        val outerAngle = (time * 0.3).toFloat(); val segCount = 40
        val outerRx = w * 0.40f; val outerRy = h * 0.20f
        for (i in 0 until segCount step 2) {
            val a = outerAngle + (i.toFloat() / segCount) * PI.toFloat() * 2f
            drawCircle(gridCyan.copy(alpha = 0.5f), 2f, Offset(cx + cos(a) * outerRx, cy + sin(a) * outerRy))
        }
        val innerSpeed = 0.4f + load * 0.4f
        val innerAngle = (-time * innerSpeed).toFloat()
        val innerRx = w * 0.25f; val innerRy = h * 0.12f
        for (i in 0 until segCount step 2) {
            val a = innerAngle + (i.toFloat() / segCount) * PI.toFloat() * 2f
            drawCircle(sunInner.copy(alpha = 0.4f), 1.5f, Offset(cx + cos(a) * innerRx, cy + sin(a) * innerRy))
        }
    }

    private fun DrawScope.drawBorderGlow(w: Float, h: Float, isProcessing: Boolean, degraded: Boolean, t: Float) {
        val bracketLen = 40f; val thickness = 8f; val baseAlpha = 0.35f
        val pulseAlpha = if (isProcessing) (0.5f + sin((t * 3.33).toDouble()).toFloat() * 0.3f) else 0.0f
        val alpha = (baseAlpha + pulseAlpha * 0.5f).coerceIn(0f, 1f); val color = borderPurple.copy(alpha = alpha)
        // Top-left
        drawLine(color, Offset(0f, bracketLen), Offset(0f, thickness / 2), thickness)
        drawLine(color, Offset(0f, 0f), Offset(bracketLen, 0f), thickness)
        // Top-right
        drawLine(color, Offset(w, bracketLen), Offset(w, thickness / 2), thickness)
        drawLine(color, Offset(w, 0f), Offset(w - bracketLen, 0f), thickness)
        // Bottom-left
        drawLine(color, Offset(0f, h - bracketLen), Offset(0f, h - thickness / 2), thickness)
        drawLine(color, Offset(0f, h), Offset(bracketLen, h), thickness)
        // Bottom-right
        drawLine(color, Offset(w, h - bracketLen), Offset(w, h - thickness / 2), thickness)
        drawLine(color, Offset(w, h), Offset(w - bracketLen, h), thickness)
    }
}

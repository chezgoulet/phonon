package com.chezgoulet.phonon.ui.packs

import androidx.compose.animation.core.withFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.chezgoulet.phonon.ui.VisualizationPack
import com.chezgoulet.phonon.ui.VizState
import kotlin.random.Random

/**
 * E-Ink — monochrome, high-contrast, zero-gradient visualization pack.
 *
 * Looks like a Sharp Memory LCD or Kindle display. Pure readability and
 * minimal battery draw. All aesthetic comes from density and stroke.
 * No gradients, no transparency, no motion blur.
 *
 * The screen reducer: for people who want their phone to emit fewer photons.
 */
object EInkPack : VisualizationPack {

    override val id = "e-ink"
    override val name = "E-Ink"
    override val description = "Monochrome, high-contrast, zero-gradient. Looks like a Kindle display. Pure readability."
    override val author = "chezgoulet"
    override val version = "1.0.0"

    override val defaultConfig = mapOf(
        "refresh_flash" to "true",  // enable full-screen inversion on state transitions
    )

    // ── Palette ──────────────────────────────────────────────────────
    private val substrate    = Color(0xFFF0F0F0)   // off-white paper
    private val inkBlack     = Color(0xFF1A1A1A)   // near-black
    private val midGrey      = Color(0xFF999999)
    private val lightGrey    = Color(0xFFCCCCCC)

    // ── Substrate noise (static per activation) ──────────────────────
    private var noisePixels: FloatArray? = null
    private val noiseRng = Random(73)

    private fun ensureNoise(w: Float, h: Float): FloatArray {
        val n = noisePixels
        if (n != null) return n
        val count = (w * h / 300).toInt().coerceIn(100, 800)
        val arr = FloatArray(count)
        for (i in 0 until count) arr[i] = noiseRng.nextFloat() * 0.005f - 0.0025f
        noisePixels = arr
        return arr
    }

    override fun onActivate() {
        noisePixels = null
        lastState = null  // reset refresh-flash trigger
    }

    // ── State tracking for refresh flash ─────────────────────────────
    private var lastState: String? = null

    // ── Composable entry point ──────────────────────────────────────

    @Composable
    override fun Render(state: VizState, modifier: Modifier) {
        val noise = remember(state.deviceId) { noiseRng.nextFloat() * 1000f }
        var tSec by remember { mutableStateOf(0f) }
        var lastFrame by remember { mutableStateOf(0f) }
        var refreshFlash by remember { mutableStateOf(0f) }  // 0 = none, >0 = flash phase

        LaunchedEffect(state.deviceId) {
            val start = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val tn = (now - start) / 1_000_000_000f
                lastFrame = tn
                tSec = tn
            }
        }

        // Detect state transitions for refresh flash
        LaunchedEffect(state.isProcessing, state.isHealthy, state.lowPowerMode) {
            val currentKey = "${state.isProcessing}|${state.isHealthy}|${state.lowPowerMode}"
            val prev = lastState
            lastState = currentKey
            if (prev != null && prev != currentKey && (state.themeConfig["refresh_flash"] ?: "true").toBoolean()) {
                refreshFlash = 1f
            }
        }

        Canvas(modifier = modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val t = tSec
            val load = state.inferenceLoad
            val lowPower = state.lowPowerMode

            // ── 1. Substrate ──
            drawRect(substrate)

            // Substrate noise
            val arr = ensureNoise(w, h)
            val ns = noise
            var idx = (ns * 100).toInt()
            for (v in arr) {
                val bx = (idx % w.toInt()).toFloat()
                val by = (idx / w.toInt()).toFloat()
                val c = (0.94f + v).coerceIn(0f, 1f)
                drawCircle(Color(c, c, c), 1f, Offset(bx, by))
                idx++
            }

            // ── 7. Scanlines (column-driver grid) ──
            if (!lowPower) {
                var sx = 0f
                while (sx < w) {
                    drawLine(Color(0f, 0f, 0f, 0.03f), Offset(sx, 0f), Offset(sx, h), 1f)
                    sx += 4f
                }
            }

            // ── 2. Top info rail ──
            val railY = 8f
            drawLine(midGrey, Offset(0f, railY), Offset(w, railY), 1f)
            val stats = buildString {
                append("${state.tokensPerSecond.toInt()} t/s")
                append(" \u00B7 ")
                append("Q${state.queueDepth}")
                append(" \u00B7 ")
                append("${state.batteryLevel}%")
                append(" \u00B7 ")
                append("${state.batteryTemperature.toInt()}\u00B0C")
            }
            val statX = w - 6f - stats.length * 5.5f
            drawEinkText(stats, statX, railY + 3f, midGrey)

            // ── 3. Display number (large, bold, centered) ──
            val numStr = (load * 100).toInt().coerceIn(0, 100).toString().padStart(3, '0')
            val numSize = (w * 0.13f).coerceIn(30f, 80f)
            val numCx = w / 2f
            val numCy = h * 0.48f
            drawEinkLargeNumber(numStr, numCx, numCy, numSize)

            // ── 4. Activity bar (bottom quarter) ──
            if (!lowPower) {
                val barY = h * 0.82f
                val barH = h * 0.04f
                val barW = w * 0.85f
                val barX = (w - barW) / 2f

                // Frame
                drawRect(inkBlack, Offset(barX, barY), Size(barW, barH), style = Stroke(1f))

                // 20 discrete segments
                val segments = 20
                val segW = barW / segments
                val filledSegs = (load * segments).toInt().coerceIn(0, segments)
                for (i in 0 until filledSegs) {
                    drawRect(inkBlack, Offset(barX + i * segW + 1f, barY + 1f), Size(segW - 2f, barH - 2f))
                }
            }

            // ── 5. Status indicators (left rail) ──
            val railX = 8f
            val dotR = 2.5f
            val startY = h * 0.68f
            val gap = h * 0.05f

            // Battery dot
            val battCenter = Offset(railX, startY)
            if (state.isCharging) {
                drawCircle(inkBlack, dotR, battCenter)
            } else if (state.batteryLevel < 15) {
                drawCircle(inkBlack, dotR, battCenter, style = Stroke(1.2f))
                drawLine(inkBlack, Offset(railX - 2f, startY - 2f), Offset(railX + 2f, startY + 2f), 1f)
            } else {
                drawCircle(inkBlack, dotR, battCenter, style = Stroke(1.2f))
            }

            // Temperature dot
            val tempCenter = Offset(railX, startY + gap)
            if (state.batteryTemperature > 42f) {
                drawCircle(inkBlack, dotR, tempCenter)
                drawLine(inkBlack, Offset(railX - 2f, tempCenter.y - 2f), Offset(railX + 2f, tempCenter.y + 2f), 1f)
            } else {
                drawCircle(inkBlack, dotR, tempCenter, style = Stroke(1.2f))
            }

            // Health dot
            val healthCenter = Offset(railX, startY + gap * 2f)
            if (!state.isHealthy) {
                drawCircle(inkBlack, dotR, healthCenter)
                drawLine(inkBlack, Offset(railX - 2f, healthCenter.y - 2f), Offset(railX + 2f, healthCenter.y + 2f), 1f)
                drawLine(inkBlack, Offset(railX - 2f, healthCenter.y + 2f), Offset(railX + 2f, healthCenter.y - 2f), 1f)
            } else {
                drawCircle(inkBlack, dotR, healthCenter, style = Stroke(1.2f))
            }

            // Vertical rail line
            drawLine(lightGrey, Offset(railX, startY - gap * 0.5f), Offset(railX, startY + gap * 2f + gap * 0.5f), 1f)

            // ── 6. Full-screen refresh flash ──
            if (refreshFlash > 0f) {
                refreshFlash -= 0.016f
                if (refreshFlash > 0.08f) {
                    // Phase 1: white → black
                    val tFlash = (refreshFlash - 0.08f) / 0.1f
                    val a = tFlash.coerceIn(0f, 1f)
                    drawRect(inkBlack.copy(alpha = a))
                } else if (refreshFlash > 0f) {
                    // Phase 2: black → white
                    val tFlash = refreshFlash / 0.08f
                    val a = (1f - tFlash).coerceIn(0f, 1f)
                    drawRect(inkBlack.copy(alpha = a))
                } else {
                    refreshFlash = 0f
                }
            }
        }
    }

    // ── E-Ink text rendering (thin sans-serif, no antialiasing illusion) ──

    // 5×7 monospaced bitmasks for ASCII characters used in stats
    private val font5x7 = mapOf(
        '0' to intArrayOf(0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E),
        '1' to intArrayOf(0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E),
        '2' to intArrayOf(0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F),
        '3' to intArrayOf(0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E),
        '4' to intArrayOf(0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02),
        '5' to intArrayOf(0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E),
        '6' to intArrayOf(0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E),
        '7' to intArrayOf(0x1F, 0x01, 0x02, 0x04, 0x04, 0x04, 0x04),
        '8' to intArrayOf(0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E),
        '9' to intArrayOf(0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C),
        ' ' to intArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
        '.' to intArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04),
        '·' to intArrayOf(0x00, 0x00, 0x00, 0x0E, 0x00, 0x00, 0x00),
        'Q' to intArrayOf(0x0E, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0D),
        '%' to intArrayOf(0x10, 0x10, 0x02, 0x04, 0x08, 0x10, 0x10),
        't' to intArrayOf(0x04, 0x04, 0x1E, 0x04, 0x04, 0x04, 0x02),
        's' to intArrayOf(0x00, 0x0E, 0x10, 0x0C, 0x02, 0x1C, 0x00),
        '/' to intArrayOf(0x01, 0x02, 0x04, 0x08, 0x10, 0x00, 0x00),
        '°' to intArrayOf(0x06, 0x09, 0x06, 0x00, 0x00, 0x00, 0x00),
        'C' to intArrayOf(0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E),
        '-' to intArrayOf(0x00, 0x00, 0x1F, 0x00, 0x00, 0x00, 0x00),
    )

    private fun DrawScope.drawEinkText(text: String, x: Float, y: Float, c: Color) {
        val chW = 4f
        val chH = 7f
        var cx = x
        for (ch in text) {
            val rows = font5x7[ch] ?: intArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
            for ((ri, mask) in rows.withIndex()) {
                for (ci in 0..4) {
                    if (mask and (1 shl (4 - ci)) != 0) {
                        drawRect(c, Offset(cx + ci, y + ri), Size(1f, 1f))
                    }
                }
            }
            cx += chW + 1.5f
        }
    }

    private fun DrawScope.drawEinkLargeNumber(text: String, cx: Float, cy: Float, size: Float) {
        val chW = size * 0.6f
        val chH = size
        val gap = size * 0.1f
        val totalW = text.length * (chW + gap) - gap
        val startX = cx - totalW / 2f

        // Thick felt-tip marker look: stroke each character with weight
        // Render as solid block with 2px-ish stroke offset for thickness
        for ((i, ch) in text.withIndex()) {
            val rows = font5x7[ch] ?: intArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
            val ox = startX + i * (chW + gap)
            val baseY = cy - chH / 2f
            val scaleX = chW / 5f
            val scaleY = chH / 7f

            // Draw each pixel enlarged with 2px offset for thickness
            for ((ri, mask) in rows.withIndex()) {
                for (ci in 0..4) {
                    if (mask and (1 shl (4 - ci)) != 0) {
                        val px = ox + ci * scaleX
                        val py = baseY + ri * scaleY
                        // Thick marker look: fill a slightly larger block
                        drawRect(inkBlack, Offset(px - 1f, py - 1f), Size(scaleX + 2f, scaleY + 2f))
                    }
                }
            }
        }
    }
}

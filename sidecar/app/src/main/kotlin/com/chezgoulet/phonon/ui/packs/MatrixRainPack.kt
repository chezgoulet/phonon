package com.chezgoulet.phonon.ui.packs

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.chezgoulet.phonon.ui.VisualizationPack
import com.chezgoulet.phonon.ui.VizState
import kotlin.math.*
import kotlin.random.Random

/**
 * Matrix Rain — CRT green phosphor visualization pack.
 *
 * Columns of falling green characters (hex digits, katakana, symbols)
 * create the classic rain effect. Rain density, speed, and brightness
 * respond to inference activity. A subtle CRT scanline overlay completes
 * the retro terminal aesthetic. Peer devices appear as brighter column
 * clusters at their arranged positions.
 *
 * Low power mode: fewer columns (6 vs 16), slower rain, no scanlines,
 * no peer highlights.
 */
object MatrixRainPack : VisualizationPack {

    override val id = "matrix-rain"
    override val name = "Matrix Rain"
    override val description = "CRT green phosphor rain driven by inference activity"
    override val author = "chezgoulet"
    override val version = "1.0.0"

    override val defaultConfig = mapOf(
        "rain_density" to "1.0",
        "rain_speed" to "1.0",
        "char_brightness" to "1.0",
        "glow_color" to "#00FF41"
    )

    // Reusable character pool — hex + katakana vibes
    private val charPool = listOf(
        '0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F',
        'ア','イ','ウ','エ','オ','カ','キ','ク','ケ','コ',
        'サ','シ','ス','セ','ソ','タ','チ','ツ','テ','ト',
        'ナ','ニ','ヌ','ネ','ノ','ハ','ヒ','フ','ヘ','ホ',
        'マ','ミ','ム','メ','モ','ヤ','ユ','ヨ','ラ','リ',
        'ル','レ','ロ','ワ','ヲ','ン',
        ':',';','<','>','?','@','[',']','^','_','`','{','|','}','~'
    ).map { it.toString() }

    private const val DEFAULT_COLUMNS = 16
    private const val LOW_POWER_COLUMNS = 6
    private const val CHARS_PER_COLUMN = 20

    @Composable
    override fun Render(state: VizState, modifier: Modifier) {
        val density = (state.themeConfig["rain_density"] ?: "1.0").toFloatOrNull() ?: 1.0f
        val speedMul = (state.themeConfig["rain_speed"] ?: "1.0").toFloatOrNull() ?: 1.0f
        val brightness = (state.themeConfig["char_brightness"] ?: "1.0").toFloatOrNull() ?: 1.0f
        val glowColor = parseHexColor(state.themeConfig["glow_color"] ?: "#00FF41")

        val lowPower = state.lowPowerMode
        val columnCount = if (lowPower) LOW_POWER_COLUMNS
                          else (DEFAULT_COLUMNS * density).toInt().coerceIn(4, 30)

        val textMeasurer = rememberTextMeasurer()

        // ── Rain state: each column has a leader position, speed offset, and char set ──
        val rainState = remember {
            val rng = Random(42)
            List(columnCount) {
                RainColumn(
                    leader = rng.nextFloat() * CHARS_PER_COLUMN,
                    speed = 0.5f + rng.nextFloat() * 1.5f,
                    charOffset = rng.nextInt(charPool.size)
                )
            }
        }

        // Inference speed multiplier — processed packets = faster rain
        val infSpeed = when {
            state.isProcessing -> 1.8f + state.inferenceLoad
            else -> 0.6f
        }

        // ── Animation ──
        val infiniteTransition = rememberInfiniteTransition(label = "matrixRain")

        val time by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1000f,
            animationSpec = infiniteRepeatable(
                animation = tween(if (lowPower) 30000 else 20000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "time"
        )

        val scanlinePhase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "scanline"
        )

        Canvas(modifier = modifier.fillMaxSize()) {
            val cw = size.width / columnCount
            val ch = size.height / CHARS_PER_COLUMN
            val charSize = ch * 0.85f

            // ── Background ──
            drawRect(Color(0xFF0A0A0A))

            // ── Dim column backgrounds (subtle phosphor glow) ──
            if (!lowPower) {
                for (c in rainState.indices) {
                    val bgX = c * cw
                    val bgAlpha = 0.015f + 0.01f * (c % 3)
                    drawRect(
                        color = glowColor.copy(alpha = bgAlpha),
                        topLeft = Offset(bgX, 0f),
                        size = androidx.compose.ui.geometry.Size(cw, size.height)
                    )
                }
            }

            // ── Draw each column of falling characters ──
            val baseSpeed = (0.8f + 0.4f * infSpeed) * speedMul
            val currentTime = time / 1000f * baseSpeed
            val charAlpha = if (lowPower) 0.4f * brightness else (0.5f * brightness).coerceIn(0.15f, 1f)
            val leadAlpha = if (lowPower) 0.6f * brightness else (0.9f * brightness).coerceIn(0.3f, 1f)

            for (colIdx in rainState.indices) {
                val col = rainState[colIdx]
                val x = colIdx * cw

                // Leader position scrolling through the column
                val scroll = (currentTime * col.speed) % CHARS_PER_COLUMN
                val leaderRow = ((col.leader + scroll) % CHARS_PER_COLUMN).toInt()

                for (row in 0 until CHARS_PER_COLUMN) {
                    val y = row * ch
                    val charIdx = (row + col.charOffset) % charPool.size
                    val char = charPool[charIdx]

                    // Distance from leader: 0 = brightest (leader), higher = dimmer trail
                    val distFromLeader = if (row <= leaderRow) leaderRow - row
                                         else (CHARS_PER_COLUMN - row + leaderRow)

                    if (distFromLeader > 12) continue // Fade out long trail

                    val trailFade = when {
                        distFromLeader == 0 -> 1f // Leader
                        distFromLeader <= 2 -> 0.7f
                        distFromLeader <= 5 -> 0.4f
                        else -> 0.15f
                    }

                    val alpha = if (distFromLeader == 0) leadAlpha
                                else charAlpha * trailFade

                    val color = if (distFromLeader == 0 && !lowPower) {
                        // Leader: bright white-green
                        Color(0xFFCCFFCC).copy(alpha = leadAlpha)
                    } else {
                        glowColor.copy(alpha = alpha)
                    }

                    // Draw the character
                    val textResult = textMeasurer.measure(
                        text = AnnotatedString(char),
                        style = TextStyle(
                            color = color,
                            fontSize = charSize.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    drawText(
                        textLayoutResult = textResult,
                        topLeft = Offset(
                            x = x + (cw - textResult.size.width) / 2f,
                            y = y
                        )
                    )

                    // Leader glow halo
                    if (distFromLeader == 0 && !lowPower) {
                        drawCircle(
                            color = Color(0xFFCCFFCC).copy(alpha = 0.08f),
                            radius = charSize * 0.8f,
                            center = Offset(x + cw / 2f, y + ch / 2f)
                        )
                    }
                }

                // ── Peer device highlight ──
                // Find peers whose x-position maps near this column
                if (!lowPower) {
                    for (peer in state.peerStates) {
                        val pos = peer.position ?: continue
                        val peerX = pos.x * size.width
                        val peerCol = (peerX / cw).toInt()
                        if (peerCol == colIdx) {
                            // Highlight this column with a brighter center band
                            val highlightY = (pos.y * size.height).coerceIn(0f, size.height - ch)
                            val bandY = (highlightY / ch).toInt() * ch
                            val bandAlpha = if (peer.isProcessing) 0.15f else 0.06f
                            drawRect(
                                color = glowColor.copy(alpha = bandAlpha),
                                topLeft = Offset(x, bandY),
                                size = androidx.compose.ui.geometry.Size(cw, ch * 3f)
                            )
                        }
                    }
                }
            }

            // ── CRT scanline overlay ──
            if (!lowPower) {
                val scanY = (scanlinePhase * size.height).toInt()
                drawRect(
                    color = Color.White.copy(alpha = 0.03f),
                    topLeft = Offset(0f, scanY),
                    size = androidx.compose.ui.geometry.Size(size.width, 2f)
                )
                // Static faint scanlines
                var sy = 0f
                while (sy < size.height) {
                    drawRect(
                        color = Color.Black.copy(alpha = 0.04f),
                        topLeft = Offset(0f, sy),
                        size = androidx.compose.ui.geometry.Size(size.width, 1f)
                    )
                    sy += 4f
                }
            }

            // ── Workload indicator: ambient glow intensity ──
            if (state.workload > 0.3f && !lowPower) {
                val glowAlpha = (state.workload * 0.08f).coerceAtMost(0.08f)
                val glowGradient = Brush.verticalGradient(
                    colors = listOf(glowColor.copy(alpha = glowAlpha), Color.Transparent),
                    startY = 0f,
                    endY = size.height * 0.3f
                )
                drawRect(glowGradient)
            }

            // ── Processing flash ──
            if (state.isProcessing && !lowPower) {
                val flashAlpha = 0.02f + 0.03f * (sin(time * 0.05f).toFloat().coerceIn(0f, 1f))
                drawRect(color = glowColor.copy(alpha = flashAlpha))
            }
        }
    }

    /**
     * Per-column rain parameters. Created once and reused across frames
     * so each column keeps its unique character and rhythm.
     */
    private data class RainColumn(
        val leader: Float,
        val speed: Float,
        val charOffset: Int
    )
}

/** Parse a hex color string (e.g. "#00FF41") into [Color]. */
private fun parseHexColor(hex: String): Color {
    val sanitized = hex.removePrefix("#")
    val rgb = sanitized.toLong(16)
    return Color(
        red = ((rgb shr 16) and 0xFF) / 255f,
        green = ((rgb shr 8) and 0xFF) / 255f,
        blue = (rgb and 0xFF) / 255f,
        alpha = 1f
    )
}

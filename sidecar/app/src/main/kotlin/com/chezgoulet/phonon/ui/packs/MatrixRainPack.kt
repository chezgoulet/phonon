package com.chezgoulet.phonon.ui.packs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.chezgoulet.phonon.ui.VisualizationPack
import com.chezgoulet.phonon.ui.VizState
import kotlin.math.floor
import kotlin.random.Random

/**
 * Matrix Rain — CRT green phosphor visualization pack.
 *
 * Columns of falling glyphs that mutate as they fall, leaders blooming with a
 * soft phosphor halo. Rain speed tracks workload, brightness tracks battery
 * level, and the phosphor hue bends toward amber (>35°C) then red (>42°C) as
 * the phone heats up. A bright beam sweeps down on each inference, leaving a
 * widening afterglow, and a small HUD reports live telemetry. Persistent CRT
 * scanlines complete the retro terminal look.
 *
 * Low power mode: 7 dim columns, no bloom, no beam, no HUD, no scan sweep.
 */
object MatrixRainPack : VisualizationPack {

    override val id = "matrix-rain"
    override val name = "Matrix Rain"
    override val description = "CRT phosphor rain: speed tracks workload, brightness tracks battery, hue bends red with heat"
    override val author = "chezgoulet"
    override val version = "1.1.0"

    override val defaultConfig = mapOf(
        "base_color" to "#00FF41",
        "bg_color" to "#040806",
        "column_density" to "1.0",
        "fall_speed" to "1.0",
        "char_brightness" to "1.0",
    )

    private val GLYPHS = ("0123456789ABCDEF" +
        "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホ" +
        ":;<>?@[]^_{|}~").map { it.toString() }

    private const val ROWS = 22

    private data class RainColumn(val leader: Float, val speed: Float, val offset: Int, val mutate: Float)

    @Composable
    override fun Render(state: VizState, modifier: Modifier) {
        val baseColor = parseHexColor(state.themeConfig["base_color"] ?: "#00FF41")
        val density = (state.themeConfig["column_density"] ?: "1.0").toFloatOrNull() ?: 1.0f
        val lowPower = state.lowPowerMode
        val colCount = if (lowPower) 7 else (20 * density).toInt().coerceIn(6, 30)
        val measurer = rememberTextMeasurer()

        val columns = remember(colCount) {
            val rng = Random(42)
            List(colCount) {
                RainColumn(rng.nextFloat() * ROWS, 0.5f + rng.nextFloat() * 1.5f, rng.nextInt(GLYPHS.size), rng.nextFloat() * 100f)
            }
        }

        var tSec by remember { mutableStateOf(0f) }
        var beamStart by remember { mutableStateOf(-1f) }
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                tSec = (now - start) / 1_000_000_000f
            }
        }

        Canvas(modifier = modifier.fillMaxSize()) {
            val t = tSec
            val cw = size.width / colCount
            val ch = size.height / ROWS
            val charPx = ch * 0.82f

            drawRect(Color(0xFF040806))

            // thermal hue shift
            val amber = Color(0xFFEAB308)
            val red = Color(0xFFEF4444)
            val glyphColor = when {
                state.batteryTemperature > 42f -> blend(amber, red, ((state.batteryTemperature - 42f) / 8f).clampTo(0f, 1f))
                state.batteryTemperature > 35f -> blend(baseColor, amber, ((state.batteryTemperature - 35f) / 7f).clampTo(0f, 1f))
                else -> baseColor
            }
            // battery brightness (100%→full, 20%→dim)
            val batt = lerpF(0.35f, 1f, (state.batteryLevel - 20) / 80f).clampTo(0.3f, 1f)
            val infSpeed = if (state.isProcessing) 1.8f + state.inferenceLoad else 0.55f
            val leaderColor = Color(0xFFD2FFD2)

            for (c in columns.indices) {
                val col = columns[c]
                val x = c * cw + cw / 2f
                val scroll = (t * infSpeed * col.speed) % ROWS
                val leaderRow = floor((col.leader + scroll) % ROWS).toInt()

                if (!lowPower) drawRect(glyphColor.copy(alpha = 0.015f + 0.01f * (c % 3)), Offset(c * cw, 0f), Size(cw, size.height))

                for (row in 0 until ROWS) {
                    val y = row * ch
                    val dist = if (row <= leaderRow) leaderRow - row else ROWS - row + leaderRow
                    if (dist > 13) continue

                    val mutRate = if (dist == 0) 14 else 3
                    val gi = (row + col.offset + (t * mutRate + col.mutate).toInt()) % GLYPHS.size
                    val glyph = GLYPHS[gi]

                    val trailFade = when {
                        dist == 0 -> 1f
                        dist <= 2 -> 0.7f
                        dist <= 5 -> 0.42f
                        else -> 0.16f
                    }
                    val leadAlpha = (if (lowPower) 0.6f else 0.95f) * batt
                    val alpha = if (dist == 0) leadAlpha else (if (lowPower) 0.4f else 0.55f) * batt * trailFade

                    if (!lowPower && dist <= 1) {
                        val bc = if (dist == 0) leaderColor else glyphColor
                        val br = charPx * 0.85f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(bc.copy(alpha = (if (dist == 0) 0.22f else 0.1f) * batt), bc.copy(alpha = 0f)),
                                center = Offset(x, y + ch / 2f), radius = br,
                            ),
                            radius = br, center = Offset(x, y + ch / 2f),
                        )
                    }

                    val color = if (dist == 0 && !lowPower) leaderColor.copy(alpha = leadAlpha) else glyphColor.copy(alpha = alpha)
                    val r = measurer.measure(
                        AnnotatedString(glyph),
                        TextStyle(color = color, fontSize = charPx.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                    )
                    drawText(r, topLeft = Offset(x - r.size.width / 2f, y))
                }

                // peer highlight bands
                if (!lowPower) {
                    for (peer in state.peerStates) {
                        val pp = peer.position ?: continue
                        if ((pp.x * size.width / cw).toInt() == c) {
                            val bandY = (pp.y * ROWS).toInt() * ch
                            drawRect(glyphColor.copy(alpha = if (peer.isProcessing) 0.16f else 0.06f), Offset(c * cw, bandY), Size(cw, ch * 3f))
                        }
                    }
                }
            }

            // processing beam
            if (state.isProcessing && !lowPower) {
                if (beamStart < 0f || t - beamStart > 1.6f) beamStart = t
                val age = (t - beamStart) / 1.6f
                if (age <= 1f) {
                    val beamY = age * size.height
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(leaderColor.copy(alpha = 0f), leaderColor.copy(alpha = 0.18f * (1f - age))),
                            startY = beamY - size.height * 0.18f, endY = beamY,
                        ),
                        topLeft = Offset(0f, beamY - size.height * 0.18f), size = Size(size.width, size.height * 0.18f),
                    )
                    drawRect(Color(0xFFE6FFE6).copy(alpha = 0.5f * (1f - age)), Offset(0f, beamY - 2f), Size(size.width, 3f))
                }
            }

            // CRT scanlines (always present)
            var sy = 0f
            while (sy < size.height) { drawRect(Color.Black.copy(alpha = 0.12f), Offset(0f, sy), Size(size.width, 1f)); sy += 3f }
            if (!lowPower) drawRect(Color.White.copy(alpha = 0.04f), Offset(0f, ((t * 0.5f) % 1f) * size.height), Size(size.width, 2f))

            // top-right HUD
            if (!lowPower) {
                val lines = listOf(
                    "TPS ${state.tokensPerSecond.toInt()}",
                    "Q ${state.queueDepth}",
                    "BAT ${state.batteryLevel}%",
                    "${"%.1f".format(state.batteryTemperature)}C",
                )
                val hudPx = charPx * 0.6f
                lines.forEachIndexed { i, ln ->
                    val r = measurer.measure(AnnotatedString(ln), TextStyle(color = glyphColor.copy(alpha = 0.5f), fontSize = hudPx.sp, fontFamily = FontFamily.Monospace))
                    drawText(r, topLeft = Offset(size.width - 8f - r.size.width, 8f + i * hudPx * 1.25f))
                }
            }
        }
    }
}

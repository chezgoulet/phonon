package com.chezgoulet.phonon.ui.packs

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.chezgoulet.phonon.ui.VisualizationPack
import com.chezgoulet.phonon.ui.VizState
import kotlin.math.*

/**
 * Cyber HUD — tactical cyberpunk heads-up display visualization pack.
 *
 * Angular frame elements, radar sweep, device stats, and a processing
 * waveform create a military-grade HUD aesthetic. Peer devices appear
 * as blips on the radar. The entire layout responds to inference state
 * with animated waveforms and alert-style highlights.
 *
 * Low power mode: static HUD outline, no radar sweep, no waveform
 * animation, fewer frame elements.
 */
object CyberHudPack : VisualizationPack {

    override val id = "cyber-hud"
    override val name = "Cyber HUD"
    override val description = "Tactical cyberpunk HUD visualization"
    override val author = "chezgoulet"
    override val version = "1.0.0"

    override val defaultConfig = mapOf(
        "hud_color_primary" to "#00E5FF",
        "hud_color_accent" to "#FF3D00",
        "hud_color_text" to "#00E5FF",
        "radar_range" to "0.4",
        "waveform_sensitivity" to "1.0"
    )

    @Composable
    override fun Render(state: VizState, modifier: Modifier) {
        val primaryColor = parseHexColor(state.themeConfig["hud_color_primary"] ?: "#00E5FF")
        val accentColor = parseHexColor(state.themeConfig["hud_color_accent"] ?: "#FF3D00")
        val textColor = parseHexColor(state.themeConfig["hud_color_text"] ?: "#00E5FF")
        val radarRange = (state.themeConfig["radar_range"] ?: "0.4").toFloatOrNull() ?: 0.4f
        val waveSensitivity = (state.themeConfig["waveform_sensitivity"] ?: "1.0").toFloatOrNull() ?: 1.0f

        val lowPower = state.lowPowerMode
        val textMeasurer = rememberTextMeasurer()

        // ── Animations ──
        val infiniteTransition = rememberInfiniteTransition(label = "cyberHud")

        val radarDeg by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(if (lowPower) 0 else 3000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "radarSweep"
        )

        val pulse by infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = EaseInOutCubic),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )

        val waveformPhase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(if (lowPower) 0 else 2000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "waveform"
        )

        val glitchPulse by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing = EaseInOutCubic),
                repeatMode = RepeatMode.Restart
            ),
            label = "glitch"
        )

        Canvas(modifier = modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val margin = w * 0.04f
            val cornerLen = w * 0.08f

            // ── Background ──
            drawRect(Color(0xFF050510))

            // Subtle grid overlay
            if (!lowPower) {
                val gridColor = primaryColor.copy(alpha = 0.04f)
                val gridS = w * 0.05f
                var gx = gridS
                while (gx < w) {
                    drawLine(gridColor, Offset(gx, 0f), Offset(gx, h), strokeWidth = 0.5f)
                    gx += gridS
                }
                var gy = gridS
                while (gy < h) {
                    drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 0.5f)
                    gy += gridS
                }
            }

            // ── HUD corner brackets ──
            val bracketLen = if (lowPower) cornerLen * 0.5f else cornerLen
            val bracketGap = margin * 0.5f
            val bracketColor = primaryColor.copy(alpha = if (lowPower) 0.3f else (0.5f + 0.3f * pulse))
            val bracketW = if (lowPower) 1.2f else 2f

            // Top-left
            drawLine(bracketColor, Offset(margin, margin), Offset(margin + bracketLen, margin), strokeWidth = bracketW)
            drawLine(bracketColor, Offset(margin, margin), Offset(margin, margin + bracketLen), strokeWidth = bracketW)
            // Top-right
            drawLine(bracketColor, Offset(w - margin, margin), Offset(w - margin - bracketLen, margin), strokeWidth = bracketW)
            drawLine(bracketColor, Offset(w - margin, margin), Offset(w - margin, margin + bracketLen), strokeWidth = bracketW)
            // Bottom-left
            drawLine(bracketColor, Offset(margin, h - margin), Offset(margin + bracketLen, h - margin), strokeWidth = bracketW)
            drawLine(bracketColor, Offset(margin, h - margin), Offset(margin, h - margin - bracketLen), strokeWidth = bracketW)
            // Bottom-right
            drawLine(bracketColor, Offset(w - margin, h - margin), Offset(w - margin - bracketLen, h - margin), strokeWidth = bracketW)
            drawLine(bracketColor, Offset(w - margin, h - margin), Offset(w - margin, h - margin - bracketLen), strokeWidth = bracketW)

            // ── Top status bar ──
            val topBarY = margin + bracketLen + bracketGap
            val topBarColor = primaryColor.copy(alpha = 0.3f * pulse)
            drawLine(topBarColor, Offset(margin, topBarY), Offset(w - margin, topBarY), strokeWidth = 1f)

            // Device ID + model name (left side of top bar)
            val deviceLabel = state.deviceId.takeLast(8)
            val modeLabel = state.coordinatorMode.uppercase()
            val statusLabel = when {
                state.isProcessing -> "INFERENCE"
                else -> "STANDBY"
            }

            drawHudText(textMeasurer, "DEV: $deviceLabel", textColor, 9.sp, Offset(margin + 4f, topBarY + 6f))
            drawHudText(textMeasurer, "MODE: $modeLabel", textColor.copy(alpha = 0.7f), 8.sp, Offset(margin + 4f, topBarY + 20f))

            // Battery (right side of top bar)
            val battText = when {
                state.batteryLevel < 0 -> "BATT: --"
                state.isCharging -> "BATT: ${state.batteryLevel}% ⚡"
                else -> "BATT: ${state.batteryLevel}%"
            }
            val battColor = when {
                state.batteryLevel < 15 -> Color.Red
                state.batteryLevel < 40 -> Color(0xFFEAB308)
                else -> textColor
            }
            drawHudText(textMeasurer, battText, battColor, 9.sp, Offset(w - margin - 140f, topBarY + 6f))
            drawHudText(textMeasurer, statusLabel, accentColor.copy(alpha = 0.6f + 0.4f * pulse), 8.sp, Offset(w - margin - 140f, topBarY + 20f))

            // ── Radar (center) ──
            val radarRadius = (minOf(w, h) * radarRange).coerceIn(w * 0.1f, w * 0.35f)
            val radarCx = w / 2f
            val radarCy = h * 0.45f
            val radarColor = primaryColor.copy(alpha = 0.4f)

            if (!lowPower) {
                // Outer radar ring
                drawCircle(color = radarColor, radius = radarRadius, center = Offset(radarCx, radarCy), style = Stroke(width = 1.5f))
                // Inner rings
                drawCircle(color = radarColor.copy(alpha = 0.15f), radius = radarRadius * 0.66f, center = Offset(radarCx, radarCy), style = Stroke(width = 0.8f))
                drawCircle(color = radarColor.copy(alpha = 0.1f), radius = radarRadius * 0.33f, center = Offset(radarCx, radarCy), style = Stroke(width = 0.8f))

                // Crosshairs
                drawLine(radarColor.copy(alpha = 0.15f), Offset(radarCx - radarRadius, radarCy), Offset(radarCx + radarRadius, radarCy), strokeWidth = 0.5f)
                drawLine(radarColor.copy(alpha = 0.15f), Offset(radarCx, radarCy - radarRadius), Offset(radarCx, radarCy + radarRadius), strokeWidth = 0.5f)

                // Sweeping wedge
                val sweepRad = Math.toRadians(radarDeg.toDouble()).toFloat()
                val sweepColor = primaryColor.copy(alpha = 0.12f)
                val path = Path().apply {
                    moveTo(radarCx, radarCy)
                    arcTo(
                        rect = androidx.compose.ui.geometry.Rect(
                            radarCx - radarRadius, radarCy - radarRadius,
                            radarCx + radarRadius, radarCy + radarRadius
                        ),
                        startAngleDegrees = -90f,
                        sweepAngleDegrees = 45f,
                        forceMoveTo = false
                    )
                    close()
                }
                drawPath(path, sweepColor)

                // Sweep line
                val sweepEndX = radarCx + cos(sweepRad) * radarRadius
                val sweepEndY = radarCy + sin(sweepRad) * radarRadius
                drawLine(
                    color = primaryColor.copy(alpha = 0.5f),
                    start = Offset(radarCx, radarCy),
                    end = Offset(sweepEndX, sweepEndY),
                    strokeWidth = 1.5f
                )

                // Sweep endpoint glow
                drawCircle(
                    color = primaryColor.copy(alpha = 0.6f),
                    radius = 3f,
                    center = Offset(sweepEndX, sweepEndY)
                )

                // ── Peer blips on radar ──
                for (peer in state.peerStates) {
                    val pos = peer.position ?: continue
                    // Map peer position to radar space
                    val relX = (pos.x - 0.5f) * 2f
                    val relY = (pos.y - 0.5f) * 2f
                    val dist = sqrt(relX * relX + relY * relY).coerceAtMost(1f)
                    val angle = atan2(relY, relX)
                    val blipR = dist * radarRadius
                    val bx = radarCx + cos(angle) * blipR
                    val by = radarCy + sin(angle) * blipR

                    if (blipR <= radarRadius) {
                        val blipColor = if (peer.isProcessing) accentColor else primaryColor
                        drawCircle(color = blipColor.copy(alpha = 0.5f), radius = 4f, center = Offset(bx, by))
                        drawCircle(color = blipColor.copy(alpha = 0.8f), radius = 2f, center = Offset(bx, by))

                        // Peer number label
                        if (peer.displayNumber != null && peer.displayNumber > 0) {
                            drawHudText(textMeasurer, peer.displayNumber.toString(), blipColor.copy(alpha = 0.6f), 7.sp, Offset(bx + 6f, by - 4f))
                        }
                    }
                }
            } else {
                // Low power: just a small static circle
                drawCircle(color = radarColor.copy(alpha = 0.15f), radius = radarRadius * 0.5f, center = Offset(radarCx, radarCy), style = Stroke(width = 1f))
                // Center dot
                drawCircle(color = primaryColor.copy(alpha = 0.3f), radius = 2f, center = Offset(radarCx, radarCy))
            }

            // ── Center targeting reticle ──
            val retR = if (lowPower) 6f else 14f
            val retColor = when {
                state.isProcessing -> accentColor.copy(alpha = 0.5f + 0.4f * pulse)
                else -> primaryColor.copy(alpha = 0.3f)
            }
            drawCircle(color = retColor, radius = retR, center = Offset(radarCx, radarCy), style = Stroke(width = 1.2f))
            drawCircle(color = retColor.copy(alpha = 0.3f), radius = retR * 0.5f, center = Offset(radarCx, radarCy), style = Stroke(width = 0.8f))
            // Crosshairs on reticle
            val retGap = retR * 0.3f
            drawLine(retColor, Offset(radarCx - retR, radarCy), Offset(radarCx - retGap, radarCy), strokeWidth = 1f)
            drawLine(retColor, Offset(radarCx + retGap, radarCy), Offset(radarCx + retR, radarCy), strokeWidth = 1f)
            drawLine(retColor, Offset(radarCx, radarCy - retR), Offset(radarCx, radarCy - retGap), strokeWidth = 1f)
            drawLine(retColor, Offset(radarCx, radarCy + retGap), Offset(radarCx, radarCy + retR), strokeWidth = 1f)

            // ── Bottom status line ──
            val bottomLineY = h - margin - bracketLen - bracketGap
            val bottomColor = primaryColor.copy(alpha = 0.2f * pulse)
            drawLine(bottomColor, Offset(margin, bottomLineY), Offset(w - margin, bottomLineY), strokeWidth = 1f)

            // Peer count and processing info
            val peerText = "PEERS: ${state.peerCount}"
            val tempText = "TEMP: ${"%.1f".format(state.batteryTemperature)}°C"
            val tpsText = if (state.tokensPerSecond > 0f) "TPS: ${state.tokensPerSecond.toInt()}" else ""
            drawHudText(textMeasurer, peerText, textColor.copy(alpha = 0.5f), 8.sp, Offset(margin + 4f, bottomLineY + 6f))
            drawHudText(textMeasurer, tempText, textColor.copy(alpha = 0.5f), 8.sp, Offset(w / 2f - 60f, bottomLineY + 6f))
            if (tpsText.isNotEmpty()) {
                drawHudText(textMeasurer, tpsText, textColor.copy(alpha = 0.5f), 8.sp, Offset(w - margin - 100f, bottomLineY + 6f))
            }

            // ── Processing waveform ──
            if (!lowPower && state.isProcessing) {
                val waveY = bottomLineY - 30f
                val waveW = w - margin * 2f
                val waveH = 24f
                val waveColor = accentColor.copy(alpha = 0.3f + 0.2f * pulse)
                val barCount = 40
                val barW = waveW / barCount

                val path2 = Path().apply {
                    val startX = margin
                    moveTo(startX, waveY + waveH)
                    for (i in 0 until barCount) {
                        val x = startX + i * barW
                        val normalizedPhase = (waveformPhase + i.toFloat() / barCount) % 1f
                        val wf = when {
                            state.isProcessing -> 0.2f + 0.8f * abs(sin(normalizedPhase * PI * 3f).toFloat())
                            else -> 0.1f
                        }
                        val barHeight = (wf * waveH * waveSensitivity).coerceAtMost(waveH)
                        lineTo(x, waveY + waveH - barHeight)
                        lineTo(x + barW * 0.7f, waveY + waveH - barHeight)
                        lineTo(x + barW * 0.7f, waveY + waveH)
                    }
                    lineTo(startX + waveW, waveY + waveH)
                    close()
                }
                drawPath(path2, waveColor)
            }

            // ── Ambient glitch effect ──
            if (!lowPower && glitchPulse > 0.95f) {
                val glitchSlice = (glitchPulse * h).toInt().coerceIn(0, h.toInt() - 4)
                val glitchColor = Color.White.copy(alpha = 0.04f)
                drawRect(
                    color = glitchColor,
                    topLeft = Offset(0f, glitchSlice.toFloat()),
                    size = Size(w, 2f)
                )
                val offset = ((glitchPulse * 20f).toInt() % 10) - 5
                drawRect(
                    color = primaryColor.copy(alpha = 0.03f),
                    topLeft = Offset(offset.toFloat(), (glitchSlice + 2).toFloat()),
                    size = Size(w, 1f)
                )
            }

            // ── Low power indicator ──
            if (lowPower) {
                drawHudText(textMeasurer, "LOW POWER MODE", Color(0xFFEAB308).copy(alpha = 0.4f), 7.sp, Offset(margin + 4f, h * 0.85f))
            }

            // ── Unhealthy alert ──
            if (!state.isHealthy) {
                val alertColor = Color.Red.copy(alpha = 0.3f + 0.3f * sin((radarDeg * 5f).toRad()).coerceIn(0f, 1f))
                drawRect(
                    color = alertColor,
                    topLeft = Offset(0f, 0f),
                    size = Size(w, h)
                )
                drawHudText(textMeasurer, "⚠ SYSTEM ALERT", Color.Red, 12.sp, Offset(w / 2f - 80f, h * 0.15f))
            }
        }
    }

    /** Utility to measure and draw a single line of HUD text. */
    private fun DrawScope.drawHudText(
        measurer: TextMeasurer,
        text: String,
        color: Color,
        fontSize: androidx.compose.ui.unit.TextUnit,
        topLeft: Offset
    ) {
        val result = measurer.measure(
            text = AnnotatedString(text),
            style = TextStyle(
                color = color,
                fontSize = fontSize,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp
            )
        )
        drawText(textLayoutResult = result, topLeft = topLeft)
    }
}

/** Parse a hex color string (e.g. "#00E5FF") into [Color]. */
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

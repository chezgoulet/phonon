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
import com.chezgoulet.phonon.ui.VisualizationPack
import com.chezgoulet.phonon.ui.VizState
import kotlin.math.*

/**
 * Neon Ring — synthwave-inspired visualization pack.
 *
 * Concentric neon rings orbit around the device center, pulsing with
 * inference activity. Connection lines radiate to peer devices. An
 * outer battery arc, ambient workload particles, and a dark purple
 * gradient with horizontal grid lines complete the retro-wave aesthetic.
 *
 * Low power mode: solid dark background, 2 rings, reduced opacity,
 * no particles, no connections, no glow layers.
 */
object NeonRingPack : VisualizationPack {

    override val id = "neon-ring"
    override val name = "Neon Ring"
    override val description = "Synthwave ring visualization driven by inference activity"
    override val author = "chezgoulet"
    override val version = "1.0.0"

    override val defaultConfig = mapOf(
        "ring_color_primary" to "#38BDF8",
        "ring_color_secondary" to "#D946EF",
        "ring_color_processing" to "#22C55E",
        "rotation_speed" to "0.8",
        "glow_intensity" to "1.0"
    )

    @Composable
    override fun Render(state: VizState, modifier: Modifier) {
        // ── Config ──
        val primary = parseHexColor(state.themeConfig["ring_color_primary"] ?: "#38BDF8")
        val secondary = parseHexColor(state.themeConfig["ring_color_secondary"] ?: "#D946EF")
        val procColor = parseHexColor(state.themeConfig["ring_color_processing"] ?: "#22C55E")
        val speedMul = (state.themeConfig["rotation_speed"] ?: "0.8").toFloatOrNull() ?: 0.8f
        val glowMod = (state.themeConfig["glow_intensity"] ?: "1.0").toFloatOrNull() ?: 1.0f

        val lowPower = state.lowPowerMode

        // ── Animations ──
        val infiniteTransition = rememberInfiniteTransition(label = "neonRing")

        val phase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween((2000f / speedMul).toInt(), easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "phase"
        )

        val pulseAmp = if (lowPower) 0.03f else 0.15f
        val pulse by infiniteTransition.animateFloat(
            initialValue = 1f - pulseAmp,
            targetValue = 1f + pulseAmp,
            animationSpec = infiniteRepeatable(
                animation = tween(if (lowPower) 2500 else 1500, easing = EaseInOutCubic),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )

        val procPulse by infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = EaseInOutCubic),
                repeatMode = RepeatMode.Reverse
            ),
            label = "procPulse"
        )

        Canvas(modifier = modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val maxR = minOf(cx, cy)

            // ── Background ──
            if (lowPower) {
                drawRect(Color(0xFF0A0A1A))
            } else {
                // Purple-blue gradient
                val bgGradient = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F0A2E),
                        Color(0xFF1A0640),
                        Color(0xFF2D1B69),
                        Color(0xFF1A0A2E)
                    ),
                    startY = 0f,
                    endY = size.height
                )
                drawRect(bgGradient)

                // Horizontal holographic grid
                val gridSpacing = maxR * 0.12f
                val gridColor = Color(0xFF38BDF8).copy(alpha = 0.05f)
                val gridDash = floatArrayOf(4f, 8f)
                var gy = (cy % gridSpacing + gridSpacing) % gridSpacing
                while (gy <= size.height) {
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, gy),
                        end = Offset(size.width, gy),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(gridDash, gy)
                    )
                    gy += gridSpacing
                }
            }

            // ── Connection lines to peers ──
            if (state.position != null && !lowPower) {
                val lx = state.position.x * size.width
                val ly = state.position.y * size.height
                val connColor = Color(0xFF38BDF8).copy(alpha = 0.12f * glowMod)

                for (peer in state.peerStates) {
                    val pos = peer.position ?: continue
                    val px = pos.x * size.width
                    val py = pos.y * size.height
                    val dist = sqrt((px - lx).pow(2) + (py - ly).pow(2))
                    // Fade line by distance
                    val distAlpha = (1f - (dist / size.width).coerceIn(0f, 1f)).coerceAtLeast(0.1f)

                    // Data pulse traveling along connection
                    val travelPhase = ((phase / 360f * dist) % dist) / dist.coerceAtLeast(1f)
                    val pulseX = lx + (px - lx) * travelPhase
                    val pulseY = ly + (py - ly) * travelPhase

                    drawLine(
                        color = connColor.copy(alpha = connColor.alpha * distAlpha),
                        start = Offset(lx, ly),
                        end = Offset(px, py),
                        strokeWidth = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 10f), phase)
                    )

                    // Traveling data dot
                    drawCircle(
                        color = primary.copy(alpha = 0.5f * distAlpha),
                        radius = 2f,
                        center = Offset(pulseX, pulseY)
                    )
                }

                // Peer device dots
                for (peer in state.peerStates) {
                    val pos = peer.position ?: continue
                    val px = pos.x * size.width
                    val py = pos.y * size.height
                    val peerProcColor = if (peer.isProcessing) procColor else primary
                    drawCircle(
                        color = peerProcColor.copy(alpha = 0.4f),
                        radius = 5f,
                        center = Offset(px, py)
                    )
                    drawCircle(
                        color = peerProcColor.copy(alpha = 0.8f),
                        radius = 2.5f,
                        center = Offset(px, py)
                    )
                }
            }

            // ── Neon rings ──
            val ringCount = if (lowPower) 2 else 4
            val baseRadius = maxR * 0.12f

            for (i in 0 until ringCount) {
                val ringDeg = phase + (i * 360f / ringCount)
                val ringRad = Math.toRadians(ringDeg.toDouble()).toFloat()
                val radius = baseRadius * (1f + i * 0.38f) * pulse

                val ringColor = when {
                    state.isProcessing -> procColor
                    i % 2 == 0 -> primary
                    else -> secondary
                }

                val alpha = if (lowPower) 0.18f
                            else (0.55f - i * 0.1f).coerceIn(0.15f, 0.55f)
                val strokeW = if (lowPower) 1.2f
                              else (3f - i * 0.5f).coerceAtLeast(0.8f)

                if (!lowPower) {
                    // Outer glow
                    drawCircle(
                        color = ringColor.copy(alpha = alpha * 0.25f * glowMod),
                        radius = radius + 10f,
                        center = Offset(cx, cy),
                        style = Stroke(width = strokeW + 6f)
                    )
                }

                // Main ring
                drawCircle(
                    color = ringColor.copy(alpha = alpha),
                    radius = radius,
                    center = Offset(cx, cy),
                    style = Stroke(width = strokeW)
                )

                // Orbiting dot
                val orbRad = if (lowPower) 1.5f else (3f - i * 0.5f).coerceAtLeast(1.8f)
                val dotX = cx + cos(ringRad) * radius
                val dotY = cy + sin(ringRad) * radius
                drawCircle(
                    color = ringColor.copy(alpha = if (lowPower) 0.4f else 0.85f),
                    radius = orbRad,
                    center = Offset(dotX, dotY)
                )
            }

            // ── Center node ──
            val centerR = if (lowPower) 3f else 6f
            val centerAlpha = if (state.isProcessing) 0.6f + 0.4f * procPulse else 0.4f

            if (state.isProcessing && !lowPower) {
                // Processing glow ring
                drawCircle(
                    color = procColor.copy(alpha = 0.2f * procPulse),
                    radius = centerR * 3f,
                    center = Offset(cx, cy)
                )
            }
            // Center dot
            drawCircle(
                color = when {
                    state.isProcessing -> procColor.copy(alpha = centerAlpha)
                    state.isHealthy -> Color.White.copy(alpha = if (lowPower) 0.4f else 0.85f)
                    else -> Color.Red.copy(alpha = 0.8f)
                },
                radius = centerR,
                center = Offset(cx, cy)
            )
            // Center highlight
            if (!lowPower) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.3f),
                    radius = centerR * 0.4f,
                    center = Offset(cx, cy)
                )
            }

            // ── Outer battery arc ──
            if (state.batteryLevel > 0) {
                val arcRadius = maxR * 0.88f
                val battAngle = (state.batteryLevel.coerceIn(0, 100) / 100f) * 360f
                val battColor = when {
                    state.isCharging -> Color(0xFF22C55E)
                    state.batteryLevel < 15 -> Color.Red
                    state.batteryLevel < 40 -> Color(0xFFEAB308)
                    else -> Color(0xFF22C55E)
                }
                val battAlpha = if (lowPower) 0.12f else 0.22f

                drawArc(
                    color = battColor.copy(alpha = battAlpha),
                    startAngle = -90f,
                    sweepAngle = battAngle,
                    useCenter = false,
                    topLeft = Offset(cx - arcRadius, cy - arcRadius),
                    size = Size(arcRadius * 2f, arcRadius * 2f),
                    style = Stroke(width = if (lowPower) 1f else 2f)
                )

                if (state.isCharging && !lowPower) {
                    // Charging pulse on the arc
                    val chargeDeg = -90f + battAngle
                    val chargeRad = Math.toRadians(chargeDeg.toDouble()).toFloat()
                    val chargingGlow = Color(0xFF22C55E).copy(alpha = 0.3f + 0.3f * procPulse)
                    drawCircle(
                        color = chargingGlow,
                        radius = 4f,
                        center = Offset(
                            cx + cos(chargeRad) * arcRadius,
                            cy + sin(chargeRad) * arcRadius
                        )
                    )
                }
            }

            // ── Ambient workload particles ──
            if (!lowPower) {
                val particleCount = (state.workload * 25).toInt().coerceIn(0, 25)
                for (p in 0 until particleCount) {
                    val angle = Math.toRadians((phase + p * 29f).toDouble()).toFloat()
                    val dist = maxR * (0.25f + (p % 6) * 0.09f)
                    val px = cx + cos(angle) * dist
                    val py = cy + sin(angle) * dist
                    val pAlpha = 0.15f + 0.3f * ((p % 3) / 3f)
                    drawCircle(
                        color = primary.copy(alpha = pAlpha),
                        radius = 1.2f,
                        center = Offset(px, py)
                    )
                }
            }
        }
    }
}

/** Parse a hex color string (e.g. "#38BDF8") into [Color]. */
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

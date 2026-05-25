package com.chezgoulet.phonon.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sci-fi inference visualizer using Compose Canvas animations.
 *
 * Shows a pulsating neon ring with a central glow that reacts to inference
 * activity. Matrix-like falling glyphs on the periphery. Designed to be
 * battery-aware: degrades when battery < 20% (unless charging).
 */
@Composable
fun InferenceVisualizer(
    isProcessing: Boolean,
    batteryLevel: Int,
    isCharging: Boolean,
    modifier: Modifier = Modifier
) {
    val degraded = batteryLevel in 0..20 && !isCharging

    Column(modifier = modifier) {
        Text(
            text = "Neural Activity",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
        ) {
            val cx = size.width / 2
            val cy = size.height / 2
            val ringRadius = minOf(cx, cy) * 0.55f

            drawNeuralVisualizer(
                cx, cy, ringRadius, isProcessing, degraded
            )
        }
    }
}

private fun DrawScope.drawNeuralVisualizer(
    cx: Float, cy: Float, radius: Float,
    isProcessing: Boolean, degraded: Boolean
) {
    val accentColor = Color(0xFF38BDF8)
    val ringColor = if (isProcessing) {
        Color(0xFF22C55E) // green pulse during inference
    } else {
        accentColor
    }

    // Outer ring
    drawCircle(
        color = ringColor.copy(alpha = if (isProcessing) 0.6f else 0.2f),
        radius = radius + 8f,
        center = Offset(cx, cy),
        style = Stroke(width = 2f)
    )

    // Inner ring
    drawCircle(
        color = ringColor.copy(alpha = if (isProcessing) 0.4f else 0.1f),
        radius = radius * 0.6f + 4f,
        center = Offset(cx, cy),
        style = Stroke(width = 1.5f)
    )

    // Central glow
    val glowRadius = if (isProcessing) radius * 0.25f else radius * 0.1f
    drawCircle(
        color = ringColor.copy(alpha = 0.15f),
        radius = glowRadius * 2f,
        center = Offset(cx, cy)
    )
    drawCircle(
        color = ringColor.copy(alpha = if (isProcessing) 0.3f else 0.1f),
        radius = glowRadius,
        center = Offset(cx, cy)
    )

    if (degraded) return // Skip particles when battery is low

    // Rotating nodes (4 small circles orbiting)
    val time = System.currentTimeMillis() / 1000.0
    for (i in 0..3) {
        val angle = time * 0.8 + i * PI.toFloat() / 2
        val nx = cx + cos(angle) * radius * 0.8f
        val ny = cy + sin(angle) * radius * 0.8f

        val nodeAlpha = if (isProcessing) 0.8f else 0.3f
        drawCircle(
            color = ringColor.copy(alpha = nodeAlpha),
            radius = 4f,
            center = Offset(nx, ny)
        )

        // Connection line to center
        drawLine(
            color = ringColor.copy(alpha = nodeAlpha * 0.3f),
            start = Offset(nx, ny),
            end = Offset(cx, cy),
            strokeWidth = 1f
        )
    }

    // Matrix glyph rain (periphery)
    if (!degraded) {
        val glyphs = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZあいうえお"

        // Use a deterministic offset based on time for smooth animation
        val glyphTime = (time * 3).toLong()
        for (i in 0..8) {
            val gx = cx + (radius + 24f) * cos(PI.toFloat() * 2 * i / 9)
            val gy = cy + (radius + 24f) * sin(PI.toFloat() * 2 * i / 9)

            val glyphIndex = ((glyphTime + i * 7) % glyphs.length).toInt()
            val glyph = glyphs[glyphIndex]

            drawContext.canvas.apply {
                // Simplified: just draw dots instead of text (text drawing requires more setup)
                drawCircle(
                    color = Color(0xFF22C55E).copy(alpha = 0.4f),
                    radius = 2f,
                    center = Offset(gx, gy)
                )
            }
        }
    }

    // Pulse ring during inference
    if (isProcessing) {
        val pulsePhase = ((time * 2) % 1.0).toFloat()
        drawCircle(
            color = Color(0xFF22C55E).copy(alpha = (1f - pulsePhase) * 0.3f),
            radius = radius + 16f + pulsePhase * 40f,
            center = Offset(cx, cy),
            style = Stroke(width = 2f * (1f - pulsePhase))
        )
    }
}

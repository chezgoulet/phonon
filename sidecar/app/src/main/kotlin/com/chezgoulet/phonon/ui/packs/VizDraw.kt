package com.chezgoulet.phonon.ui.packs

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp as lerpColor

/**
 * Shared drawing helpers for visualization packs.
 *
 * Consolidates utilities that were previously duplicated across each pack
 * file (notably [parseHexColor], which had three identical definitions) and
 * provides the degree→radian helper the packs rely on for sweep math.
 */

/** Parse a hex color string (e.g. "#38BDF8") into [Color]. Falls back to white on bad input. */
internal fun parseHexColor(hex: String): Color {
    val sanitized = hex.removePrefix("#")
    val rgb = sanitized.toLongOrNull(16) ?: return Color.White
    return Color(
        red = ((rgb shr 16) and 0xFF) / 255f,
        green = ((rgb shr 8) and 0xFF) / 255f,
        blue = (rgb and 0xFF) / 255f,
        alpha = 1f,
    )
}

/** Degrees → radians as a [Float]. (Replaces the undefined `toRad()` that broke CyberHudPack.) */
internal fun Float.toRad(): Float = (this * Math.PI / 180.0).toFloat()

/** Linear interpolation between two scalars. */
internal fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Clamp a float into [lo, hi]. */
internal fun Float.clampTo(lo: Float, hi: Float): Float = coerceIn(lo, hi)

/** Blend two colors by [t] in 0..1. Thin wrapper over Compose's [lerp]. */
internal fun blend(from: Color, to: Color, t: Float): Color = lerpColor(from, to, t.coerceIn(0f, 1f))

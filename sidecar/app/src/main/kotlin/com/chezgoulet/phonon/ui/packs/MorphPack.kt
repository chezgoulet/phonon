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
import kotlin.math.*

/**
 * Morph — Material You / dynamic color visualization pack.
 *
 * Adapts to wallpaper colors (via theme config), fluid transitions,
 * rounded everything, and Google's modern design language.
 * The anti-theme: it doesn't look like a theme, it looks like the
 * phone decided what it should be.
 */
object MorphPack : VisualizationPack {

    override val id = "morph"
    override val name = "Morph"
    override val description = "Material You / dynamic color aesthetic — fluid blobs, tonal tiles, and adaptive palette"
    override val author = "chezgoulet"
    override val version = "1.0.0"

    override val defaultConfig = mapOf(
        "use_wallpaper_colors" to "true",
        "blob_speed" to "0.3",
        "ripple_enabled" to "true",
        "dark_mode" to "auto",
        "elevated_surface" to "true",
    )

    // ── Colors (set on activate) ─────────────────────────────────────
    private var seedColor = Color(0xFF1A73E8)      // standard Material teal

    private data class TonalPalette(
        val primary: Color,
        val onPrimary: Color,
        val primaryContainer: Color,
        val secondary: Color,
        val tertiary: Color,
        val error: Color,
        val neutral: Color,
    )

    private var palLight = TonalPalette(
        Color(0xFF1A73E8), Color.White, Color(0xFFD2E3FC),
        Color(0xFF5F6368), Color(0xFF138F5C), Color(0xFFD93025),
        Color(0xFFF8F9FA),
    )

    private var palDark = palLight   // replaced on activate

    override fun onActivate() {
        val seed = seedColor
        palLight = generateTonalPalette(seed, dark = false)
        palDark = generateTonalPalette(seed, dark = true)
        keyframeTime = 0.0  // reset blob animation
    }

    // ── HSL helpers ──────────────────────────────────────────────────
    private fun Color.toHsl(): FloatArray {
        val r = red; val g = green; val b = blue
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        val l = (mx + mn) / 2f
        if (mx == mn) return floatArrayOf(0f, 0f, l)
        val d = mx - mn
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        var h = when {
            mx == r -> (g - b) / d + (if (g < b) 6f else 0f)
            mx == g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        h /= 6f
        return floatArrayOf(h, s, l)
    }

    private fun hslToColor(h: Float, s: Float, l: Float): Color {
        if (s == 0f) return Color(l, l, l)
        fun hue2rgb(p: Float, q: Float, t: Float): Float {
            var tt = t; if (tt < 0f) tt += 1f; if (tt > 1f) tt -= 1f
            return when {
                tt < 1f/6f -> p + (q - p) * 6f * tt
                tt < 1f/2f -> q
                tt < 2f/3f -> p + (q - p) * (2f/3f - tt) * 6f
                else -> p
            }
        }
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        return Color(
            hue2rgb(p, q, h + 1f/3f),
            hue2rgb(p, q, h),
            hue2rgb(p, q, h - 1f/3f),
        )
    }

    private fun generateTonalPalette(seed: Color, dark: Boolean): TonalPalette {
        val (h, s, l) = seed.toHsl()
        // Light theme: primary at tone 40, secondary at tone 50, etc.
        // Dark theme: primary at tone 80, secondary at tone 70, etc.
        val primary = if (dark) hslToColor(h, s * 0.8f, 0.75f) else hslToColor(h, s, if (l > 0.3f) l else 0.4f)
        val onPrimary = if (dark) Color(0xFF003D33) else Color.White
        val primaryContainer = if (dark) Color(0xFF004D40) else Color(0xFFD2E3FC)
        val secondary = if (dark) hslToColor((h + 0.08f) % 1f, s * 0.4f, 0.60f) else Color(0xFF5F6368)
        val tertiary = if (dark) hslToColor((h + 0.12f) % 1f, s * 0.6f, 0.55f) else Color(0xFF138F5C)
        val error = if (dark) Color(0xFFCF6679) else Color(0xFFD93025)
        val neutral = if (dark) Color(0xFF1C1B1F) else Color(0xFFF8F9FA)
        return TonalPalette(primary, onPrimary, primaryContainer, secondary, tertiary, error, neutral)
    }

    // ── Blob keyframes (normalized control-point offsets) ────────────
    private data class BlobKeyframe(val cps: List<Offset>)

    // 6 control points defining a soft blob shape, normalized -1..1
    private val keyframes = listOf(
        BlobKeyframe(listOf(Offset(0.0f, 0.7f), Offset(0.5f, 0.3f), Offset(0.5f, -0.3f), Offset(0.0f, -0.7f), Offset(-0.5f, -0.3f), Offset(-0.5f, 0.3f))),
        BlobKeyframe(listOf(Offset(0.3f, 0.6f), Offset(0.7f, 0.1f), Offset(0.3f, -0.6f), Offset(-0.2f, -0.7f), Offset(-0.7f, -0.2f), Offset(-0.5f, 0.5f))),
        BlobKeyframe(listOf(Offset(-0.2f, 0.6f), Offset(0.4f, 0.5f), Offset(0.6f, -0.2f), Offset(0.2f, -0.7f), Offset(-0.3f, -0.5f), Offset(-0.6f, 0.1f))),
        BlobKeyframe(listOf(Offset(0.1f, 0.7f), Offset(0.6f, 0.2f), Offset(0.3f, -0.5f), Offset(-0.1f, -0.7f), Offset(-0.5f, -0.3f), Offset(-0.6f, 0.3f))),
        BlobKeyframe(listOf(Offset(0.0f, 0.7f), Offset(0.5f, 0.3f), Offset(0.5f, -0.3f), Offset(0.0f, -0.7f), Offset(-0.5f, -0.3f), Offset(-0.5f, 0.3f))),
    )

    private var keyframeTime = 0.0

    private fun DrawScope.drawBlob(pal: TonalPalette, w: Float, h: Float, t: Float, speed: Float) {
        val blobSize = minOf(w, h) * 0.45f
        val cx = w / 2f; val cy = h / 2f

        // Interpolate between keyframes
        val totalFrames = keyframes.size
        val durPerFrame = (30.0 / speed).coerceAtLeast(8.0)
        val phase = (t / durPerFrame.toFloat()) % totalFrames
        val idx = phase.toInt()
        val frac = phase - idx
        val kfA = keyframes[idx % totalFrames]
        val kfB = keyframes[(idx + 1) % totalFrames]

        // Smoothstep
        val f = frac * frac * (3f - 2f * frac)

        val path = Path()
        val pts = kfA.cps.zip(kfB.cps).map { (a, b) ->
            Offset(
                cx + blobSize * (a.x + (b.x - a.x) * f),
                cy + blobSize * (a.y + (b.y - a.y) * f),
            )
        }
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 0 until pts.size) {
            val prev = pts[(i - 1 + pts.size) % pts.size]
            val curr = pts[i]
            val next = pts[(i + 1) % pts.size]
            val cp1 = Offset((prev.x + curr.x) / 2f, (prev.y + curr.y) / 2f)
            val cp2 = Offset((curr.x + next.x) / 2f, (curr.y + next.y) / 2f)
            path.cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, next.x, next.y)
        }
        path.close()

        val primaryAlpha = if (t % 5 < 1) 0.12f else 0.08f
        val primaryBlob = pal.primary.copy(alpha = primaryAlpha)

        // Main blob
        drawPath(path, primaryBlob)

        // Secondary accent blob (smaller, offset)
        val path2 = Path()
        val pts2 = listOf(
            Offset(cx + blobSize * 0.3f + sin(t * 0.4).toFloat() * blobSize * 0.2f, cy + blobSize * 0.2f),
            Offset(cx + blobSize * 0.5f, cy - blobSize * 0.1f),
            Offset(cx + blobSize * 0.2f, cy - blobSize * 0.5f),
            Offset(cx - blobSize * 0.2f, cy - blobSize * 0.4f),
            Offset(cx - blobSize * 0.4f, cy),
            Offset(cx - blobSize * 0.1f, cy + blobSize * 0.3f),
        )
        path2.moveTo(pts2[0].x, pts2[0].y)
        for (i in 0 until pts2.size) {
            val prev = pts2[(i - 1 + pts2.size) % pts2.size]
            val curr = pts2[i]
            val next = pts2[(i + 1) % pts2.size]
            val cp1 = Offset((prev.x + curr.x) / 2f, (prev.y + curr.y) / 2f)
            val cp2 = Offset((curr.x + next.x) / 2f, (curr.y + next.y) / 2f)
            path2.cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, next.x, next.y)
        }
        path2.close()
        drawPath(path2, pal.secondary.copy(alpha = 0.08f))
    }

    // ── Dynamic color tiles (3×2) ──────────────────────────────────
    private fun DrawScope.drawTiles(pal: TonalPalette, w: Float, h: Float, t: Float, load: Float, lowPower: Boolean) {
        val cols = 3; val rows = 2
        val tileW = w * 0.26f; val tileH = h * 0.16f
        val gapX = w * 0.045f; val gapY = h * 0.035f
        val gridW = cols * tileW + (cols - 1) * gapX
        val gridH = rows * tileH + (rows - 1) * gapY
        val startX = (w - gridW) / 2f; val startY = (h - gridH) / 2f

        val tileColors = listOf(pal.primary, pal.secondary, pal.tertiary,
            pal.primaryContainer, pal.neutral, pal.error)

        for (i in 0 until rows) {
            for (j in 0 until cols) {
                val idx = i * cols + j
                val tx = startX + j * (tileW + gapX)
                val ty = startY + i * (tileH + gapY)
                val color = tileColors[idx % tileColors.size]

                // Breathing/pulse
                val baseAlpha = if (lowPower) 0.4f else 1.0f
                val breathPhase = if (load > 0f) {
                    val stagger = idx * 0.2f
                    val speed = if (load > 0.6f) 3f else 1.5f
                    sin((t * speed + stagger) * PI).toFloat() * 0.15f
                } else {
                    sin(t * 0.8f + idx).toFloat() * 0.05f
                }

                val alpha = (baseAlpha + breathPhase).coerceIn(0.3f, 1.0f)
                val c = color.copy(alpha = alpha)

                // Iris effect on processing
                val irisPulse = if (load > 0.05f) {
                    val phase = (t * 2f + idx * 0.3f) % 1f
                    val iris = (1f - phase).coerceIn(0.1f, 1f)
                    (1f - iris) * 0.4f
                } else 0f

                // Rounded rects via intersecting rectangles (Canvas approximation)
                val cornerR = (tileW * 0.08f).coerceAtMost(tileH * 0.08f)
                drawRoundedRect(c, tx, ty, tileW, tileH, cornerR)

                // Iris gradient overlay
                if (irisPulse > 0.01f) {
                    val irisCx = tx + tileW / 2f; val irisCy = ty + tileH / 2f
                    val maxR = maxOf(tileW, tileH) * 0.7f
                    val r = irisPulse * maxR
                    drawCircle(pal.neutral.copy(alpha = irisPulse * 0.3f), r, Offset(irisCx, irisCy))
                }
            }
        }
    }

    // ── Ripple effect ──────────────────────────────────────────────
    private data class Ripple(val startTime: Float, val cx: Float, val cy: Float)

    private var ripples = mutableListOf<Ripple>()
    private var lastLoadTick = 0f

    private fun DrawScope.drawRipples(pal: TonalPalette, w: Float, h: Float, t: Float, load: Float, enabled: Boolean) {
        if (!enabled || load < 0.05f) {
            ripples.clear()
            return
        }
        val cx = w / 2f; val cy = h * 0.48f

        // Trigger new ripples
        val loadTick = (load * 4).toInt()
        if (loadTick > lastLoadTick && load > 0.3f && ripples.size < 5) {
            ripples.add(Ripple(t, cx, cy))
        }
        lastLoadTick = loadTick.toFloat()

        val toRemove = mutableListOf<Ripple>()
        for (ripple in ripples) {
            val age = (t - ripple.startTime).coerceAtLeast(0f)
            if (age > 1.2f) { toRemove.add(ripple); continue }
            val progress = (age / 0.8f).coerceIn(0f, 1f)
            val r = progress * maxOf(w, h) * 0.5f
            val alpha = (1f - progress) * 0.3f
            drawCircle(pal.primary.copy(alpha = alpha.coerceIn(0f, 0.3f)), r, Offset(ripple.cx, ripple.cy), style = Stroke(2f))
        }
        ripples.removeAll(toRemove)
    }

    // ── Display number (rounded sans-serif) ─────────────────────────
    private fun DrawScope.drawDisplayNumber(pal: TonalPalette, w: Float, h: Float, load: Float) {
        val numStr = (load * 100).toInt().coerceIn(0, 100).toString().padStart(3, '0')
        val chW = w * 0.10f; val chH = chW * 1.4f
        val totalW = numStr.length * chW * 0.65f
        val cx = w / 2f - totalW / 2f
        val cy = h * 0.485f - chH / 2f

        // Rounded sans-serif digits via thick rounded rectangles
        for ((i, ch) in numStr.withIndex()) {
            val dx = cx + i * chW * 0.65f
            val nw = chW * 0.50f; val nh = chH * 0.75f
            val ny = cy + (chH - nh) / 2f

            // Simple digit shapes using rounded rects and lines
            drawRoundedRect(pal.onPrimary, dx, ny, nw, nh, nw * 0.2f)
            drawRoundedRect(pal.primary, dx, ny, nw, nh, nw * 0.2f, style = Stroke(2f))

            // Render digit as simple grid
            val gridRows = when (ch) {
                '0' -> intArrayOf(0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110)
                '1' -> intArrayOf(0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110)
                '2' -> intArrayOf(0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111)
                '3' -> intArrayOf(0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110)
                '4' -> intArrayOf(0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010)
                '5' -> intArrayOf(0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110)
                '6' -> intArrayOf(0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110)
                '7' -> intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b00100, 0b00100, 0b00100)
                '8' -> intArrayOf(0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110)
                '9' -> intArrayOf(0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100)
                else -> intArrayOf(0, 0, 0, 0, 0, 0, 0)
            }
            val cw = nw / 5f; val ch2 = nh / 7f
            for ((ri, mask) in gridRows.withIndex()) {
                for (ci in 0..4) {
                    if (mask and (1 shl (4 - ci)) != 0) {
                        drawRect(pal.primary, Offset(dx + ci * cw, ny + ri * ch2), Size(cw, ch2))
                    }
                }
            }
        }
    }

    // ── Elevated surface (bottom card) ─────────────────────────────
    private fun DrawScope.drawElevatedSurface(pal: TonalPalette, w: Float, h: Float, t: Float, state: VizState, dark: Boolean) {
        val sh = h * 0.12f
        val sy = h - sh
        val corner = (w * 0.04f).coerceAtMost(sh * 0.5f)

        // Surface
        val surfaceColor = if (dark) pal.neutral.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.85f)
        drawRoundedRect(surfaceColor, 0f, sy, w, sh, corner)
        drawRoundedRect(pal.primary.copy(alpha = 0.1f), 0f, sy, w, sh, corner, style = Stroke(1f))

        // Battery progress bar
        val barW = w * 0.55f; val barH = sh * 0.12f; val barX = w * 0.05f; val barY = sy + sh * 0.25f
        drawRoundedRect(pal.neutral.copy(alpha = 0.3f), barX, barY, barW, barH, barH * 0.3f)
        val fill = (state.batteryLevel / 100f).coerceIn(0f, 1f)
        val battColor = if (state.batteryLevel < 15) pal.error else if (state.isCharging) pal.tertiary else pal.primary
        if (fill > 0.01f) {
            drawRoundedRect(battColor, barX, barY, barW * fill, barH, barH * 0.3f)
        }

        // Battery text
        drawSimpleText("${state.batteryLevel}%", barX + barW + 6f, barY, pal.primary, sh * 0.3f)

        // Activity dots (inference)
        val dotY = sy + sh * 0.65f
        val dotStartX = w * 0.05f
        val dotSpacing = 6f
        val dotCount = 20
        val activeDots = (state.inferenceLoad * dotCount).toInt().coerceIn(0, dotCount)
        val offset = (t * 4f).toInt() % (dotCount - activeDots + 1).coerceAtLeast(1)
        for (i in 0 until dotCount) {
            val dx = dotStartX + i * dotSpacing
            val isActive = i >= offset && i < offset + activeDots
            drawCircle(
                if (isActive) pal.primary.copy(alpha = 0.6f) else pal.neutral.copy(alpha = 0.2f),
                1.5f, Offset(dx, dotY)
            )
        }

        // Wi-Fi and BT dots
        val rightX = w - 12f
        drawCircle(pal.tertiary, 3f, Offset(rightX, dotY))
        drawCircle(pal.primary, 3f, Offset(rightX - 10f, dotY))
    }

    // ── Simple small text helper (uses path for lightweight rendering) ──
    private fun DrawScope.drawSimpleText(text: String, x: Float, y: Float, color: Color, size: Float) {
        // Placeholder: very simple sans digits
        val chW = size * 0.5f
        var cx = x
        for (ch in text) {
            drawRect(color, Offset(cx, y), Size(chW * 0.3f, size), style = Stroke(1f))
            cx += chW
        }
    }

    // ── Rounded rect helper (Canvas doesn't have this natively) ────
    private fun DrawScope.drawRoundedRect(color: Color, x: Float, y: Float, w: Float, h: Float, r: Float, style: Stroke = Stroke(0f)) {
        val cr = r.coerceAtMost(minOf(w, h) / 2f)
        if (style.width > 0) {
            // Stroke: just draw 4 lines + arcs
            drawLine(color, Offset(x + cr, y), Offset(x + w - cr, y), style.width)
            drawLine(color, Offset(x + w, y + cr), Offset(x + w, y + h - cr), style.width)
            drawLine(color, Offset(x + w - cr, y + h), Offset(x + cr, y + h), style.width)
            drawLine(color, Offset(x, y + h - cr), Offset(x, y + cr), style.width)
        } else {
            // Fill: main rect plus corner squares
            val inner = Path()
            inner.moveTo(x + cr, y)
            inner.lineTo(x + w - cr, y)
            inner.quadraticBezierTo(x + w, y, x + w, y + cr)
            inner.lineTo(x + w, y + h - cr)
            inner.quadraticBezierTo(x + w, y + h, x + w - cr, y + h)
            inner.lineTo(x + cr, y + h)
            inner.quadraticBezierTo(x, y + h, x, y + h - cr)
            inner.lineTo(x, y + cr)
            inner.quadraticBezierTo(x, y, x + cr, y)
            inner.close()
            drawPath(inner, color)
        }
    }

    // ── Composable entry point ─────────────────────────────────────

    @Composable
    override fun Render(state: VizState, modifier: Modifier) {
        var tSec by remember { mutableStateOf(0f) }
        var lastFrame by remember { mutableStateOf(0f) }

        LaunchedEffect(state.deviceId) {
            while (true) {
                val now = withFrameNanos { it }
                val tn = (now / 1_000_000_000f).toFloat()
                lastFrame = tn
                tSec = tn
            }
        }

        // Pick palette based on dark mode config
        val darkModeCfg = state.themeConfig["dark_mode"] ?: "auto"
        val isDark = darkModeCfg == "dark" || (darkModeCfg == "auto" && state.lowPowerMode)
        val pal = if (isDark) palDark else palLight

        val load = state.inferenceLoad
        val lowPower = state.lowPowerMode
        val enableRipple = (state.themeConfig["ripple_enabled"] ?: "true").toBoolean()
        val blobSpeed = (state.themeConfig["blob_speed"] ?: "0.3").toFloatOrNull() ?: 0.3f
        val showSurface = (state.themeConfig["elevated_surface"] ?: "true").toBoolean()

        // Use wallpaper colors from config seed
        val seedStr = state.themeConfig["seed_color"]
        if (seedStr != null) {
            val parsed = try {
                Color(seedStr.toLong(16) or 0xFF000000L)
            } catch (_: Exception) { seedColor }
            if (parsed != seedColor) {
                seedColor = parsed
                palLight = generateTonalPalette(seedColor, dark = false)
                palDark = generateTonalPalette(seedColor, dark = true)
            }
        }

        Canvas(modifier = modifier.fillMaxSize()) {
            val w = size.width; val h = size.height; val t = tSec

            // Background
            val bg = if (isDark) Color(0xFF121212) else pal.neutral
            drawRect(bg)

            // 1. Fluid background blob
            if (!lowPower) drawBlob(pal, w, h, t, blobSpeed)

            // 2. Dynamic color tiles
            drawTiles(pal, w, h, t, load, lowPower)

            // 3. Display number
            drawDisplayNumber(pal, w, h, load)

            // 4. Ripples
            drawRipples(pal, w, h, t, load, enableRipple && !lowPower)

            // 5. Elevated surface
            if (showSurface) drawElevatedSurface(pal, w, h, t, state, isDark)

            // Low battery overlay
            if (state.batteryLevel < 15 && !state.isCharging) {
                drawRect(pal.error.copy(alpha = 0.05f))
            }

            // Charging glow
            if (state.isCharging) {
                drawCircle(pal.tertiary.copy(alpha = 0.04f), w * 0.5f, Offset(w / 2f, h))
            }

            // Overheating: warm tint
            if (state.batteryTemperature > 42f) {
                drawRect(pal.error.copy(alpha = 0.06f))
            }

            // Unhealthy: static error
            if (!state.isHealthy) {
                drawRect(pal.error.copy(alpha = 0.12f))
            }
        }
    }
}

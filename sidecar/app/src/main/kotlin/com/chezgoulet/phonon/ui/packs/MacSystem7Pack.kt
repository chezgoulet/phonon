package com.chezgoulet.phonon.ui.packs

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Macintosh System 7 — retro-computing classic UI aesthetic.
 *
 * Platinum grey desktop with Chicago-style bitmap elements,
 * Finder window chrome, menu bar, trash can, and system face icons.
 *
 * Palette:
 *   BG: Platinum grey (#DDDDDD) with slight noise
 *   Window: White fill, platinum title bar (#AAAAAA)
 *   Accents: Classic Mac blue (#0000FF), active selection (#0080FF)
 *   Text: Black (#000000), Error: Bomb red (#DD0000)
 */
object MacSystem7Pack : VisualizationPack {

    override val name: String = "Macintosh System 7"

    // ── Palette ──────────────────────────────────────────────────────
    private val platinum    = Color(0xFFDDDDDD)
    private val titleBar    = Color(0xFFAAAAAA)
    private val chromeOuter = Color(0xFFFFFFFF)
    private val chromeInner = Color(0xFF888888)
    private val macBlue     = Color(0xFF0000FF)
    private val textBlack   = Color(0xFF000000)
    private val bombRed     = Color(0xFFDD0000)
    private val shadowColor = Color(0x40000000)

    // Chicago bitmap digits: each row = hex bitmask (LSB = left), 6 bits wide, 8 rows
    private val chicagoDigits = arrayOf(
        intArrayOf(0x1E, 0x21, 0x21, 0x21, 0x21, 0x21, 0x21, 0x1E), // 0
        intArrayOf(0x08, 0x18, 0x08, 0x08, 0x08, 0x08, 0x08, 0x1C), // 1
        intArrayOf(0x1E, 0x21, 0x01, 0x02, 0x04, 0x08, 0x10, 0x3F), // 2
        intArrayOf(0x3F, 0x02, 0x04, 0x0E, 0x01, 0x01, 0x21, 0x1E), // 3
        intArrayOf(0x04, 0x0C, 0x14, 0x24, 0x3F, 0x04, 0x04, 0x04), // 4
        intArrayOf(0x3F, 0x20, 0x3E, 0x01, 0x01, 0x01, 0x21, 0x1E), // 5
        intArrayOf(0x0E, 0x10, 0x20, 0x3E, 0x21, 0x21, 0x21, 0x1E), // 6
        intArrayOf(0x3F, 0x01, 0x02, 0x04, 0x04, 0x04, 0x04, 0x04), // 7
        intArrayOf(0x1E, 0x21, 0x21, 0x1E, 0x21, 0x21, 0x21, 0x1E), // 8
        intArrayOf(0x1E, 0x21, 0x21, 0x1F, 0x01, 0x01, 0x22, 0x1C)  // 9
    )

    // Menu char bitmaps: 5-bit rows, multiple rows. Hex = mask, LSB = rightmost
    private val charMap = mapOf(
        'F' to intArrayOf(0x1F, 0x10, 0x10, 0x1C, 0x10, 0x10, 0x10),
        'E' to intArrayOf(0x1F, 0x10, 0x10, 0x1C, 0x10, 0x10, 0x1F),
        'i' to intArrayOf(0x04, 0x00, 0x04, 0x04, 0x04, 0x04, 0x04),
        'l' to intArrayOf(0x04, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04),
        'S' to intArrayOf(0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E),
        'V' to intArrayOf(0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04),
        'a' to intArrayOf(0x0E, 0x11, 0x01, 0x1F, 0x21, 0x21, 0x1E),
        'o' to intArrayOf(0x0E, 0x11, 0x11, 0x11, 0x11, 0x0E),
        'd' to intArrayOf(0x02, 0x02, 0x0E, 0x12, 0x12, 0x12, 0x0E),
        'e' to intArrayOf(0x0E, 0x11, 0x1F, 0x10, 0x10, 0x0E),
        'v' to intArrayOf(0x11, 0x11, 0x11, 0x0A, 0x0A, 0x04),
        'n' to intArrayOf(0x00, 0x3B, 0x04, 0x04, 0x04, 0x04, 0x04), // 'n' as "no" shortcut
        ' ' to intArrayOf(0x00)
    )

    // ── Noise texture (static per session) ───────────────────────────
    private var noiseMap: FloatArray? = null
    private val noiseRng = Random(137)

    private fun noise(w: Float, h: Float): FloatArray {
        val n = noiseMap
        if (n != null) return n
        val arr = FloatArray((w * h / 200).toInt().coerceAtMost(500))
        for (i in arr.indices) arr[i] = noiseRng.nextFloat() * 0.04f - 0.02f
        noiseMap = arr
        return arr
    }

    // ── Main draw ───────────────────────────────────────────────────
    override fun DrawScope.drawFrame(
        isProcessing: Boolean,
        inferenceLoad: Float,
        batteryLevel: Int,
        isCharging: Boolean,
        isDegraded: Boolean
    ) {
        val w = size.width
        val h = size.height
        val t = System.currentTimeMillis() / 1000.0
        val load = inferenceLoad.coerceIn(0f, 1f)

        // 1. Desktop
        fill(Color(0xFFD0D0D0))
        val n = noise(w, h)
        for (v in n) {
            val idx = noiseRng.nextInt()
            val bx = (idx % w.toInt()).toFloat()
            val by = (idx / w.toInt()).toFloat()
            drawCircle(Color(0.92f + v, 0.92f + v, 0.92f + v), 1f, Offset(bx, by))
        }

        // 2. Menu bar
        drawMenuBar(w, t, isProcessing)

        // 3. Finder window(s)
        val nWin = if (load > 0.6f) 2 else 1
        drawFinderWindow(w, h, t, nWin)

        // 4. Trash
        val papers = when { load > 0.6f -> 4; load > 0.3f -> 2; else -> 0 }
        drawTrash(w, h, t, load > 0.3f, papers)

        // 5. Chicago display number
        drawNumber(w, h, (load * 100).toInt().coerceIn(0, 100))

        // 6. System faces
        if (load >= 1f) drawFace(w, h, sad = true)
        else if (load < 0.05f && !isDegraded && t.toLong() % 30 < 3) drawFace(w, h, sad = false)

        // 7. Bomb dialog (overheat)
        if (load > 0.8f && !isDegraded) {
            val phase = t % 5.0
            if (phase < 3.0) drawBombDialog(w, h, phase)
        }

        // 8. Cursor
        val cx = w * (0.4f + 0.2f * sin(t / 5.0)).toFloat()
        val cy = h * (0.5f + 0.2f * cos(t / 7.0)).toFloat()
        drawCursor(cx, cy)

        // 9. Charging indicator
        if (isCharging) drawBatteryIcon(w, t)
    }

    // ── Layer 2: Menu bar ───────────────────────────────────────────
    private fun DrawScope.drawMenuBar(w: Float, t: Double, active: Boolean) {
        val bh = 18f
        fill(Color.White, 0f, 0f, w, bh)
        drawLine(textBlack, Offset(0f, bh), Offset(w, bh), 1f)

        // ⌘ symbol
        drawLine(textBlack, Offset(12f + 7f, 3f), Offset(12f + 7f, 3f + 7f), 1f)
        drawLine(textBlack, Offset(12f, 3f + 3.5f), Offset(12f + 14f, 3f + 3.5f), 1f)
        drawLine(textBlack, Offset(12f + 3.5f, 3f), Offset(12f + 3.5f, 3f + 7f), 1f)

        val menus = listOf("File" to 'F', "Edit" to 'E', "View" to 'V', "Special" to 'S')
        var x = 28f
        for ((i, (_, ch)) in menus.withIndex()) {
            val hl = active && t.toInt() % 6 == i
            val bg = if (hl) macBlue else Color.Transparent
            val fg = if (hl) Color.White else textBlack
            if (hl) drawRect(bg, Offset(x, 0f), Size(28f, bh))
            drawBitmapChar(ch, x + 3f, 4f, fg)
            x += 32f
        }

        // Battery indicator in menu bar when charging
    }

    // ── Layer 3: Finder window ──────────────────────────────────────
    private fun DrawScope.drawFinderWindow(w: Float, h: Float, t: Double, count: Int) {
        val winW = w * 0.55f; val winH = h * 0.50f; val tbH = 18f
        val drift = Offset((sin(t / 60.0) * 8f).toFloat(), (cos(t / 90.0) * 4f).toFloat())

        for (i in 0 until count) {
            val off = i * 0.08f
            val bx = w * 0.22f + drift.x + w * off
            val by = h * 0.22f + drift.y + h * off * 0.5f

            drawRect(shadowColor, Offset(bx + 4f, by + 4f), Size(winW, winH))
            drawRect(Color.White, Offset(bx, by), Size(winW, winH))
            drawRect(chromeOuter, Offset(bx, by), Size(winW, winH), style = Stroke(2f))
            drawRect(chromeInner, Offset(bx + 2f, by + 2f), Size(winW - 4f, winH - 4f), style = Stroke(1f))
            drawRect(titleBar, Offset(bx + 2f, by + 2f), Size(winW - 4f, tbH))
            // Close box
            drawRect(Color.White, Offset(bx + 6f, by + 5f), Size(9f, 9f))
            drawRect(textBlack, Offset(bx + 6f, by + 5f), Size(9f, 9f), style = Stroke(1f))
            // Title
            val tw = "Phonon".length * 6f
            drawString("Phonon", bx + winW / 2 - tw / 2, by + 4f, textBlack)
        }
        // File icon in foremost window
        val bx = w * 0.22f + drift.x
        val by = h * 0.22f + drift.y
        val iy = by + tbH + 24f; val ix = bx + winW / 2 - 12f
        drawRect(Color.White, Offset(ix, iy), Size(24f, 28f))
        drawRect(textBlack, Offset(ix, iy), Size(24f, 28f), style = Stroke(1f))
        val fold = Path().apply { moveTo(ix+24f, iy); lineTo(ix+24f, iy+8f); lineTo(ix+16f, iy); close() }
        drawPath(fold, Color.LightGray)
        drawString("Phonon", ix - 4f, iy + 30f, textBlack)
        drawString("Kind: Audio Device", ix - 10f, iy + 46f, textBlack)
    }

    // ── Layer 4: Trash can ──────────────────────────────────────────
    private fun DrawScope.drawTrash(w: Float, h: Float, t: Double, full: Boolean, nPapers: Int) {
        val tx = w * 0.88f; val ty = h * 0.78f
        val shake = if (full) sin(t * 4.0).toFloat() * 1.5f else 0f

        // Documents
        for (i in 0 until nPapers) {
            val px = tx - 6f + i * 4f; val py = ty - 16f - i * 6f
            drawRect(Color(0xFFFFF8DC), Offset(px, py), Size(12f, 14f))
            drawRect(textBlack, Offset(px, py), Size(12f, 14f), style = Stroke(1f))
        }

        val c = if (full) Color(0xFF666666) else textBlack
        val s = shake
        val body = Path().apply {
            moveTo(tx - 5f + s, ty); lineTo(tx + 29f + s, ty)
            lineTo(tx + 22f + s, ty + 32f); lineTo(tx + 2f + s, ty + 32f); close()
        }
        drawPath(body, c, style = Stroke(1.5f))
        drawLine(c, Offset(tx - 7f + s, ty - 4f), Offset(tx + 31f + s, ty - 4f), 2f)
        if (full) repeat(3) { i ->
            drawLine(Color(0xFF888888), Offset(tx + 2f + s, ty + 6f + i * 8f), Offset(tx + 22f + s, ty + 12f + i * 8f), 1.5f)
        }
    }

    // ── Layer 5: Chicago display number ─────────────────────────────
    private fun DrawScope.drawNumber(w: Float, h: Float, v: Int) {
        val s = v.toString().padStart(3, '0')
        val sx = w / 2 - (s.length * 7f) / 2; val sy = h * 0.60f
        for ((i, ch) in s.withIndex()) {
            val rows = chicagoDigits[ch - '0']
            val dx = sx + i * 7f
            for (r in rows.indices) {
                var mask = rows[r]; var col = 0
                while (mask != 0) {
                    if (mask and 0x20 != 0) drawCircle(textBlack, 1f, Offset(dx + col, sy + r))
                    mask = mask shl 1; col++
                }
            }
        }
    }

    // ── Layer 6: Happy/Sad Mac ──────────────────────────────────────
    private fun DrawScope.drawFace(w: Float, h: Float, sad: Boolean) {
        val fs = 40f; val fx = w / 2 - fs / 2; val fy = h / 2 - fs / 2
        drawRect(Color.White, Offset(fx - 6f, fy - 6f), Size(fs + 12f, fs + 12f))
        drawRect(textBlack, Offset(fx - 6f, fy - 6f), Size(fs + 12f, fs + 12f), style = Stroke(2f))

        // Happy: 0b01100110, 0b00000000, 0b00000000, 0b00100100, 0b00011000
        // Sad:   0b01100110, 0b00000000, 0b00000000, 0b00011000, 0b01100110
        val rows = intArrayOf(
            0b01100110, 0b00000000, 0b00000000, 0b00000000,
            if (sad) 0b00011000 else 0b00100100,
            if (sad) 0b01100110 else 0b00011000
        )
        val cw = fs / 8f
        for ((ri, rv) in rows.withIndex()) {
            for (ci in 0..7) {
                if (rv and (1 shl (7 - ci)) != 0) {
                    drawRect(textBlack, Offset(fx + ci * cw, fy + (ri + 1) * cw), Size(cw, cw))
                }
            }
        }
        if (sad) repeat(3) { drawLine(Color(0xFF888888), Offset(fx + 4f, fy + fs + 8f + it * 4f), Offset(fx + fs - 4f, fy + fs + 8f + it * 4f), 1f) }
    }

    // ── Layer 7: Bomb dialog ────────────────────────────────────────
    private fun DrawScope.drawBombDialog(w: Float, h: Float, phase: Double) {
        val a = if (phase > 2.5f) (1f - (phase - 2.5f) * 2f).toFloat() else 1f
        val dw = w * 0.6f; val dx = w / 2 - dw / 2; val dy = h / 2 - 50f
        drawRect(Color.White.copy(a), Offset(dx, dy), Size(dw, 100f))
        drawRect(textBlack.copy(a), Offset(dx, dy), Size(dw, 100f), style = Stroke(2f))
        // Bomb
        drawCircle(Color(0xFF333333).copy(a), 18f, Offset(dx + 40f, dy + 40f))
        drawCircle(bombRed.copy(a), 10f, Offset(dx + 40f, dy + 40f))
        drawLine(Color(0xFF333333).copy(a), Offset(dx + 45f, dy + 22f), Offset(dx + 52f, dy + 12f), 2f)
        drawString("Sorry, a system", dx + 50f, dy + 20f, textBlack.copy(a))
        drawString("error occurred.", dx + 50f, dy + 36f, textBlack.copy(a))
    }

    // ── Layer 8: Cursor ─────────────────────────────────────────────
    private fun DrawScope.drawCursor(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y); lineTo(x + 14f, y + 10f); lineTo(x + 10f, y + 14f)
            lineTo(x + 12f, y + 18f); lineTo(x + 8f, y + 20f); lineTo(x + 6f, y + 16f)
            lineTo(x + 2f, y + 20f); lineTo(x - 4f, y + 4f); close()
        }
        drawPath(path, textBlack)
        drawPath(path, Color.White, style = Stroke(1f))
    }

    // ── Layer 9: Battery charging icon ──────────────────────────────
    private fun DrawScope.drawBatteryIcon(w: Float, t: Double) {
        val bx = w * 0.93f; val by = 2f
        drawRect(Color.White, Offset(bx, by), Size(18f, 14f))
        drawRect(textBlack, Offset(bx, by), Size(18f, 14f), style = Stroke(1f))
        // Nub
        drawRect(textBlack, Offset(bx + 18f, by + 4f), Size(3f, 6f))
        // Striped charge animation
        val stripe = (t * 2).toInt() % 4
        var sx = bx + 2f
        for (i in 0..3) {
            drawRect(if (i == stripe) macBlue else Color(0xFFCCCCCC), Offset(sx, by + 2f), Size(3f, 10f))
            sx += 4f
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────
    private fun DrawScope.fill(c: Color, x: Float, y: Float, w: Float, h: Float) =
        drawRect(c, Offset(x, y), Size(w, h))

    private fun DrawScope.drawString(text: String, x: Float, y: Float, c: Color) {
        var cx = x
        for (ch in text) {
            val rows = charMap[ch] ?: charMap[' '] ?: intArrayOf(0)
            for ((ri, mask) in rows.withIndex()) {
                var col = 0; var m = mask
                while (m != 0) {
                    if (m and 0x10 != 0) drawCircle(c, 1f, Offset(cx + col, y + ri))
                    m = m shl 1; col++
                }
            }
            cx += 5.5f
        }
    }

    private fun DrawScope.drawBitmapChar(ch: Char, x: Float, y: Float, c: Color) {
        val rows = charMap[ch] ?: return
        for ((ri, mask) in rows.withIndex()) {
            var col = 0; var m = mask
            while (m != 0) {
                if (m and 0x10 != 0) drawCircle(c, 1f, Offset(x + col, y + ri))
                m = m shl 1; col++
            }
        }
    }
}

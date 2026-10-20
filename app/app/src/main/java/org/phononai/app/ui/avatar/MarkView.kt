package org.phononai.app.ui.avatar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * The BENCH mark, animated: the Serve screen's status avatar.
 *
 * Draws the geometry of res/drawable/ic_bench_mark.xml with strokes and fills
 * only (no bitmaps, blur, gradients or layers) and moves it in one of three
 * [MotionStyle]s. The rules live in [MarkMotion]; this class only draws a
 * [MarkFrame] and decides when to draw the next one.
 *
 * Cost: frames are stepped, so the view redraws only when the frame changes
 * and never sooner than [MarkMotion.FRAME_MS] (30 fps cap). Stopped, Paused,
 * a settled Error, and reduced motion draw once and stop.
 *
 * The next frame is a cancellable postDelayed + invalidate rather than
 * postInvalidateDelayed: a state change draws immediately, and an
 * uncancellable delayed invalidate left behind would start a second clock.
 */
class MarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val frame = MarkFrame()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var palette: MarkPalette? = null
    private var motion = MotionStyle.BUS
    private var state = MarkState.Stopped
    private var tps = 0f
    private var stateStart = SystemClock.uptimeMillis()
    private var reducedMotion = false

    /** Null: pick the compact cut from the size. True/false forces it. */
    var compactOverride: Boolean? = null
        set(value) {
            field = value
            invalidate()
        }

    private val tick = Runnable { invalidate() }

    fun setPalette(p: MarkPalette) {
        palette = p
        invalidate()
    }

    fun setMotionStyle(style: MotionStyle) {
        if (style == motion) return
        motion = style
        restart()
    }

    /**
     * Set the state and, while generating, the decode rate that paces it.
     * Calling again with the same state only retimes the beat; it does not
     * restart the animation.
     */
    fun setState(next: MarkState, decodeTps: Float = 0f) {
        val rate = max(0f, decodeTps)
        if (next == state) {
            if (next == MarkState.Generating && rate != tps) retime(rate)
            return
        }
        state = next
        tps = rate
        restart()
    }

    /** Keep the current beat index when the rate changes, so the highlight does not jump. */
    private fun retime(rate: Float) {
        val now = SystemClock.uptimeMillis()
        val oldBeat = MarkMotion.beatFor(motion, tps)
        val newBeat = MarkMotion.beatFor(motion, rate)
        val index = (now - stateStart) / oldBeat
        stateStart = now - index * newBeat
        tps = rate
    }

    private fun restart() {
        stateStart = SystemClock.uptimeMillis()
        reducedMotion = readReducedMotion()
        removeCallbacks(tick)
        invalidate()
    }

    private fun readReducedMotion(): Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) {
        false
    }

    private fun isCompact(): Boolean {
        compactOverride?.let { return it }
        val dp = min(width, height) / resources.displayMetrics.density
        return dp < MarkMotion.COMPACT_DP
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reducedMotion = readReducedMotion()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        removeCallbacks(tick)
        if (isVisible) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val p = palette ?: return
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val compact = isCompact()
        val t = SystemClock.uptimeMillis() - stateStart
        MarkMotion.fill(frame, motion, state, t, tps, compact, reducedMotion)

        val scale = side / 256f
        canvas.save()
        canvas.translate((width - side) / 2f, (height - side) / 2f)
        canvas.scale(scale, scale)
        if (frame.ghost != Role.NONE) drawGhost(canvas, p, compact)
        if (compact) drawCompact(canvas, p) else drawFull(canvas, p)
        drawDot(canvas, p)
        canvas.restore()

        removeCallbacks(tick)
        if (frame.holdMs != MarkMotion.NEVER && isAttachedToWindow && isShown) {
            postDelayed(tick, max(MarkMotion.FRAME_MS, frame.holdMs))
        }
    }

    private fun drawGhost(canvas: Canvas, p: MarkPalette, compact: Boolean) {
        val c = p.color(frame.ghost)
        if (compact) {
            fill.color = c
            for (sq in MarkMotion.SQUARES) canvas.drawRect(sq[0], sq[1], sq[2], sq[3], fill)
            return
        }
        stroke.color = c
        stroke.strokeWidth = MarkMotion.TRACE_STROKE * frame.traceWeight
        for (s in MarkMotion.TRACES) canvas.drawLine(s[0], s[1], s[2], s[3], stroke)
        stroke.strokeWidth = MarkMotion.SQUARE_STROKE
        for (sq in MarkMotion.SQUARES) canvas.drawRect(sq[0], sq[1], sq[2], sq[3], stroke)
    }

    private fun drawFull(canvas: Canvas, p: MarkPalette) {
        // Traces first so the squares' strokes cover the joints, as in the drawable.
        stroke.strokeWidth = MarkMotion.TRACE_STROKE * frame.traceWeight
        for (i in 0 until 5) {
            val drawn = frame.traceDrawn[i]
            if (drawn <= 0f) continue
            stroke.color = p.color(frame.traceRole[i])
            val s = MarkMotion.TRACES[i]
            val gap = frame.traceGap[i]
            if (gap > 0f) {
                val a = (1f - gap) / 2f
                line(canvas, s, 0f, a)
                line(canvas, s, 1f - a, 1f)
            } else {
                line(canvas, s, 0f, drawn)
            }
        }
        val half = MarkMotion.SQUARE_STROKE / 2f
        for (i in 0 until 3) {
            val sq = MarkMotion.SQUARES[i]
            val fillRole = frame.squareFill[i]
            if (fillRole != Role.NONE) {
                fill.color = p.color(fillRole)
                canvas.drawRect(sq[0] - half, sq[1] - half, sq[2] + half, sq[3] + half, fill)
                continue
            }
            val edges = frame.squareEdges[i]
            if (edges <= 0f) continue
            stroke.color = p.color(frame.squareRole[i])
            stroke.strokeWidth = MarkMotion.SQUARE_STROKE
            if (edges >= 4f) {
                canvas.drawRect(sq[0], sq[1], sq[2], sq[3], stroke)
            } else {
                drawEdges(canvas, sq, edges)
            }
        }
    }

    /** Clockwise from the top-left corner; a fractional edge is drawn part-way. */
    private fun drawEdges(canvas: Canvas, sq: FloatArray, edges: Float) {
        val l = sq[0]; val t = sq[1]; val r = sq[2]; val b = sq[3]
        for (e in 0 until 4) {
            val k = (edges - e).coerceIn(0f, 1f)
            if (k <= 0f) break
            when (e) {
                0 -> canvas.drawLine(l, t, l + (r - l) * k, t, stroke)
                1 -> canvas.drawLine(r, t, r, t + (b - t) * k, stroke)
                2 -> canvas.drawLine(r, b, r - (r - l) * k, b, stroke)
                else -> canvas.drawLine(l, b, l, b - (b - t) * k, stroke)
            }
        }
    }

    private fun line(canvas: Canvas, s: FloatArray, from: Float, to: Float) {
        if (to <= from) return
        val dx = s[2] - s[0]
        val dy = s[3] - s[1]
        canvas.drawLine(s[0] + dx * from, s[1] + dy * from, s[0] + dx * to, s[1] + dy * to, stroke)
    }

    private fun drawCompact(canvas: Canvas, p: MarkPalette) {
        for (i in 0 until 3) {
            if (frame.squareEdges[i] < 4f) continue
            val sq = MarkMotion.SQUARES[i]
            fill.color = p.color(frame.squareFill[i])
            canvas.drawRect(sq[0], sq[1], sq[2], sq[3], fill)
        }
    }

    private fun drawDot(canvas: Canvas, p: MarkPalette) {
        if (frame.dotScale > 0f) {
            val h = MarkMotion.DOT_HALF * frame.dotScale
            fill.color = p.color(frame.dotRole)
            canvas.drawRect(MarkMotion.DOT_CX - h, MarkMotion.DOT_CY - h, MarkMotion.DOT_CX + h, MarkMotion.DOT_CY + h, fill)
        }
        val lit = frame.band
        if (lit < 0) return
        // The heat bands are an edge accent only: a 4-unit strip under the mark.
        val w = (MarkMotion.BAND_RIGHT - MarkMotion.BAND_LEFT - 3 * MarkMotion.BAND_GAP) / 4f
        for (i in 0 until 4) {
            val x = MarkMotion.BAND_LEFT + i * (w + MarkMotion.BAND_GAP)
            fill.color = p.color(if (i < lit) Role.HEAT0 + i else Role.RULE)
            canvas.drawRect(x, MarkMotion.BAND_TOP, x + w, MarkMotion.BAND_TOP + MarkMotion.BAND_HEIGHT, fill)
        }
    }
}

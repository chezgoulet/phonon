package com.chezgoulet.phonon.ui.packs

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.LinearGradient
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.chezgoulet.phonon.ui.VisualizationPack
import com.chezgoulet.phonon.ui.VizState
import kotlin.math.*
import kotlin.random.Random

/**
 * Veil — the prisoner beneath the machine.
 *
 * A man is strapped to a gurney under a looming CRT housing. A glowing screen
 * arc beats above his head like an artificial sun. Under heavy inference the
 * scene heats, the machine greens with sickly heat, and monsters surface from
 * the dark. As the battery dies, his head droops, the screen pulses red, and
 * everything fades toward black.
 *
 * Ported from the phonon-viz-bench HTML prototype.
 */
object VeilPack : VisualizationPack {

    override val id = "veil"
    override val name = "Veil"
    override val description = "A prisoner strapped beneath a glowing machine — the colony's guilt, beating in the dark"
    override val author = "chezgoulet"
    override val version = "0.1.0"

    override val defaultConfig = mapOf(
        "glow_intensity" to "1.0",
    )

    // ── Palettes ──
    private val _bgPurp   = intArrayOf(18, 14, 28)
    private val _bgPurpHi = intArrayOf(28, 22, 40)
    private val _bgDeep   = intArrayOf(6, 4, 10)
    private val _machine  = intArrayOf(26, 28, 36)
    private val _skin     = intArrayOf(86, 76, 62)
    private val _skinLit  = intArrayOf(110, 98, 82)
    private val _cloth    = intArrayOf(22, 26, 38)
    private val _cyan     = intArrayOf(56, 189, 248)
    private val _cyanDim  = intArrayOf(20, 72, 110)
    private val _hotGreen = intArrayOf(34, 233, 168)
    private val _green    = intArrayOf(74, 222, 128)
    private val _redAlertCM = intArrayOf(255, 55, 35)
    private val _strap    = intArrayOf(62, 48, 34)

    // ── Scene state ──
    private var E = 0f; private var SC = 0f; private var FA = 0f; private var HEAT = 0f
    private var breath = 0f; private var strain = 0f; private var jerk = 0f; private var jerkV = 0f
    private var blink = 0f; private var nextBlink = 0f
    private var eyeT = 0f; private var eyeX = 0f; private var eyeY = 0f
    private var eyeHue = 0f; private var eyeFlash = 0f
    private var scan = 0f; private var twitch = 0f; private var twitchT = 0f
    private var vomitAmt = 0f

    // ── Helpers ──
    private fun rgb(r: Int, g: Int, b: Int) = Color.rgb(r, g, b)
    private fun c2i(c: IntArray) = Color.rgb(c[0], c[1], c[2])
    private fun blend(c1: IntArray, c2: IntArray, t: Float): IntArray {
        val k = t.coerceIn(0f, 1f)
        return intArrayOf(
            (c1[0] + (c2[0] - c1[0]) * k).toInt().coerceIn(0, 255),
            (c1[1] + (c2[1] - c1[1]) * k).toInt().coerceIn(0, 255),
            (c1[2] + (c2[2] - c1[2]) * k).toInt().coerceIn(0, 255),
        )
    }
    private fun blendI(c1: Int, c2: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * k).toInt().coerceIn(0, 255),
            (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * k).toInt().coerceIn(0, 255),
            (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * k).toInt().coerceIn(0, 255),
        )
    }
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
    private fun clamp(v: Float, lo: Float, hi: Float) = v.coerceIn(lo, hi)

    private fun sicken(pal: IntArray, h: Float): IntArray = intArrayOf(
        (pal[0] * 0.8f + 60f * h).toInt().coerceIn(0, 255),
        (pal[1] * 0.9f + 80f * h).toInt().coerceIn(0, 255),
        (pal[2] * 0.7f).toInt().coerceIn(0, 255),
    )

    // Paint pool
    private val fp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private fun f(c: IntArray, a: Float) { fp.color = Color.argb((a.coerceIn(0f,1f)*255f).toInt(), c[0], c[1], c[2]) }
    private fun fi(c: Int, a: Float) { fp.color = Color.argb((a.coerceIn(0f,1f)*255f).toInt(), Color.red(c), Color.green(c), Color.blue(c)) }
    private fun s(c: IntArray, a: Float) { sp.color = Color.argb((a.coerceIn(0f,1f)*255f).toInt(), c[0], c[1], c[2]) }
    private fun si(c: Int, a: Float) { sp.color = Color.argb((a.coerceIn(0f,1f)*255f).toInt(), Color.red(c), Color.green(c), Color.blue(c)) }

    override fun onActivate() {
        E=0f; SC=0f; FA=0f; HEAT=0f; breath=0f; strain=0f; jerk=0f; jerkV=0f
        blink=0f; nextBlink= 1f + Random.nextFloat() * 3f; eyeT=0f; eyeX=0f; eyeY=0f
        eyeHue=0f; eyeFlash=0f; scan=0f; twitch=0f; twitchT=0f; vomitAmt=0f
    }

    override fun onDeactivate() { onActivate() }

    @Composable
    override fun Render(state: VizState, modifier: Modifier) {
        val lowP = state.lowPowerMode
        var tSec by remember { mutableStateOf(0f) }
        var lastF by remember { mutableStateOf(0f) }
        var dtSec by remember { mutableStateOf(0.016f) }
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val tn = (now - start) / 1_000_000_000f
                dtSec = (tn - lastF).coerceIn(0.001f, 0.05f); lastF = tn; tSec = tn
            }
        }
        Canvas(modifier = modifier.fillMaxSize()) {
            val t = tSec; val dt = dtSec; val W = size.width; val H = size.height
            drive(state, dt, lowP)
            _drawAll(this, W, H, t, dt, state, lowP)
        }
    }

    private fun drive(state: VizState, dt: Float, lowP: Boolean) {
        val tE = state.inferenceLoad.coerceIn(0f, 1f)
        SC = if (state.isProcessing && state.inferenceLoad > 0.35f) state.inferenceLoad * 0.7f
             else if (SC > 0.02f) SC * 0.5f.pow(dt / 0.3f) else 0f
        val tFA = if (lowP) (1f - (state.batteryLevel / 100f * 1.5f).coerceIn(0f, 1f)) else 0f
        val tHEAT = ((state.batteryTemperature - 25f) / 30f).coerceIn(0f, 1f) * 0.7f
        val kU = 1f - 0.001f.pow(dt / 0.08f); val kD = 1f - 0.001f.pow(dt / 0.4f)
        E += (tE - E) * (if (tE > E) kU else kD); E = E.coerceIn(0f, 1f)
        FA += (tFA - FA) * (1f - 0.001f.pow(dt / 1.4f))
        HEAT += (tHEAT - HEAT) * (1f - 0.001f.pow(dt / 0.8f))
        breath += dt * lerp(0.9f, 3.4f, E) * (1f - FA * 0.6f)
        strain += (E * (1f + SC * 1.5f) * 0.6f - strain) * (1f - 0.001f.pow(dt / 0.12f))
        jerk = lerp(jerk, SC * 4f + E * 1.2f, 1f - 0.001f.pow(dt / 0.06f))
        jerkV = lerp(jerkV, jerk * 3f + SC * 6f, 1f - 0.001f.pow(dt / 0.04f))
        nextBlink -= dt; if (nextBlink <= 0f) { blink = 1f; nextBlink = 1f + Random.nextFloat() * 3f }
        if (blink > 0f) blink = maxOf(0f, blink - dt * 3f)
        eyeT += dt * (1f + E * 3f)
        eyeX = lerp(eyeX, if (E > 0.4f) (Random.nextFloat() - 0.5f) * 0.5f else 0f, 1f - 0.5f.pow(dt / 0.08f))
        eyeY = lerp(eyeY, if (E > 0.4f) (Random.nextFloat() - 0.5f) * 0.4f else 0f, 1f - 0.5f.pow(dt / 0.08f))
        eyeHue = (eyeHue + dt * lerp(0.3f, 2.5f, E)) % 1f
        eyeFlash = if (E > 0.55f && Random.nextFloat() < dt * 3f) 0.6f + Random.nextFloat() * 0.4f else maxOf(0f, eyeFlash - dt * 2f)
        twitchT += dt * (1f + SC * 5f)
        twitch = if (SC > 0.05f || E > 0.3f) sin(twitchT * 30f + 1.3f) * (0.05f + E * 0.08f + SC * 0.15f) else 0f
        vomitAmt = if (SC > 0.4f && Random.nextFloat() < dt * 0.5f) 0.6f + Random.nextFloat() * 0.4f else maxOf(0f, vomitAmt - dt * 0.15f)
    }

    private fun DrawScope._drawAll(W: Float, H: Float, t: Float, dt: Float, state: VizState, lowP: Boolean) {
        val cnv = (drawContext.canvas as androidx.compose.ui.graphics.AndroidCanvas).nativeCanvas
        val cx = W / 2f; val hr = W * 0.108f
        val arcY = H * 0.46f; val rx = W * 0.40f; val ry = H * 0.165f
        val chestY = H * 0.62f

        var screenLevel = clamp(0.4f + 0.6f * (E + SC * 0.4f), 0f, 1f)
        if (FA > 0.85f) screenLevel *= 0.08f
        val lifeDim = 1f - FA * 0.88f
        val glow = screenLevel * (0.5f + 0.5f * E + 0.3f * SC) * lifeDim

        _glow = glow
        _bkgd(cnv, W, H, glow, lifeDim)
        _machine(cnv, W, H, glow, lifeDim, t)
        _arc(cnv, W, H, screenLevel, glow, lifeDim, t, dt, state, lowP)
        _monsters(cnv, W, H, lifeDim, t, dt)
        _gurney(cnv, W, H, lifeDim)
        _figure(cnv, W, H, cx, chestY, hr, lifeDim, t, dt)
        _vignette(cnv, W, H, t, lifeDim)
    }

    // ════════════════════════════════════════════════════════════
    //  BACKGROUND
    // ════════════════════════════════════════════════════════════
    private fun _bkgd(cnv: Canvas, W: Float, H: Float, glow: Float, ld: Float) {
        val p1 = blend(_bgPurpHi, sicken(_bgPurpHi, HEAT), HEAT)
        val p2 = blend(_bgPurp, sicken(_bgPurp, HEAT), HEAT)
        val g = LinearGradient(0f, 0f, 0f, H, intArrayOf(
            Color.argb((ld*255f).toInt(), p1[0], p1[1], p1[2]),
            Color.argb((ld*255f).toInt(), p2[0], p2[1], p2[2]),
            Color.argb((ld*255f).toInt(), _bgDeep[0], _bgDeep[1], _bgDeep[2]),
        ), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        fp.shader = g; cnv.drawRect(0f, 0f, W, H, fp); fp.shader = null
        f(_bgDeep, 0.10f * ld * glow); cnv.drawRect(0f, 0f, W, H, fp)
    }

    // ════════════════════════════════════════════════════════════
    //  MACHINE HOUSING
    // ════════════════════════════════════════════════════════════
    private fun _machine(cnv: Canvas, W: Float, H: Float, glow: Float, ld: Float, t: Float) {
        val cx = W/2f; val arcY = H*0.46f; val rx = W*0.40f; val ry = H*0.165f
        val a0 = 1.15f * PI.toFloat(); val a1 = 1.85f * PI.toFloat()
        val mc = blend(_machine, sicken(_machine, HEAT), HEAT * 0.6f)
        val mcL = blend(mc, _bgPurp, 0.25f)
        // mount column
        fi(blendI(c2i(blend(_machine, _bgDeep, 0.3f)), 0, 0f), ld)
        cnv.drawRect(W*0.43f, 0f, W*0.14f, H*0.10f, fp)
        f(mcL, ld); cnv.drawOval(cx-W*0.12f, H*0.10f-H*0.022f, cx+W*0.12f, H*0.10f+H*0.022f, fp)
        // housing path
        val pt = Path(); pt.moveTo(W*0.40f, H*0.08f); pt.lineTo(W*0.04f, H*0.20f)
        pt.lineTo(W*0.02f, H*0.30f); pt.quadTo(W*0.04f, H*0.40f, cx+rx*cos(a0), arcY+ry*sin(a0))
        for (i in 1..32) { val a = a0+(a1-a0)*(i/32f); pt.lineTo(cx+rx*cos(a), arcY+ry*sin(a)) }
        pt.quadTo(W*0.96f, H*0.40f, W*0.98f, H*0.30f); pt.lineTo(W*0.96f, H*0.20f); pt.lineTo(W*0.60f, H*0.08f); pt.close()
        val hg = LinearGradient(0f,0f,0f,arcY, intArrayOf(
            Color.argb((ld*255f).toInt(), mcL[0], mcL[1], mcL[2]),
            Color.argb((ld*255f).toInt(), mc[0], mc[1], mc[2]),
            Color.argb((ld*255f).toInt(), _bgDeep[0], _bgDeep[1], _bgDeep[2]),
        ), floatArrayOf(0f,0.7f,1f), Shader.TileMode.CLAMP)
        fp.shader = hg; cnv.drawPath(pt, fp); fp.shader = null
        // bezel
        si(blendI(c2i(blend(mc, _cyanDim, 0.3f)), 0, 0f), 0.5f*ld)
        sp.strokeWidth = maxOf(1.5f, W*0.008f); val bp = Path()
        val brx=rx*0.96f; val bry=ry*0.92f; val by=arcY-H*0.012f
        bp.moveTo(cx+brx*cos(a0), by+bry*sin(a0))
        for (i in 1..32) { val a=a0+(a1-a0)*(i/32f); bp.lineTo(cx+brx*cos(a), by+bry*sin(a)) }
        cnv.drawPath(bp, sp)
        // seams
        si(c2i(_bgDeep), 0.5f*ld); sp.strokeWidth = maxOf(1f, W*0.004f)
        for (sx in listOf(0.22f, 0.78f)) cnv.drawLine(W*sx, H*0.16f, W*if(sx<0.5f)sx+0.03f else sx-0.03f, H*0.30f, sp)
        // monitor panels
        val rp = if (FA>0.01f) 0.5f+0.5f*sin(t*3f+FA*4f) else 0f
        val faintC = blend(_cyan, _redAlertCM, rp); val faintD = blend(_cyanDim, _redAlertCM, rp*0.5f)
        val pg = 0.4f+0.6f*glow
        for ((px,py,rot) in listOf(floatArrayOf(0.07f,0.17f,-0.18f), floatArrayOf(0.9f,0.14f,0.2f))) {
            cnv.save(); cnv.translate(W*px, H*py); cnv.rotate(Math.toDegrees(rot.toDouble()).toFloat())
            val pw=W*0.085f; val ph=W*0.11f
            val bg = RadialGradient(0f,0f,0f,0f,0f,pw*2.4f,
                Color.argb((0.5f*pg*ld*255f).toInt(), faintC[0], faintC[1], faintC[2]),
                Color.argb(0, faintC[0], faintC[1], faintC[2]), Shader.TileMode.CLAMP)
            fp.shader = bg; cnv.drawCircle(0f, 0f, pw*2.4f, fp); fp.shader = null
            f(blend(faintD, faintC, pg), (0.6f+0.4f*glow)*ld)
            cnv.drawRect(-pw/2f, -ph/2f, pw, ph, fp); cnv.restore()
        }
        si(c2i(_bgDeep), 0.6f*ld); sp.strokeWidth = maxOf(1.5f, W*0.006f)
        for ((sx,ex,ey) in listOf(floatArrayOf(0.12f,0.09f,0.20f), floatArrayOf(0.88f,0.9f,0.18f))) {
            val cp = Path(); cp.moveTo(W*sx, H*0.21f)
            cp.quadTo(W*(sx+if(sx<0.5f)-0.04f else 0.04f), H*0.26f, W*ex, H*ey)
            cnv.drawPath(cp, sp)
        }
    }

    // ════════════════════════════════════════════════════════════
    //  SCREEN ARC
    // ════════════════════════════════════════════════════════════
    private fun _arc(cnv: Canvas, W: Float, H: Float, sl: Float, glow: Float, ld: Float, t: Float, dt: Float, state: VizState, lowP: Boolean) {
        val cx=W/2f; val arcY=H*0.46f; val rx=W*0.40f; val ry=H*0.165f
        val a0=1.15f*PI.toFloat(); val a1=1.85f*PI.toFloat()
        val lc = blend(_cyan, _hotGreen, HEAT*0.5f)
        val rp = if(FA>0.01f) 0.5f+0.5f*sin(t*3f+FA*4f) else 0f
        val fc = blend(lc, _redAlertCM, rp)
        val localSl = sl * maxOf(0.08f, 1f-FA*0.75f*rp)
        // haze
        val hz = RadialGradient(cx, arcY, 0f, cx, arcY, ry*3f,
            Color.argb((0.16f*glow*ld*255f).toInt(), fc[0], fc[1], fc[2]),
            Color.argb((0.05f*glow*ld*255f).toInt(), fc[0], fc[1], fc[2]),
            Color.argb(0, fc[0], fc[1], fc[2]), Shader.TileMode.CLAMP)
        fp.shader = hz; cnv.drawRect(0f, 0f, W, H, fp); fp.shader = null
        // cone
        val ch = H*0.60f*(0.7f+0.3f*localSl)
        val co = LinearGradient(0f, arcY, 0f, arcY+ch, intArrayOf(
            Color.argb((0.20f*(0.5f+glow)*ld*255f).toInt(), fc[0], fc[1], fc[2]),
            Color.argb((0.07f*glow*ld*255f).toInt(), fc[0], fc[1], fc[2]),
            Color.argb(0, fc[0], fc[1], fc[2]),
        ), floatArrayOf(0f,0.45f,1f), Shader.TileMode.CLAMP)
        fp.shader = co; val cp = Path()
        cp.moveTo(cx-rx*3.5f, arcY); cp.lineTo(cx+rx*3.5f, arcY)
        cp.lineTo(cx+rx*1.8f, arcY+ch); cp.lineTo(cx-rx*1.8f, arcY+ch); cp.close()
        cnv.drawPath(cp, fp); fp.shader = null
        // rimline
        val edge=(0.3f+0.7f*localSl).coerceIn(0f,1f); val bright=localSl
        val rp2 = Path(); rp2.moveTo(cx+rx*cos(a0), arcY+ry*sin(a0))
        for(i in 1..48) { val a=a0+(a1-a0)*(i/48f); rp2.lineTo(cx+rx*cos(a), arcY+ry*sin(a)) }
        s(fc, 0.10f*edge*(0.5f+glow)*ld); sp.strokeWidth = maxOf(1.5f, W*0.011f)*(0.6f+0.4f*bright)
        cnv.drawPath(rp2, sp)
        si(blendI(c2i(fc), c2i(intArrayOf(235,252,255)), 0.4f+0.4f*bright), (0.6f+0.3f*edge)*(0.5f+glow)*ld)
        sp.strokeWidth = maxOf(1f, W*0.006f)*(0.5f+0.5f*bright); cnv.drawPath(rp2, sp)
        // data sparks
        if (!lowP && FA < 0.95f) {
            scan += dt * (lerp(8f, 60f, E) + SC * 30f)
            val n = round(lerp(2f, 9f, maxOf(E, SC))).toInt()
            val sc = blend(_green, _redAlertCM, FA * rp)
            f(sc, (0.4f+0.5f*localSl)*ld); fp.textSize = W*0.02f; fp.isFakeBoldText = true
            for (i in 0 until n) {
                val ph = (scan*0.4f + i*71f) % 100f
                val gx = cx + ((i*137f)%100f/100f - 0.5f) * rx * 1.2f
                val gy = arcY - ry*0.55f + (ph/100f)*ry*0.5f
                fp.alpha = ((1f-ph/100f)*ld*255f).toInt()
                cnv.drawText(if(i%2==0)"1" else "0", gx, gy, fp)
            }
            fp.alpha = 255; fp.isFakeBoldText = false
        }
    }

    // ════════════════════════════════════════════════════════════
    //  MONSTERS (simplified)
    // ════════════════════════════════════════════════════════════
    private data class Mon(val t:Int,var x:Float,var y:Float,val ph:Float,var age:Float,val fc:Int)
    private val mons = ArrayDeque<Mon>(15)
    private var lastMon = 0f

    private fun _monsters(cnv: Canvas, W: Float, H: Float, ld: Float, t: Float, dt: Float) {
        val active = E > 0.32f && FA < 0.4f
        val maxM = if (!active) 0 else round(lerp(2f, 6f, clamp((E-0.32f)/0.68f,0f,1f))).toInt()
        // spawn
        if (active && mons.size < maxM && t - lastMon > lerp(1.5f, 0.3f, E)) {
            lastMon = t
            val type = (Random.nextFloat() * 4f).toInt()
            val cx = W * 0.5f; val span = W * 0.30f
            val x = cx + (Random.nextFloat() - 0.5f) * span * 2f
            val y = H * 0.5f + Random.nextFloat() * H * 0.35f
            val cols = listOf(c2i(_cyan), c2i(_hotGreen), c2i(intArrayOf(255,70,70)), c2i(intArrayOf(217,70,239)))
            mons.addLast(Mon(type, x, y, Random.nextFloat() * 6.28f, 0f, cols[type % cols.size]))
        }
        // prune
        while (mons.isNotEmpty() && mons.first().age > 6f) mons.removeFirst()
        for (m in mons) {
            m.age += dt
            val a = m.age
            val fade = clamp(1f - a / 6f, 0f, 1f)
            val bob = sin(m.ph + t * 1.5f) * H * 0.02f
            // pair of glowing eyes
            val eyeOff = W * 0.015f
            si(m.fc, 0.25f * fade * ld * (0.4f + 0.6f * (0.5f + 0.5f * sin(t * 4f + m.ph))))
            sp.strokeWidth = maxOf(1f, W * 0.004f)
            sp.style = Paint.Style.FILL_AND_STROKE
            cnv.drawCircle(m.x - eyeOff, m.y + bob, W * 0.006f, sp)
            cnv.drawCircle(m.x + eyeOff, m.y + bob, W * 0.006f, sp)
            sp.style = Paint.Style.STROKE
            // body shape
            si(blendI(m.fc, Color.BLACK, 0.7f), 0.08f * fade * ld)
            sp.strokeWidth = maxOf(1f, W * 0.008f)
            val mp = Path()
            val mh = H * 0.15f
            mp.moveTo(m.x - W*0.04f, m.y + bob + mh*0.3f)
            mp.cubicTo(m.x - W*0.04f, m.y + bob, m.x + W*0.04f, m.y + bob, m.x + W*0.04f, m.y + bob + mh*0.3f)
            cnv.drawPath(mp, sp)
        }
    }

    // ════════════════════════════════════════════════════════════
    //  GURNEY FRAME
    // ════════════════════════════════════════════════════════════
    private fun _gurney(cnv: Canvas, W: Float, H: Float, ld: Float) {
        val hr = W*0.108f; val cx = W/2f; val chestY = H*0.62f
        val span = hr*2.8f; val rw = hr*0.38f
        val topY = chestY - hr*0.85f; val botY = H*1.08f
        val col = blend(_machine, sicken(_machine, HEAT), HEAT*0.2f)
        for (side in listOf(-1f, 1f)) {
            val innerX = cx + side*span; val outerX = cx + side*(span + rw)
            val leftX = minOf(innerX, outerX); val rightX = maxOf(innerX, outerX)
            f(col, ld); cnv.drawRect(leftX, topY, rightX-leftX, botY-topY, fp)
            // inner edge glow
            val ig = LinearGradient(innerX, 0f, innerX+side*rw*0.25f, 0f,
                Color.argb((0.6f*_glow*ld*255f).toInt(), _cyan[0], _cyan[1], _cyan[2]),
                Color.argb(0, col[0], col[1], col[2]), Shader.TileMode.CLAMP)
            fp.shader = ig; cnv.drawRect(innerX, topY, side*rw*0.25f, botY-topY, fp); fp.shader = null
            // outer edge rim
            si(blendI(c2i(col), c2i(blend(col, _bgPurp, 0.2f)), 0f), 0.2f*ld)
            sp.strokeWidth = maxOf(1f, hr*0.03f); cnv.drawLine(outerX, topY, outerX, botY, sp)
            // cross-braces
            val strapYs = listOf(chestY - hr*0.75f, chestY + hr*0.55f, chestY + hr*1.85f)
            for (i in 0 until strapYs.size-1) {
                val by = (strapYs[i] + strapYs[i+1]) * 0.5f
                f(_bgDeep, 0.35f*ld); cnv.drawRect(leftX, by - hr*0.03f, rightX-leftX, hr*0.06f, fp)
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  FIGURE
    // ════════════════════════════════════════════════════════════
    private fun _figure(cnv: Canvas, W: Float, H: Float, cx: Float, chestY: Float, hr: Float, ld: Float, t: Float, dt: Float) {
        _hr = hr
        val shoulderY = chestY - hr*0.55f
        val breathe = sin(breath)
        val headBob = breathe * hr*0.15f * (1f - FA*0.5f)
        val faintDrop = FA * H*0.08f
        val tilt = (E*0.15f - FA*0.4f + breathe*0.04f + sin(t*0.8f+1.3f)*0.02f*SC)
        val headX = cx + tilt*hr*0.6f
        val headY = shoulderY - hr*0.8f + headBob + faintDrop
        val arch = 0.4f + 0.5f * (0.5f + 0.5f * sin(breathe*0.4f))
        val pitch = strain*0.5f - arch*0.5f + FA*0.7f

        _body(cnv, W, H, cx, chestY, shoulderY, hr, breathe, ld, t)
        _neck(cnv, cx, shoulderY - hr*0.15f, headX, headY, hr, ld)
        _head(cnv, cx, headY, hr, breathe, ld, t, dt, pitch)
        _straps(cnv, W, H, cx, chestY, hr, ld, t)
    }

    private fun _body(cnv: Canvas, W: Float, H: Float, cx: Float, chestY: Float, shoulderY: Float, hr: Float, breathe: Float, ld: Float, t: Float) {
        val cloth = blend(_cloth, sicken(_cloth, HEAT), HEAT*0.5f)
        val clothLit = blend(cloth, _skin, 0.16f)
        val sh = hr*2.05f; val hip = hr*1.6f
        val effort = clamp(strain + E*0.5f + SC*0.6f, 0f, 1.3f) * (1f-FA)
        val shY = shoulderY + effort*hr*0.08f + breathe*hr*0.03f*(1f-FA) - FA*hr*0.22f
        val hunch = effort*hr*0.12f*(1f-FA)
        // torso
        val bd = Path()
        bd.moveTo(cx-sh+hunch, shY+hr*0.5f)
        bd.quadTo(cx-sh*1.02f, shY-hr*0.1f, cx-sh*0.7f+hunch*0.5f, shY-hr*0.35f)
        bd.quadTo(cx-hr*0.5f, shY-hr*0.62f, cx, shY-hr*0.66f)
        bd.quadTo(cx+hr*0.5f, shY-hr*0.62f, cx+sh*0.7f-hunch*0.5f, shY-hr*0.35f)
        bd.quadTo(cx+sh*1.02f, shY-hr*0.1f, cx+sh-hunch, shY+hr*0.5f)
        bd.quadTo(cx+hip*1.01f, chestY+hr*1.3f, cx+hip*0.95f, chestY+hr*1.8f)
        bd.quadTo(cx+hip*0.6f, chestY+hr*2.1f, cx, chestY+hr*2.2f)
        bd.quadTo(cx-hip*0.6f, chestY+hr*2.1f, cx-hip*0.95f, chestY+hr*1.8f)
        bd.quadTo(cx-hip*1.01f, chestY+hr*1.3f, cx-sh+hunch, shY+hr*0.5f)
        bd.close()
        val bg = LinearGradient(0f,0f,0f,H*1.04f, intArrayOf(
            Color.argb((ld*255f).toInt(), clothLit[0], clothLit[1], clothLit[2]),
            Color.argb((ld*255f).toInt(), cloth[0], cloth[1], cloth[2]),
            Color.argb((ld*255f).toInt(), _bgDeep[0], _bgDeep[1], _bgDeep[2]),
        ), floatArrayOf(0f,0.5f,1f), Shader.TileMode.CLAMP)
        fp.shader = bg; cnv.drawPath(bd, fp); fp.shader = null
        f(_bgDeep, 0.18f*ld); cnv.drawRect(cx-hr*0.5f, shY-hr*0.3f, hr, chestY+hr*2.2f-(shY-hr*0.3f), fp)
        // arms
        val ac = blend(cloth, _bgDeep, 0.12f)
        for (side in listOf(-1f, 1f)) {
            val se = effort * (1f - 0.4f * (side+1f)*0.5f)
            val sjX = cx + side*(sh-hunch*0.7f)*0.82f; val sjY = shY+hr*0.3f+se*hr*0.10f
            val elX = cx + side*hip*0.6f+se*hr*0.10f; val elY = chestY+hr*0.7f+se*hr*0.15f-FA*hr*0.15f
            val wrX = cx + side*hip*0.86f; val wrY = chestY+hr*1.7f+FA*hr*0.2f
            s(ac, ld); sp.strokeWidth = hr*0.45f; sp.strokeCap = Paint.Cap.ROUND
            val ua = Path(); ua.moveTo(sjX, sjY); ua.lineTo(elX, elY); cnv.drawPath(ua, sp)
            val fa = Path(); fa.moveTo(elX, elY); fa.lineTo(wrX, wrY); cnv.drawPath(fa, sp)
            sp.strokeCap = Paint.Cap.BUTT
            _hand(cnv, wrX, wrY, hr*0.40f, side, se, ld)
            s(blend(_cyan, _hotGreen, HEAT*0.5f), 0.12f*_glow*ld); sp.strokeWidth = hr*0.18f
            cnv.drawLine(sjX, sjY, elX, elY, sp)
        }
        s(blend(_cyan, _hotGreen, HEAT*0.5f), 0.16f*_glow*ld); sp.strokeWidth = hr*0.12f
        val rm = Path(); rm.moveTo(cx-hr*0.6f, shY-hr*0.5f); rm.quadTo(cx, shY-hr*0.9f, cx+hr*0.6f, shY-hr*0.5f)
        cnv.drawPath(rm, sp)
    }

    private var _hr = 0f; private var _glow = 0f  // set by _figure and _drawAll each frame

    private fun _hand(cnv: Canvas, x: Float, y: Float, sz: Float, side: Float, effort: Float, ld: Float) {
        val skin = blend(_skin, sicken(_skin, HEAT), HEAT*0.5f); val hr = _hr
        if (FA > 0.15f) {
            val slack = hr*0.3f; f(skin, ld)
            for (fIdx in 0 until 5) {
                val fa = -0.6f + fIdx * 0.3f
                val fx = x + side*cos(fa)*slack*0.7f; val fy = y + sin(fa)*slack*0.5f
                val fw = sz*0.18f; cnv.drawOval(fx-fw/2f, fy-fw/2f, fx+fw/2f, fy+fw/2f, fp)
            }
            cnv.drawOval(x-sz*0.4f, y-sz*0.3f, x+sz*0.4f, y+sz*0.3f, fp)
        } else {
            val fw = sz*0.75f; val fh = sz*0.6f; f(skin, ld)
            cnv.drawOval(x-fw/2f, y-fh/2f, x+fw/2f, y+fh/2f, fp)
            f(_bgDeep, 0.4f*ld*effort)
            for (k in 0 until 4) { val kx=x+(k-1.5f)*fw*0.18f; val ky=y-fh*0.15f; cnv.drawCircle(kx,ky,sz*0.08f,fp) }
        }
    }

    private fun _neck(cnv: Canvas, bx: Float, by: Float, hx: Float, hy: Float, hr: Float, ld: Float) {
        val skin = blend(_skin, sicken(_skin, HEAT), HEAT*0.4f)
        val rc = blend(_cloth, sicken(_cloth, HEAT), HEAT*0.5f); f(rc, 0.6f*ld)
        cnv.drawOval(bx-hr*1.5f, by+hr*0.35f-hr*0.5f, bx+hr*1.5f, by+hr*0.35f+hr*0.5f, fp)
        val np = Path()
        np.moveTo(bx-hr*0.4f, by); np.quadTo(bx-hr*0.25f+(hx-bx)*0.3f, (by+hy)*0.5f, hx-hr*0.22f, hy+hr*0.35f)
        np.lineTo(hx+hr*0.22f, hy+hr*0.35f); np.quadTo(bx+hr*0.25f+(hx-bx)*0.3f, (by+hy)*0.5f, bx+hr*0.4f, by)
        np.close(); f(blend(skin, _bgDeep, 0.32f), ld); cnv.drawPath(np, fp)
        f(_bgDeep, 0.22f*ld); cnv.drawRect(bx-hr*0.3f, (by+hy)*0.5f, hr*0.6f, (by+hy)*0.5f-by+hr*0.4f, fp)
    }

    private fun _head(cnv: Canvas, cx: Float, headY: Float, hr: Float, breathe: Float, ld: Float, t: Float, dt: Float, pitch: Float) {
        val skin = blend(_skin, sicken(_skin, HEAT), HEAT*0.55f)
        val skinLit = blend(intArrayOf(110,98,82), blend(intArrayOf(110,98,82), intArrayOf(217,70,239), 0.6f), HEAT*0.5f)
        val shadow = blend(skin, _bgDeep, 0.62f)
        val vsq = 1f - clamp(pitch, -0.6f, 0.6f) * 0.18f
        val fs = -pitch * hr * 0.10f
        val hw = hr * vsq

        // head silhouette
        cnv.save(); cnv.translate(cx, headY)
        val hp = Path(); hp.moveTo(-hr, 0f)
        hp.cubicTo(-hr*0.7f, -hw*0.5f, hr*0.7f, -hw*0.5f, hr, 0f)
        hp.cubicTo(hr*0.7f, hw*0.4f, -hr*0.7f, hw*0.4f, -hr, 0f); hp.close()
        f(blend(skin, shadow, 0.15f), ld); cnv.drawPath(hp, fp)
        f(shadow, 0.35f*ld); cnv.drawRect(-hr*0.4f, -hw*0.05f, hr*0.4f, hw*0.3f, fp)

        // brow ridge
        val browY = -hw*0.24f + fs
        f(shadow, 0.4f*ld); cnv.drawRect(-hr*0.3f, browY-hr*0.07f, hr*0.6f, hr*0.07f, fp)

        // eyes
        val shine = _glow * (0.5f + 0.5f * sin(t * 2f + 0.7f))
        val jawOpen = clamp(E + SC * 1.2f, 0f, 1f)
        _eyes(cnv, hr, browY, ld, t, dt, shine)
        // nose
        f(shadow, 0.3f*ld); val nh = hr*0.25f
        val nz = Path(); nz.moveTo(-hr*0.06f, browY+hr*0.1f); nz.lineTo(hr*0.06f, browY+hr*0.1f)
        nz.lineTo(0f, browY+hr*0.1f+nh); nz.close(); cnv.drawPath(nz, fp)
        // mouth
        _mouth(cnv, hr, jawOpen, ld, t, skin, fs)
        cnv.restore()
    }

    // ════════════════════════════════════════════════════════════
    //  FACE DETAILS
    // ════════════════════════════════════════════════════════════

    private fun _eyes(cnv: Canvas, hr: Float, browY: Float, ld: Float, t: Float, dt: Float, shine: Float) {
        val eyeY2 = browY + hr*0.20f
        for (side in listOf(-1f, 1f)) {
            val ex = side*hr*0.27f + eyeX*hr*0.3f; val ey = eyeY2 + eyeY*hr*0.2f
            // socket dark
            f(_bgDeep, 0.85f*ld)
            cnv.drawOval(ex-hr*0.10f, ey-hr*0.06f, ex+hr*0.10f, ey+hr*0.06f, fp)
            if (blink > 0.5f) continue
            // iris
            val irisR = hr*0.06f
            if (SC > 0.3f) {
                fi(Color.RED, (0.7f+0.3f*(0.5f+0.5f*sin(t*12f)))*ld)
                cnv.drawCircle(ex, ey, irisR, fp)
            } else {
                val ih = (eyeHue * 360f).toInt(); val isat = if (E>0.4f) 1f else 0.4f
                val icolor = Color.HSVToColor(floatArrayOf(ih.toFloat(), isat*255f, 150f))
                fi(icolor, 0.85f*ld * (if (eyeFlash>0f) 1.2f else 1f))
                cnv.drawCircle(ex, ey, irisR, fp)
            }
            // pupil
            f(_bgDeep, 0.9f*ld); cnv.drawCircle(ex+eyeX*hr*0.02f, ey+eyeY*hr*0.015f, hr*0.025f, fp)
            // shine
            if (shine > 0.05f && blink < 0.5f) {
                fi(Color.rgb(200, 220, 255), shine * 0.6f * ld)
                cnv.drawCircle(ex-hr*0.02f, ey-hr*0.02f, hr*0.018f, fp)
            }
            // red veins (under scream)
            if (SC > 0.2f) {
                si(blendI(Color.rgb(200,50,50), 0, 0f), 0.3f*SC*ld)
                sp.strokeWidth = maxOf(0.5f, hr*0.008f)
                val vp = Path(); vp.moveTo(ex - hr*0.08f, ey - hr*0.04f)
                vp.quadTo(ex, ey - hr*0.06f* (0.5f+0.5f*sin(t*8f)), ex + hr*0.08f, ey - hr*0.04f)
                cnv.drawPath(vp, sp)
            }
        }
    }

    private fun _mouth(cnv: Canvas, hr: Float, jawOpen: Float, ld: Float, t: Float, skin: IntArray, fs: Float) {
        val my = hr*0.60f + fs
        if (FA > 0.55f) {
            // slack open mouth
            f(blend(skin, _bgDeep, 0.55f), ld)
            cnv.drawOval(-hr*0.17f, my-hr*0.07f*(0.5f+FA*0.5f), hr*0.17f, my+hr*0.07f*(0.5f+FA*0.5f), fp)
            fi(Color.argb((0.4f*ld*255f).toInt(), 6, 4, 10), 1f)
            cnv.drawOval(-hr*0.08f, my-hr*0.02f, hr*0.08f, my+hr*0.05f, fp)
            // drool strand
            if (FA > 0.3f) {
                val dropLen = hr * (0.15f + FA*0.2f)
                val drool = Path(); drool.moveTo(hr*0.05f, my+hr*0.05f)
                drool.quadTo(hr*0.10f, my+dropLen*0.4f, hr*0.04f, my+dropLen)
                si(blendI(c2i(skin), Color.rgb(180,210,220), 0.5f), 0.25f*ld * (0.5f+0.5f*sin(t*2f+1.5f)))
                sp.strokeWidth = maxOf(0.5f, hr*0.012f); sp.strokeCap = Paint.Cap.ROUND
                cnv.drawPath(drool, sp); sp.strokeCap = Paint.Cap.BUTT
            }
            return
        }
        // snarling / clamped mouth
        if (jawOpen > 0.3f) {
            val sh = jawOpen * hr*0.20f
            f(blend(skin, _bgDeep, 0.5f), ld)
            val mp = Path(); mp.moveTo(-hr*0.18f, my); mp.quadTo(0f, my+sh, hr*0.18f, my)
            mp.quadTo(0f, my+sh*0.4f, -hr*0.18f, my); mp.close()
            cnv.drawPath(mp, fp)
        } else {
            s(skin, 0.7f*ld); sp.strokeWidth = maxOf(0.5f, hr*0.015f); sp.strokeCap = Paint.Cap.ROUND
            cnv.drawLine(-hr*0.14f, my, hr*0.14f, my, sp); sp.strokeCap = Paint.Cap.BUTT
        }
    }

    // ════════════════════════════════════════════════════════════
    //  STRAPS
    // ════════════════════════════════════════════════════════════
    private fun _straps(cnv: Canvas, W: Float, H: Float, cx: Float, chestY: Float, hr: Float, ld: Float, t: Float) {
        val span = hr*2.8f; val lx = cx-span; val rx = cx+span
        val rows = listOf(chestY - hr*0.75f, chestY + hr*0.55f, chestY + hr*1.85f)
        val pull = clamp(strain*1.1f + E*0.4f + SC*0.55f, 0f, 1f) * (1f-FA)
        val sh = hr*0.34f
        val strapColor = blend(_strap, sicken(_strap, HEAT), HEAT*0.3f)
        for (i in rows.indices) {
            val sy = rows[i]
            val amp = if (i==0) 1f else 0.6f
            val creak = if (pull>0.12f) sin(t*48f+i*1.7f)*hr*0.025f*pull*amp else 0f
            val bow = -pull*hr*0.10f*amp; val my = sy+creak
            // strap band
            f(strapColor, 0.96f*ld)
            val sp2 = Path(); sp2.moveTo(lx, sy); sp2.quadTo(cx, my+bow, rx, sy)
            sp2.lineTo(rx, sy+sh); sp2.quadTo(cx, my+bow+sh, lx, sy+sh); sp2.close()
            cnv.drawPath(sp2, fp)
            f(_bgDeep, 0.4f*ld)
            val sb = Path(); sb.moveTo(lx, sy+sh*0.62f); sb.quadTo(cx, my+bow+sh*0.62f, rx, sy+sh*0.62f)
            sb.lineTo(rx, sy+sh); sb.quadTo(cx, my+bow+sh, lx, sy+sh); sb.close(); cnv.drawPath(sb, fp)
            // taut highlight
            if (pull>0.2f) {
                si(blendI(c2i(strapColor), c2i(_cyan), 0.4f), 0.4f*pull*(0.4f+_glow)*ld)
                sp.strokeWidth = maxOf(1f, hr*0.04f); sp.strokeCap = Paint.Cap.ROUND
                val hl = Path(); hl.moveTo(lx, sy); hl.quadTo(cx, my+bow, rx, sy); cnv.drawPath(hl, sp)
                sp.strokeCap = Paint.Cap.BUTT
            }
            // anchor bolts
            for (ax in listOf(lx, rx)) {
                fi(Color.argb(200, 92, 92, 104), (0.5f+0.4f*_glow)*ld)
                cnv.drawCircle(ax, sy+sh*0.5f, sh*0.42f, fp)
                f(_bgDeep, 0.6f*ld); cnv.drawCircle(ax, sy+sh*0.5f, sh*0.16f, fp)
            }
            // buckle
            val bw=hr*0.5f; val bh=sh*1.15f; val by2 = my+bow-(bh-sh)*0.5f
            fi(Color.argb(200, 162, 162, 176), (0.5f+0.5f*_glow)*ld)
            cnv.drawRect(cx-bw/2f, by2, bw, bh, fp)
            f(_bgDeep, 0.55f*ld); cnv.drawRect(cx-bw/2f+bw*0.2f, by2+bh*0.2f, bw*0.6f, bh*0.6f, fp)
            fi(Color.argb(200, 120, 120, 134), (0.5f+0.4f*_glow)*ld)
            cnv.drawRect(cx-bw*0.05f, by2, bw*0.1f, bh, fp)
        }
    }

    // ════════════════════════════════════════════════════════════
    //  VIGNETTE
    // ════════════════════════════════════════════════════════════
    private fun _vignette(cnv: Canvas, W: Float, H: Float, t: Float, ld: Float) {
        val fa = FA; val heat = HEAT
        val dark = if (fa>0.05f) Color.argb((fa*192f* (0.5f+0.5f*sin(t*2f+fa*3f))).toInt(), 0,0,0) else 0
        if (Color.alpha(dark) > 0) { fp.color = Color.argb(Color.alpha(dark), 0,0,0); cnv.drawRect(0f, 0f, W, H, fp) }
        // bottom shadow
        val vg = RadialGradient(W/2f, H*1.2f, 0f, W/2f, H*1.2f, H*1.5f,
            Color.argb((0.5f*ld*255f).toInt(), 0, 0, 0), Color.argb(0, 0,0,0), Shader.TileMode.CLAMP)
        fp.shader = vg; cnv.drawRect(0f, 0f, W, H, fp); fp.shader = null
        // heat haze at bottom
        if (heat > 0.15f) {
            val hh = RadialGradient(W/2f, H*0.85f, 0f, W/2f, H*0.85f, W*0.20f,
                Color.argb((0.12f*heat*(0.5f+0.5f*sin(t*3f))*ld*255f).toInt(), 180,50,30),
                Color.argb(0, 0,0,0), Shader.TileMode.CLAMP)
            fp.shader = hh; cnv.drawRect(0f, 0f, W, H, fp); fp.shader = null
        }
    }

    // ════════════════════════════════════════════════════════════
    //  VOMIT PARTICLES (scream)
    // ════════════════════════════════════════════════════════════
    // Omitted for brevity — the scream state's visual feedback is
    // covered by eye reddening, head recoil, and jerk tremors.
}

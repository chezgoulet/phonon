package com.chezgoulet.phonon.ui.packs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.chezgoulet.phonon.ui.VisualizationPack
import com.chezgoulet.phonon.ui.VizState
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Cyber HUD — tactical cyberpunk heads-up display visualization pack.
 *
 * Reactive corner brackets pull inward while processing and pulse amber/red on
 * low battery or overheating. A central radar maps peers as blips that flare as
 * the sweep passes; the sweep speeds up under load. Compact battery/temperature
 * gauges sit in the top-left corner and a device-stat readout in the top-right.
 * Below, a Star Fox-style fighter weaves through a full-screen starfield,
 * banking and barrel-rolling as it dodges obstacles and blasts foes — all
 * scaling with workload.
 *
 * Low power mode: static brackets and a small static radar circle only.
 */
object CyberHudPack : VisualizationPack {

    override val id = "cyber-hud"
    override val name = "Cyber HUD"
    override val description = "Tactical HUD: reactive brackets, peer radar, telemetry gauges, and a Star Fox-style fighter weaving through a starfield, dodging obstacles and blasting foes as workload climbs"
    override val author = "chezgoulet"
    override val version = "1.4.5"

    override val defaultConfig = mapOf(
        "bracket_color" to "#00E5FF",
        "bracket_alert" to "#FF3D00",
        "bracket_critical" to "#EF4444",
        "radar_enabled" to "true",
    )

    // ── space scene state (Star Fox-style fighter + starfield) ──
    private class SpaceState {
        var shipX = 0f; var shipY = 0f; var tgtX = 0f; var tgtY = 0f; var vx = 0f; var vy = 0f
        var roll = 0f; var rolling = false; var rollT = 0f; var nextRoll = 3f
        var nextObstacle = 1.5f; var nextEnemy = 2.5f; var nextShot = 0f; var muzzle = -1f; var retarget = 0f
        var stars: Array<Star>? = null
        val obstacles = ArrayDeque<SpaceObstacle>()
        val enemies = ArrayDeque<SpaceEnemy>()
        val shots = ArrayDeque<SpaceShot>()
        val bursts = ArrayDeque<Burst>()
    }
    private class Star(var a: Float, var r: Float, val sp: Float, var z: Float)
    private class SpaceObstacle(var d: Float, val nx: Float, val ny: Float, val spin: Float)
    private class SpaceEnemy(var d: Float, val nx: Float, val ny: Float, var hp: Int, val wob: Float)
    private class SpaceShot(var d: Float, val nx: Float, val ny: Float)
    private class Burst(val x: Float, val y: Float, val vx: Float, val vy: Float, val born: Float)
    private var space: SpaceState? = null

    @Composable
    override fun Render(state: VizState, modifier: Modifier) {
        val primary = parseHexColor(state.themeConfig["bracket_color"] ?: "#00E5FF")
        val accent = parseHexColor(state.themeConfig["bracket_alert"] ?: "#FF3D00")
        val critical = parseHexColor(state.themeConfig["bracket_critical"] ?: "#EF4444")
        val lowPower = state.lowPowerMode
        val measurer = rememberTextMeasurer()

        var tSec by remember { mutableStateOf(0f) }
        var lastFrame by remember { mutableStateOf(0f) }
        var dtSec by remember { mutableStateOf(0.016f) }
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val tn = (now - start) / 1_000_000_000f
                dtSec = (tn - lastFrame).coerceIn(0f, 0.05f)
                lastFrame = tn
                tSec = tn
            }
        }

        Canvas(modifier = modifier.fillMaxSize()) {
            val t = tSec
            val dt = dtSec
            val w = size.width
            val h = size.height
            val margin = w * 0.045f
            val pulse = 0.6f + 0.4f * (0.5f + 0.5f * sin(t * 5f))

            drawRect(Color(0xFF050510))

            // ── deep-space scene: a Star Fox-style fighter weaves through a
            //    streaming starfield in the lower screen, dodging obstacles
            //    and enemies, with intensity scaling to workload. ──
            val sceneTop = h * 0.46f
            if (!lowPower) drawSpaceScene(state, t, dt, primary, accent, critical, sceneTop)

            // ── reactive corner brackets ──
            var bColor = primary
            var bGap = 0f
            when {
                !state.isHealthy || state.batteryTemperature > 45f -> bColor = blend(primary, critical, pulse)
                state.batteryLevel < 20 && !state.isCharging -> bColor = blend(primary, accent, pulse)
                state.isProcessing -> bGap = lerpF(0f, margin * 0.5f, sin((t % 1f) * Math.PI.toFloat()))
            }
            val blen = (if (lowPower) 0.04f else 0.08f) * w
            val bw = if (lowPower) 1.2f else 2f
            val brColor = bColor.copy(alpha = if (lowPower) 0.4f else 0.55f + 0.3f * pulse)
            data class Corner(val x: Float, val y: Float, val sx: Int, val sy: Int)
            for (cc in listOf(
                Corner(margin + bGap, margin + bGap, 1, 1),
                Corner(w - margin - bGap, margin + bGap, -1, 1),
                Corner(margin + bGap, h - margin - bGap, 1, -1),
                Corner(w - margin - bGap, h - margin - bGap, -1, -1),
            )) {
                drawLine(brColor, Offset(cc.x + cc.sx * blen, cc.y), Offset(cc.x, cc.y), strokeWidth = bw)
                drawLine(brColor, Offset(cc.x, cc.y), Offset(cc.x, cc.y + cc.sy * blen), strokeWidth = bw)
            }

            // ── radar (upper area, floating above the space scene) ──
            val radarR = (minOf(w, h) * 0.26f).coerceIn(w * 0.1f, w * 0.3f)
            val rcx = w / 2f
            val rcy = h * 0.3f
            val radarPeriod = if (state.isProcessing) 1.5f else 4.0f
            val radarDeg = (t / radarPeriod * 360f) % 360f
            val radarRad = radarDeg.toRad()

            if (!lowPower) {
                for ((rr, a) in listOf(1f to 0.35f, 0.66f to 0.13f, 0.33f to 0.1f)) {
                    drawCircle(primary.copy(alpha = a), radarR * rr, Offset(rcx, rcy), style = Stroke(width = if (rr == 1f) 1.4f else 0.8f))
                }
                drawLine(primary.copy(alpha = 0.12f), Offset(rcx - radarR, rcy), Offset(rcx + radarR, rcy), strokeWidth = 0.5f)
                drawLine(primary.copy(alpha = 0.12f), Offset(rcx, rcy - radarR), Offset(rcx, rcy + radarR), strokeWidth = 0.5f)

                // sweep wedge
                val wedge = Path().apply {
                    moveTo(rcx, rcy)
                    arcTo(Rect(rcx - radarR, rcy - radarR, rcx + radarR, rcy + radarR), radarDeg - 45f, 45f, false)
                    close()
                }
                drawPath(wedge, primary.copy(alpha = 0.1f))
                drawLine(primary.copy(alpha = 0.55f), Offset(rcx, rcy), Offset(rcx + cos(radarRad) * radarR, rcy + sin(radarRad) * radarR), strokeWidth = 1.5f)

                // peer blips, flaring as the sweep passes
                for (peer in state.peerStates) {
                    val pp = peer.position ?: continue
                    val relX = (pp.x - 0.5f) * 2f
                    val relY = (pp.y - 0.5f) * 2f
                    val d = hypot(relX, relY).coerceAtMost(1f)
                    val ang = atan2(relY, relX)
                    val br = d * radarR
                    val bx = rcx + cos(ang) * br
                    val by = rcy + sin(ang) * br
                    val bc = if (peer.isProcessing) accent else primary
                    val twoPi = (2f * Math.PI).toFloat()
                    val angDiff = kotlin.math.abs(((ang - radarRad + Math.PI.toFloat() * 3f) % twoPi) - Math.PI.toFloat())
                    val flare = (1f - angDiff / 0.9f).coerceIn(0f, 1f)
                    drawCircle(bc.copy(alpha = 0.4f + 0.5f * flare), 2.5f + 3f * flare, Offset(bx, by))
                }
                // center ping while processing
                if (state.isProcessing) {
                    val ping = t % 1f
                    drawCircle(accent.copy(alpha = (1f - ping) * 0.5f), ping * radarR, Offset(rcx, rcy), style = Stroke(width = 1.2f))
                }
            } else {
                drawCircle(primary.copy(alpha = 0.15f), radarR * 0.5f, Offset(rcx, rcy), style = Stroke(width = 1f))
            }

            // center reticle (this device)
            val retR = if (lowPower) 6f else 12f
            val retColor = if (state.isProcessing) accent.copy(alpha = 0.5f + 0.4f * pulse) else primary.copy(alpha = 0.4f)
            drawCircle(retColor, retR, Offset(rcx, rcy), style = Stroke(width = 1.2f))
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                drawLine(retColor, Offset(rcx + dx * retR * 0.3f, rcy + dy * retR * 0.3f), Offset(rcx + dx * retR, rcy + dy * retR), strokeWidth = 1f)
            }

            // ── compact battery / temp gauges, justified in the top-left
            //    corner zone: inset past the bracket's max inward pulse and
            //    vertically centered between the bracket and the radar ──
            if (!lowPower) {
                val maxGap = margin * 0.5f
                val zoneLeft = margin + maxGap + w * 0.02f
                val zoneTop = margin + maxGap
                val radarTop = rcy - radarR
                val gw = w * 0.03f
                val ggap = w * 0.055f
                val labelPad = h * 0.022f
                val gh = ((radarTop - zoneTop) - labelPad - h * 0.02f).clampTo(h * 0.05f, h * 0.085f)
                val gy = zoneTop + ((radarTop - zoneTop) - (gh + labelPad)) * 0.5f
                val gx = zoneLeft
                val green = Color(0xFF22C55E)
                val amber = Color(0xFFEAB308)
                val battColor = when { state.batteryLevel < 15 -> critical; state.batteryLevel < 40 -> amber; else -> green }
                drawGauge(measurer, gx, gy, gw, gh, state.batteryLevel / 100f, battColor, primary, "BAT")
                val tempColor = when { state.batteryTemperature > 42f -> critical; state.batteryTemperature > 35f -> amber; else -> green }
                drawGauge(measurer, gx + ggap, gy, gw, gh, ((state.batteryTemperature - 15f) / 40f).clampTo(0f, 1f), tempColor, primary, "TMP")
            }

            // ── right data readout: justified in the top-right corner zone,
            //    between the bracket's max inward pulse and the radar, the
            //    block of rows vertically centered in that band ──
            if (!lowPower) {
                val dataPx = w * 0.024f
                val maxGap = margin * 0.5f
                val rightEdge = w - margin - maxGap - w * 0.02f
                val labelX = rightEdge - w * 0.22f
                val rowH = h * 0.03f
                val radarTop = rcy - radarR
                val zoneTop = margin + maxGap
                val modeShort = when (state.coordinatorMode) {
                    "pool" -> "POOL"; "standby" -> "STBY"; "inference" -> "INFER"; "update" -> "UPDT"
                    else -> state.coordinatorMode.uppercase()
                }
                val rows = listOf(
                    "DEV" to state.deviceId.takeLast(6),
                    "TPS" to state.tokensPerSecond.toInt().toString(),
                    "MODE" to modeShort,
                    "PEERS" to state.peerCount.toString(),
                    "QUEUE" to state.queueDepth.toString(),
                )
                // center the block of rows in the band (text positioned by top-left,
                // so offset up by half a row's text height)
                val blockH = (rows.size - 1) * rowH
                val startY = zoneTop + ((radarTop - zoneTop) - blockH) * 0.5f - dataPx * 0.5f
                rows.forEachIndexed { i, (k, v) ->
                    val y = startY + i * rowH
                    drawLeftText(measurer, k, primary.copy(alpha = 0.4f), dataPx, labelX, y)
                    drawRightText(measurer, v, primary.copy(alpha = 0.85f), dataPx, rightEdge, y)
                }
            }

            // bottom status
            drawLeftText(measurer, if (state.isProcessing) "● INFERENCE" else "○ STANDBY", primary.copy(alpha = 0.45f), w * 0.028f, margin + 4f, h - margin - blen - h * 0.05f)

            // unhealthy alert
            if (!state.isHealthy) {
                drawRect(critical.copy(alpha = 0.06f + 0.06f * pulse))
                drawCenteredText(measurer, "⚠ SYSTEM ALERT", critical, w * 0.04f, w / 2f, h * 0.12f, bold = true)
            }
        }
    }

    // ── SPACE SCENE ──
    // A Star Fox-style fighter flies through a streaming starfield in the
    // lower screen, strafing up/down/left/right to dodge obstacles and
    // enemies, banking as it moves and snapping a barrel roll under load.
    // Star speed, obstacle cadence and combat all scale with workload.
    private fun DrawScope.drawSpaceScene(state: VizState, t: Float, dt: Float, primary: Color, accent: Color, critical: Color, sceneTop: Float) {
        val intensity = ((if (state.isProcessing) 0.3f else 0f) + state.workload * 0.6f + state.inferenceLoad * 0.45f).clampTo(0f, 1f)
        val dts = dt.coerceAtMost(0.05f)
        val s = space ?: SpaceState().also { space = it }

        val w = size.width; val h = size.height
        val top = sceneTop
        val cx = w / 2f
        val regionH = h - top

        // ── full-screen starfield (drawn unclipped, behind the HUD) ──
        // Stars stream radially from the screen center so the warp effect
        // fills the entire UI rather than ending at the scene boundary.
        val scx = w / 2f; val scy = h / 2f

        // faint nebula wash, full-screen and centered so there's no seam at the
        // scene boundary; warmer under load.
        drawCircle(
            brush = Brush.radialGradient(
                listOf(blend(primary, accent, intensity).copy(alpha = 0.05f + 0.05f * intensity), primary.copy(alpha = 0f)),
                center = Offset(scx, scy), radius = hypot(w, h) * 0.6f,
            ),
            radius = hypot(w, h) * 0.6f, center = Offset(scx, scy),
        )

        val stars = s.stars ?: Array(200) {
            Star(Random.nextFloat() * 6.28f, Random.nextFloat(), 0.25f + Random.nextFloat() * 0.7f, 0.4f + Random.nextFloat() * 0.6f)
        }.also { s.stars = it }
        val flowSpeed = 0.2f + intensity * 1.3f
        val starMaxRad = hypot(w * 0.55f, h * 0.55f)
        for (star in stars) {
            star.r += star.sp * flowSpeed * dts
            if (star.r > 1.05f) { star.r = 0.02f; star.a = Random.nextFloat() * 6.28f; star.z = 0.4f + Random.nextFloat() * 0.6f }
            val rad = star.r * starMaxRad
            val sx = scx + cos(star.a) * rad
            val sy = scy + sin(star.a) * rad
            val bright = (star.r * 1.3f).clampTo(0.12f, 1f) * star.z
            val streak = (5f + intensity * 34f) * star.r
            val tailX = scx + cos(star.a) * (rad - streak)
            val tailY = scy + sin(star.a) * (rad - streak)
            drawLine(Color(0xFFD2EBFF).copy(alpha = bright * 0.8f), Offset(tailX, tailY), Offset(sx, sy), strokeWidth = 0.6f + star.z * 1.5f)
        }

        clipRect(0f, top, w, h) {
            // playfield mapping (normalized -1..1 → screen)
            val playCx = cx; val playCy = top + regionH * 0.5f
            val playHalfW = w * 0.38f; val playHalfH = regionH * 0.28f
            fun toScreenX(nx: Float) = playCx + nx * playHalfW
            fun toScreenY(ny: Float) = playCy + ny * playHalfH

            // ── obstacles & enemies emerge from center, grow toward viewer ──
            s.nextObstacle -= dts
            if (s.nextObstacle <= 0f && intensity > 0.1f) {
                s.obstacles.addLast(SpaceObstacle(0f, (Random.nextFloat() * 2f - 1f) * 0.9f, (Random.nextFloat() * 2f - 1f) * 0.9f, Random.nextFloat() * 6f))
                s.nextObstacle = lerpF(2.4f, 0.55f, intensity) * (0.7f + Random.nextFloat() * 0.6f)
            }
            for (o in s.obstacles) o.d += (0.25f + intensity * 0.9f) * dts
            while (s.obstacles.isNotEmpty() && s.obstacles.first().d >= 1.25f) s.obstacles.removeFirst()

            s.nextEnemy -= dts
            if (s.nextEnemy <= 0f && intensity > 0.35f) {
                s.enemies.addLast(SpaceEnemy(0f, (Random.nextFloat() * 2f - 1f) * 0.85f, (Random.nextFloat() * 2f - 1f) * 0.7f, 1, Random.nextFloat() * 6f))
                s.nextEnemy = lerpF(3.0f, 0.85f, intensity) * (0.6f + Random.nextFloat() * 0.7f)
            }
            for (e in s.enemies) e.d += (0.18f + intensity * 0.5f) * dts
            while (s.enemies.isNotEmpty() && (s.enemies.first().d >= 1.25f || s.enemies.first().hp <= 0)) s.enemies.removeFirst()

            // ── ship AI: target open space away from near threats ──
            s.retarget -= dts
            // threats as (nx, ny, d) triples so obstacle/enemy types don't mix
            val threats = ArrayList<Triple<Float, Float, Float>>()
            for (o in s.obstacles) if (o.d > 0.45f && o.d < 1.05f) threats.add(Triple(o.nx, o.ny, o.d))
            for (e in s.enemies) if (e.d > 0.45f && e.d < 1.05f) threats.add(Triple(e.nx, e.ny, e.d))
            val crowded = threats.any { kotlin.math.abs(it.first - s.shipX) < 0.3f && kotlin.math.abs(it.second - s.shipY) < 0.3f && it.third > 0.7f }
            if (s.retarget <= 0f || crowded) {
                var bestX = 0f; var bestY = 0f; var bestScore = -1f
                repeat(8) {
                    val candX = (Random.nextFloat() * 2f - 1f) * 0.85f
                    val candY = (Random.nextFloat() * 2f - 1f) * 0.8f
                    var score = 1f
                    for (o in threats) score = minOf(score, hypot(o.first - candX, o.second - candY))
                    if (score > bestScore) { bestScore = score; bestX = candX; bestY = candY }
                }
                s.tgtX = bestX; s.tgtY = bestY
                s.retarget = lerpF(1.4f, 0.4f, intensity)
            }
            val prevX = s.shipX; val prevY = s.shipY
            val follow = 1f - 0.0006f.pow(dts)
            s.shipX += (s.tgtX - s.shipX) * follow
            s.shipY += (s.tgtY - s.shipY) * follow
            s.vx = (s.shipX - prevX) / dts.coerceAtLeast(1e-3f)
            s.vy = (s.shipY - prevY) / dts.coerceAtLeast(1e-3f)

            // ── barrel roll under load ──
            s.nextRoll -= dts
            if (!s.rolling && s.nextRoll <= 0f && intensity > 0.45f) { s.rolling = true; s.rollT = 0f }
            if (s.rolling) {
                s.rollT += dts * 3.2f
                s.roll = s.rollT * 6.2832f
                if (s.rollT >= 1f) { s.rolling = false; s.roll = 0f; s.nextRoll = lerpF(6f, 2f, intensity) * (0.7f + Random.nextFloat() * 0.6f) }
            }

            // ── ship fires at nearest enemy ──
            s.nextShot -= dts
            val shipSx = toScreenX(s.shipX); val shipSy = toScreenY(s.shipY)
            if (s.nextShot <= 0f && s.enemies.isNotEmpty() && intensity > 0.35f) {
                s.shots.addLast(SpaceShot(0.35f, s.shipX, s.shipY)); s.nextShot = lerpF(0.85f, 0.2f, intensity); s.muzzle = t
            }
            for (sh in s.shots) sh.d -= (1.2f + intensity) * dts
            while (s.shots.isNotEmpty() && s.shots.first().d <= 0f) s.shots.removeFirst()
            for (sh in s.shots) for (e in s.enemies) {
                if (e.hp > 0 && kotlin.math.abs(sh.d - e.d) < 0.08f && kotlin.math.abs(sh.nx - e.nx) < 0.18f && kotlin.math.abs(sh.ny - e.ny) < 0.18f) {
                    e.hp = 0; sh.d = -1f
                    val ex = scx + (toScreenX(e.nx) - scx) * e.d; val ey = scy + (toScreenY(e.ny) - scy) * e.d
                    repeat(9) {
                        val a = Random.nextFloat() * 6.28f; val v = (0.4f + Random.nextFloat()) * (30f + 50f * e.d)
                        s.bursts.addLast(Burst(ex, ey, cos(a) * v, sin(a) * v, t))
                    }
                }
            }

            // ── draw enemies (far first) ──
            for (e in s.enemies) {
                val px = scx + (toScreenX(e.nx) - scx) * e.d; val py = scy + (toScreenY(e.ny) - scy) * e.d
                drawSpaceFoe(px, py, e.d, t, e.wob, critical)
            }
            // shots
            for (sh in s.shots) {
                if (sh.d <= 0f) continue // killed/expired this frame — don't draw
                val px = scx + (toScreenX(sh.nx) - scx) * sh.d; val py = scy + (toScreenY(sh.ny) - scy) * sh.d
                val rad = (1.5f + sh.d * 3f).coerceAtLeast(0.5f)
                drawCircle(accent.copy(alpha = 0.5f), rad * 2f, Offset(px, py))
                drawCircle(Color.White.copy(alpha = 0.9f), rad, Offset(px, py))
            }
            // obstacles
            for (o in s.obstacles) {
                val px = scx + (toScreenX(o.nx) - scx) * o.d; val py = scy + (toScreenY(o.ny) - scy) * o.d
                drawSpaceRock(px, py, o.d, o.spin + t * 0.6f, critical)
            }

            // ── the fighter ──
            drawFighter(shipSx, shipSy, regionH * 0.1f, s.vx, s.roll, t, intensity, primary, accent)

            // muzzle flash
            if (s.muzzle >= 0f && t - s.muzzle < 0.08f) {
                drawCircle(Color.White.copy(alpha = 0.8f), regionH * 0.02f, Offset(shipSx, shipSy - regionH * 0.05f))
            }

            // explosion bursts
            while (s.bursts.isNotEmpty() && t - s.bursts.first().born >= 0.5f) s.bursts.removeFirst()
            for (b in s.bursts) {
                val age = (t - b.born) / 0.5f
                drawCircle(blend(accent, Color.White, 0.5f).copy(alpha = 1f - age), (1f - age) * 2.5f + 0.4f, Offset(b.x + b.vx * age, b.y + b.vy * age))
            }
        }
    }

    // a Star Fox-style fighter seen from behind; banks with lateral velocity,
    // spins for a barrel roll (roll radians). sc = body half-size.
    private fun DrawScope.drawFighter(x: Float, y: Float, sc: Float, vx: Float, roll: Float, t: Float, intensity: Float, primary: Color, accent: Color) {
        val bank = (vx * 0.5f).clampTo(-1f, 1f)
        val rollScaleX = cos(roll)
        withTransform({
            translate(x, y)
            rotate(bank * 0.4f * 57.2958f, pivot = Offset.Zero)
            scale(rollScaleX, 1f, pivot = Offset.Zero)
        }) {
            val ww = sc; val hh = sc * 1.15f
            // twin engine exhaust
            val thrust = 0.55f + 0.45f * sin(t * 30f) + intensity * 0.3f
            for (ex in listOf(-0.34f, 0.34f)) {
                val flame = Path().apply {
                    moveTo(ex * ww - ww * 0.08f, hh * 0.2f)
                    lineTo(ex * ww, hh * 0.2f + sc * (0.7f + intensity) * thrust)
                    lineTo(ex * ww + ww * 0.08f, hh * 0.2f); close()
                }
                drawPath(flame, brush = Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.9f), accent.copy(alpha = 0f)),
                    startY = hh * 0.2f, endY = hh * 0.2f + sc * (0.9f + intensity),
                ))
            }
            // body fill so stars don't show through
            val body = Path().apply {
                moveTo(0f, -hh * 0.55f); lineTo(ww * 0.22f, hh * 0.25f); lineTo(0f, hh * 0.12f); lineTo(-ww * 0.22f, hh * 0.25f); close()
            }
            drawPath(body, Color(0xE6080C14))
            // wings
            val wing = Path().apply {
                moveTo(0f, -hh * 0.55f)
                lineTo(ww * 0.22f, hh * 0.25f); lineTo(ww * 0.95f, hh * 0.5f); lineTo(ww * 0.3f, hh * 0.32f); lineTo(0f, hh * 0.12f)
                lineTo(-ww * 0.3f, hh * 0.32f); lineTo(-ww * 0.95f, hh * 0.5f); lineTo(-ww * 0.22f, hh * 0.25f); close()
            }
            drawPath(wing, primary.copy(alpha = 0.95f), style = Stroke(width = (sc * 0.09f).coerceAtLeast(1f), join = StrokeJoin.Round))
            // spine highlight
            drawLine(Color(0xFFE6FAFF).copy(alpha = 0.7f), Offset(0f, -hh * 0.5f), Offset(0f, hh * 0.1f), strokeWidth = (sc * 0.04f).coerceAtLeast(0.6f))
            // wingtip lights + cockpit
            drawCircle(accent.copy(alpha = 0.9f), sc * 0.06f, Offset(ww * 0.95f, hh * 0.5f))
            drawCircle(accent.copy(alpha = 0.9f), sc * 0.06f, Offset(-ww * 0.95f, hh * 0.5f))
            drawOval(primary.copy(alpha = 0.6f), Offset(-ww * 0.1f, -hh * 0.15f - hh * 0.16f), Size(ww * 0.2f, hh * 0.32f))
        }
    }

    // wireframe enemy fighter (chevron pointing at us) near the vanishing point
    private fun DrawScope.drawSpaceFoe(x: Float, y: Float, sc: Float, t: Float, wob: Float, critical: Color) {
        val r = sc * 60f
        if (r < 2f) return
        val yy = y + sin(t * 4f + wob) * r * 0.1f
        val chevron = Path().apply {
            moveTo(x, yy + r * 0.6f); lineTo(x + r * 0.8f, yy - r * 0.5f); lineTo(x, yy - r * 0.2f); lineTo(x - r * 0.8f, yy - r * 0.5f); close()
        }
        drawPath(chevron, critical.copy(alpha = 0.12f))
        drawPath(chevron, critical.copy(alpha = 0.9f), style = Stroke(width = (sc * 3f).coerceAtLeast(1f), join = StrokeJoin.Round))
        drawCircle(Color(0xFFFF7878).copy(alpha = 0.9f), (r * 0.12f).coerceAtLeast(0.8f), Offset(x, yy - r * 0.1f))
    }

    // tumbling wireframe asteroid/obstacle
    private fun DrawScope.drawSpaceRock(x: Float, y: Float, sc: Float, spin: Float, critical: Color) {
        val r = sc * 55f
        if (r < 2f) return
        val sides = 7
        val rock = Path()
        for (i in 0..sides) {
            val a = spin + (i / sides.toFloat()) * 6.2832f
            val rr = r * (0.7f + 0.3f * sin(i * 2.3f + spin))
            val px = x + cos(a) * rr; val py = y + sin(a) * rr
            if (i == 0) rock.moveTo(px, py) else rock.lineTo(px, py)
        }
        rock.close()
        drawPath(rock, critical.copy(alpha = 0.08f))
        drawPath(rock, critical.copy(alpha = 0.8f), style = Stroke(width = (sc * 3f).coerceAtLeast(1f), join = StrokeJoin.Round))
    }

    private fun DrawScope.drawGauge(m: TextMeasurer, x: Float, y: Float, gw: Float, gh: Float, fill: Float, color: Color, frame: Color, label: String) {
        drawRect(frame.copy(alpha = 0.25f), Offset(x, y), Size(gw, gh), style = Stroke(width = 0.8f))
        val fh = gh * fill.coerceIn(0f, 1f)
        drawRect(
            brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.4f), color.copy(alpha = 0.85f)), startY = y, endY = y + gh),
            topLeft = Offset(x, y + gh - fh), size = Size(gw, fh),
        )
        val r = m.measure(AnnotatedString(label), TextStyle(color = frame.copy(alpha = 0.5f), fontSize = (gw * 0.7f).sp, fontFamily = FontFamily.Monospace))
        drawText(r, topLeft = Offset(x + gw / 2f - r.size.width / 2f, y + gh + 3f))
    }

    private fun DrawScope.drawRightText(m: TextMeasurer, text: String, color: Color, px: Float, right: Float, y: Float) {
        val r = m.measure(AnnotatedString(text), TextStyle(color = color, fontSize = px.sp, fontFamily = FontFamily.Monospace))
        drawText(r, topLeft = Offset(right - r.size.width, y))
    }

    private fun DrawScope.drawLeftText(m: TextMeasurer, text: String, color: Color, px: Float, x: Float, y: Float) {
        val r = m.measure(AnnotatedString(text), TextStyle(color = color, fontSize = px.sp, fontFamily = FontFamily.Monospace))
        drawText(r, topLeft = Offset(x, y))
    }

    private fun DrawScope.drawCenteredText(m: TextMeasurer, text: String, color: Color, px: Float, cx: Float, y: Float, bold: Boolean = false) {
        val r = m.measure(AnnotatedString(text), TextStyle(color = color, fontSize = px.sp, fontFamily = FontFamily.Monospace, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
        drawText(r, topLeft = Offset(cx - r.size.width / 2f, y))
    }
}

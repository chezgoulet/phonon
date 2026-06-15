package com.chezgoulet.phonon.ui.packs

import androidx.compose.animation.core.withFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.chezgoulet.phonon.ui.VisualizationPack
import com.chezgoulet.phonon.ui.VizState
import kotlin.math.*
import kotlin.random.Random

/**
 * Honeycomb — a living nest filling the screen. One unbroken comb of hexagonal
 * cells; a deep tunnel-door opens into the hive. Bees ride a shared, slowly-rotating
 * flow field so the swarm has organic currents; individuals peel off to pump empty
 * cells full of glowing honey, others wander and groom, and returning foragers
 * waggle-dance at the door.
 *
 * State → behaviour:
 *   • Throughput (tokens/s): bees carry visible pollen; deposit rate & pollen
 *     brightness scale with throughput — the headline signal.
 *   • Queue depth: number of EMPTY cells = work backlog, drained in.
 *   • Temperature: wax reddens AND bees grow sluggish + agitated when hot.
 *   • Health: unhealthy → swarm scatters, abandons cells, erratic flight.
 *   • Battery: whole-comb honey warmth/brightness dims as battery falls.
 *   • Activity also lights the door: busy = bright, bustling entrance.
 */
object HoneycombPack : VisualizationPack {

    override val id = "honeycomb"
    override val name = "Honeycomb"
    override val description = "A living nest filling the screen. Bees ride organic flow-field currents from a deep tunnel-door, pump cells full of glowing honey, wander and groom, and waggle-dance on return."
    override val author = "chezgoulet"
    override val version = "0.1.0"

    override val defaultConfig = mapOf(
        "comb_fill" to "1.0",
        "bee_count" to "1.0",
    )

    // ── Colour helpers ─────────────────────────────────────────────

    private data class Rgb(val r: Float, val g: Float, val b: Float)

    private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
    private fun lerpRgb(a: Rgb, b: Rgb, t: Float): Rgb = Rgb(lerpF(a.r, b.r, t), lerpF(a.g, b.g, t), lerpF(a.b, b.b, t))
    private fun rgba(c: Rgb, a: Float): Color = Color(c.r, c.g, c.b, a)
    private fun rgba(r: Float, g: Float, b: Float, a: Float): Color = Color(red = r, green = g, blue = b, alpha = a.coerceIn(0f, 1f))
    private fun colorFromHex(hex: Int): Rgb = Rgb(((hex shr 16) and 0xFF) / 255f, ((hex shr 8) and 0xFF) / 255f, (hex and 0xFF) / 255f)
    private fun blend(c: Rgb, t: Float): Rgb = lerpRgb(c, Rgb(1f, 1f, 1f), t)

    // ── Cell state ─────────────────────────────────────────────────

    private data class Cell(
        val cx: Float, val cy: Float,
        var fill: Float, var target: Float,
        var claimedBy: Int = -1,
        var flash: Float = 0f,
        val jitter: Float,
        var isBrood: Boolean = false,
        var hunger: Float = 0f,
        var beingFed: Boolean = false,
        var overfill: Float = 0f,
        var dead: Float = 0f,
    )

    // ── Bee state ──────────────────────────────────────────────────

    private data class Bee(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var heading: Float,
        var role: String,
        var state: String,
        var cell: Int,
        var work: Float,
        var born: Float,
        var brood: Int,
        var load: Float,
        var wanderUntil: Float,
        var groomUntil: Float,
        var danceUntil: Float,
        var danceT: Float,
        val ph1: Float,
        val ph2: Float,
        val f1: Float,
        val f2: Float,
        val amp: Float,
        val sp: Float,
        var flap: Float,
        val size: Float,
        var pollen: Float,
        var mopX: Float = 0f,
        var mopY: Float = 0f,
        var mopUntil: Float = 0f,
    )

    private data class Guard(
        var ang: Float, var sp: Float, var flap: Float,
    )

    private data class Drip(
        var x: Float, var y: Float, var vy: Float, val r: Float, var life: Float,
    )

    private data class Corpse(
        var x: Float, var y: Float, var vy: Float,
        var rot: Float, var vr: Float, val r: Float, var life: Float,
    )

    private data class QueenState(
        var x: Float, var y: Float,
        var tx: Float, var ty: Float,
        var vx: Float, var vy: Float,
        var heading: Float,
        var repick: Float,
        var wob: Float,
        var layT: Float,
        var target: Int,
        var flap: Float,
        var stride: Float,
        var pauseT: Float,
    )

    // ── Mutable scene state ────────────────────────────────────────

    private var cells = mutableListOf<Cell>()
    private var gridCols = 0; private var gridRows = 0
    private var gridR = 0f; private var gridHexW = 0f
    private var gridVert = 0f; private var gridW = 0f; private var gridH = 0f; private var gridLp = false
    private var bees = mutableListOf<Bee>()
    private var guards = mutableListOf<Guard>()
    private var lastSpawn = 0f; private var drainAcc = 0f
    private var windowCell = -1
    private var broodCells = mutableListOf<Int>()
    private var drips = mutableListOf<Drip>()
    private var leakAcc = 0f; private var pool = 0f; private var lastMop = 0f
    private var queen: QueenState? = null
    private var corpses = mutableListOf<Corpse>()
    private var dieAcc = 0f
    private var melt = 0f

    override fun onActivate() { reset() }
    override fun onDeactivate() {}

    private fun reset() {
        cells.clear(); bees.clear(); guards.clear()
        drips.clear(); corpses.clear(); broodCells.clear()
        queen = null; windowCell = -1; lastSpawn = 0f; drainAcc = 0f
        leakAcc = 0f; pool = 0f; lastMop = 0f; dieAcc = 0f; melt = 0f
        gridCols = 0; gridRows = 0; gridR = 0f; gridHexW = 0f
        gridVert = 0f; gridW = 0f; gridH = 0f; gridLp = false
    }

    // ════════════════════════════════════════════════════════════════
    // RENDER
    // ════════════════════════════════════════════════════════════════

    @Composable
    override fun Render(state: VizState, modifier: Modifier) {
        val fillMod = state.themeConfig["comb_fill"]?.toFloatOrNull() ?: 1f
        val beeMod = state.themeConfig["bee_count"]?.toFloatOrNull() ?: 1f

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
            val t = tSec; val dt = dtSec
            val W = size.width; val H = size.height
            val lowPower = state.lowPowerMode
            val sig = HoneycombSignals(
                t = t, dt = dt,
                lowPower = lowPower,
                fillMod = fillMod, beeMod = beeMod,
                isProcessing = state.isProcessing,
                inferenceLoad = state.inferenceLoad,
                queueDepth = state.queueDepth,
                tokensPerSecond = state.tokensPerSecond,
                batteryTemperature = state.batteryTemperature,
                batteryLevel = state.batteryLevel,
                isHealthy = state.isHealthy,
                isCharging = state.isCharging,
            )

            ensureGrid(W, H, lowPower)

            val nc = drawContext.canvas.nativeCanvas

            // ── Background ──
            drawRect(nativeColor(0xFF0c0903), Offset.Zero, size)

            // ── Update derived cell state ──
            // Cell fill targets
            for (i in cells.indices) {
                if (i == windowCell || cells[i].isBrood) continue
                cells[i].target = sig.fillMod * sig.reserve
            }

            // Hunger: brood get hungrier with inference load
            val hungerRate = (0.012f + sig.inferenceLoad * 0.5f) * dt
            for (bi in broodCells) {
                val b = cells[bi]
                b.hunger = (b.hunger + hungerRate).coerceIn(0f, 1f)
            }

            // Survival mode: brood die-off + queen
            if (sig.survival > 0.01f) {
                dieAcc += sig.survival * sig.survival * 0.9f * dt
                while (dieAcc >= 1f) {
                    dieAcc -= 1f
                    val living = broodCells.mapNotNull { bi ->
                        val c = cells[bi]; if (c.dead < 0.5f && c.hunger < 0.98f) c else null
                    }
                    if (living.isNotEmpty()) {
                        val victim = living[Random.nextInt(living.size)]
                        victim.dead = 1f; victim.hunger = 1f; victim.beingFed = false
                        corpses.add(Corpse(
                            x = victim.cx, y = victim.cy, vy = 4f + Random.nextFloat() * 6f,
                            rot = Random.nextFloat() * 6.28f, vr = (Random.nextFloat() - 0.5f) * 4f,
                            r = gridR * 0.3f, life = 1f,
                        ))
                    }
                }
                if (queen == null) {
                    queen = QueenState(
                        x = cells[windowCell].cx, y = cells[windowCell].cy,
                        tx = cells[windowCell].cx, ty = cells[windowCell].cy,
                        vx = 0f, vy = -8f, heading = -PI.toFloat() / 2f,
                        repick = 0f, wob = Random.nextFloat() * 6.28f,
                        layT = 0f, target = -1,
                        flap = Random.nextFloat() * 6.28f,
                        stride = Random.nextFloat() * 6.28f,
                        pauseT = 0f,
                    )
                }
            } else if (queen != null && queen!!.y < -gridR) {
                queen = null
            }

            // Heat leak: full cells weep honey
            val leakRate = (0.35f * sig.leak + 1.6f * sig.leak * sig.leak) * (if (lowPower) 4f else 16f)
            leakAcc += leakRate * dt
            while (leakAcc >= 1f) {
                leakAcc -= 1f
                for (k in 0 until 10) {
                    val idx = Random.nextInt(cells.size)
                    val c = cells[idx]
                    if (idx == windowCell || c.isBrood || c.fill < 0.5f) continue
                    val sag = sig.leak * gridR * (0.25f + (c.cy / H) * 0.9f) * (0.7f + 0.3f * sin(t * 1.3f + c.jitter))
                    c.fill = max(0f, c.fill - (0.25f + 0.4f * sig.leak))
                    drips.add(Drip(
                        x = c.cx + (Random.nextFloat() - 0.5f) * gridR * 0.4f,
                        y = c.cy + sag + gridR * 0.5f,
                        vy = 8f + Random.nextFloat() * 12f,
                        r = gridR * (0.10f + Random.nextFloat() * 0.06f),
                        life = 1f,
                    ))
                    break
                }
            }

            // Ambient backlog drain
            drainAcc += sig.queueBacklog * 1.4f * dt
            while (drainAcc >= 1f) {
                drainAcc -= 1f
                for (k in 0 until 8) {
                    val idx = Random.nextInt(cells.size)
                    val c = cells[idx]
                    if (idx != windowCell && !c.isBrood && c.fill > 0.55f && c.claimedBy < 0) {
                        c.fill = 0f; break
                    }
                }
            }

            // Melt recovery/sag
            val wax = waxTint(sig.batteryTemperature)
            if (sig.heat > 0f) melt = min(1f, melt + dt * 0.4f * sig.heat)
            else melt = max(0f, melt - dt * 0.15f)

            // ── Draw comb ──
            for (i in cells.indices) {
                if (i == windowCell) continue
                val c = cells[i]
                val isDeadBrood = c.isBrood && c.dead > 0.5f

                val meltSag = if (c.isBrood) 0f else exp((c.cy / H) * melt * 1.8f) * gridR * 0.35f
                val cx = c.cx; val cy = c.cy + meltSag
                val hexP = nativeHexPath(cx, cy, gridR)
                val hexFill = android.graphics.Paint().apply { isAntiAlias = true; style = android.graphics.Paint.Style.FILL }
                val hexStroke = android.graphics.Paint().apply { isAntiAlias = true; style = android.graphics.Paint.Style.STROKE }

                if (c.isBrood && c.dead < 0.5f) {
                    val hungerGlow = 0.08f + 0.12f * c.hunger
                    hexFill.color = 0xFF070300.toInt()
                    nc.drawPath(hexP, hexFill)
                    hexFill.color = rgba(Rgb(0.5f, 0.3f, 0.1f), hungerGlow * sig.batt).toArgb()
                    nc.drawPath(hexP, hexFill)

                    if (!lowPower) {
                        val larvaSz = gridR * (0.18f + 0.08f * (1f - c.hunger))
                        val wiggleX = sin(t * 4f + i.toFloat()) * gridR * 0.04f
                        val wiggleY = cos(t * 5f + i.toFloat()) * gridR * 0.03f
                        nc.save()
                        nc.translate(cx + wiggleX, cy + wiggleY)
                        val larva = android.graphics.Path()
                        larva.addOval(-larvaSz, -larvaSz * 0.35f, larvaSz, larvaSz * 0.35f, android.graphics.Path.Direction.CW)
                        nc.drawPath(larva, androidPaint(rgba(0.85f, 0.75f, 0.55f, 0.7f * sig.batt)))
                        nc.restore()
                    }
                } else if (isDeadBrood) {
                    hexFill.color = 0xFF0D0803.toInt()
                    nc.drawPath(hexP, hexFill)
                } else {
                    val fill = c.fill.coerceIn(0f, 1f)
                    val over = c.overfill.coerceIn(0f, 1f)

                    hexFill.color = 0xFF140D03.toInt()
                    nc.drawPath(hexP, hexFill)

                    if (fill > 0.01f) {
                        val honeyH = fill * gridR * 1.1f
                        val honeyP = nativeHexPathClipped(cx, cy, gridR, honeyH)
                        val honeyCol = lerpRgb(Rgb(0.95f, 0.7f, 0.15f), wax, 0.5f)
                        val honeyBright = lerpF(0.4f, 1f, fill) * sig.batt
                        hexFill.color = rgba(honeyCol.r, honeyCol.g, honeyCol.b, honeyBright).toArgb()
                        nc.drawPath(honeyP, hexFill)

                        if (!lowPower) {
                            val glowR = gridR * 1.1f
                            val huesh = android.graphics.RadialGradient(
                                cx, cy, 0f, cx, cy, glowR,
                                intArrayOf(rgba(honeyCol, 0.12f * fill * sig.batt).toArgb(), Color.Transparent.toArgb()),
                                floatArrayOf(0f, 1f),
                                android.graphics.Shader.TileMode.CLAMP,
                            )
                            nc.save()
                            nc.clipPath(hexP)
                            nc.drawRect(cx - glowR, cy - glowR, glowR * 2f, glowR * 2f,
                                android.graphics.Paint().apply { shader = huesh })
                            nc.restore()
                        }
                    }

                    if (over > 0.01f && !lowPower) {
                        hexFill.color = rgba(Rgb(1f, 0.9f, 0.6f), 0.12f * over * sig.batt).toArgb()
                        nc.drawPath(hexP, hexFill)
                    }
                }

                val strokeCol = lerpRgb(Rgb(0.4f, 0.3f, 0.12f), wax, 0.3f)
                val strokeAlpha = if (c.isBrood) 0.4f else if (c.fill > 0.1f) 0.6f + 0.2f * c.fill else 0.3f
                hexStroke.color = rgba(strokeCol, strokeAlpha * sig.batt).toArgb()
                hexStroke.strokeWidth = max(0.8f, gridR * 0.08f)
                nc.drawPath(hexP, hexStroke)
            }

            // ── Window cell / Door ──
            val door = cells[windowCell]
            drawDoor(nc, door.cx, door.cy, gridR, t, dt, sig)

            // ── Honey drips ──
            val dripKeep = mutableListOf<Drip>()
            for (d in drips) {
                d.y += d.vy * dt
                d.life -= dt * 1.2f
                if (d.life > 0f && d.y < H + gridR) {
                    val dripCol = lerpRgb(Rgb(0.95f, 0.7f, 0.15f), wax, 0.4f)
                    nc.save()
                    nc.translate(d.x, d.y)
                    val dripPaint = android.graphics.Paint().apply {
                        color = rgba(dripCol, d.life * 0.6f * sig.batt).toArgb()
                        isAntiAlias = true
                    }
                    nc.drawOval(-d.r, -d.r * 0.3f, d.r * 2f, d.r * 0.6f, dripPaint)
                    nc.restore()
                    if (d.y > H - gridR * 0.5f) pool = min(pool + dt * 0.01f, 1f)
                    dripKeep.add(d)
                }
            }
            drips = dripKeep

            // ── Pool at bottom ──
            if (pool > 0.01f) {
                pool = max(0f, pool - dt * 0.05f)
                val poolCol = lerpRgb(Rgb(0.95f, 0.7f, 0.15f), wax, 0.5f)
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(rgba(poolCol, 0f), rgba(poolCol, 0.08f * pool * sig.batt)),
                        startY = H - gridR * 2f, endY = H,
                    ),
                    topLeft = Offset(0f, H - gridR * 2f),
                    size = androidx.compose.ui.geometry.Size(W, gridR * 2f),
                )
            }

            // ── Update + draw bees ──
            val oldBees = bees.toList()
            bees.clear()
            for (b in oldBees) {
                if (updateBee(nc, b, cells, windowCell, gridR, t, dt, W, H, sig)) {
                    bees.add(b)
                }
            }

            // ── Spawn bees ──
            spawnBees(t, dt, lowPower, sig.beeMod, sig.throughput, sig.queueBacklog)

            // ── Guards ──
            val doorC = cells[windowCell]
            for (g in guards) {
                g.ang += dt * g.sp * (0.6f + sig.heat * 0.8f)
                g.flap += dt * 30f
                val rr = gridR * (1.0f + 0.06f * sin(t * 2f + g.ang))
                val gx = doorC.cx + cos(g.ang) * rr
                val gy = doorC.cy + sin(g.ang) * rr
                drawBeeBody(nc, gx, gy, g.ang + PI.toFloat() / 2f, g.flap, gridR * 0.18f, wax, sig.batt, lowPower, pollen = 0f, throughput = sig.throughput, grooming = false)
            }

            // ── Queen ──
            if (queen != null) {
                updateQueen(nc, queen!!, cells, gridR, t, dt, wax, sig.survival, W, H, sig.batt)
            }

            // ── Corpses ──
            val corpseKeep = mutableListOf<Corpse>()
            for (cr in corpses) {
                cr.vy += 24f * dt; cr.y += cr.vy * dt; cr.rot += cr.vr * dt
                if (cr.y > H - gridR * 0.3f) cr.life -= dt * 1.2f
                nc.save()
                nc.translate(cr.x, cr.y); nc.rotate(cr.rot)
                nc.drawOval(-cr.r, -cr.r * 0.45f, cr.r * 2f, cr.r * 0.9f,
                    android.graphics.Paint().apply { color = rgba(70f / 255f, 62f / 255f, 46f / 255f, 0.7f * cr.life * sig.batt).toArgb(); isAntiAlias = true })
                nc.restore()
                if (cr.life > 0f && cr.y < H + gridR) corpseKeep.add(cr)
            }
            corpses = corpseKeep

            // ── Heat shimmer overlay ──
            if (sig.heat > 0.05f && !lowPower) {
                drawRect(rgba(1f, 80f / 255f, 40f / 255f, 0.10f * sig.heat), Offset.Zero, size)
            }

            // ── Unhealthy vignette ──
            if (!sig.healthy) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Transparent, rgba(180f / 255f, 30f / 255f, 20f / 255f, 0.18f + 0.06f * sin(t * 3f))),
                        center = Offset(W / 2f, H / 2f),
                        radius = H * 0.78f,
                    )
                )
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // SIGNALS PACKAGE
    // ════════════════════════════════════════════════════════════════

    private class HoneycombSignals(
        t: Float, dt: Float,
        lowPower: Boolean,
        fillMod: Float, beeMod: Float,
        isProcessing: Boolean,
        inferenceLoad: Float,
        queueDepth: Int,
        tokensPerSecond: Float,
        batteryTemperature: Float,
        batteryLevel: Float,
        isHealthy: Boolean,
        isCharging: Boolean,
    ) {
        val t = t; val dt = dt
        val lowPower = lowPower
        val fillMod = fillMod; val beeMod = beeMod
        val isProcessing = isProcessing
        val inferenceLoad = inferenceLoad
        val queueDepth = queueDepth
        val tokensPerSecond = tokensPerSecond
        val batteryTemperature = batteryTemperature
        val batteryLevel = batteryLevel
        val healthy = isHealthy
        val isCharging = isCharging

        val throughput: Float = (tokensPerSecond / 60f).coerceIn(0f, 1f)
        val workload: Float = ((if (isProcessing) 0.30f else 0f) +
            inferenceLoad * 0.45f +
            (queueDepth / 18f).coerceIn(0f, 1f) * 0.45f).coerceIn(0f, 1f)
        val queueBacklog: Float = (queueDepth / 16f).coerceIn(0f, 1f)
        val heat: Float = ((batteryTemperature - 38f) / 12f).coerceIn(0f, 1f)
        val beard: Float = ((batteryTemperature - 47f) / 6f).coerceIn(0f, 1f)
        val leak: Float = ((batteryTemperature - 44f) / 9f).coerceIn(0f, 1f)
        val panic: Float = if (isHealthy) 0f else 1f
        val reserve: Float = ((batteryLevel - 8f) / 32f).coerceIn(0.15f, 1f)
        val dusk: Float = 1f - ((batteryLevel - 8f) / 32f).coerceIn(0f, 1f)
        val survival: Float = ((25f - batteryLevel) / 17f).coerceIn(0f, 1f)
        val batt: Float = lerpF(0.4f, 1f, (batteryLevel - 15f) / 85f).coerceIn(0.35f, 1f)
    }

    // ════════════════════════════════════════════════════════════════
    // GRID
    // ════════════════════════════════════════════════════════════════

    private fun ensureGrid(W: Float, H: Float, lowPower: Boolean) {
        if (gridW == W && gridH == H && gridLp == lowPower && cells.isNotEmpty()) return
        val targetCols = 11
        val hexW = W / targetCols
        val r = hexW / sqrt(3f)
        val vert = r * 1.5f
        val cols = ceil(W / hexW).toInt() + 2
        val rows = ceil(H / vert).toInt() + 2
        val rng = Random(1337)
        cells.clear()
        for (row in -1 until rows) {
            val offset = if (row and 1 == 1) hexW / 2f else 0f
            for (col in -1 until cols) {
                cells.add(Cell(
                    cx = col * hexW + offset,
                    cy = row * vert,
                    fill = 1f, target = 1f,
                    jitter = rng.nextFloat() * (2f * PI).toFloat(),
                ))
            }
        }
        gridCols = cols; gridRows = rows; gridR = r; gridHexW = hexW
        gridVert = vert; gridW = W; gridH = H; gridLp = lowPower

        // Brood nest
        val bx = W * 0.5f; val by = H * 0.66f
        val scored = cells.indices.map { i ->
            val c = cells[i]
            val d = hypot(c.cx - bx, c.cy - by) + (rng.nextFloat() - 0.5f) * hexW * 2.5f
            d to i
        }.sortedBy { it.first }
        val broodMax = 14
        broodCells = scored.take(broodMax).map { it.second }.toMutableList()
        for (i in broodCells) { cells[i].isBrood = true; cells[i].fill = 0f; cells[i].target = 0f }

        // Door cell
        var best = -1; var bestD = 1e9f
        val wx = W * 0.5f; val wy = H * 0.28f
        for (i in cells.indices) {
            val c = cells[i]
            if (c.cx < r || c.cx > W - r || c.cy < r || c.cy > H - r) continue
            val d = (c.cx - wx).pow(2) + (c.cy - wy).pow(2)
            if (d < bestD) { bestD = d; best = i }
        }
        windowCell = best

        // Guards
        guards.clear()
        val n = if (lowPower) 1 else 3
        for (i in 0 until n) {
            guards.add(Guard(
                ang = (i.toFloat() / n) * (2f * PI).toFloat(),
                sp = 0.5f + rng.nextFloat() * 0.4f,
                flap = rng.nextFloat() * (2f * PI).toFloat(),
            ))
        }
    }

    // ════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════

    private fun waxTint(temp: Float): Rgb {
        val gold = Rgb(1f, 196f / 255f, 64f / 255f)
        val hot = Rgb(239f / 255f, 92f / 255f, 60f / 255f)
        return lerpRgb(gold, hot, ((temp - 38f) / 12f).coerceIn(0f, 1f))
    }

    private fun sampleField(x: Float, y: Float, t: Float, W: Float, H: Float): Float {
        val nx = x / W; val ny = y / H
        val a = sin(nx * 6.28f * 0.7f + t * 0.25f) + cos(ny * 6.28f * 0.9f - t * 0.21f)
        val b = cos(nx * 6.28f * 1.1f - t * 0.19f) + sin(ny * 6.28f * 0.6f + t * 0.23f)
        return atan2(b, a)
    }

    /** Build an android.graphics.Path for a flat-top hexagon centred at (cx, cy). */
    private fun nativeHexPath(cx: Float, cy: Float, r: Float): android.graphics.Path {
        val p = android.graphics.Path()
        for (i in 0 until 6) {
            val a = i.toFloat() * PI.toFloat() / 3f - PI.toFloat() / 6f
            val x = cx + r * cos(a); val y = cy + r * sin(a)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }

    /** Hex path clipped to a given fill height from the bottom. */
    private fun nativeHexPathClipped(cx: Float, cy: Float, r: Float, fillH: Float): android.graphics.Path {
        val p = android.graphics.Path()
        val bottom = cy + r
        val top = bottom - fillH.coerceAtMost(r * 1.1f)
        for (i in 0 until 6) {
            val a = i.toFloat() * PI.toFloat() / 3f - PI.toFloat() / 6f
            var x = cx + r * cos(a); var y = cy + r * sin(a)
            if (y < top) y = top
            if (y > bottom) y = bottom
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }

    // ── Colour+draw helpers for native canvas ──

    private fun Color.toArgb(): Int = android.graphics.Color.argb(
        (alpha * 255).toInt().coerceIn(0, 255),
        (red * 255).toInt().coerceIn(0, 255),
        (green * 255).toInt().coerceIn(0, 255),
        (blue * 255).toInt().coerceIn(0, 255),
    )

    private fun androidPaint(color: Color): android.graphics.Paint =
        android.graphics.Paint().apply {
            this.color = color.toArgb()
            isAntiAlias = true
        }

    // ════════════════════════════════════════════════════════════════
    // SPAWN
    // ════════════════════════════════════════════════════════════════

    private fun spawnBees(t: Float, dt: Float, lowPower: Boolean, beeMod: Float,
                          throughput: Float, queueBacklog: Float) {
        if (bees.size < 1 || (bees.size < 16 && t - lastSpawn > (if (lowPower) 4f else 2f))) {
            if (bees.size < 60) {
                val empty = cells.indices.filter { i ->
                    i != windowCell && !cells[i].isBrood && cells[i].fill < 0.2f && cells[i].claimedBy < 0
                }
                if (empty.isNotEmpty() && throughput > 0.05f) {
                    val target = empty[Random.nextInt(empty.size)]
                    val bee = spawnBee("forage", target, t)
                    if (bee != null) bees.add(bee)
                } else {
                    val bee = spawnBee("wander", -1, t)
                    if (bee != null) bees.add(bee)
                }
                lastSpawn = t
            }
        }

        val fullCells = cells.indices.filter { i ->
            i != windowCell && !cells[i].isBrood && cells[i].fill > 0.7f && cells[i].claimedBy < 0
        }
        if (fullCells.isNotEmpty() && bees.count { it.role == "haul" } < 3 && t - lastSpawn > 0.3f) {
            val target = fullCells[Random.nextInt(fullCells.size)]
            val bee = spawnBee("haul", target, t)
            if (bee != null) bees.add(bee)
        }

        val hungryBrood = broodCells.filter { cells[it].hunger > 0.3f && cells[it].dead < 0.5f && !cells[it].beingFed }
        if (hungryBrood.isNotEmpty() && bees.count { it.role == "nurse" } < 3 && t - lastSpawn > 0.3f) {
            val bi = hungryBrood[Random.nextInt(hungryBrood.size)]
            val source = cells.indices.filter { i ->
                i != windowCell && !cells[i].isBrood && cells[i].fill > 0.4f && cells[i].claimedBy < 0
            }.minByOrNull { hypot(cells[it].cx - cells[bi].cx, cells[it].cy - cells[bi].cy) }
            if (source != null) {
                val bee = spawnBee("nurse", source, t)
                if (bee != null) { bee.brood = bi; cells[bi].beingFed = true; bees.add(bee) }
            }
        }
    }

    private fun spawnBee(role: String, cellIndex: Int, t: Float): Bee? {
        val door = cells[windowCell]
        val startState = when (role) { "forage", "haul", "nurse" -> "seek"; else -> "wander" }
        val bee = Bee(
            x = door.cx + (Random.nextFloat() - 0.5f) * 8f,
            y = door.cy + (Random.nextFloat() - 0.5f) * 8f,
            vx = 0f, vy = 0f, heading = 0f,
            role = role, state = startState,
            cell = cellIndex, work = 0f, born = t, brood = -1, load = 0f,
            wanderUntil = t + 2f + Random.nextFloat() * 3f,
            groomUntil = 0f, danceUntil = 0f, danceT = 0f,
            ph1 = Random.nextFloat() * 6.28f, ph2 = Random.nextFloat() * 6.28f,
            f1 = 1.6f + Random.nextFloat() * 1.4f, f2 = 3.1f + Random.nextFloat() * 2.0f,
            amp = 0.4f + Random.nextFloat() * 0.6f, sp = 0.85f + Random.nextFloat() * 0.5f,
            flap = Random.nextFloat() * 6.28f, size = 0.9f + Random.nextFloat() * 0.25f,
            pollen = if (role == "forage") 1f else 0f,
        )
        if (cellIndex >= 0) cells[cellIndex].claimedBy = cells.size // temp placeholder
        return bee
    }

    // ════════════════════════════════════════════════════════════════
    // BEE UPDATE + DRAW
    // ════════════════════════════════════════════════════════════════

    private fun separation(bee: Bee): Pair<Float, Float> {
        var sx = 0f; var sy = 0f; var n = 0f
        for (o in bees) {
            if (o === bee) continue
            val dx = bee.x - o.x; val dy = bee.y - o.y
            val d2 = dx * dx + dy * dy
            if (d2 > 0f && d2 < 22f * 22f) { val inv = 1f / sqrt(d2); sx += dx * inv; sy += dy * inv; n++ }
        }
        return if (n > 0f) Pair(sx / n, sy / n) else Pair(0f, 0f)
    }

    private fun updateBee(nc: android.graphics.Canvas, bee: Bee, cells: List<Cell>, doorIdx: Int,
                           hexR: Float, t: Float, dt: Float, W: Float, H: Float, sig: HoneycombSignals): Boolean {
        val door = cells[doorIdx]
        val heatSlow = lerpF(1f, 0.62f, sig.heat)
        val battSlow = lerpF(1f, 0.5f, sig.survival)
        val heatJit = 1f + sig.heat * 1.6f
        val baseSpeed = (if (sig.lowPower) 95f else 165f) * bee.sp * heatSlow * battSlow
        val (sepX, sepY) = separation(bee)

        // Cohesion in survival mode
        var cohX = 0f; var cohY = 0f
        if (sig.survival > 0.05f && bees.size > 1) {
            var mx = 0f; var my = 0f
            for (o in bees) { mx += o.x; my += o.y }
            mx /= bees.size; my /= bees.size
            val cdx = mx - bee.x; val cdy = my - bee.y; val cd = hypot(cdx, cdy).coerceAtLeast(1f)
            cohX = (cdx / cd) * sig.survival * 28f
            cohY = (cdy / cd) * sig.survival * 28f
        }

        val sigArgs = BeeDrawSig(isLowPower = sig.lowPower, throughput = sig.throughput, batt = sig.batt, heat = sig.heat, panic = sig.panic, survival = sig.survival)

        // State machine
        when (bee.state) {
            "work" -> {
                val c = cells.getOrNull(bee.cell) ?: return false
                if (sig.panic > 0.5f) { c.claimedBy = -1; bee.state = "leave"; bee.pollen = 0f; return true }

                when (bee.role) {
                    "haul", "nurse" -> {
                        val drainTime = if (sig.lowPower) 1.0f else 1.6f
                        bee.load = min(1f, bee.load + dt / drainTime)
                        c.fill = max(0f, 1f - bee.load)
                        bee.pollen = bee.load
                        bee.x = c.cx + sin(t * 6f + bee.ph1) * hexR * 0.16f * heatJit
                        bee.y = c.cy + cos(t * 7f + bee.ph2) * hexR * 0.16f * heatJit - hexR * 0.1f
                        bee.heading = sin(t * 4f) * 0.5f
                        bee.flap += dt * (if (sig.lowPower) 30f else 46f) * heatJit
                        drawBeeBody(nc, bee.x, bee.y, bee.heading, bee.flap, hexR * bee.size, waxTint(sig.batteryTemperature), sig.batt, sig.lowPower, bee.pollen, sig.throughput, false)
                        if (bee.load >= 1f) { c.fill = 0f; c.claimedBy = -1; bee.state = if (bee.role == "nurse" && bee.brood >= 0) "feed" else "return" }
                        return true
                    }
                    else -> { // forage
                        val cap = max(0.15f, c.target.coerceAtLeast(0.1f))
                        val baseFill = if (sig.lowPower) 1.2f else 2.6f
                        val fillTime = baseFill / lerpF(0.6f, 1.8f, sig.throughput)
                        bee.work = min(cap, bee.work + dt / fillTime)
                        c.fill = max(c.fill, bee.work)
                        bee.pollen = cap - bee.work
                        bee.x = c.cx + sin(t * 7f + bee.ph1) * hexR * 0.18f * heatJit
                        bee.y = c.cy + cos(t * 6f + bee.ph2) * hexR * 0.18f * heatJit - hexR * 0.12f
                        bee.heading = sin(t * 5f) * 0.5f
                        bee.flap += dt * (if (sig.lowPower) 30f else 46f) * heatJit
                        drawBeeBody(nc, bee.x, bee.y, bee.heading, bee.flap, hexR * bee.size, waxTint(sig.batteryTemperature), sig.batt, sig.lowPower, bee.pollen, sig.throughput, false)
                        if (bee.work >= cap - 0.01f) { c.fill = cap; c.flash = 1f; c.claimedBy = -1; bee.state = "return" }
                        return true
                    }
                }
            }
            "feed" -> {
                val b = cells.getOrNull(bee.brood) ?: run { bee.state = "return"; return true }
                val dx = b.cx - bee.x; val dy = b.cy - bee.y; val d = hypot(dx, dy).coerceAtLeast(1f)
                if (d > hexR * 0.5f) {
                    val sp = (if (sig.lowPower) 90f else 150f) * bee.sp
                    bee.x += (dx / d) * sp * dt; bee.y += (dy / d) * sp * dt
                    bee.heading = atan2(dy, dx); bee.flap += dt * 42f
                } else {
                    b.hunger = max(0f, b.hunger - dt / 0.8f)
                    b.beingFed = true
                    bee.pollen = max(0f, bee.pollen - dt / 0.8f)
                    bee.x = b.cx + sin(t * 8f) * hexR * 0.12f
                    bee.y = b.cy + cos(t * 9f) * hexR * 0.12f
                    bee.flap += dt * 30f
                    if (bee.pollen <= 0.02f || b.hunger <= 0.02f) { b.beingFed = false; bee.state = "return"; return true }
                }
                drawBeeBody(nc, bee.x, bee.y, bee.heading, bee.flap, hexR * bee.size, waxTint(sig.batteryTemperature), sig.batt, sig.lowPower, bee.pollen, sig.throughput, false)
                return true
            }
            "dance" -> {
                bee.danceT += dt
                val r = hexR * 0.7f
                bee.x = door.cx + sin(bee.danceT * 7f) * r
                bee.y = door.cy + sin(bee.danceT * 14f) * r * 0.5f
                bee.heading = sin(bee.danceT * 7f) * 1.2f
                bee.flap += dt * 60f
                drawBeeBody(nc, bee.x, bee.y, bee.heading, bee.flap, hexR * bee.size, waxTint(sig.batteryTemperature), sig.batt, sig.lowPower, bee.pollen, sig.throughput, false)
                return t < bee.danceUntil
            }
            "groom" -> {
                bee.x += sin(t * 9f + bee.ph1) * 0.4f
                bee.y += cos(t * 11f + bee.ph2) * 0.4f
                bee.heading += sin(t * 4f) * 0.04f
                bee.flap += dt * 10f
                drawBeeBody(nc, bee.x, bee.y, bee.heading, bee.flap, hexR * bee.size, waxTint(sig.batteryTemperature), sig.batt, sig.lowPower, bee.pollen, sig.throughput, true)
                return t < bee.groomUntil
            }
            "seek" -> {
                val c = cells.getOrNull(bee.cell)
                if (c == null || c.claimedBy < 0) { bee.state = "wander"; bee.wanderUntil = t + 2f; return steerBee(nc, bee, t, dt, hexR, baseSpeed, heatJit, W, H, door, sig, Pair(door.cx, door.cy), Pair(sepX, sepY), cohX, cohY) }
                return steerBee(nc, bee, t, dt, hexR, baseSpeed, heatJit, W, H, door, sig, Pair(c.cx, c.cy), Pair(sepX, sepY), cohX, cohY)
            }
            "return" -> {
                return steerBee(nc, bee, t, dt, hexR, baseSpeed, heatJit, W, H, door, sig, Pair(door.cx, door.cy), Pair(sepX, sepY), cohX, cohY)
            }
            "wander", "leave" -> {
                val fieldAng = sampleField(bee.x, bee.y, t, W, H)
                val targetX = bee.x + cos(fieldAng) * 60f
                val targetY = bee.y + sin(fieldAng) * 60f
                val result = steerBee(nc, bee, t, dt, hexR, baseSpeed, heatJit, W, H, door, sig, Pair(targetX, targetY), Pair(sepX, sepY), cohX, cohY)
                if (!result) return false

                if (bee.role == "wander" && !sig.lowPower && Random.nextFloat() < 0.004f) {
                    bee.state = "groom"; bee.groomUntil = t + 1.5f + Random.nextFloat() * 2f
                }
                if (bee.state == "leave" || (bee.role == "wander" && t > bee.wanderUntil)) {
                    if (hypot(door.cx - bee.x, door.cy - bee.y) < hexR * 0.7f) {
                        if (!sig.lowPower && Random.nextFloat() < 0.4f) { bee.state = "dance"; bee.danceT = 0f; bee.danceUntil = t + 1.5f + Random.nextFloat(); return true }
                        return false
                    }
                }
                return true
            }
        }
        return true
    }

    private data class BeeDrawSig(
        val isLowPower: Boolean,
        val throughput: Float,
        val batt: Float,
        val heat: Float,
        val panic: Float,
        val survival: Float,
    )

    private fun steerBee(nc: android.graphics.Canvas, bee: Bee, t: Float, dt: Float, hexR: Float,
                          baseSpeed: Float, heatJit: Float, W: Float, H: Float,
                          door: Cell, sig: HoneycombSignals,
                          target: Pair<Float, Float>, sep: Pair<Float, Float>,
                          cohX: Float, cohY: Float): Boolean {
        val (tx, ty) = target
        val dx = tx - bee.x; val dy = ty - bee.y
        val dist = hypot(dx, dy).coerceAtLeast(1f)
        val dirx = dx / dist; val diry = dy / dist
        val px = -diry; val py = dirx
        val ease = (dist / (hexR * 4f)).coerceIn(0f, 1f)
        val squig = (sin(t * bee.f1 + bee.ph1) + 0.6f * sin(t * bee.f2 + bee.ph2)) * bee.amp * ease * heatJit
        val lateral = squig * baseSpeed * 0.5f
        val scat = if (sig.panic > 0.5f) (Random.nextFloat() - 0.5f) * baseSpeed * 2.5f else 0f
        val (sepX, sepY) = sep

        bee.vx = dirx * baseSpeed + px * lateral + sepX * 40f + cohX + scat
        bee.vy = diry * baseSpeed + py * lateral + sepY * 40f + cohY + if (sig.panic > 0.5f) (Random.nextFloat() - 0.5f) * baseSpeed * 2.5f else 0f
        bee.x += bee.vx * dt; bee.y += bee.vy * dt
        bee.heading = atan2(bee.vy, bee.vx)
        bee.flap += dt * (if (sig.lowPower) 26f else 42f) * heatJit
        bee.x = bee.x.coerceIn(-10f, W + 10f)
        bee.y = bee.y.coerceIn(-10f, H + 10f)

        // Transition at arrive
        val arriveDist = hexR * 0.55f
        if (dist < arriveDist) {
            if (bee.state == "seek") { bee.state = "work"; bee.work = max(bee.work, cells.getOrNull(bee.cell)?.fill ?: 0f) }
            if (bee.state == "return") {
                if (!sig.lowPower && Random.nextFloat() < 0.6f) { bee.state = "dance"; bee.danceT = 0f; bee.danceUntil = t + 1.5f + Random.nextFloat(); return true }
                return false
            }
        }
        return true
    }

    // ════════════════════════════════════════════════════════════════
    // DRAWING PRIMITIVES
    // ════════════════════════════════════════════════════════════════

    private fun drawBeeBody(nc: android.graphics.Canvas, x: Float, y: Float, heading: Float, flap: Float,
                             beeR: Float, wax: Rgb, batt: Float, lowPower: Boolean,
                             pollen: Float, throughput: Float, grooming: Boolean) {
        if (beeR < 1f) return
        nc.save()
        nc.translate(x, y)
        nc.rotate(heading)

        // Wings (folded when grooming)
        if (!lowPower) {
            val wspread = if (grooming) 0.18f else 0.5f + 0.5f * abs(sin(flap))
            val wingPaint = android.graphics.Paint().apply {
                color = rgba(225f / 255f, 238f / 255f, 1f, 0.42f).toArgb()
                isAntiAlias = true
            }
            nc.drawOval(-beeR * 0.6f, -beeR * 0.85f, beeR * 0.72f * wspread, beeR * 0.36f * wspread, wingPaint)
            nc.drawOval(-beeR * 0.6f, beeR * 0.5f, beeR * 0.72f * wspread, beeR * 0.36f * wspread, wingPaint)
        }

        // Body
        val bodyPaint = androidPaint(rgba(22f / 255f, 17f / 255f, 8f / 255f, 0.96f))
        nc.drawOval(-beeR, -beeR * 0.6f, beeR * 2f, beeR * 1.2f, bodyPaint)

        // Stripes
        val stripeCol = rgba(wax, 0.95f).toArgb()
        val stripePaint = android.graphics.Paint().apply { color = stripeCol; isAntiAlias = true }
        nc.drawOval(-beeR * 0.73f, -beeR * 0.56f, beeR * 0.38f, beeR * 1.04f, stripePaint)
        nc.drawOval(-beeR * 0.28f, -beeR * 0.56f, beeR * 0.38f, beeR * 1.04f, stripePaint)
        nc.drawOval(beeR * 0.17f, -beeR * 0.56f, beeR * 0.38f, beeR * 1.04f, stripePaint)

        // Pollen glow
        if (pollen > 0.05f && !lowPower) {
            val bright = 0.3f + 0.6f * throughput
            val pr = beeR * (1.3f + 0.6f * pollen)
            val pg = android.graphics.RadialGradient(
                0f, 0f, 0f, 0f, 0f, pr,
                intArrayOf(rgba(wax, bright * pollen * batt).toArgb(), Color.Transparent.toArgb()),
                floatArrayOf(0f, 1f), android.graphics.Shader.TileMode.CLAMP,
            )
            nc.drawCircle(0f, 0f, pr, android.graphics.Paint().apply { shader = pg; isAntiAlias = true })
        }

        nc.restore()
    }

    // ── DOOR ───────────────────────────────────────────────────────

    private fun drawDoor(nc: android.graphics.Canvas, cx: Float, cy: Float, r: Float, t: Float, dt: Float, sig: HoneycombSignals) {
        val traffic = sig.throughput.coerceIn(0f, 1f)

        nc.save()
        // Clip to door hex
        val doorHex = nativeHexPath(cx, cy, r * 1.06f)
        nc.clipPath(doorHex)

        // Base dark interior
        nc.drawRect(cx - r * 1.2f, cy - r * 1.2f, r * 2.4f, r * 2.4f,
            android.graphics.Paint().apply { color = 0xFF070401.toInt() })

        // Receding hex rings
        val rings = if (sig.lowPower) 3 else 6
        for (i in 0 until rings) {
            val k = i.toFloat() / rings
            val rr = r * (1.0f - k * 0.9f)
            val ox = cx - r * 0.10f * k; val oy = cy + r * 0.14f * k
            val shade = (0.10f + 0.16f * (1f - k)).coerceIn(0f, 1f)
            val ringP = nativeHexPath(ox, oy, rr)
            val cr = ((20 - k * 12).toInt().coerceIn(0, 255))
            val cg = ((14 - k * 8).toInt().coerceIn(0, 255))
            val cb = ((6 - k * 4).toInt().coerceIn(0, 255))
            nc.drawPath(ringP, android.graphics.Paint().apply {
                color = android.graphics.Color.argb((shade * 255).toInt(), cr, cg, cb)
                style = android.graphics.Paint.Style.FILL
                isAntiAlias = true
            })
            nc.drawPath(ringP, android.graphics.Paint().apply {
                color = rgba(120f / 255f, 86f / 255f, 30f / 255f, 0.3f * (1f - k) * sig.batt).toArgb()
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = max(0.5f, r * 0.04f * (1f - k))
                isAntiAlias = true
            })
        }

        // Warm activity glow at depth
        val heatGlow = (0.25f + 0.6f * traffic + 0.3f * sig.throughput).coerceIn(0f, 1f) * (1f - sig.dusk * 0.8f)
        val gx = cx - r * 0.10f; val gy = cy + r * 0.14f
        val dayCol = if (sig.healthy) Rgb(1f, 190f / 255f, 90f / 255f) else Rgb(230f / 255f, 110f / 255f, 70f / 255f)
        val duskCol = Rgb(120f / 255f, 70f / 255f, 110f / 255f)
        val glowCol = lerpRgb(dayCol, duskCol, sig.dusk)
        val glowPaint = android.graphics.Paint().apply { isAntiAlias = true }
        glowPaint.shader = android.graphics.RadialGradient(
            gx, gy, 0f, gx, gy, r * 0.9f,
            intArrayOf(
                rgba(glowCol, (heatGlow * sig.batt).coerceIn(0f, 0.95f)).toArgb(),
                Color.Transparent.toArgb(),
            ),
            floatArrayOf(0f, 1f), android.graphics.Shader.TileMode.CLAMP,
        )
        nc.drawRect(cx - r * 1.2f, cy - r * 1.2f, r * 2.4f, r * 2.4f, glowPaint)

        // Flecks inside
        if (!sig.lowPower) {
            val n = (2f + traffic * 5f).toInt().coerceAtLeast(1)
            for (i in 0 until n) {
                val a = t * (0.6f + i * 0.2f)
                val rad = r * (0.2f + 0.5f * ((i.toFloat() / n + (t * 0.1f % 1f)) % 1f))
                val bxp = gx + cos(a) * rad; val byp = gy + sin(a) * rad * 0.7f
                nc.drawCircle(bxp, byp, max(1f, r * 0.05f),
                    android.graphics.Paint().apply { color = rgba(20f / 255f, 14f / 255f, 6f / 255f, 0.5f * sig.batt).toArgb(); isAntiAlias = true })
            }
        }
        nc.restore()

        // Glowing rim
        val rimPulse = 0.7f + 0.3f * sin(t * 3f) * traffic
        val rimPaint = android.graphics.Paint().apply {
            color = rgba(150f / 255f, 110f / 255f, 44f / 255f, (0.55f + 0.4f * traffic) * rimPulse * sig.batt).toArgb()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = max(1.5f, r * 0.16f)
            isAntiAlias = true
        }
        nc.drawPath(doorHex, rimPaint)

        // Fanning shimmer (heat-driven)
        if (!sig.lowPower && sig.heat > 0.3f) {
            val shimmerCol = rgba(1f, 240f / 255f, 220f / 255f, 0.06f * sig.heat).toArgb()
            for (k in 0 until 3) {
                val yy = cy - r * (1.4f + k * 0.4f)
                val fPath = android.graphics.Path()
                var xx = cx - r
                fPath.moveTo(xx, yy + sin(xx * 0.3f + t * 6f + k.toFloat()) * 2f)
                while (xx <= cx + r) {
                    val yo = sin(xx * 0.3f + t * 6f + k.toFloat()) * 2f
                    fPath.lineTo(xx, yy + yo)
                    xx += 4f
                }
                nc.drawPath(fPath, android.graphics.Paint().apply {
                    color = shimmerCol; style = android.graphics.Paint.Style.STROKE; strokeWidth = 1f; isAntiAlias = true
                })
            }
        }
    }

    // ── QUEEN ──────────────────────────────────────────────────────

    private fun updateQueen(nc: android.graphics.Canvas, q: QueenState, cells: List<Cell>,
                             hexR: Float, t: Float, dt: Float, wax: Rgb,
                             survival: Float, W: Float, H: Float, batt: Float) {
        val speed = 26f
        val door = cells[windowCell]

        q.repick -= dt
        if (q.target < 0 || q.repick <= 0f || hypot(q.tx - q.x, q.ty - q.y) < hexR * 0.5f) {
            q.repick = 1.6f + Random.nextFloat() * 1.4f
            val dead = broodCells.filter { cells[it].dead > 0.5f }
            if (dead.isNotEmpty()) {
                q.target = dead[Random.nextInt(dead.size)]
                q.tx = cells[q.target].cx; q.ty = cells[q.target].cy
            } else {
                q.target = -1; q.tx = door.cx; q.ty = door.cy - (if (survival < 0.02f) hexR * 3f else 0f)
            }
        }

        val qdx = q.tx - q.x; val qdy = q.ty - q.y; val qdist = hypot(qdx, qdy).coerceAtLeast(1f)
        q.pauseT -= dt
        var gait = 1f
        if (q.pauseT > 0f) gait = 0.12f
        else if (Random.nextFloat() < dt * 0.5f) q.pauseT = 0.25f + Random.nextFloat() * 0.5f
        q.stride += dt * (2.4f + 2.0f * gait)
        val surge = 0.7f + 0.3f * sin(q.stride * 2f)
        q.wob += dt * 1.6f
        val weave = sin(q.wob) * 0.5f
        val aimAng = atan2(qdy, qdx) + weave * (qdist / (hexR * 6f)).coerceIn(0f, 1f)
        val accel = speed * 2.4f
        q.vx += (cos(aimAng) - q.vx / speed) * accel * dt * gait * surge
        q.vy += (sin(aimAng) - q.vy / speed) * accel * dt * gait * surge
        val sp = hypot(q.vx, q.vy)
        val maxSp = speed * (0.4f + 0.6f * gait)
        if (sp > maxSp) { q.vx = (q.vx / sp) * maxSp; q.vy = (q.vy / sp) * maxSp }
        q.x += q.vx * dt; q.y += q.vy * dt

        if (sp > 2f) {
            var want = atan2(q.vy, q.vx)
            var diff = ((want - q.heading + PI.toFloat()) % (2f * PI.toFloat())) - PI.toFloat()
            if (diff < -PI.toFloat()) diff += 2f * PI.toFloat()
            q.heading += diff * min(1f, dt * 6f)
        }
        q.flap += dt * 24f

        if (q.target >= 0 && qdist < hexR * 0.6f) {
            q.pauseT = max(q.pauseT, 0.4f)
            q.layT += dt
            if (q.layT > 1.0f) {
                q.layT = 0f
                val b = cells[q.target]
                b.dead = 0f; b.hunger = 0.55f; b.beingFed = false
                b.flash = 0.6f
                q.target = -1
            }
        } else q.layT = 0f

        drawQueen(nc, q, hexR, t, wax, batt)
    }

    private fun drawQueen(nc: android.graphics.Canvas, q: QueenState, hexR: Float, t: Float,
                           wax: Rgb, batt: Float) {
        val r = hexR * 0.5f
        val laying = q.layT > 0.01f
        val breathe = 1f + 0.05f * sin(t * 2.2f)
        val layPulse = if (laying) 1f + 0.12f * sin(t * 14f) else 1f
        val legSwing = sin(q.stride * 3.2f)
        val legSwing2 = sin(q.stride * 3.2f + 2.0f)
        val torsoCol = android.graphics.Color.argb((0.95f * batt * 255).toInt(), 50, 38, 18)
        val headCol = android.graphics.Color.argb((0.95f * batt * 255).toInt(), 35, 28, 15)
        val legCol = android.graphics.Color.argb((0.9f * batt * 255).toInt(), 60, 45, 25)
        val antennaCol = android.graphics.Color.argb((0.7f * batt * 255).toInt(), 80, 65, 35)
        val stripeCol = rgba(wax, 0.6f * batt).toArgb()

        nc.save()
        nc.translate(q.x, q.y)
        nc.rotate(q.heading)

        // Halo
        val halo = android.graphics.RadialGradient(
            0f, 0f, 0f, 0f, 0f, r * 2.3f,
            intArrayOf(rgba(wax, 0.16f * batt).toArgb(), Color.Transparent.toArgb()),
            floatArrayOf(0f, 1f), android.graphics.Shader.TileMode.CLAMP,
        )
        nc.drawCircle(0f, 0f, r * 2.3f, android.graphics.Paint().apply { shader = halo; isAntiAlias = true })

        // Abdomen (larger, breathing)
        val abdR = r * 0.6f * breathe * layPulse
        nc.drawOval(-r * 0.2f, -abdR, r * 0.8f, abdR * 2f,
            android.graphics.Paint().apply { color = torsoCol; isAntiAlias = true })
        // Abdomen stripes
        for (si in -1..1) {
            nc.drawOval(-r * 0.1f + si * r * 0.15f, -abdR * 0.7f, r * 0.25f, abdR * 1.4f,
                android.graphics.Paint().apply { color = stripeCol; isAntiAlias = true })
        }

        // Legs
        val legPaint = android.graphics.Paint().apply { color = legCol; strokeWidth = 1.5f; isAntiAlias = true }
        for (side in listOf(-1f, 1f)) {
            val swing = if (side < 0) legSwing else legSwing2
            for (li in 0 until 3) {
                val lx = (li - 1) * r * 0.4f
                val ly = side * r * 0.3f
                nc.drawLine(lx, ly, lx + side * r * 0.5f, ly + swing * r * 0.2f, legPaint)
                nc.drawLine(lx + side * r * 0.5f, ly + swing * r * 0.2f, lx + side * r * 0.7f, ly + swing * r * 0.4f,
                    android.graphics.Paint().apply { color = legCol; strokeWidth = 1f; isAntiAlias = true })
            }
        }

        // Thorax
        nc.drawCircle(r * 0.1f, r * 0.1f, r * 0.35f,
            android.graphics.Paint().apply { color = android.graphics.Color.argb((0.95f * batt * 255).toInt(), 40, 30, 15); isAntiAlias = true })

        // Wings
        if (!gridLp) {
            val wingPaint = android.graphics.Paint().apply { color = android.graphics.Color.argb((0.3f * batt * 255).toInt(), 255, 255, 255); isAntiAlias = true }
            nc.drawOval(r * 0.1f, -r * 0.7f, r * 1.1f, r * 0.35f, wingPaint)
            nc.drawOval(r * 0.1f, r * 0.35f, r * 1.1f, r * 0.35f, wingPaint)
        }

        // Head
        nc.drawCircle(r * 0.6f, 0f, r * 0.18f,
            android.graphics.Paint().apply { color = headCol; isAntiAlias = true })
        // Antennae
        nc.drawLine(r * 0.6f, -r * 0.1f, r * 0.9f, -r * 0.3f,
            android.graphics.Paint().apply { color = antennaCol; strokeWidth = 1f; isAntiAlias = true })
        nc.drawLine(r * 0.6f, r * 0.1f, r * 0.9f, r * 0.3f,
            android.graphics.Paint().apply { color = antennaCol; strokeWidth = 1f; isAntiAlias = true })
        nc.drawCircle(r * 0.9f, -r * 0.3f, 1.5f,
            android.graphics.Paint().apply { color = antennaCol; isAntiAlias = true })
        nc.drawCircle(r * 0.9f, r * 0.3f, 1.5f,
            android.graphics.Paint().apply { color = antennaCol; isAntiAlias = true })

        nc.restore()
    }
}

private fun Color(r: Float, g: Float, b: Float, a: Float): Color = Color(
    red = r.coerceIn(0f, 1f),
    green = g.coerceIn(0f, 1f),
    blue = b.coerceIn(0f, 1f),
    alpha = a.coerceIn(0f, 1f),
)

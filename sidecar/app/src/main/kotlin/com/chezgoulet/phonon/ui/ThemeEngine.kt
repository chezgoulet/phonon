package com.chezgoulet.phonon.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Singleton engine that manages the active visualization pack, arrangement
 * data, and display number overlay.
 *
 * CoordinatorClient calls [activatePack], [applyArrangement], [setShowNumbers],
 * and [flashNumber] from WebSocket command handlers. PackSurface is the
 * single Compose entry point for the Visualizer tab.
 */
object ThemeEngine {

    // ── Registered packs ──
    // Packs register themselves here (via companion object or manual reg).
    // The 3 built-in packs are added during App startup; stubs exist here
    // so the app compiles without them.

    private val _packs = mutableMapOf<String, VisualizationPack>()

    /** Registered pack ids in registration order. */
    val packIds: Set<String> get() = _packs.keys

    /** Look up a pack by id. */
    fun getPack(id: String): VisualizationPack? = _packs[id]

    /** Register a pack. Replaces any existing pack with the same id. */
    fun registerPack(pack: VisualizationPack) {
        _packs[pack.id] = pack
    }

    // ── Observable state ──

    /** Id of the currently active pack. */
    var activePackId: String by mutableStateOf("neon-ring")
        private set

    /** Whether the display number overlay is visible. */
    var isDisplayNumberVisible: Boolean by mutableStateOf(false)
        private set

    /** Current arrangement layout from the coordinator. */
    var arrangement: List<DeviceArrangementEntry> by mutableStateOf(emptyList())
        private set

    /** Counter incremented on flashNumber() — Compose observes changes. */
    private var flashCounter by mutableStateOf(0L)

    /** This device's ID (set once from app context so we can filter arrangement). */
    private var localDeviceId: String = ""

    /** Reference to the previously-active pack for lifecycle calls. */
    private var previousPack: VisualizationPack? = null

    // ── Actions (called from CoordinatorClient) ──

    /**
     * Switch to a different visualization pack.
     * Calls [VisualizationPack.onDeactivate] on the old pack and
     * [VisualizationPack.onActivate] on the new one.
     */
    fun activatePack(id: String) {
        if (id == activePackId) return
        if (id !in _packs) return

        previousPack = _packs[activePackId]
        previousPack?.onDeactivate()
        activePackId = id
        _packs[id]?.onActivate()
    }

    /**
     * Apply a new device arrangement from the coordinator.
     * Updates [arrangement] used for proximity-aware rendering.
     */
    fun applyArrangement(entries: List<DeviceArrangementEntry>) {
        arrangement = entries
    }

    /** Toggle the display number overlay on/off. */
    fun setShowNumbers(visible: Boolean) {
        isDisplayNumberVisible = visible
    }

    /** Brief full-opacity flash of the display number. */
    fun flashNumber() {
        flashCounter++
    }

    // ── Registration helper ──

    /** Initialize with the default set of built-in packs. */
    fun initializeWithDefaults() {
        registerPack(NeonRingPlaceholderPack)
    }

    /**
     * Set the local device id so arrangement resolution knows which
     * arrangement entry belongs to this device.
     */
    fun setLocalDeviceId(id: String) {
        localDeviceId = id
    }

    /**
     * Build a list of [PeerState] for all devices in the current arrangement
     * EXCEPT this device. Returns empty list if no arrangement is set.
     */
    fun buildPeerStates(allEntries: List<DeviceArrangementEntry>? = null): List<PeerState> {
        val entries = allEntries ?: arrangement
        if (entries.isEmpty()) return emptyList()
        return entries
            .filter { it.deviceId != localDeviceId }
            .map { PeerState(
                deviceId = it.deviceId,
                displayNumber = it.displayNumber,
                position = it.position,
                batteryLevel = -1,   // peer battery not available via arrangement
                isProcessing = false,
                isHealthy = true
            ) }
    }

    /**
     * Resolve this device's [DevicePosition] from the current arrangement.
     * Returns null if this device has no arrangement entry.
     */
    fun resolvePosition(): DevicePosition? {
        return arrangement.firstOrNull { it.deviceId == localDeviceId }?.position
    }

    /**
     * Find this device's display number from the arrangement.
     * Returns null if unassigned.
     */
    fun resolveDisplayNumber(): Int? {
        return arrangement.firstOrNull { it.deviceId == localDeviceId }?.displayNumber
            ?.takeIf { it > 0 }  // 0 means unassigned
    }

    // ── Composable entry point ──

    /**
     * Root composable for the Visualizer tab.
     *
     * Renders the active pack's [VisualizationPack.Render] and overlays
     * the display number when [isDisplayNumberVisible] is true.
     *
     * @param state  Current VizState snapshot (updated ~10fps).
     */
    @Composable
    fun PackSurface(state: VizState) {
        val pack = _packs[activePackId]

        Box(modifier = Modifier.fillMaxSize()) {
            // Active pack render
            if (pack != null) {
                pack.Render(state = state, modifier = Modifier.fillMaxSize())
            } else {
                // Fallback when no pack is registered for the active id
                FallbackPack(state = state)
            }

            // Number overlay on top
            val number = state.displayNumber
            if (isDisplayNumberVisible && number != null) {
                DisplayNumberOverlay(
                    number = number,
                    flashTrigger = flashCounter
                )
            }
        }
    }

    // ── Display number overlay ──

    @Composable
    private fun DisplayNumberOverlay(
        number: Int,
        flashTrigger: Long
    ) {
        // Track flash state — each new trigger fires a brief full-opacity pulse
        var flashAlpha by remember { mutableStateOf(0f) }
        LaunchedEffect(flashTrigger) {
            // Animate: instant full → fade to 0 over 0.5s
            val startTime = withFrameMillis { it }
            while (true) {
                val elapsed = withFrameMillis { it } - startTime
                if (elapsed > 500) break
                flashAlpha = 1f - (elapsed / 500f)
            }
            flashAlpha = 0f
        }

        // Breathing glow animation (always running)
        val infiniteTransition = rememberInfiniteTransition(label = "numberGlow")
        val glowAlpha by infiniteTransition.animateFloat(
            initialValue = 0.08f,
            targetValue = 0.22f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = EaseInOutCubic),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glowAlpha"
        )

        val displayAlpha = maxOf(glowAlpha, flashAlpha)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number.toString().padStart(2, '0'),
                color = Color.White.copy(alpha = displayAlpha),
                fontSize = 140.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 8.sp
            )
        }
    }
}

// ── Placeholder / fallback packs for initial compilation ──

/**
 * Placeholder pack shown when the actual Neon Ring pack hasn't been
 * implemented yet. Shows the device state as floating text overlay.
 * Replaced by [com.chezgoulet.phonon.ui.packs.NeonRingPack] in #190.
 */
private object NeonRingPlaceholderPack : VisualizationPack {
    override val id = "neon-ring"
    override val name = "Neon Ring"
    override val description = "Placeholder — install pack for full visuals"
    override val author = "chezgoulet"
    override val version = "0.1.0"
    override val defaultConfig = mapOf(
        "ring_color_primary" to "#38BDF8",
        "ring_color_secondary" to "#D946EF",
        "ring_color_processing" to "#22C55E",
        "rotation_speed" to "0.8",
        "glow_intensity" to "1.0"
    )

    @Composable
    override fun Render(state: VizState, modifier: Modifier) {
        // Minimal placeholder: dark bg with pulsing center dot and pack name
        val infiniteTransition = rememberInfiniteTransition(label = "placeholder")
        val pulse by infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = EaseInOutCubic),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )

        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2
                val cy = size.height / 2
                val r = minOf(cx, cy) * 0.2f

                // Outer ring
                drawCircle(
                    color = Color(0xFF38BDF8).copy(alpha = 0.3f * pulse),
                    radius = r * 1.5f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2f)
                )
                // Inner dot
                drawCircle(
                    color = if (state.isProcessing) Color(0xFF22C55E)
                            else Color(0xFF38BDF8).copy(alpha = 0.6f),
                    radius = 6f,
                    center = Offset(cx, cy)
                )
            }

            Text(
                text = "Visualization Pack",
                color = Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)
            )
        }
    }
}

/**
 * Fallback composable shown when the active pack id has no registered
 * implementation. Indicates a configuration issue.
 */
@Composable
private fun FallbackPack(state: VizState) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Pack '${state.activeThemePack}' not loaded",
            color = Color.Gray.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

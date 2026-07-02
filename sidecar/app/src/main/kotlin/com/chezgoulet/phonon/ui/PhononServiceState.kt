package com.chezgoulet.phonon.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.chezgoulet.phonon.PhononService

/**
 * Mutable state holder that reflects PhononService state.
 *
 * The CompanionApp connects to PhononService via a binding and updates
 * this object's fields. Compose recomposes whenever any observed field
 * changes.
 */
class PhononServiceState {
    /** Connection status text from PhononService. */
    var connectionStatus by mutableStateOf("connecting")
        private set

    /** Coordinator host:port. */
    var coordinatorHost by mutableStateOf("discovering…")
        private set
    var coordinatorPort by mutableStateOf(8080)
        private set

    /** Device identity. */
    var deviceId by mutableStateOf("")
        private set

    /** Currently loaded model name (or null). */
    var loadedModel by mutableStateOf<String?>(null)
        private set

    /** Engine in use (always litert-lm with LiteRT-LM SDK). */
    var engine by mutableStateOf("")
        private set

    /** Battery level 0..100, -1 if unknown. */
    var batteryLevel by mutableStateOf(-1)
        private set

    /** Battery temperature in Celsius, -1 if unknown. */
    var batteryTemp by mutableStateOf(-1f)
        private set

    /** Whether the device is charging. */
    var isCharging by mutableStateOf(false)
        private set

    /** Whether inference is currently in progress. */
    var isProcessing by mutableStateOf(false)
        private set

    /** Inference token count from the last request. */
    var lastTokens by mutableStateOf(0)
        private set

    /** Tokens per second computed from the last inference. */
    var lastTokensPerSecond by mutableStateOf(0f)
        private set

    /** True when paired with a coordinator. */
    var isPaired by mutableStateOf(false)
        private set

    /** Uptime in seconds since service start. */
    var uptimeSeconds by mutableStateOf(0L)
        private set

    /** Heartbeat queue depth (from last heartbeat cycle). */
    var queueDepth by mutableStateOf(0)
        private set

    /** Recent log entries (newest first). */
    var logs by mutableStateOf(listOf<LogEntry>())
        private set

    /** Pairing code or QR data. */
    var pairingCode by mutableStateOf("")
        private set

    // ── Visualization pack state (set by coordinator commands → ThemeEngine) ──

    /** Display number resolved from ThemeEngine arrangement (null = hidden). */
    val displayNumber: Int? get() = ThemeEngine.resolveDisplayNumber()

    /** Active pack id mirrored from ThemeEngine. */
    val activePackId: String get() = ThemeEngine.activePackId

    /** Peer states built from current arrangement (other devices). */
    var peerStates: List<PeerState> by mutableStateOf(emptyList())
        private set

    /** This device's position resolved from ThemeEngine arrangement. */
    val position: DevicePosition? get() = ThemeEngine.resolvePosition()

    /** Coordinator-assigned mode label. */
    var coordinatorMode: String by mutableStateOf("pool")
        private set

    /** Synchronize all state from a service instance. */
    fun syncFrom(service: PhononService) {
        connectionStatus = service.connectionStatus
        loadedModel = service.loadedModel
        deviceId = (service.application as com.chezgoulet.phonon.PhononApplication).deviceId
        engine = service.loadedModel?.let { "litert-lm" } ?: ""
        batteryLevel = service.batteryLevel.toInt()
        batteryTemp = service.batteryTempC.toFloat()
        isCharging = service.isCharging
        isProcessing = service.isProcessing
        coordinatorHost = service.coordinatorHost
        coordinatorPort = service.coordinatorPort
        lastTokensPerSecond = service.lastTokensPerSecond
        queueDepth = service.queueDepth

        // Pairing screen state: without this the QR/code UI never renders
        // (the field silently stayed at its "" default).
        pairingCode = service.pairingCode ?: ""
        uptimeSeconds = service.uptimeSeconds

        // VizState fields synced from ThemeEngine singleton
        peerStates = ThemeEngine.buildPeerStates()
        coordinatorMode = service.coordinatorMode
    }

    /**
     * Build a [VizState] snapshot from current state fields.
     * Called by PhononCompanionApp every frame.
     */
    fun toVizState(): VizState = VizState(
        deviceId = deviceId,
        displayNumber = ThemeEngine.resolveDisplayNumber(),
        displayNumberFlash = false,
        activeThemePack = activePackId,
        isProcessing = isProcessing,
        tokensPerSecond = lastTokensPerSecond,
        inferenceLoad = if (lastTokensPerSecond > 0f)
            (lastTokensPerSecond / 100f).coerceIn(0f, 1f) else 0f,
        batteryLevel = batteryLevel.coerceIn(0, 100),
        batteryTemperature = batteryTemp,
        isCharging = isCharging,
        isHealthy = batteryLevel > 15 && batteryTemp < 45f,
        workload = computeWorkload(),
        queueDepth = queueDepth,
        position = position,
        peerStates = peerStates,
        peerCount = peerStates.size,
        coordinatorMode = coordinatorMode,
        lastHeartbeatAgo = 0L,
        themeConfig = emptyMap(),
        lowPowerMode = batteryLevel <= 20 && !isCharging
    )

    /**
     * Composite workload: 50% inference load, 30% queue depth, 20% battery.
     * Returns 0.0–1.0.
     */
    fun computeWorkload(): Float {
        val infLoad = if (lastTokensPerSecond > 0f)
            (lastTokensPerSecond / 100f).coerceIn(0f, 1f) else 0f
        val qLoad = (queueDepth.toFloat() / 50f).coerceIn(0f, 1f)
        val bLoad = 1f - (batteryLevel.toFloat() / 100f)
        return 0.5f * infLoad + 0.3f * qLoad + 0.2f * bLoad
    }

    /** Add a log entry (newest first, capped at 100). */
    fun addLog(entry: LogEntry) {
        logs = (listOf(entry) + logs).take(100)
    }
}

data class LogEntry(
    val timestamp: Long,
    val level: String,
    val message: String
)

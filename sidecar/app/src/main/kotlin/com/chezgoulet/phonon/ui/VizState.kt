package com.chezgoulet.phonon.ui

/**
 * Standardized data model shared across all visualization packs.
 *
 * Every pack receives a VizState snapshot and renders using only these fields.
 * The coordinator drives pack switching, arrangement, and number toggle via
 * WebSocket commands mapped into this model.
 */

/** Spatial position of a device on the coordinator's arrangement canvas (0.0–1.0 normalized). */
data class DevicePosition(
    val x: Float,
    val y: Float,
    val gridSize: Int = 0
)

/** Simplified state of a peer device for proximity-aware rendering. */
data class PeerState(
    val deviceId: String,
    val displayNumber: Int?,
    val position: DevicePosition?,
    val batteryLevel: Int,
    val isProcessing: Boolean,
    val isHealthy: Boolean
)

/** Per-device arrangement entry received from the coordinator. */
data class DeviceArrangementEntry(
    val deviceId: String,
    val displayNumber: Int,
    val position: DevicePosition
)

/**
 * Complete rendering context for one device at one moment in time.
 *
 * All measurement values that a pack could need are normalized and included
 * here so packs never need to reach into service internals.
 */
data class VizState(
    val deviceId: String,

    /** Display number overlay — null means hidden. */
    val displayNumber: Int?,
    /** Brief full-opacity flash trigger for the number overlay. */
    val displayNumberFlash: Boolean,

    /** Currently active pack id (e.g. "neon-ring", "matrix-rain"). */
    val activeThemePack: String,

    // ── Inference ──
    val isProcessing: Boolean,
    val tokensPerSecond: Float,
    /** Normalized 0.0–1.0 inference compute load. */
    val inferenceLoad: Float,

    // ── Battery & thermal ──
    val batteryLevel: Int,          // 0–100
    val batteryTemperature: Float,  // °C
    val isCharging: Boolean,
    val isHealthy: Boolean,

    /** Composite workload: 0.5*inferenceLoad + 0.3*normalizedQueue + 0.2*(1-batteryLevel/100). */
    val workload: Float,            // 0.0–1.0

    val queueDepth: Int,

    // ── Spatial (from coordinator arrangement) ──
    val position: DevicePosition?,
    val peerStates: List<PeerState>,
    val peerCount: Int,

    // ── System ──
    val coordinatorMode: String,    // "pool" | "standby" | "inference" | "update"
    val lastHeartbeatAgo: Long,     // seconds

    /** Pack-specific config overrides from coordinator (viz_config command). */
    val themeConfig: Map<String, String>,

    /** True when battery ≤ 20% and not charging — packs should degrade complexity. */
    val lowPowerMode: Boolean
)

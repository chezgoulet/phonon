package com.chezgoulet.phonon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Interface for a visualization theme pack.
 *
 * Each pack is a standalone .kt file compiled into the APK. Packs receive
 * a [VizState] snapshot every frame and render using only that data.
 * The coordinator selects the active pack via the `viz_switch` WebSocket
 * command.
 */
interface VisualizationPack {

    /** Stable identifier (e.g. "neon-ring", "matrix-rain"). */
    val id: String

    /** Human-readable name displayed in the pack manager. */
    val name: String

    /** One-line description. */
    val description: String

    val author: String
    val version: String

    /**
     * Default theme config key-value pairs.
     * Operators can override these per-device via the `viz_config` command.
     */
    val defaultConfig: Map<String, String>

    /** Called when this pack becomes the active visualization. */
    fun onActivate() {}

    /** Called when this pack is replaced by another. */
    fun onDeactivate() {}

    /**
     * Compose rendering entry point.
     *
     * @param state  Current device state snapshot (updated ~10fps).
     * @param modifier  Modifier applied by the parent ThemeEngine layout.
     */
    @Composable
    fun Render(state: VizState, modifier: Modifier)
}

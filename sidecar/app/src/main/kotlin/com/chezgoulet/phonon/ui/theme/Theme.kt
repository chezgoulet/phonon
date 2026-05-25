package com.chezgoulet.phonon.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cyberpunk dark palette — matches the coordinator Web UI theme
private val PhononColors = darkColorScheme(
    primary = Color(0xFF38BDF8),        // accent blue
    onPrimary = Color(0xFF0F172A),       // bg
    primaryContainer = Color(0xFF1E293B),// card
    onPrimaryContainer = Color(0xFFF1F5F9),// text

    secondary = Color(0xFF22C55E),       // success green
    tertiary = Color(0xFFEAB308),        // warning yellow
    error = Color(0xFFEF4444),           // error red

    background = Color(0xFF0F172A),      // bg
    onBackground = Color(0xFFF1F5F9),    // text
    surface = Color(0xFF1E293B),         // card
    onSurface = Color(0xFFF1F5F9),       // text

    surfaceVariant = Color(0xFF334155),  // border
    onSurfaceVariant = Color(0xFF94A3B8),// muted
    outline = Color(0xFF334155),
    inverseSurface = Color(0xFF0F172A),
)

@Composable
fun PhononTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PhononColors,
        content = content
    )
}

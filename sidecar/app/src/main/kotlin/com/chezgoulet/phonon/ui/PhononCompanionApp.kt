package com.chezgoulet.phonon.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chezgoulet.phonon.ui.screens.LogViewer
import com.chezgoulet.phonon.ui.screens.StatusScreen
import com.chezgoulet.phonon.ui.theme.PhononTheme

/**
 * Root composable for the Phonon companion app.
 *
 * Provides tab-based navigation:
 * - Status: main status dashboard with telemetry
 * - Log: scrollable log viewer
 * - Visualizer: pack-based visualization driven by ThemeEngine
 */
@Composable
fun PhononCompanionApp(
    state: PhononServiceState,
    onForceHeartbeat: () -> Unit
) {
    PhononTheme {
        var selectedTab by remember { mutableStateOf(0) }

        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { },
                        label = { Text("Status") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { },
                        label = { Text("Log") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { },
                        label = { Text("Visualizer") }
                    )
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (selectedTab) {
                    0 -> StatusScreen(
                        state = state,
                        onForceHeartbeat = onForceHeartbeat
                    )
                    1 -> LogViewer(state = state)
                    2 -> ThemeEngine.PackSurface(
                        state = state.toVizState()
                    )
                }
            }
        }
    }
}

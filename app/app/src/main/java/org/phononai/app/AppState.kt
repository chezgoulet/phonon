package org.phononai.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Same-process handle from the activity to the running service. */
object AppState {
    @Volatile var service: PhononService? = null

    /** Idle shape, mirrored here so the Serve and Settings screens agree. */
    var presenceShape by mutableStateOf("aura")
}

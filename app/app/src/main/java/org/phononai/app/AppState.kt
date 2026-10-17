package org.phononai.app

/** Same-process handle from the activity to the running service. */
object AppState {
    @Volatile var service: PhononService? = null
}

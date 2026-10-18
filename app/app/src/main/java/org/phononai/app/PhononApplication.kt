package org.phononai.app

import android.app.Application

/**
 * Loads the model catalog before any component can read it.
 *
 * The catalog lives in `assets/catalog.json` and every screen, the service and
 * the settings reader depend on it. Doing this in the application — rather than
 * in each entry point — means there is exactly one place the load can be
 * forgotten, and it is the first code the process runs.
 */
class PhononApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ModelCatalog.load(this)
    }
}

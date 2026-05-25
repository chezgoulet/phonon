package com.chezgoulet.phonon

import android.os.Build

/** API level checks without importing the whole Build.VERSION_CODES enum. */
object BuildCheck {
    val atLeastO: Boolean get() = Build.VERSION.SDK_INT >= 26        // Android 8.0
    val atLeastP: Boolean get() = Build.VERSION.SDK_INT >= 28        // Android 9.0
    val atLeastQ: Boolean get() = Build.VERSION.SDK_INT >= 29        // Android 10.0
    val atLeastR: Boolean get() = Build.VERSION.SDK_INT >= 30        // Android 11.0
    val atLeastS: Boolean get() = Build.VERSION.SDK_INT >= 31        // Android 12.0
    val atLeastT: Boolean get() = Build.VERSION.SDK_INT >= 33        // Android 13.0
    val atLeastU: Boolean get() = Build.VERSION.SDK_INT >= 34        // Android 14.0
    val atLeastV: Boolean get() = Build.VERSION.SDK_INT >= 35        // Android 15.0
}

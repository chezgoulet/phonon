package org.phononai.app

import android.os.Build

/**
 * Per-vendor guidance for the one thing a foreground service cannot do for
 * itself: survive an aggressive background killer.
 *
 * Every entry points at the vendor's own battery/autostart screen and at
 * dontkillmyapp.com, which tracks the current path per manufacturer. The app
 * only links out; it never claims to fix the restriction.
 */
object OemGuidance {

    data class Guidance(
        val vendor: String,
        val steps: String,
        val dontKillUrl: String,
    )

    /** The guidance for this device's manufacturer, or the generic entry. */
    fun forDevice(): Guidance = forManufacturer(Build.MANUFACTURER ?: "")

    fun forManufacturer(manufacturer: String): Guidance {
        val key = manufacturer.trim().lowercase()
        return when {
            key.contains("samsung") -> Guidance(
                "Samsung",
                "Settings → Battery → Background usage limits: make sure Phonon is not in " +
                    "\"Sleeping apps\", and set it to \"Never sleeping apps\".",
                "https://dontkillmyapp.com/samsung",
            )
            key.contains("xiaomi") || key.contains("redmi") || key.contains("poco") -> Guidance(
                "Xiaomi (MIUI/HyperOS)",
                "Settings → Apps → Phonon → Battery saver → \"No restrictions\", and enable " +
                    "\"Autostart\" in Security → Permissions.",
                "https://dontkillmyapp.com/xiaomi",
            )
            key.contains("huawei") || key.contains("honor") -> Guidance(
                "Huawei/Honor",
                "Settings → Battery → App launch: turn Phonon to \"Manage manually\" with " +
                    "all three switches on.",
                "https://dontkillmyapp.com/huawei",
            )
            key.contains("oneplus") || key.contains("oppo") || key.contains("realme") -> Guidance(
                "OnePlus/OPPO/realme",
                "Settings → Battery → Battery optimisation → Phonon → \"Don't optimise\", and " +
                    "lock the app in the recents view.",
                "https://dontkillmyapp.com/oneplus",
            )
            key.contains("vivo") || key.contains("iqoo") -> Guidance(
                "vivo/iQOO",
                "Settings → Battery → Background power consumption management → Phonon → " +
                    "\"Don't restrict\", and enable Auto-start.",
                "https://dontkillmyapp.com/vivo",
            )
            key.contains("asus") -> Guidance(
                "Asus",
                "Settings → Battery → PowerMaster/Mobile Manager: allow Phonon to auto-start " +
                    "and remove it from any app-cleanup list.",
                "https://dontkillmyapp.com/asus",
            )
            key.contains("nokia") -> Guidance(
                "Nokia",
                "Settings → Apps → Phonon → Battery: \"Unrestricted\" and allow background " +
                    "activity.",
                "https://dontkillmyapp.com/nokia",
            )
            key.contains("google") || key.contains("pixel") -> Guidance(
                "Google Pixel",
                "Settings → Apps → Phonon → Battery → \"Unrestricted\". Pixel is the closest to " +
                    "stock; no autostart toggle is needed.",
                "https://dontkillmyapp.com/google",
            )
            else -> Guidance(
                if (Build.MANUFACTURER.isNullOrBlank()) "this device" else Build.MANUFACTURER,
                "Settings → Apps → Phonon → Battery: set it to \"Unrestricted\" so the server " +
                    "is not frozen when the screen is off.",
                "https://dontkillmyapp.com/",
            )
        }
    }

    /**
     * True when the phone has sat at full charge long enough that battery
     * longevity is at stake. The platform owns charge limiting; the app can only
     * say so. Four hours is the threshold the brief names.
     */
    fun sittingFull(battery: BatterySnapshot, fullSinceMs: Long, nowMs: Long): Boolean =
        battery.charging && battery.levelPct == 100 &&
            fullSinceMs > 0 && nowMs - fullSinceMs >= FULL_CHARGE_WARN_MS

    const val FULL_CHARGE_WARN_MS = 4 * 60 * 60 * 1000L
}

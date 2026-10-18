package org.phononai.app

import android.os.Build

/**
 * Per-vendor guidance for the one thing a foreground service cannot do for
 * itself: survive an aggressive background killer.
 *
 * Every entry names the vendor's own battery/autostart screen — both the text
 * step and, where a stable component is known, a deep link into it — and points
 * at dontkillmyapp.com, which tracks the current path per manufacturer. The app
 * only links out; it never claims to fix the restriction, and an unknown vendor
 * gets a generic line rather than a wrong one.
 */
object OemGuidance {

    data class Guidance(
        val vendor: String,
        val steps: String,
        val dontKillUrl: String,
        /**
         * The vendor's own settings screen as `package/class`, when one is
         * known. Null means "no known intent": the caller falls back to this
         * app's own settings page.
         */
        val settingsComponent: String?,
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
                "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
            )
            key.contains("xiaomi") || key.contains("redmi") || key.contains("poco") -> Guidance(
                "Xiaomi (MIUI/HyperOS)",
                "Settings → Apps → Phonon → Battery saver → \"No restrictions\", and enable " +
                    "\"Autostart\" in Security → Permissions.",
                "https://dontkillmyapp.com/xiaomi",
                "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
            key.contains("huawei") || key.contains("honor") -> Guidance(
                "Huawei/Honor",
                "Settings → Battery → App launch: turn Phonon to \"Manage manually\" with " +
                    "all three switches on.",
                "https://dontkillmyapp.com/huawei",
                "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            )
            key.contains("oneplus") -> Guidance(
                "OnePlus",
                "Settings → Battery → Battery optimisation → Phonon → \"Don't optimise\", and " +
                    "lock the app in the recents view.",
                "https://dontkillmyapp.com/oneplus",
                "com.oneplus.security/com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            )
            key.contains("oppo") || key.contains("realme") -> Guidance(
                "OPPO/realme (ColorOS)",
                "Settings → Battery → Battery optimisation → Phonon → \"Don't optimise\", and " +
                    "lock the app in the recents view.",
                "https://dontkillmyapp.com/oppo",
                "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            )
            key.contains("vivo") || key.contains("iqoo") -> Guidance(
                "vivo/iQOO",
                "Settings → Battery → Background power consumption management → Phonon → " +
                    "\"Don't restrict\", and enable Auto-start.",
                "https://dontkillmyapp.com/vivo",
                "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            )
            key.contains("asus") -> Guidance(
                "Asus",
                "Settings → Battery → PowerMaster/Mobile Manager: allow Phonon to auto-start " +
                    "and remove it from any app-cleanup list.",
                "https://dontkillmyapp.com/asus",
                null,
            )
            key.contains("nokia") -> Guidance(
                "Nokia",
                "Settings → Apps → Phonon → Battery: \"Unrestricted\" and allow background " +
                    "activity.",
                "https://dontkillmyapp.com/nokia",
                null,
            )
            key.contains("google") || key.contains("pixel") -> Guidance(
                "Google Pixel",
                "Settings → Apps → Phonon → Battery → \"Unrestricted\". Pixel is the closest to " +
                    "stock; no autostart toggle is needed.",
                "https://dontkillmyapp.com/google",
                null,
            )
            else -> Guidance(
                if (Build.MANUFACTURER.isNullOrBlank()) "this device" else Build.MANUFACTURER,
                "Settings → Apps → Phonon → Battery: set it to \"Unrestricted\" so the server " +
                    "is not frozen when the screen is off.",
                "https://dontkillmyapp.com/",
                null,
            )
        }
    }

    /**
     * Split a `package/class` component into its parts, or null when it is
     * absent or malformed. Pure so the caller never builds an Intent from junk.
     */
    fun componentOf(settingsComponent: String?): Pair<String, String>? {
        val value = settingsComponent?.trim().orEmpty()
        if (value.isBlank()) return null
        val slash = value.indexOf('/')
        if (slash <= 0 || slash == value.length - 1) return null
        val pkg = value.substring(0, slash).trim()
        val cls = value.substring(slash + 1).trim()
        if (pkg.isEmpty() || cls.isEmpty()) return null
        return pkg to cls
    }

    /**
     * True when the phone has sat at full charge long enough that battery
     * longevity is at stake. The platform owns charge limiting; the app can only
     * say so. Four hours is the threshold the brief names.
     */
    fun sittingFull(battery: BatterySnapshot, fullSinceMs: Long, nowMs: Long): Boolean =
        battery.charging && battery.levelPct == 100 &&
            fullSinceMs > 0 && nowMs - fullSinceMs >= FULL_CHARGE_WARN_MS

    /**
     * Whole hours the phone has been held at 100%, or null below the warning
     * threshold. Pure duration maths, so it is unit tested without a device.
     */
    fun fullHours(fullSinceMs: Long, nowMs: Long): Int? {
        if (fullSinceMs <= 0 || nowMs <= fullSinceMs) return null
        val elapsed = nowMs - fullSinceMs
        if (elapsed < FULL_CHARGE_WARN_MS) return null
        return (elapsed / 3_600_000L).toInt()
    }

    const val FULL_CHARGE_WARN_MS = 4 * 60 * 60 * 1000L
}

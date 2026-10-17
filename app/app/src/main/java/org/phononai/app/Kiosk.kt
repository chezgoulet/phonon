package org.phononai.app

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings

/**
 * Device Owner kiosk policy. Everything here is policy the platform grants to a
 * Device Owner; no HTTP route is involved and no server setting changes.
 *
 * The receiver component must match the manifest and the provisioning command.
 */
object Kiosk {

    val ADMIN: ComponentName = ComponentName(
        "org.phononai.app",
        "org.phononai.app.PhononDeviceAdmin",
    )

    const val PROVISION_COMMAND =
        "adb shell dpm set-device-owner org.phononai.app/.PhononDeviceAdmin"

    fun isDeviceOwner(context: Context): Boolean = try {
        devicePolicy(context).isDeviceOwnerApp(context.packageName)
    } catch (_: Throwable) {
        false
    }

    /** True when Android would still accept managed-device provisioning. */
    fun isProvisioningAllowed(context: Context): Boolean = try {
        devicePolicy(context)
            .isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE)
    } catch (_: Throwable) {
        false
    }

    fun isLockTaskActive(context: Context): Boolean = try {
        val am = context.getSystemService(ActivityManager::class.java)
        am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    } catch (_: Throwable) {
        false
    }

    fun isBatteryExempt(context: Context): Boolean = try {
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Throwable) {
        false
    }

    fun isSafeBootDisallowed(context: Context): Boolean = hasRestriction(
        context,
        UserManager.DISALLOW_SAFE_BOOT,
    )

    fun isFactoryResetDisallowed(context: Context): Boolean = hasRestriction(
        context,
        UserManager.DISALLOW_FACTORY_RESET,
    )

    private fun hasRestriction(context: Context, restriction: String): Boolean = try {
        devicePolicy(context).getUserRestrictions(ADMIN).getBoolean(restriction, false)
    } catch (_: Throwable) {
        false
    }

    /**
     * Enter appliance mode: pin this activity, apply the two user restrictions,
     * and ask for the battery-optimisation exemption. Returns null on success or
     * a human-readable reason.
     *
     * startLockTask() must be called from the activity that should be pinned.
     */
    fun enter(activity: Activity): String? {
        if (!isDeviceOwner(activity)) return "not Device Owner"

        // Restrictions are independent of the pin; apply them first so a pin
        // failure still leaves the appliance policy visible in Settings.
        try {
            val dpm = devicePolicy(activity)
            dpm.addUserRestriction(ADMIN, UserManager.DISALLOW_SAFE_BOOT)
            dpm.addUserRestriction(ADMIN, UserManager.DISALLOW_FACTORY_RESET)
        } catch (t: Throwable) {
            return "restrictions: ${t.javaClass.simpleName}: ${t.message}"
        }

        try {
            activity.startLockTask()
        } catch (t: Throwable) {
            return "lock task: ${t.javaClass.simpleName}: ${t.message}"
        }

        requestBatteryExemption(activity)
        return null
    }

    /** Reverse the pin. Restrictions stay in force until the admin is removed. */
    fun leave(activity: Activity): String? = try {
        activity.stopLockTask()
        null
    } catch (t: Throwable) {
        "lock task: ${t.javaClass.simpleName}: ${t.message}"
    }

    /**
     * The standard per-app exemption request. A Device Owner is allowed to ask;
     * some OEM builds auto-grant it, stock Android shows a confirmation. If no
     * activity handles the intent, the Settings list is opened instead.
     */
    fun requestBatteryExemption(context: Context) {
        if (isBatteryExempt(context)) return
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        try {
            context.startActivity(direct)
        } catch (_: Throwable) {
            try {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
                // No settings activity on this build; the card still reports the state.
            }
        }
    }

    fun restrictionSummary(context: Context): String {
        if (!isDeviceOwner(context)) return "Not Device Owner"
        val safeBoot = if (isSafeBootDisallowed(context)) "safe boot off" else "safe boot allowed"
        val reset = if (isFactoryResetDisallowed(context)) "factory reset off" else "factory reset allowed"
        return "$safeBoot · $reset"
    }

    private fun devicePolicy(context: Context): DevicePolicyManager =
        context.getSystemService(DevicePolicyManager::class.java)
}

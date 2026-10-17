package org.phononai.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device-admin receiver. The component name is the provisioning target:
 *
 *     adb shell dpm set-device-owner org.phononai.app/.PhononDeviceAdmin
 *
 * Kiosk policy is applied by [Kiosk] once the app is Device Owner. This
 * receiver exists so the platform has an admin component to bind to; it does
 * not implement password or wipe callbacks.
 */
class PhononDeviceAdmin : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        // Device Owner activation lands here when set-device-owner succeeds.
    }

    override fun onDisabled(context: Context, intent: Intent) {
        // Nothing to undo in the app itself: restrictions are cleared by the
        // platform when the last admin is removed.
    }
}

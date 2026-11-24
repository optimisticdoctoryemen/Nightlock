package com.nightlock.app.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.nightlock.app.R

/**
 * Prevents NightLock from being uninstalled directly while activated.
 * During sleep lock hours, Settings is blocked by SleepGuardAccessibilityService,
 * so this receiver cannot be deactivated until morning.
 */
class SleepDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return context.getString(R.string.device_admin_disable_warning)
    }
}

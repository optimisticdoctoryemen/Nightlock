package com.nightlock.app.ui

import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import com.nightlock.app.receiver.SleepDeviceAdminReceiver
import com.nightlock.app.service.SleepGuardAccessibilityService

data class PermissionStatus(
    val overlayGranted: Boolean,
    val accessibilityGranted: Boolean,
    val deviceAdminGranted: Boolean,
    val exactAlarmGranted: Boolean,
    val batteryUnrestricted: Boolean
) {
    val coreReady: Boolean
        get() = overlayGranted && accessibilityGranted && deviceAdminGranted

    val allReady: Boolean
        get() = coreReady && exactAlarmGranted && batteryUnrestricted
}

object PermissionHelper {

    fun checkStatus(context: Context): PermissionStatus {
        return PermissionStatus(
            overlayGranted = Settings.canDrawOverlays(context),
            accessibilityGranted = isAccessibilityServiceEnabled(context),
            deviceAdminGranted = isDeviceAdminActive(context),
            exactAlarmGranted = canScheduleExactAlarms(context),
            batteryUnrestricted = isIgnoringBatteryOptimizations(context)
        )
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedComponent = ComponentName(context, SleepGuardAccessibilityService::class.java)
            .flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val component = splitter.next()
            if (component.equals(expectedComponent, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val component = ComponentName(context, SleepDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(component)
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        runCatching { context.startActivity(intent) }
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        runCatching { context.startActivity(intent) }
    }

    fun openAppDetailsForRestrictedSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        runCatching { context.startActivity(intent) }
    }

    fun openDeviceAdminPrompt(context: Context) {
        val component = ComponentName(context, SleepDeviceAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Protects NightLock from being uninstalled while bedtime lock is active."
            )
        }
        runCatching { context.startActivity(intent) }
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}")
            )
            runCatching { context.startActivity(intent) }
        }
    }

    fun openBatteryOptimizationSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}

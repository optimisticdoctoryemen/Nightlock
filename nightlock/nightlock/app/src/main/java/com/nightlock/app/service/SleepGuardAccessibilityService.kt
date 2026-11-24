package com.nightlock.app.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.nightlock.app.data.LockManager

/**
 * Core bedtime guard. Monitors window transitions and multi-window / pop-up windows on Android
 * tablets. When locked, blocks every app (including Settings, launchers, split-screen, and quick
 * settings) while keeping NightLock's dark sleep screen and the tablet's alarm clock accessible.
 */
class SleepGuardAccessibilityService : AccessibilityService() {

    private var lastLaunchTimestampMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        LockManager.init(this)
        val state = LockManager.refreshState(triggerEnforcement = true)
        if (state.isLocked) {
            enforceLock(isTamperAttempt = false)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        LockManager.init(this)

        val state = LockManager.refreshState(triggerEnforcement = false)
        if (!state.isLocked) {
            SleepOverlayManager.hide(this)
            return
        }

        val eventPkg = event.packageName?.toString()
        val eventClass = event.className?.toString().orEmpty()

        // 1. Handle SystemUI (allow system lockscreen/power menu; collapse quick settings shade)
        if (eventPkg == SYSTEM_UI_PACKAGE) {
            handleSystemUiEvent(eventClass)
            return
        }

        // 2. Inspect all visible application windows (catches tablet split-screen & pop-up windows!)
        val visibleAppPackages = collectVisibleAppPackages()
        val activePackages = if (visibleAppPackages.isNotEmpty()) {
            visibleAppPackages
        } else {
            listOfNotNull(eventPkg)
        }

        val blockedPackages = activePackages.filterNot { pkg ->
            pkg == SYSTEM_UI_PACKAGE || LockManager.isPackageAllowedDuringLock(pkg)
        }

        if (blockedPackages.isEmpty()) {
            // Only NightLock or an allowed Alarm Clock app is visible -> hide temporary shield
            // so the dark sleep clock or ringing morning alarm can be interacted with cleanly.
            SleepOverlayManager.hide(this)
            return
        }

        // 3. A blocked app (or Settings / Installer / Launcher) is visible -> enforce immediately!
        val isTamper = blockedPackages.any { LockManager.TAMPER_PACKAGES.contains(it) } ||
            (eventPkg != null && LockManager.TAMPER_PACKAGES.contains(eventPkg))
        enforceLock(isTamperAttempt = isTamper)
    }

    private fun handleSystemUiEvent(eventClass: String) {
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguard.isKeyguardLocked) {
            // Do not interfere with the tablet's native lock screen
            return
        }

        // Collapse notification shade / Quick Settings so the user cannot tap the Settings gear
        val isShadeOrQuickSettings = eventClass.contains("NotificationShade", ignoreCase = true) ||
            eventClass.contains("QuickSettings", ignoreCase = true) ||
            eventClass.contains("StatusBar", ignoreCase = true) ||
            eventClass.contains("Shade", ignoreCase = true)

        if (isShadeOrQuickSettings && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
    }

    private fun collectVisibleAppPackages(): List<String> {
        val result = mutableListOf<String>()
        val activeWindows = runCatching { windows }.getOrNull() ?: return result
        for (window in activeWindows) {
            if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                val pkg = window.root?.packageName?.toString()
                if (!pkg.isNullOrBlank()) {
                    result.add(pkg)
                }
            }
        }
        return result
    }

    private fun enforceLock(isTamperAttempt: Boolean) {
        // Instant opaque shield covers the blocked screen in <1 frame
        SleepOverlayManager.show(this)

        if (isTamperAttempt) {
            // Aggressively kick Settings / Package Installer out of the foreground
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchTimestampMs >= 250L) {
            lastLaunchTimestampMs = now
            LockManager.launchLockScreen(this)
        }
    }

    override fun onInterrupt() {
        // Nothing to release
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}

package com.nightlock.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import com.nightlock.app.scheduler.SleepScheduler
import com.nightlock.app.service.SleepLockForegroundService
import com.nightlock.app.service.SleepOverlayManager
import com.nightlock.app.ui.LockScreenActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZonedDateTime

data class LockUiState(
    val isLocked: Boolean = false,
    val isTestMode: Boolean = false,
    val unlockAtMillis: Long = 0L,
    val remainingMillis: Long = 0L,
    val schedule: SleepSchedule = SleepSchedule(),
    val isScheduleEditFrozen: Boolean = false,
    val allowedAlarmPackages: Set<String> = emptySet()
)

object LockManager {

    private lateinit var appContext: Context
    private lateinit var prefs: SleepPreferences

    private val _state = MutableStateFlow(LockUiState())
    val state: StateFlow<LockUiState> = _state.asStateFlow()

    @Volatile
    private var cachedAlarmPackages: Set<String> = emptySet()

    // Known Android tablet & OEM alarm clock packages
    private val KNOWN_ALARM_PACKAGES = setOf(
        "com.google.android.deskclock",
        "com.sec.android.app.clockpackage",
        "com.android.deskclock",
        "com.miui.clock",
        "com.oneplus.deskclock",
        "com.coloros.alarmclock",
        "com.oplus.alarmclock",
        "com.lenovo.clock",
        "com.asus.deskclock",
        "com.htc.android.worldclock",
        "com.lge.clock",
        "com.sonyericsson.organizer",
        "com.motorola.timeweatherwidget"
    )

    // High-risk settings & installer packages that must be aggressively blocked during lock
    val TAMPER_PACKAGES = setOf(
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.samsung.android.lool",
        "com.samsung.android.sm",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.android.vending"
    )

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = SleepPreferences(appContext)
        cachedAlarmPackages = discoverAlarmPackages(appContext)
        refreshState(triggerEnforcement = false)
    }

    fun getAllowedAlarmPackages(): Set<String> {
        if (cachedAlarmPackages.isEmpty() && ::appContext.isInitialized) {
            cachedAlarmPackages = discoverAlarmPackages(appContext)
        }
        return cachedAlarmPackages
    }

    fun isPackageAllowedDuringLock(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return true
        if (packageName == appContext.packageName) return true
        if (cachedAlarmPackages.contains(packageName)) return true
        return false
    }

    fun getCurrentBootCount(): Int {
        return runCatching {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)
    }

    fun updateSchedule(newSchedule: SleepSchedule): Boolean {
        val now = ZonedDateTime.now()
        val currentSchedule = prefs.loadSchedule()
        // Never allow modifying the schedule while locked or during the pre-bedtime freeze window
        if (isCurrentlyLocked() || ScheduleCalculator.isScheduleEditFrozen(currentSchedule, now)) {
            return false
        }
        prefs.saveSchedule(newSchedule)
        SleepScheduler.syncAlarms(appContext)
        refreshState(triggerEnforcement = true)
        return true
    }

    /**
     * Immediately locks the tablet until the next occurrence of [wakeHour]:[wakeMinute].
     * Once engaged, there is NO undo method exposed to the UI.
     */
    fun lockUntilMorning(wakeHour: Int, wakeMinute: Int) {
        val nowZdt = ZonedDateTime.now()
        val nowWallMs = nowZdt.toInstant().toEpochMilli()
        val targetWallMs = ScheduleCalculator.computeManualLockTargetMillis(nowZdt, wakeHour, wakeMinute)
        startLockSession(
            nowWallMs = nowWallMs,
            untilWallMs = targetWallMs,
            isTestMode = false
        )
    }

    /**
     * Safe 60-second dry run so the user can verify permissions and alarm behavior before overnight use.
     */
    fun startTestLock(durationSeconds: Long = 60L) {
        if (isCurrentlyLocked()) return
        val nowWallMs = System.currentTimeMillis()
        val durationMs = (durationSeconds * 1000L)
            .coerceIn(ScheduleCalculator.MIN_LOCK_DURATION_MS, 5 * 60_000L)
        startLockSession(
            nowWallMs = nowWallMs,
            untilWallMs = nowWallMs + durationMs,
            isTestMode = true
        )
    }

    private fun startLockSession(nowWallMs: Long, untilWallMs: Long, isTestMode: Boolean) {
        val durationMs = (untilWallMs - nowWallMs)
            .coerceIn(ScheduleCalculator.MIN_LOCK_DURATION_MS, ScheduleCalculator.MAX_LOCK_DURATION_MS)
        val clampedUntil = nowWallMs + durationMs

        val session = LockSession(
            active = true,
            isTestMode = isTestMode,
            lockStartedWallMs = nowWallMs,
            lockUntilWallMs = clampedUntil,
            lockDurationMs = durationMs,
            lockStartedElapsedMs = SystemClock.elapsedRealtime(),
            bootCountAtLock = getCurrentBootCount()
        )
        prefs.saveSession(session)
        SleepScheduler.scheduleUnlockAlarm(appContext, clampedUntil)
        SleepLockForegroundService.start(appContext)
        refreshState(triggerEnforcement = true)
        launchLockScreen(appContext)
    }

    /**
     * Re-evaluates active lock session and automatic nightly schedule.
     * Automatically engages lock at bedtime or unlocks when morning arrives.
     */
    fun refreshState(triggerEnforcement: Boolean = true): LockUiState {
        val nowZdt = ZonedDateTime.now()
        val nowWallMs = nowZdt.toInstant().toEpochMilli()
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val bootCount = getCurrentBootCount()
        val schedule = prefs.loadSchedule()
        var session = prefs.loadSession()

        // 1. Check active session first
        if (session.active) {
            val remaining = ScheduleCalculator.computeRemainingMillis(
                lockStartedWallMs = session.lockStartedWallMs,
                lockUntilWallMs = session.lockUntilWallMs,
                lockDurationMs = session.lockDurationMs,
                lockStartedElapsedMs = session.lockStartedElapsedMs,
                bootCountAtLock = session.bootCountAtLock,
                currentWallMs = nowWallMs,
                currentElapsedMs = nowElapsedMs,
                currentBootCount = bootCount
            )
            if (remaining <= 0L) {
                // Morning has arrived (or test finished)! Unlock cleanly.
                prefs.clearSession()
                session = LockSession(active = false)
                SleepOverlayManager.hide(appContext)
                SleepLockForegroundService.stop(appContext)
                SleepScheduler.syncAlarms(appContext)
            } else {
                val uiState = LockUiState(
                    isLocked = true,
                    isTestMode = session.isTestMode,
                    unlockAtMillis = session.lockUntilWallMs,
                    remainingMillis = remaining,
                    schedule = schedule,
                    isScheduleEditFrozen = true,
                    allowedAlarmPackages = cachedAlarmPackages
                )
                _state.value = uiState
                if (triggerEnforcement) {
                    SleepLockForegroundService.start(appContext)
                }
                return uiState
            }
        }

        // 2. No active session -> check if automatic nightly schedule window is active right now
        // Require at least 15s left in the window so minor NTP/monotonic drift at wake time won't re-lock
        val activeWindow = ScheduleCalculator.getActiveWindow(schedule, nowZdt)
        if (activeWindow != null && (activeWindow.endMillis - nowWallMs) >= 15_000L) {
            startLockSession(
                nowWallMs = nowWallMs,
                untilWallMs = activeWindow.endMillis,
                isTestMode = false
            )
            return _state.value
        }

        // 3. Daytime / unlocked state
        val uiState = LockUiState(
            isLocked = false,
            isTestMode = false,
            unlockAtMillis = 0L,
            remainingMillis = 0L,
            schedule = schedule,
            isScheduleEditFrozen = ScheduleCalculator.isScheduleEditFrozen(schedule, nowZdt),
            allowedAlarmPackages = cachedAlarmPackages
        )
        _state.value = uiState
        return uiState
    }

    fun isCurrentlyLocked(): Boolean {
        val current = refreshState(triggerEnforcement = false)
        return current.isLocked
    }

    fun launchLockScreen(context: Context) {
        val intent = Intent(context, LockScreenActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        runCatching { context.startActivity(intent) }
    }

    fun openAlarmClock(context: Context): Boolean {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun discoverAlarmPackages(context: Context): Set<String> {
        val discovered = mutableSetOf<String>()
        val pm = context.packageManager

        val intents = listOf(
            Intent(AlarmClock.ACTION_SHOW_ALARMS),
            Intent(AlarmClock.ACTION_SET_ALARM)
        )
        for (intent in intents) {
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
            resolved.forEach { info ->
                val pkg = info.activityInfo?.packageName
                if (!pkg.isNullOrBlank()) discovered.add(pkg)
            }
        }

        // Also keep any known OEM clock packages that are installed on this tablet
        for (pkg in KNOWN_ALARM_PACKAGES) {
            val installed = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
                true
            }.getOrDefault(false)
            if (installed) discovered.add(pkg)
        }

        // Always include the baseline known list as fallback in case direct-boot hides queries
        if (discovered.isEmpty()) {
            discovered.addAll(KNOWN_ALARM_PACKAGES)
        }
        return discovered
    }
}

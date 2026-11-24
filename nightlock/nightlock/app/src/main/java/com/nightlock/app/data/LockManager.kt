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
    val allowedAlarmPackages: Set<String> = emptySet(),
    val allowedDialerPackages: Set<String> = emptySet(),
    val isBreakActive: Boolean = false,
    val breakRemainingMillis: Long = 0L,
    val canStartBreak: Boolean = false,
    val nextBreakInMillis: Long = 0L
)

object LockManager {

    private lateinit var appContext: Context
    private lateinit var prefs: SleepPreferences

    private val _state = MutableStateFlow(LockUiState())
    val state: StateFlow<LockUiState> = _state.asStateFlow()

    @Volatile
    private var cachedAlarmPackages: Set<String> = emptySet()

    @Volatile
    private var cachedDialerPackages: Set<String> = emptySet()

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

    // Known Android phone dialer & in-call UI packages (never user-editable)
    private val KNOWN_DIALER_PACKAGES = setOf(
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.dialer",
        "com.android.incallui",
        "com.samsung.android.incallui",
        "com.android.server.telecom"
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
        cachedAlarmPackages = discoverPackages(
            context = appContext,
            intents = listOf(
                Intent(AlarmClock.ACTION_SHOW_ALARMS),
                Intent(AlarmClock.ACTION_SET_ALARM)
            ),
            knownPackages = KNOWN_ALARM_PACKAGES
        )
        cachedDialerPackages = discoverPackages(
            context = appContext,
            intents = listOf(Intent(Intent.ACTION_DIAL)),
            knownPackages = KNOWN_DIALER_PACKAGES
        )
        refreshState(triggerEnforcement = false)
    }

    fun getAllowedAlarmPackages(): Set<String> {
        if (cachedAlarmPackages.isEmpty() && ::appContext.isInitialized) {
            cachedAlarmPackages = discoverPackages(
                context = appContext,
                intents = listOf(
                    Intent(AlarmClock.ACTION_SHOW_ALARMS),
                    Intent(AlarmClock.ACTION_SET_ALARM)
                ),
                knownPackages = KNOWN_ALARM_PACKAGES
            )
        }
        return cachedAlarmPackages
    }

    fun isPackageAllowedDuringLock(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return true
        // Settings, installers, and Play Store are ALWAYS blocked while locked — even during a 5-min break
        if (TAMPER_PACKAGES.contains(packageName)) return false
        if (packageName == appContext.packageName) return true
        if (cachedAlarmPackages.contains(packageName)) return true
        if (cachedDialerPackages.contains(packageName)) return true
        // During an active 5-minute hourly break, all other non-tamper apps are temporarily allowed
        if (_state.value.isBreakActive) return true
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
     * Grants a single 5-minute break (or 10s in Test Mode) once per hour of lock time.
     * Tamper-sensitive packages (Settings, installers) remain strictly blocked during the break.
     */
    fun startHourlyBreak(): Boolean {
        val current = refreshState(triggerEnforcement = false)
        if (!current.isLocked || !current.canStartBreak || current.isBreakActive) return false

        val session = prefs.loadSession()
        if (!session.active) return false

        val nowWallMs = System.currentTimeMillis()
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val rawBreakDuration = if (session.isTestMode) {
            ScheduleCalculator.TEST_BREAK_DURATION_MS
        } else {
            ScheduleCalculator.HOURLY_BREAK_DURATION_MS
        }
        val breakDurationMs = rawBreakDuration.coerceAtMost(current.remainingMillis)
        if (breakDurationMs < 5_000L) return false

        val updatedSession = session.copy(
            breakActive = true,
            breakStartedWallMs = nowWallMs,
            breakDurationMs = breakDurationMs,
            breakStartedElapsedMs = nowElapsedMs,
            bootCountAtBreakStart = getCurrentBootCount()
        )
        prefs.saveSession(updatedSession)
        SleepScheduler.scheduleBreakEndAlarm(appContext, nowWallMs + breakDurationMs)
        SleepOverlayManager.hide(appContext)
        refreshState(triggerEnforcement = false)
        return true
    }

    /**
     * Ends an active 5-minute break early and immediately re-engages the full lock screen.
     */
    fun endBreakEarly() {
        val session = prefs.loadSession()
        if (!session.active || !session.breakActive) return
        val nowWallMs = System.currentTimeMillis()
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val updatedSession = session.copy(
            breakActive = false,
            breakStartedWallMs = 0L,
            breakDurationMs = 0L,
            breakStartedElapsedMs = 0L,
            bootCountAtBreakStart = -1,
            lastBreakEndedWallMs = nowWallMs,
            lastBreakEndedElapsedMs = nowElapsedMs,
            bootCountAtLastBreakEnd = getCurrentBootCount()
        )
        prefs.saveSession(updatedSession)
        SleepScheduler.cancelBreakEndAlarm(appContext)
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
                SleepScheduler.cancelBreakEndAlarm(appContext)
                SleepOverlayManager.hide(appContext)
                SleepLockForegroundService.stop(appContext)
                SleepScheduler.syncAlarms(appContext)
            } else {
                var breakStatus = ScheduleCalculator.computeBreakStatus(
                    hourlyBreakEnabled = schedule.hourlyBreakEnabled,
                    isTestMode = session.isTestMode,
                    remainingLockMillis = remaining,
                    lockStartedWallMs = session.lockStartedWallMs,
                    lockStartedElapsedMs = session.lockStartedElapsedMs,
                    bootCountAtLock = session.bootCountAtLock,
                    breakActive = session.breakActive,
                    breakStartedWallMs = session.breakStartedWallMs,
                    breakDurationMs = session.breakDurationMs,
                    breakStartedElapsedMs = session.breakStartedElapsedMs,
                    bootCountAtBreakStart = session.bootCountAtBreakStart,
                    lastBreakEndedWallMs = session.lastBreakEndedWallMs,
                    lastBreakEndedElapsedMs = session.lastBreakEndedElapsedMs,
                    bootCountAtLastBreakEnd = session.bootCountAtLastBreakEnd,
                    currentWallMs = nowWallMs,
                    currentElapsedMs = nowElapsedMs,
                    currentBootCount = bootCount
                )

                // If break was active and just expired, close it out and re-lock immediately
                if (session.breakActive && !breakStatus.isBreakActive) {
                    val closedSession = session.copy(
                        breakActive = false,
                        breakStartedWallMs = 0L,
                        breakDurationMs = 0L,
                        breakStartedElapsedMs = 0L,
                        bootCountAtBreakStart = -1,
                        lastBreakEndedWallMs = nowWallMs,
                        lastBreakEndedElapsedMs = nowElapsedMs,
                        bootCountAtLastBreakEnd = bootCount
                    )
                    prefs.saveSession(closedSession)
                    SleepScheduler.cancelBreakEndAlarm(appContext)
                    breakStatus = ScheduleCalculator.computeBreakStatus(
                        hourlyBreakEnabled = schedule.hourlyBreakEnabled,
                        isTestMode = closedSession.isTestMode,
                        remainingLockMillis = remaining,
                        lockStartedWallMs = closedSession.lockStartedWallMs,
                        lockStartedElapsedMs = closedSession.lockStartedElapsedMs,
                        bootCountAtLock = closedSession.bootCountAtLock,
                        breakActive = false,
                        breakStartedWallMs = 0L,
                        breakDurationMs = 0L,
                        breakStartedElapsedMs = 0L,
                        bootCountAtBreakStart = -1,
                        lastBreakEndedWallMs = nowWallMs,
                        lastBreakEndedElapsedMs = nowElapsedMs,
                        bootCountAtLastBreakEnd = bootCount,
                        currentWallMs = nowWallMs,
                        currentElapsedMs = nowElapsedMs,
                        currentBootCount = bootCount
                    )
                    if (triggerEnforcement) {
                        launchLockScreen(appContext)
                    }
                }

                val uiState = LockUiState(
                    isLocked = true,
                    isTestMode = session.isTestMode,
                    unlockAtMillis = session.lockUntilWallMs,
                    remainingMillis = remaining,
                    schedule = schedule,
                    isScheduleEditFrozen = true,
                    allowedAlarmPackages = cachedAlarmPackages,
                    allowedDialerPackages = cachedDialerPackages,
                    isBreakActive = breakStatus.isBreakActive,
                    breakRemainingMillis = breakStatus.breakRemainingMillis,
                    canStartBreak = breakStatus.canStartBreak,
                    nextBreakInMillis = breakStatus.nextBreakInMillis
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
            allowedAlarmPackages = cachedAlarmPackages,
            allowedDialerPackages = cachedDialerPackages
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

    fun openPhoneDialer(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun discoverPackages(
        context: Context,
        intents: List<Intent>,
        knownPackages: Set<String>
    ): Set<String> {
        val discovered = mutableSetOf<String>()
        val pm = context.packageManager

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

        // Also keep any known OEM packages that are installed on this device
        for (pkg in knownPackages) {
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
            discovered.addAll(knownPackages)
        }
        return discovered
    }
}

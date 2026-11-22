package com.nightlock.app.data

import android.content.Context
import android.content.SharedPreferences
import java.time.DayOfWeek

data class LockSession(
    val active: Boolean = false,
    val isTestMode: Boolean = false,
    val lockStartedWallMs: Long = 0L,
    val lockUntilWallMs: Long = 0L,
    val lockDurationMs: Long = 0L,
    val lockStartedElapsedMs: Long = 0L,
    val bootCountAtLock: Int = -1
)

class SleepPreferences(context: Context) {

    // Direct-boot-aware storage ensures lock state is readable immediately after reboot
    private val prefs: SharedPreferences = context
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSchedule(): SleepSchedule {
        val daysRaw = prefs.getStringSet(KEY_ACTIVE_DAYS, null)
        val days = daysRaw?.mapNotNull { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }?.toSet()
            ?: DayOfWeek.entries.toSet()

        return SleepSchedule(
            enabled = prefs.getBoolean(KEY_SCHEDULE_ENABLED, true),
            bedtimeHour = prefs.getInt(KEY_BEDTIME_HOUR, 22),
            bedtimeMinute = prefs.getInt(KEY_BEDTIME_MINUTE, 30),
            wakeHour = prefs.getInt(KEY_WAKE_HOUR, 6),
            wakeMinute = prefs.getInt(KEY_WAKE_MINUTE, 30),
            activeDays = days,
            freezeBeforeBedtimeMinutes = prefs.getInt(KEY_FREEZE_MINUTES, 30)
        )
    }

    fun saveSchedule(schedule: SleepSchedule) {
        prefs.edit()
            .putBoolean(KEY_SCHEDULE_ENABLED, schedule.enabled)
            .putInt(KEY_BEDTIME_HOUR, schedule.bedtimeHour)
            .putInt(KEY_BEDTIME_MINUTE, schedule.bedtimeMinute)
            .putInt(KEY_WAKE_HOUR, schedule.wakeHour)
            .putInt(KEY_WAKE_MINUTE, schedule.wakeMinute)
            .putStringSet(KEY_ACTIVE_DAYS, schedule.activeDays.map { it.name }.toSet())
            .putInt(KEY_FREEZE_MINUTES, schedule.freezeBeforeBedtimeMinutes)
            .commit()
    }

    fun loadSession(): LockSession {
        return LockSession(
            active = prefs.getBoolean(KEY_LOCK_ACTIVE, false),
            isTestMode = prefs.getBoolean(KEY_LOCK_TEST_MODE, false),
            lockStartedWallMs = prefs.getLong(KEY_LOCK_STARTED_WALL, 0L),
            lockUntilWallMs = prefs.getLong(KEY_LOCK_UNTIL_WALL, 0L),
            lockDurationMs = prefs.getLong(KEY_LOCK_DURATION, 0L),
            lockStartedElapsedMs = prefs.getLong(KEY_LOCK_STARTED_ELAPSED, 0L),
            bootCountAtLock = prefs.getInt(KEY_LOCK_BOOT_COUNT, -1)
        )
    }

    fun saveSession(session: LockSession) {
        prefs.edit()
            .putBoolean(KEY_LOCK_ACTIVE, session.active)
            .putBoolean(KEY_LOCK_TEST_MODE, session.isTestMode)
            .putLong(KEY_LOCK_STARTED_WALL, session.lockStartedWallMs)
            .putLong(KEY_LOCK_UNTIL_WALL, session.lockUntilWallMs)
            .putLong(KEY_LOCK_DURATION, session.lockDurationMs)
            .putLong(KEY_LOCK_STARTED_ELAPSED, session.lockStartedElapsedMs)
            .putInt(KEY_LOCK_BOOT_COUNT, session.bootCountAtLock)
            .commit()
    }

    fun clearSession() {
        saveSession(LockSession(active = false))
    }

    companion object {
        private const val PREFS_NAME = "nightlock_direct_boot_prefs"
        private const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
        private const val KEY_BEDTIME_HOUR = "bedtime_hour"
        private const val KEY_BEDTIME_MINUTE = "bedtime_minute"
        private const val KEY_WAKE_HOUR = "wake_hour"
        private const val KEY_WAKE_MINUTE = "wake_minute"
        private const val KEY_ACTIVE_DAYS = "active_days"
        private const val KEY_FREEZE_MINUTES = "freeze_minutes"

        private const val KEY_LOCK_ACTIVE = "lock_active"
        private const val KEY_LOCK_TEST_MODE = "lock_test_mode"
        private const val KEY_LOCK_STARTED_WALL = "lock_started_wall"
        private const val KEY_LOCK_UNTIL_WALL = "lock_until_wall"
        private const val KEY_LOCK_DURATION = "lock_duration"
        private const val KEY_LOCK_STARTED_ELAPSED = "lock_started_elapsed"
        private const val KEY_LOCK_BOOT_COUNT = "lock_boot_count"
    }
}

package com.nightlock.app.data

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

data class SleepSchedule(
    val enabled: Boolean = true,
    val bedtimeHour: Int = 22,
    val bedtimeMinute: Int = 30,
    val wakeHour: Int = 6,
    val wakeMinute: Int = 30,
    val activeDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val freezeBeforeBedtimeMinutes: Int = 30,
    val hourlyBreakEnabled: Boolean = true
) {
    val bedtime: LocalTime get() = LocalTime.of(bedtimeHour, bedtimeMinute)
    val wakeTime: LocalTime get() = LocalTime.of(wakeHour, wakeMinute)
    val isOvernight: Boolean get() = bedtime.isAfter(wakeTime)
}

data class BreakStatus(
    val isBreakActive: Boolean = false,
    val breakRemainingMillis: Long = 0L,
    val canStartBreak: Boolean = false,
    val nextBreakInMillis: Long = 0L
)

data class ActiveWindow(
    val startMillis: Long,
    val endMillis: Long
)

object ScheduleCalculator {
    const val MIN_LOCK_DURATION_MS = 60_000L // 1 minute (allows safe Test Mode)
    const val MAX_LOCK_DURATION_MS = 16L * 60L * 60L * 1000L // 16-hour hard safety cap
    const val HOURLY_BREAK_COOLDOWN_MS = 60L * 60L * 1000L // 1 hour between breaks
    const val HOURLY_BREAK_DURATION_MS = 5L * 60L * 1000L // 5-minute break
    const val TEST_BREAK_COOLDOWN_MS = 15L * 1000L // 15s cooldown in 60s test mode
    const val TEST_BREAK_DURATION_MS = 10L * 1000L // 10s break in 60s test mode

    /**
     * Returns the active [ActiveWindow] if [now] falls inside [schedule], or null otherwise.
     */
    fun getActiveWindow(schedule: SleepSchedule, now: ZonedDateTime): ActiveWindow? {
        if (!schedule.enabled || schedule.activeDays.isEmpty()) return null
        if (schedule.bedtime == schedule.wakeTime) return null

        val today = now.toLocalDate()
        val zone = now.zone

        if (schedule.isOvernight) {
            // Case 1: Late evening (e.g., 22:30..23:59) -> started today, ends tomorrow at wakeTime
            val todayStart = today.atTime(schedule.bedtime).atZone(zone)
            val tomorrowEnd = today.plusDays(1).atTime(schedule.wakeTime).atZone(zone)
            if (schedule.activeDays.contains(today.dayOfWeek) &&
                !now.isBefore(todayStart) && now.isBefore(tomorrowEnd)
            ) {
                return clampWindow(todayStart.toInstant().toEpochMilli(), tomorrowEnd.toInstant().toEpochMilli())
            }

            // Case 2: Early morning (e.g., 00:00..06:30) -> started yesterday, ends today at wakeTime
            val yesterday = today.minusDays(1)
            val yesterdayStart = yesterday.atTime(schedule.bedtime).atZone(zone)
            val todayEnd = today.atTime(schedule.wakeTime).atZone(zone)
            if (schedule.activeDays.contains(yesterday.dayOfWeek) &&
                !now.isBefore(yesterdayStart) && now.isBefore(todayEnd)
            ) {
                return clampWindow(yesterdayStart.toInstant().toEpochMilli(), todayEnd.toInstant().toEpochMilli())
            }
        } else {
            // Same-day sleep window (e.g., 08:00..16:00 for night-shift schedules)
            val todayStart = today.atTime(schedule.bedtime).atZone(zone)
            val todayEnd = today.atTime(schedule.wakeTime).atZone(zone)
            if (schedule.activeDays.contains(today.dayOfWeek) &&
                !now.isBefore(todayStart) && now.isBefore(todayEnd)
            ) {
                return clampWindow(todayStart.toInstant().toEpochMilli(), todayEnd.toInstant().toEpochMilli())
            }
        }
        return null
    }

    /**
     * Returns the epoch millis of the next upcoming bedtime strictly after [now], or null if disabled.
     */
    fun getNextBedtimeMillis(schedule: SleepSchedule, now: ZonedDateTime): Long? {
        if (!schedule.enabled || schedule.activeDays.isEmpty()) return null
        if (schedule.bedtime == schedule.wakeTime) return null

        val zone = now.zone
        val today = now.toLocalDate()
        for (offset in 0L..7L) {
            val date = today.plusDays(offset)
            if (!schedule.activeDays.contains(date.dayOfWeek)) continue
            val candidate = date.atTime(schedule.bedtime).atZone(zone)
            if (candidate.isAfter(now)) {
                return candidate.toInstant().toEpochMilli()
            }
        }
        return null
    }

    /**
     * Returns true when we are within [SleepSchedule.freezeBeforeBedtimeMinutes] of the next bedtime,
     * preventing last-minute procrastination edits right before sleep.
     */
    fun isScheduleEditFrozen(schedule: SleepSchedule, now: ZonedDateTime): Boolean {
        if (!schedule.enabled || schedule.freezeBeforeBedtimeMinutes <= 0) return false
        if (getActiveWindow(schedule, now) != null) return true
        val nextBedtime = getNextBedtimeMillis(schedule, now) ?: return false
        val nowMs = now.toInstant().toEpochMilli()
        val diffMs = nextBedtime - nowMs
        return diffMs in 1..(schedule.freezeBeforeBedtimeMinutes * 60_000L)
    }

    /**
     * Computes target wake timestamp for a manual "Lock Now" until [wakeHour]:[wakeMinute].
     * Always picks the next occurrence of that time, clamped to [MAX_LOCK_DURATION_MS].
     */
    fun computeManualLockTargetMillis(
        now: ZonedDateTime,
        wakeHour: Int,
        wakeMinute: Int
    ): Long {
        val zone = now.zone
        val today = now.toLocalDate()
        val wakeLocalTime = LocalTime.of(wakeHour, wakeMinute)
        var target = today.atTime(wakeLocalTime).atZone(zone)
        if (!target.isAfter(now.plusSeconds(30))) {
            target = today.plusDays(1).atTime(wakeLocalTime).atZone(zone)
        }
        val nowMs = now.toInstant().toEpochMilli()
        val duration = (target.toInstant().toEpochMilli() - nowMs)
            .coerceIn(MIN_LOCK_DURATION_MS, MAX_LOCK_DURATION_MS)
        return nowMs + duration
    }

    /**
     * Computes remaining lock time in milliseconds using monotonic [elapsedRealtimeMs] when on the
     * same boot, so changing the system wall clock cannot bypass an active lock.
     * Falls back to wall clock only if the tablet was rebooted during the lock.
     */
    fun computeRemainingMillis(
        lockStartedWallMs: Long,
        lockUntilWallMs: Long,
        lockDurationMs: Long,
        lockStartedElapsedMs: Long,
        bootCountAtLock: Int,
        currentWallMs: Long,
        currentElapsedMs: Long,
        currentBootCount: Int
    ): Long {
        val safeDuration = lockDurationMs.coerceIn(MIN_LOCK_DURATION_MS, MAX_LOCK_DURATION_MS)
        val sameBoot = bootCountAtLock >= 0 &&
            currentBootCount == bootCountAtLock &&
            currentElapsedMs >= lockStartedElapsedMs

        return if (sameBoot) {
            val elapsed = currentElapsedMs - lockStartedElapsedMs
            (safeDuration - elapsed).coerceAtLeast(0L)
        } else {
            // After reboot, elapsedRealtime resets. Protect against clock moving backwards too.
            if (currentWallMs < lockStartedWallMs) {
                safeDuration
            } else {
                (lockUntilWallMs - currentWallMs).coerceIn(0L, safeDuration)
            }
        }
    }

    private fun clampWindow(startMs: Long, endMs: Long): ActiveWindow {
        val duration = (endMs - startMs).coerceIn(MIN_LOCK_DURATION_MS, MAX_LOCK_DURATION_MS)
        return ActiveWindow(startMs, startMs + duration)
    }

    /**
     * Computes whether a 5-minute hourly break is currently active or available.
     * Uses monotonic [currentElapsedMs] on the same boot so wall-clock changes cannot bypass cooldowns.
     */
    fun computeBreakStatus(
        hourlyBreakEnabled: Boolean,
        isTestMode: Boolean,
        remainingLockMillis: Long,
        lockStartedWallMs: Long,
        lockStartedElapsedMs: Long,
        bootCountAtLock: Int,
        breakActive: Boolean,
        breakStartedWallMs: Long,
        breakDurationMs: Long,
        breakStartedElapsedMs: Long,
        bootCountAtBreakStart: Int,
        lastBreakEndedWallMs: Long,
        lastBreakEndedElapsedMs: Long,
        bootCountAtLastBreakEnd: Int,
        currentWallMs: Long,
        currentElapsedMs: Long,
        currentBootCount: Int
    ): BreakStatus {
        if (!hourlyBreakEnabled || remainingLockMillis <= 0L) {
            return BreakStatus()
        }

        if (breakActive && breakDurationMs > 0L) {
            val sameBoot = bootCountAtBreakStart >= 0 &&
                currentBootCount == bootCountAtBreakStart &&
                currentElapsedMs >= breakStartedElapsedMs
            val elapsed = if (sameBoot) {
                currentElapsedMs - breakStartedElapsedMs
            } else {
                if (currentWallMs < breakStartedWallMs) 0L else currentWallMs - breakStartedWallMs
            }
            val breakRemaining = (breakDurationMs - elapsed).coerceIn(0L, remainingLockMillis)
            if (breakRemaining > 0L) {
                return BreakStatus(
                    isBreakActive = true,
                    breakRemainingMillis = breakRemaining,
                    canStartBreak = false,
                    nextBreakInMillis = 0L
                )
            }
        }

        val cooldownMs = if (isTestMode) TEST_BREAK_COOLDOWN_MS else HOURLY_BREAK_COOLDOWN_MS
        val hasPreviousBreak = lastBreakEndedWallMs > 0L || lastBreakEndedElapsedMs > 0L
        val anchorBoot = if (hasPreviousBreak) bootCountAtLastBreakEnd else bootCountAtLock
        val anchorElapsed = if (hasPreviousBreak) lastBreakEndedElapsedMs else lockStartedElapsedMs
        val anchorWall = if (hasPreviousBreak) lastBreakEndedWallMs else lockStartedWallMs

        val sameBoot = anchorBoot >= 0 &&
            currentBootCount == anchorBoot &&
            currentElapsedMs >= anchorElapsed
        val elapsedSinceAnchor = if (sameBoot) {
            currentElapsedMs - anchorElapsed
        } else {
            if (currentWallMs < anchorWall) 0L else currentWallMs - anchorWall
        }

        val nextBreakIn = (cooldownMs - elapsedSinceAnchor).coerceAtLeast(0L)
        return BreakStatus(
            isBreakActive = false,
            breakRemainingMillis = 0L,
            canStartBreak = nextBreakIn == 0L && remainingLockMillis > 5_000L,
            nextBreakInMillis = nextBreakIn
        )
    }

    fun formatDuration(millis: Long): String {
        val clamped = millis.coerceAtLeast(0L)
        val duration = Duration.ofMillis(clamped)
        val hours = duration.toHours()
        val minutes = duration.toMinutes() % 60
        val seconds = duration.seconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }
}

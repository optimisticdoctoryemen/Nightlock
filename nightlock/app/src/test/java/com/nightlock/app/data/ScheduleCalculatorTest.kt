package com.nightlock.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleCalculatorTest {

    private val zone = ZoneId.of("UTC")

    @Test
    fun overnightScheduleActiveLateEveningAndEarlyMorning() {
        val schedule = SleepSchedule(
            enabled = true,
            bedtimeHour = 22,
            bedtimeMinute = 30,
            wakeHour = 6,
            wakeMinute = 30,
            activeDays = setOf(DayOfWeek.MONDAY)
        )

        // Monday 22:29 -> not active yet
        val before = ZonedDateTime.of(2026, 11, 23, 22, 29, 0, 0, zone)
        assertNull(ScheduleCalculator.getActiveWindow(schedule, before))

        // Monday 22:30 -> active until Tuesday 06:30
        val atBedtime = ZonedDateTime.of(2026, 11, 23, 22, 30, 0, 0, zone)
        val eveningWindow = ScheduleCalculator.getActiveWindow(schedule, atBedtime)
        assertNotNull(eveningWindow)
        val expectedEnd = ZonedDateTime.of(2026, 11, 24, 6, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expectedEnd, eveningWindow!!.endMillis)

        // Tuesday 02:15 -> still active from Monday night!
        val earlyMorning = ZonedDateTime.of(2026, 11, 24, 2, 15, 0, 0, zone)
        val morningWindow = ScheduleCalculator.getActiveWindow(schedule, earlyMorning)
        assertNotNull(morningWindow)
        assertEquals(expectedEnd, morningWindow!!.endMillis)

        // Tuesday 06:30 -> unlocked!
        val wake = ZonedDateTime.of(2026, 11, 24, 6, 30, 0, 0, zone)
        assertNull(ScheduleCalculator.getActiveWindow(schedule, wake))
    }

    @Test
    fun preBedtimeFreezeBlocksLastMinuteEdits() {
        val schedule = SleepSchedule(
            enabled = true,
            bedtimeHour = 22,
            bedtimeMinute = 30,
            wakeHour = 6,
            wakeMinute = 30,
            freezeBeforeBedtimeMinutes = 30
        )

        val at2155 = ZonedDateTime.of(2026, 11, 23, 21, 55, 0, 0, zone)
        assertFalse(ScheduleCalculator.isScheduleEditFrozen(schedule, at2155))

        val at2205 = ZonedDateTime.of(2026, 11, 23, 22, 5, 0, 0, zone)
        assertTrue(ScheduleCalculator.isScheduleEditFrozen(schedule, at2205))
    }

    @Test
    fun monotonicTimerResistsWallClockTampering() {
        val startWall = 1_700_000_000_000L
        val duration = 8L * 60L * 60L * 1000L // 8 hours
        val untilWall = startWall + duration
        val startElapsed = 100_000L

        // User somehow jumped wall clock forward by 12 hours, but only 10 minutes actually elapsed
        val tamperedWall = startWall + 12L * 60L * 60L * 1000L
        val actualElapsed = startElapsed + 10L * 60L * 1000L

        val remaining = ScheduleCalculator.computeRemainingMillis(
            lockStartedWallMs = startWall,
            lockUntilWallMs = untilWall,
            lockDurationMs = duration,
            lockStartedElapsedMs = startElapsed,
            bootCountAtLock = 5,
            currentWallMs = tamperedWall,
            currentElapsedMs = actualElapsed,
            currentBootCount = 5
        )

        assertEquals(duration - 10L * 60L * 1000L, remaining)
    }

    @Test
    fun manualLockTargetClampsSafetyCap() {
        // Locking at 07:00 until 06:30 tomorrow is 23.5h -> clamped to 16h hard safety cap
        val now = ZonedDateTime.of(2026, 11, 23, 7, 0, 0, 0, zone)
        val target = ScheduleCalculator.computeManualLockTargetMillis(now, 6, 30)
        val diff = target - now.toInstant().toEpochMilli()
        assertEquals(ScheduleCalculator.MAX_LOCK_DURATION_MS, diff)
    }
}

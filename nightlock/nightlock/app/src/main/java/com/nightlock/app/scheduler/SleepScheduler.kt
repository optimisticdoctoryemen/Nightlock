package com.nightlock.app.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.nightlock.app.data.ScheduleCalculator
import com.nightlock.app.data.SleepPreferences
import com.nightlock.app.receiver.ScheduleAlarmReceiver
import com.nightlock.app.ui.MainActivity
import java.time.ZonedDateTime

object SleepScheduler {

    const val ACTION_BEDTIME_START = "com.nightlock.app.ACTION_BEDTIME_START"
    const val ACTION_WAKE_UNLOCK = "com.nightlock.app.ACTION_WAKE_UNLOCK"
    const val ACTION_BREAK_END = "com.nightlock.app.ACTION_BREAK_END"

    private const val REQ_BEDTIME = 1001
    private const val REQ_UNLOCK = 1002
    private const val REQ_BREAK_END = 1003

    fun syncAlarms(context: Context) {
        val prefs = SleepPreferences(context)
        val schedule = prefs.loadSchedule()
        val session = prefs.loadSession()

        // 1. Schedule next automatic bedtime trigger
        val nextBedtime = ScheduleCalculator.getNextBedtimeMillis(schedule, ZonedDateTime.now())
        if (nextBedtime != null) {
            scheduleExact(context, nextBedtime, ACTION_BEDTIME_START, REQ_BEDTIME)
        } else {
            cancelAlarm(context, ACTION_BEDTIME_START, REQ_BEDTIME)
        }

        // 2. Schedule morning unlock trigger if currently locked
        if (session.active && session.lockUntilWallMs > System.currentTimeMillis()) {
            scheduleUnlockAlarm(context, session.lockUntilWallMs)
        } else {
            cancelAlarm(context, ACTION_WAKE_UNLOCK, REQ_UNLOCK)
            cancelBreakEndAlarm(context)
        }
    }

    fun scheduleUnlockAlarm(context: Context, triggerAtMillis: Long) {
        scheduleExact(context, triggerAtMillis, ACTION_WAKE_UNLOCK, REQ_UNLOCK)
    }

    fun scheduleBreakEndAlarm(context: Context, triggerAtMillis: Long) {
        scheduleExact(context, triggerAtMillis, ACTION_BREAK_END, REQ_BREAK_END)
    }

    fun cancelBreakEndAlarm(context: Context) {
        cancelAlarm(context, ACTION_BREAK_END, REQ_BREAK_END)
    }

    private fun scheduleExact(context: Context, triggerAtMillis: Long, action: String, requestCode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            this.action = action
        }
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val canUseExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        if (canUseExact) {
            // setAlarmClock survives Doze mode reliably on Samsung/Xiaomi/Lenovo/Pixel tablets
            val showIntent = PendingIntent.getActivity(
                context,
                requestCode + 10,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val clockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent)
            runCatching {
                alarmManager.setAlarmClock(clockInfo, pi)
            }.onFailure {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    private fun cancelAlarm(context: Context, action: String, requestCode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            this.action = action
        }
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            alarmManager.cancel(pi)
            pi.cancel()
        }
    }
}

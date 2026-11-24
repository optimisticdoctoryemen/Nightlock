package com.nightlock.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.nightlock.app.R
import com.nightlock.app.data.LockManager
import com.nightlock.app.data.ScheduleCalculator
import com.nightlock.app.ui.LockScreenActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SleepLockForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private var tickCount = 0
    private var wasBreakActive = false

    private val heartbeat = object : Runnable {
        override fun run() {
            val state = LockManager.refreshState(triggerEnforcement = true)
            if (!state.isLocked) {
                SleepOverlayManager.hide(this@SleepLockForegroundService)
                stopSelf()
                return
            }

            if (wasBreakActive && !state.isBreakActive) {
                LockManager.launchLockScreen(this@SleepLockForegroundService)
            }
            wasBreakActive = state.isBreakActive

            val updateInterval = if (state.isBreakActive) 5 else 30
            if (tickCount % updateInterval == 0) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(
                    NOTIFICATION_ID,
                    buildNotification(
                        unlockAtMillis = state.unlockAtMillis,
                        remainingMillis = state.remainingMillis,
                        isBreakActive = state.isBreakActive,
                        breakRemainingMillis = state.breakRemainingMillis
                    )
                )
            }
            tickCount++
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        LockManager.init(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = LockManager.refreshState(triggerEnforcement = false)
        if (!state.isLocked) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification(
            unlockAtMillis = state.unlockAtMillis,
            remainingMillis = state.remainingMillis,
            isBreakActive = state.isBreakActive,
            breakRemainingMillis = state.breakRemainingMillis
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)

        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NightLock Sleep Enforcement",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps bedtime lock active until morning wake time"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(
        unlockAtMillis: Long,
        remainingMillis: Long,
        isBreakActive: Boolean,
        breakRemainingMillis: Long
    ): Notification {
        val unlockText = if (unlockAtMillis > 0L) {
            Instant.ofEpochMilli(unlockAtMillis)
                .atZone(ZoneId.systemDefault())
                .format(timeFormatter)
        } else {
            "morning"
        }

        val openLockIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, LockScreenActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isBreakActive) "NightLock — Urgent Break" else "NightLock Active"
        val text = if (isBreakActive) {
            "Break ends in ${ScheduleCalculator.formatDuration(breakRemainingMillis)} (Settings still blocked)"
        } else {
            "Unlocks at $unlockText (${ScheduleCalculator.formatDuration(remainingMillis)} left)"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_nightlock)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openLockIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "nightlock_enforcement_channel"
        private const val NOTIFICATION_ID = 4201

        fun start(context: Context) {
            val intent = Intent(context, SleepLockForegroundService::class.java)
            runCatching {
                context.startForegroundService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SleepLockForegroundService::class.java)
            runCatching {
                context.stopService(intent)
            }
        }
    }
}

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

    private val heartbeat = object : Runnable {
        override fun run() {
            val state = LockManager.refreshState(triggerEnforcement = false)
            if (!state.isLocked) {
                SleepOverlayManager.hide(this@SleepLockForegroundService)
                stopSelf()
                return
            }

            // Update notification every 30 seconds to keep battery usage minimal
            if (tickCount % 30 == 0) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(state.unlockAtMillis, state.remainingMillis))
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

        val notification = buildNotification(state.unlockAtMillis, state.remainingMillis)
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

    private fun buildNotification(unlockAtMillis: Long, remainingMillis: Long): Notification {
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_nightlock)
            .setContentTitle("NightLock Active")
            .setContentText("Unlocks at $unlockText (${ScheduleCalculator.formatDuration(remainingMillis)} left)")
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

package com.nightlock.app.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.nightlock.app.data.LockManager
import com.nightlock.app.data.ScheduleCalculator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Instant opaque dark shield overlay shown via SYSTEM_ALERT_WINDOW the millisecond a blocked app
 * or Settings attempts to appear. Automatically hides once LockScreenActivity or an allowed alarm
 * clock app is cleanly in the foreground.
 */
object SleepOverlayManager {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var statusTextView: TextView? = null
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun show(context: Context) {
        val appContext = context.applicationContext
        if (!Settings.canDrawOverlays(appContext)) return

        mainHandler.post {
            val state = LockManager.state.value
            if (!state.isLocked) {
                hideInternal(appContext)
                return@post
            }

            if (overlayView != null) {
                updateText(state.unlockAtMillis, state.remainingMillis)
                return@post
            }

            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val container = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#FF05070C"))
                setPadding(64, 64, 64, 64)
                isClickable = true
                isFocusable = true
            }

            val title = TextView(appContext).apply {
                text = "Time to Sleep"
                setTextColor(Color.parseColor("#FFE2E8F0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                gravity = Gravity.CENTER
            }

            val subtitle = TextView(appContext).apply {
                setTextColor(Color.parseColor("#FF94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
                setPadding(0, 24, 0, 0)
            }
            statusTextView = subtitle
            updateText(state.unlockAtMillis, state.remainingMillis)

            container.addView(title)
            container.addView(subtitle)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE
            ).apply {
                gravity = Gravity.CENTER
            }

            runCatching {
                wm.addView(container, params)
                overlayView = container
            }
        }
    }

    fun hide(context: Context) {
        val appContext = context.applicationContext
        mainHandler.post { hideInternal(appContext) }
    }

    private fun hideInternal(context: Context) {
        val view = overlayView ?: return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeViewImmediate(view) }
        overlayView = null
        statusTextView = null
    }

    private fun updateText(unlockAtMillis: Long, remainingMillis: Long) {
        val unlockTime = if (unlockAtMillis > 0L) {
            Instant.ofEpochMilli(unlockAtMillis)
                .atZone(ZoneId.systemDefault())
                .format(timeFormatter)
        } else {
            "--:--"
        }
        statusTextView?.text = "Locked until $unlockTime  •  ${ScheduleCalculator.formatDuration(remainingMillis)} remaining"
    }
}

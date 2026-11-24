package com.nightlock.app.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nightlock.app.data.LockManager
import com.nightlock.app.data.ScheduleCalculator
import com.nightlock.app.service.SleepOverlayManager
import com.nightlock.app.ui.theme.NightAmber
import com.nightlock.app.ui.theme.NightIndigo
import com.nightlock.app.ui.theme.NightLockTheme
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Full-screen dark sleep clock shown during bedtime lockdown.
 * Intentionally has NO unlock, cancel, snooze, or settings buttons.
 * Only permits opening the tablet's alarm clock app so morning alarms work reliably.
 */
class LockScreenActivity : ComponentActivity() {

    private var extraDimMode = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LockManager.init(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }

        applyImmersiveAndBrightness(dim = true)

        // Completely disable hardware/gesture Back navigation while locked
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!LockManager.isCurrentlyLocked()) {
                        finish()
                    }
                }
            }
        )

        setContent {
            NightLockTheme {
                val lockState by LockManager.state.collectAsState()
                var now by remember { mutableStateOf(LocalDateTime.now()) }
                val isExtraDim by extraDimMode

                // Refresh clock and check unlock status every second
                LaunchedEffect(Unit) {
                    while (true) {
                        val updated = LockManager.refreshState(triggerEnforcement = false)
                        now = LocalDateTime.now()
                        if (!updated.isLocked) {
                            SleepOverlayManager.hide(this@LockScreenActivity)
                            finish()
                            break
                        }
                        delay(1000L)
                    }
                }

                SleepClockContent(
                    now = now,
                    unlockAtMillis = lockState.unlockAtMillis,
                    remainingMillis = lockState.remainingMillis,
                    isTestMode = lockState.isTestMode,
                    hourlyBreakEnabled = lockState.schedule.hourlyBreakEnabled,
                    isBreakActive = lockState.isBreakActive,
                    breakRemainingMillis = lockState.breakRemainingMillis,
                    canStartBreak = lockState.canStartBreak,
                    nextBreakInMillis = lockState.nextBreakInMillis,
                    isExtraDim = isExtraDim,
                    onToggleDim = {
                        val nextDim = !extraDimMode.value
                        extraDimMode.value = nextDim
                        applyImmersiveAndBrightness(dim = nextDim)
                    },
                    onOpenAlarms = {
                        LockManager.openAlarmClock(this@LockScreenActivity)
                    },
                    onOpenPhone = {
                        LockManager.openPhoneDialer(this@LockScreenActivity)
                    },
                    onStartBreak = {
                        if (LockManager.startHourlyBreak()) {
                            moveTaskToBack(true)
                        }
                    },
                    onEndBreakEarly = {
                        LockManager.endBreakEarly()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveAndBrightness(dim = extraDimMode.value)
        val state = LockManager.refreshState(triggerEnforcement = false)
        if (!state.isLocked) {
            SleepOverlayManager.hide(this)
            finish()
        } else {
            // Lock screen is now visibly on top -> hide temporary shield overlay
            SleepOverlayManager.hide(this)
        }
    }

    private fun applyImmersiveAndBrightness(dim: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        val lp = window.attributes
        lp.screenBrightness = if (dim) 0.02f else 0.18f
        window.attributes = lp
    }
}

@Composable
private fun SleepClockContent(
    now: LocalDateTime,
    unlockAtMillis: Long,
    remainingMillis: Long,
    isTestMode: Boolean,
    hourlyBreakEnabled: Boolean,
    isBreakActive: Boolean,
    breakRemainingMillis: Long,
    canStartBreak: Boolean,
    nextBreakInMillis: Long,
    isExtraDim: Boolean,
    onToggleDim: () -> Unit,
    onOpenAlarms: () -> Unit,
    onOpenPhone: () -> Unit,
    onStartBreak: () -> Unit,
    onEndBreakEarly: () -> Unit
) {
    val clockFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEEE, MMM d") }
    val unlockTimeText = remember(unlockAtMillis) {
        if (unlockAtMillis > 0L) {
            Instant.ofEpochMilli(unlockAtMillis)
                .atZone(ZoneId.systemDefault())
                .format(clockFormatter)
        } else {
            "--:--"
        }
    }

    val clockColor = if (isExtraDim) Color(0xFF5A657D) else Color(0xFFCBD5E1)
    val mutedColor = if (isExtraDim) Color(0xFF3E475C) else Color(0xFF94A3B8)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF030408))
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isTestMode) {
                Surface(
                    color = NightAmber.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = "60-SECOND TEST LOCK",
                        color = NightAmber,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            Icon(
                imageVector = Icons.Default.Bedtime,
                contentDescription = null,
                tint = NightIndigo.copy(alpha = if (isExtraDim) 0.45f else 0.85f),
                modifier = Modifier.size(40.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = now.format(clockFormatter),
                color = clockColor,
                fontSize = 104.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-2).sp
            )

            Text(
                text = now.format(dateFormatter),
                color = mutedColor,
                fontSize = 18.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Time to sleep. Apps are locked until $unlockTimeText.",
                color = mutedColor,
                fontSize = 18.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "${ScheduleCalculator.formatDuration(remainingMillis)} remaining",
                color = NightIndigo.copy(alpha = if (isExtraDim) 0.55f else 0.9f),
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(36.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onOpenAlarms,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = mutedColor),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Alarm,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Alarm Clock")
                }

                OutlinedButton(
                    onClick = onOpenPhone,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = mutedColor),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Phone")
                }

                OutlinedButton(
                    onClick = onToggleDim,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = mutedColor),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BrightnessLow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isExtraDim) "Brighter" else "Night Dim")
                }
            }

            if (hourlyBreakEnabled) {
                Spacer(modifier = Modifier.height(24.dp))
                when {
                    isBreakActive -> {
                        Button(
                            onClick = onEndBreakEarly,
                            colors = ButtonDefaults.buttonColors(containerColor = NightAmber),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Break Active (${ScheduleCalculator.formatDuration(breakRemainingMillis)} left) — Lock Now",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    canStartBreak -> {
                        OutlinedButton(
                            onClick = onStartBreak,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NightAmber),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (isTestMode) "Use 10s Test Break" else "Use 5-Min Urgent Break (1x/hr)"
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = if (isTestMode) {
                                "10s test break available in ${ScheduleCalculator.formatDuration(nextBreakInMillis)}"
                            } else {
                                "Next 5-min break available in ${ScheduleCalculator.formatDuration(nextBreakInMillis)}"
                            },
                            color = mutedColor,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

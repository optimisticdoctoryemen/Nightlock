package com.nightlock.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nightlock.app.data.LockManager
import com.nightlock.app.data.SleepSchedule
import com.nightlock.app.scheduler.SleepScheduler
import com.nightlock.app.ui.theme.NightAmber
import com.nightlock.app.ui.theme.NightBackground
import com.nightlock.app.ui.theme.NightGreen
import com.nightlock.app.ui.theme.NightIndigo
import com.nightlock.app.ui.theme.NightLockTheme
import com.nightlock.app.ui.theme.NightRed
import com.nightlock.app.ui.theme.NightSurface
import com.nightlock.app.ui.theme.NightSurfaceVariant
import com.nightlock.app.ui.theme.NightTextMuted
import com.nightlock.app.ui.theme.NightTextPrimary
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var permissionStatusState = mutableStateOf(
        PermissionStatus(
            overlayGranted = false,
            accessibilityGranted = false,
            deviceAdminGranted = false,
            exactAlarmGranted = true,
            batteryUnrestricted = false
        )
    )

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* handled passively */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LockManager.init(this)

        if (redirectIfLocked()) return
        requestNotificationPermissionIfNeeded()
        permissionStatusState.value = PermissionHelper.checkStatus(this)

        setContent {
            NightLockTheme {
                val lockUiState by LockManager.state.collectAsState()
                val permStatus by permissionStatusState

                // If automatic bedtime fires while dashboard is open, lock immediately!
                if (lockUiState.isLocked) {
                    LockManager.launchLockScreen(this)
                    finish()
                    return@NightLockTheme
                }

                DashboardScreen(
                    schedule = lockUiState.schedule,
                    isScheduleFrozen = lockUiState.isScheduleEditFrozen,
                    allowedAlarmPackages = lockUiState.allowedAlarmPackages,
                    permStatus = permStatus,
                    onOpenOverlay = { PermissionHelper.openOverlaySettings(this) },
                    onOpenAccessibility = { PermissionHelper.openAccessibilitySettings(this) },
                    onOpenAppDetails = { PermissionHelper.openAppDetailsForRestrictedSettings(this) },
                    onOpenDeviceAdmin = { PermissionHelper.openDeviceAdminPrompt(this) },
                    onOpenExactAlarm = { PermissionHelper.openExactAlarmSettings(this) },
                    onOpenBattery = { PermissionHelper.openBatteryOptimizationSettings(this) },
                    onUpdateSchedule = { updated ->
                        LockManager.updateSchedule(updated)
                    },
                    onLockNowUntil = { hour, minute ->
                        LockManager.lockUntilMorning(hour, minute)
                        finish()
                    },
                    onStartTestLock = {
                        LockManager.startTestLock(60L)
                        finish()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (redirectIfLocked()) return
        permissionStatusState.value = PermissionHelper.checkStatus(this)
        SleepScheduler.syncAlarms(this)
    }

    private fun redirectIfLocked(): Boolean {
        val state = LockManager.refreshState(triggerEnforcement = true)
        if (state.isLocked) {
            LockManager.launchLockScreen(this)
            finish()
            return true
        }
        return false
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScreen(
    schedule: SleepSchedule,
    isScheduleFrozen: Boolean,
    allowedAlarmPackages: Set<String>,
    permStatus: PermissionStatus,
    onOpenOverlay: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onOpenDeviceAdmin: () -> Unit,
    onOpenExactAlarm: () -> Unit,
    onOpenBattery: () -> Unit,
    onUpdateSchedule: (SleepSchedule) -> Unit,
    onLockNowUntil: (Int, Int) -> Unit,
    onStartTestLock: () -> Unit
) {
    var manualWakeHour by remember(schedule.wakeHour) { mutableIntStateOf(schedule.wakeHour) }
    var manualWakeMinute by remember(schedule.wakeMinute) { mutableIntStateOf(schedule.wakeMinute) }

    var showConfirmLockDialog by remember { mutableStateOf(false) }
    var showTestLockDialog by remember { mutableStateOf(false) }
    var activeTimePicker by remember { mutableStateOf<TimePickerTarget?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NightBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(NightIndigo.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bedtime,
                            contentDescription = null,
                            tint = NightIndigo,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "NightLock",
                            color = NightTextPrimary,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Strict tablet sleep lock until morning",
                            color = NightTextMuted,
                            fontSize = 14.sp
                        )
                    }
                }

                StatusPill(
                    text = if (permStatus.allReady) "Fully Armed" else if (permStatus.coreReady) "Ready" else "Setup Needed",
                    color = if (permStatus.allReady) NightGreen else if (permStatus.coreReady) NightIndigo else NightAmber
                )
            }

            // 1. Manual "Lock Now Until Morning" Card
            Card(
                colors = CardDefaults.cardColors(containerColor = NightSurface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = NightIndigo)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Lock Now Until Morning",
                            color = NightTextPrimary,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Immediately locks all apps (except your alarm clock) until the chosen morning time. Once started, there is NO undo button.",
                        color = NightTextMuted,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = NightSurfaceVariant,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .clickable { activeTimePicker = TimePickerTarget.MANUAL_WAKE }
                                .padding(0.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                                Text("Unlock at", color = NightTextMuted, fontSize = 12.sp)
                                Text(
                                    text = formatTime(manualWakeHour, manualWakeMinute),
                                    color = NightTextPrimary,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Button(
                            onClick = { showConfirmLockDialog = true },
                            enabled = permStatus.coreReady,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NightIndigo),
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Lock Until ${formatTime(manualWakeHour, manualWakeMinute)}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { showTestLockDialog = true },
                        enabled = permStatus.coreReady,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Run Safe 60-Second Dry-Run Test")
                    }

                    if (!permStatus.coreReady) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Complete the first 3 permissions below to enable locking.",
                            color = NightAmber,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // 2. Automatic Nightly Schedule Card
            Card(
                colors = CardDefaults.cardColors(containerColor = NightSurface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Schedule, contentDescription = null, tint = NightIndigo)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Automatic Nightly Schedule",
                                color = NightTextPrimary,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Switch(
                            checked = schedule.enabled,
                            enabled = !isScheduleFrozen,
                            onCheckedChange = { enabled ->
                                onUpdateSchedule(schedule.copy(enabled = enabled))
                            }
                        )
                    }

                    if (isScheduleFrozen) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = NightAmber.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = NightAmber, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Schedule edits are frozen within ${schedule.freezeBeforeBedtimeMinutes} minutes of bedtime.",
                                    color = NightAmber,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        TimeSlotBox(
                            label = "Bedtime (Lock Starts)",
                            timeText = formatTime(schedule.bedtimeHour, schedule.bedtimeMinute),
                            enabled = schedule.enabled && !isScheduleFrozen,
                            onClick = { activeTimePicker = TimePickerTarget.SCHEDULE_BEDTIME },
                            modifier = Modifier.weight(1f)
                        )
                        TimeSlotBox(
                            label = "Morning (Unlock)",
                            timeText = formatTime(schedule.wakeHour, schedule.wakeMinute),
                            enabled = schedule.enabled && !isScheduleFrozen,
                            onClick = { activeTimePicker = TimePickerTarget.SCHEDULE_WAKE },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Active nights", color = NightTextMuted, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DayOfWeek.entries.forEach { day ->
                            val selected = schedule.activeDays.contains(day)
                            FilterChip(
                                selected = selected,
                                enabled = schedule.enabled && !isScheduleFrozen,
                                onClick = {
                                    val nextDays = if (selected && schedule.activeDays.size > 1) {
                                        schedule.activeDays - day
                                    } else {
                                        schedule.activeDays + day
                                    }
                                    onUpdateSchedule(schedule.copy(activeDays = nextDays))
                                },
                                label = {
                                    Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault()))
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = NightIndigo.copy(alpha = 0.25f),
                                    selectedLabelColor = NightTextPrimary
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = NightSurfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Pre-bedtime anti-procrastination lock",
                                color = NightTextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Freeze schedule edits 30 min before bedtime so you can't push it back",
                                color = NightTextMuted,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = schedule.freezeBeforeBedtimeMinutes > 0,
                            enabled = schedule.enabled && !isScheduleFrozen,
                            onCheckedChange = { enabled ->
                                onUpdateSchedule(
                                    schedule.copy(freezeBeforeBedtimeMinutes = if (enabled) 30 else 0)
                                )
                            }
                        )
                    }
                }
            }

            // 3. Protection Setup Checklist Card
            Card(
                colors = CardDefaults.cardColors(containerColor = NightSurface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = NightIndigo)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Lockdown Permissions",
                            color = NightTextPrimary,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    PermissionRow(
                        title = "1. Draw Over Other Apps",
                        subtitle = "Instantly shields blocked apps & Settings with zero flicker.",
                        granted = permStatus.overlayGranted,
                        actionLabel = "Grant",
                        onAction = onOpenOverlay
                    )

                    PermissionRow(
                        title = "2. Accessibility Sleep Guard",
                        subtitle = "Blocks all apps, split-screen, and Settings until morning.",
                        granted = permStatus.accessibilityGranted,
                        actionLabel = "Enable",
                        onAction = onOpenAccessibility
                    )

                    if (!permStatus.accessibilityGranted) {
                        Surface(
                            color = NightSurfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Android 13+ tip: If Accessibility says \"Restricted setting\", open App Info below → tap the top-right ⋮ menu → \"Allow restricted settings\", then return to Accessibility.",
                                    color = NightTextMuted,
                                    fontSize = 12.sp
                                )
                                TextButton(onClick = onOpenAppDetails) {
                                    Text("Open NightLock App Info", color = NightIndigo)
                                }
                            }
                        }
                    }

                    PermissionRow(
                        title = "3. Uninstall Shield (Device Admin)",
                        subtitle = "Disables uninstalling NightLock while Settings is blocked.",
                        granted = permStatus.deviceAdminGranted,
                        actionLabel = "Activate",
                        onAction = onOpenDeviceAdmin
                    )

                    PermissionRow(
                        title = "4. Exact Alarms (Recommended)",
                        subtitle = "Fires bedtime lock & morning unlock right on time in Doze mode.",
                        granted = permStatus.exactAlarmGranted,
                        actionLabel = "Allow",
                        onAction = onOpenExactAlarm
                    )

                    PermissionRow(
                        title = "5. Unrestricted Battery (Recommended)",
                        subtitle = "Prevents tablet OEMs (Samsung/Xiaomi/Lenovo) from killing the guard overnight.",
                        granted = permStatus.batteryUnrestricted,
                        actionLabel = "Allow",
                        onAction = onOpenBattery
                    )
                }
            }

            // 4. Allowed Morning Alarms Card
            Card(
                colors = CardDefaults.cardColors(containerColor = NightSurface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Alarm, contentDescription = null, tint = NightGreen)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Morning Alarms Always Allowed",
                            color = NightTextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your tablet's alarm clock stays exempt so morning alarms always ring and can be dismissed. Detected clock package(s): ${allowedAlarmPackages.take(4).joinToString(", ")}",
                        color = NightTextMuted,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    // Irreversible Lock Confirmation Dialog
    if (showConfirmLockDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmLockDialog = false },
            containerColor = NightSurface,
            title = { Text("Lock Tablet Until ${formatTime(manualWakeHour, manualWakeMinute)}?", color = NightTextPrimary) },
            text = {
                Text(
                    text = "All apps and Settings will be strictly blocked until ${formatTime(manualWakeHour, manualWakeMinute)}. Only the dark sleep screen and your morning alarm clock will work.\n\nThere is NO undo button once locked.",
                    color = NightTextMuted
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmLockDialog = false
                        onLockNowUntil(manualWakeHour, manualWakeMinute)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NightRed)
                ) {
                    Text("Lock Until Morning", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmLockDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 60-Second Dry Run Confirmation Dialog
    if (showTestLockDialog) {
        AlertDialog(
            onDismissRequest = { showTestLockDialog = false },
            containerColor = NightSurface,
            title = { Text("Run 60-Second Dry-Run Test?", color = NightTextPrimary) },
            text = {
                Text(
                    text = "This locks your tablet for 60 seconds with full protection so you can verify that Home, Recents, Settings, and Alarm Clock behave exactly as expected before relying on it overnight.",
                    color = NightTextMuted
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showTestLockDialog = false
                        onStartTestLock()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NightIndigo)
                ) {
                    Text("Start 60s Test")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTestLockDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Time Picker Dialog
    activeTimePicker?.let { target ->
        val (initHour, initMin, title) = when (target) {
            TimePickerTarget.SCHEDULE_BEDTIME -> Triple(schedule.bedtimeHour, schedule.bedtimeMinute, "Select Bedtime")
            TimePickerTarget.SCHEDULE_WAKE -> Triple(schedule.wakeHour, schedule.wakeMinute, "Select Morning Wake Time")
            TimePickerTarget.MANUAL_WAKE -> Triple(manualWakeHour, manualWakeMinute, "Lock Until (Morning Time)")
        }
        val pickerState = rememberTimePickerState(
            initialHour = initHour,
            initialMinute = initMin,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { activeTimePicker = null },
            containerColor = NightSurface,
            title = { Text(title, color = NightTextPrimary) },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                Button(onClick = {
                    when (target) {
                        TimePickerTarget.SCHEDULE_BEDTIME -> onUpdateSchedule(
                            schedule.copy(bedtimeHour = pickerState.hour, bedtimeMinute = pickerState.minute)
                        )
                        TimePickerTarget.SCHEDULE_WAKE -> onUpdateSchedule(
                            schedule.copy(wakeHour = pickerState.hour, wakeMinute = pickerState.minute)
                        )
                        TimePickerTarget.MANUAL_WAKE -> {
                            manualWakeHour = pickerState.hour
                            manualWakeMinute = pickerState.minute
                        }
                    }
                    activeTimePicker = null
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { activeTimePicker = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private enum class TimePickerTarget {
    SCHEDULE_BEDTIME,
    SCHEDULE_WAKE,
    MANUAL_WAKE
}

@Composable
private fun TimeSlotBox(
    label: String,
    timeText: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = NightSurfaceVariant.copy(alpha = if (enabled) 1f else 0.45f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, color = NightTextMuted, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = timeText,
                color = if (enabled) NightTextPrimary else NightTextMuted,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = NightTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = NightTextMuted, fontSize = 12.sp)
        }
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Enabled",
                    tint = NightGreen,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Active", color = NightGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            OutlinedButton(
                onClick = onAction,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}

private fun formatTime(hour: Int, minute: Int): String {
    return String.format(Locale.US, "%02d:%02d", hour, minute)
}

package com.nightlock.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nightlock.app.data.LockManager
import com.nightlock.app.scheduler.SleepScheduler

class BootAndTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LockManager.init(context)
        val state = LockManager.refreshState(triggerEnforcement = true)
        if (state.isLocked) {
            LockManager.launchLockScreen(context)
        }
        SleepScheduler.syncAlarms(context)
    }
}

package com.nightlock.app

import android.app.Application
import com.nightlock.app.data.LockManager
import com.nightlock.app.scheduler.SleepScheduler

class NightLockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LockManager.init(this)
        SleepScheduler.syncAlarms(this)
    }
}

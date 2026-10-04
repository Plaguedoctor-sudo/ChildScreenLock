package com.childlock.screentime.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.childlock.screentime.data.PreferencesManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefsManager = PreferencesManager(context)
            if (prefsManager.isLockActive()) {
                val remainingMillis = prefsManager.getRemainingMillis()
                if (remainingMillis > 0) {
                    val remainingMinutes = ((remainingMillis / 60000L) + 1L).coerceAtLeast(1L)
                    LockTimerService.startLock(context, remainingMinutes)
                }
            }
        }
    }
}

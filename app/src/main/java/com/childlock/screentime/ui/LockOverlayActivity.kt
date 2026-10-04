package com.childlock.screentime.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.childlock.screentime.data.PreferencesManager
import com.childlock.screentime.databinding.ActivityLockOverlayBinding
import com.childlock.screentime.service.LockTimerService

class LockOverlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockOverlayBinding
    private lateinit var prefsManager: PreferencesManager

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "extra_blocked_package"
    }

    private val timerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                LockTimerService.BROADCAST_TICK -> {
                    val remaining = intent.getLongExtra(LockTimerService.EXTRA_REMAINING_MILLIS, 0L)
                    updateTimerText(remaining)
                }
                LockTimerService.BROADCAST_EXPIRED -> {
                    finish()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PreferencesManager(this)

        // Block back button so child cannot dismiss lock screen
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing or send to home
                goToHomeScreen()
            }
        })

        val blockedPkg = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE)
        if (!blockedPkg.isNullOrEmpty()) {
            try {
                val appLabel = packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(blockedPkg, 0)
                )
                binding.tvOverlaySubtitle.text = "\"$appLabel\" is locked during screen time hours."
            } catch (e: Exception) {
                // Fallback to default message
            }
        }

        binding.btnGoHome.setOnClickListener {
            goToHomeScreen()
        }

        binding.btnParentOverride.setOnClickListener {
            PinDialogHelper.showPinPrompt(this, "Parent Unlock") {
                // Parent entered correct PIN! Unlock session
                LockTimerService.stopLock(this)
                finish()
            }
        }

        updateTimerText(prefsManager.getRemainingMillis())
    }

    private fun goToHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    private fun updateTimerText(remainingMillis: Long) {
        if (remainingMillis <= 0) {
            finish()
            return
        }
        val totalSec = remainingMillis / 1000
        val hrs = totalSec / 3600
        val mins = (totalSec % 3600) / 60
        val secs = totalSec % 60
        binding.tvOverlayTimer.text = String.format("Remaining: %02d:%02d:%02d", hrs, mins, secs)
    }

    override fun onResume() {
        super.onResume()
        if (!prefsManager.isLockActive()) {
            finish()
            return
        }
        val filter = IntentFilter().apply {
            addAction(LockTimerService.BROADCAST_TICK)
            addAction(LockTimerService.BROADCAST_EXPIRED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(timerReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(timerReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
    }
}

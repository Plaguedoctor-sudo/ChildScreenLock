package com.childlock.screentime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.childlock.screentime.R
import com.childlock.screentime.data.PreferencesManager
import com.childlock.screentime.ui.MainActivity

class LockTimerService : Service() {

    private lateinit var prefsManager: PreferencesManager
    private var countDownTimer: CountDownTimer? = null
    private lateinit var notificationManager: NotificationManager

    companion object {
        const val CHANNEL_ID = "child_lock_timer_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.childlock.action.START"
        const val ACTION_STOP = "com.childlock.action.STOP"
        const val EXTRA_DURATION_MINUTES = "extra_duration_minutes"

        const val BROADCAST_TICK = "com.childlock.broadcast.TICK"
        const val BROADCAST_EXPIRED = "com.childlock.broadcast.EXPIRED"
        const val EXTRA_REMAINING_MILLIS = "remaining_millis"

        fun startLock(context: Context, durationMinutes: Long) {
            val intent = Intent(context, LockTimerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DURATION_MINUTES, durationMinutes)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopLock(context: Context) {
            val intent = Intent(context, LockTimerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefsManager = PreferencesManager(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val durationMinutes = intent.getLongExtra(EXTRA_DURATION_MINUTES, 30L)
                startLockSession(durationMinutes)
            }
            ACTION_STOP -> {
                stopLockSession()
            }
            else -> {
                // If service restarted by system, restore active lock if valid
                if (prefsManager.isLockActive()) {
                    val remaining = prefsManager.getRemainingMillis()
                    if (remaining > 0) {
                        startTimer(remaining)
                        startForegroundWithNotification(formatMillis(remaining))
                    } else {
                        stopLockSession()
                    }
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startLockSession(durationMinutes: Long) {
        prefsManager.setLockActive(true, durationMinutes)
        val remainingMillis = durationMinutes * 60 * 1000L
        startForegroundWithNotification(formatMillis(remainingMillis))
        startTimer(remainingMillis)
        try {
            startService(Intent(this, TelemetrySinkholeService::class.java))
        } catch (e: Exception) {
            // VPN preparation required if not previously authorized
        }
    }

    private fun startTimer(millis: Long) {
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(millis, 1000L) {
            private var lastMinutesRemaining = -1L

            override fun onTick(millisUntilFinished: Long) {
                val currentMinutes = millisUntilFinished / 60000L
                // Throttle notification update to once per minute to avoid disturbing alert spam
                if (currentMinutes != lastMinutesRemaining) {
                    lastMinutesRemaining = currentMinutes
                    val formatted = formatMillis(millisUntilFinished)
                    updateNotification(formatted)
                }
                sendBroadcast(Intent(BROADCAST_TICK).apply {
                    putExtra(EXTRA_REMAINING_MILLIS, millisUntilFinished)
                    setPackage(packageName)
                })
            }

            override fun onFinish() {
                sendBroadcast(Intent(BROADCAST_EXPIRED).apply {
                    setPackage(packageName)
                })
                stopLockSession()
            }
        }.start()
    }

    private fun stopLockSession() {
        countDownTimer?.cancel()
        countDownTimer = null
        prefsManager.setLockActive(false, 0)
        try {
            startService(Intent(this, TelemetrySinkholeService::class.java).apply {
                action = TelemetrySinkholeService.ACTION_STOP_SINKHOLE
            })
        } catch (e: Exception) {
            // Ignored
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Child Lock Active Session",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Silent background lock session"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification(timeString: String) {
        val notification = buildNotification(timeString)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(timeString: String) {
        val notification = buildNotification(timeString)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(timeString: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Child Screen Lock Active")
            .setContentText("Lock active • $timeString remaining")
            .setSmallIcon(R.drawable.ic_lock)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun formatMillis(millis: Long): String {
        return prefsManager.formatDuration(millis)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (prefsManager.isLockActive()) {
            val remaining = prefsManager.getRemainingMillis()
            if (remaining > 0) {
                val restartIntent = Intent(applicationContext, LockTimerService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_DURATION_MINUTES, ((remaining / 60000) + 1).toInt().coerceAtLeast(1))
                }
                val pendingIntent = PendingIntent.getService(
                    applicationContext,
                    99,
                    restartIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
                alarmManager?.set(
                    android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + 1000,
                    pendingIntent
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

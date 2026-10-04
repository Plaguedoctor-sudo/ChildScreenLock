package com.childlock.screentime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.childlock.screentime.R
import com.childlock.screentime.data.PreferencesManager
import com.childlock.screentime.face.FaceDetectorHelper
import com.childlock.screentime.face.FaceEmbeddingHelper

class FaceMonitoringService : Service() {

    private lateinit var prefsManager: PreferencesManager
    private lateinit var faceDetector: FaceDetectorHelper
    private lateinit var faceEmbeddingHelper: FaceEmbeddingHelper
    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false

    companion object {
        const val CHANNEL_ID = "face_monitoring_channel"
        const val NOTIFICATION_ID = 1002
        private const val CHECK_INTERVAL_MS = 15000L // Check every 15 seconds

        fun startMonitoring(context: Context) {
            val intent = Intent(context, FaceMonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopMonitoring(context: Context) {
            val intent = Intent(context, FaceMonitoringService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefsManager = PreferencesManager(this)
        faceDetector = FaceDetectorHelper()
        faceEmbeddingHelper = FaceEmbeddingHelper(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceNotification()
        if (!isMonitoring) {
            isMonitoring = true
            scheduleNextFaceCheck()
        }
        return START_STICKY
    }

    private fun scheduleNextFaceCheck() {
        if (!isMonitoring) return
        handler.postDelayed({
            performPeriodicCheck()
            if (isMonitoring) {
                scheduleNextFaceCheck()
            }
        }, CHECK_INTERVAL_MS)
    }

    private fun performPeriodicCheck() {
        if (!prefsManager.isFaceGuardEnabled() || !prefsManager.hasChildFaceRegistered()) {
            return
        }

        // When child face is already recognized and lock is active, maintain lock
        if (prefsManager.isLockActive()) {
            return
        }

        // Camera frame check is invoked when active
    }

    fun onFaceCapturedForVerification(bitmap: Bitmap) {
        val childEmbedding = prefsManager.getChildFaceEmbedding() ?: return
        faceDetector.processImage(bitmap).addOnSuccessListener { faces ->
            if (faces.isNotEmpty()) {
                val face = faces[0]
                val croppedFace = faceEmbeddingHelper.cropFace(bitmap, face.boundingBox)
                val currentEmbedding = faceEmbeddingHelper.extractEmbedding(croppedFace)
                if (faceEmbeddingHelper.isMatch(currentEmbedding, childEmbedding)) {
                    // Match found! Auto-activate child lock
                    LockTimerService.startLock(this, 30)
                }
            }
        }
    }

    private fun startForegroundServiceNotification() {
        val channelId = CHANNEL_ID
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Face Recognition Guard")
            .setContentText("Monitoring screen time and child face detection")
            .setSmallIcon(R.drawable.ic_face)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Face Recognition Guard Service",
                NotificationManager.IMPORTANCE_MIN
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isMonitoring = false
        handler.removeCallbacksAndMessages(null)
        faceDetector.stop()
        faceEmbeddingHelper.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

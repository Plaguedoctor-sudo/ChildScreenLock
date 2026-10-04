package com.childlock.screentime.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.childlock.screentime.data.PreferencesManager
import com.childlock.screentime.databinding.ActivityFaceVerificationBinding
import com.childlock.screentime.face.FaceDetectorHelper
import com.childlock.screentime.face.FaceEmbeddingHelper
import com.childlock.screentime.security.AuditLedger
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Translucent, silent background verification activity.
 * Runs camera scan quietly without intrusive notifications.
 * Automatically grants access in milliseconds if non-child face is detected.
 */
class FaceVerificationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFaceVerificationBinding
    private lateinit var prefsManager: PreferencesManager
    private lateinit var faceDetector: FaceDetectorHelper
    private lateinit var faceEmbeddingHelper: FaceEmbeddingHelper
    private lateinit var cameraExecutor: ExecutorService

    private var targetPackage: String = ""
    private var isVerified = false
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.childlock.screentime.BuildConfig.DEBUG) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        binding = ActivityFaceVerificationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PreferencesManager(this)
        faceDetector = FaceDetectorHelper()
        faceEmbeddingHelper = FaceEmbeddingHelper(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: ""

        binding.btnCancel.setOnClickListener {
            goToHome()
        }

        binding.btnEnterPin.setOnClickListener {
            showParentPinFallback()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            binding.cardQuickFeedback.visibility = View.GONE
            binding.cardFallbackDialog.visibility = View.VISIBLE
        }

        // Silent background scan timeout: If no face detected after 2.5s, display PIN fallback dialog
        mainHandler.postDelayed({
            if (!isVerified && !isFinishing) {
                binding.cardQuickFeedback.visibility = View.GONE
                binding.cardFallbackDialog.visibility = View.VISIBLE
            }
        }, 2500L)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                analyzeFrame(imageProxy)
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                binding.cardQuickFeedback.visibility = View.GONE
                binding.cardFallbackDialog.visibility = View.VISIBLE
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (isVerified) {
            imageProxy.close()
            return
        }

        val bitmap = imageProxyToBitmap(imageProxy)
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        imageProxy.close()

        val rotated = if (rotationDegrees != 0) {
            rotateBitmap(bitmap, rotationDegrees.toFloat())
        } else {
            bitmap
        }

        faceDetector.processImage(rotated).addOnSuccessListener { faces ->
            if (isVerified || faces.isEmpty()) return@addOnSuccessListener

            val face = faces[0]
            val cropped = faceEmbeddingHelper.cropFace(rotated, face.boundingBox)
            val candidateEmbedding = faceEmbeddingHelper.extractEmbedding(cropped)

            val matchingChild = prefsManager.findMatchingChild(candidateEmbedding, faceEmbeddingHelper)

            if (matchingChild != null) {
                // Pre-stored child face recognized -> IMMEDIATELY BLOCK SILENTLY
                isVerified = true
                mainHandler.post {
                    prefsManager.auditLedger.logEvent(
                        AuditLedger.EVENT_APP_BLOCKED,
                        "Biometric verification: Child ${matchingChild.name} restricted for package $targetPackage"
                    )
                    goToHome()
                }
            } else {
                // Face detected does NOT match any of the pre-stored children -> INSTANT SILENT PASS
                isVerified = true
                mainHandler.post {
                    // Grant 30 minutes access so the user is not disturbed again
                    prefsManager.grantAdultAccess(targetPackage, 30 * 60 * 1000L)
                    finish()
                }
            }
        }
    }

    private fun showParentPinFallback() {
        PinDialogHelper.showPinPrompt(
            context = this,
            title = "Parent Authorization",
            onSuccess = {
                prefsManager.grantAdultAccess(targetPackage, 30 * 60 * 1000L)
                finish()
            },
            onCancel = {
                goToHome()
            }
        )
    }

    private fun goToHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 85, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        faceDetector.stop()
        faceEmbeddingHelper.close()
        mainHandler.removeCallbacksAndMessages(null)
    }
}

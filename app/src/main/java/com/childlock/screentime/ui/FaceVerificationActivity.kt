package com.childlock.screentime.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
 * Translucent, silent biometric verification activity.
 * Runs camera scan quietly to distinguish between registered children (restricted)
 * and parents/adults (granted temporary 30-min pass).
 */
class FaceVerificationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFaceVerificationBinding
    private lateinit var prefsManager: PreferencesManager
    private lateinit var faceDetector: FaceDetectorHelper
    private lateinit var faceEmbeddingHelper: FaceEmbeddingHelper
    private lateinit var cameraExecutor: ExecutorService

    private var targetPackage: String = ""
    private var isVerified = false
    private var consecutiveAdultFrames = 0
    private val requiredAdultFrames = 3
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        private const val SCAN_TIMEOUT_MS = 3000L
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
            showFallbackUI()
        }

        // Scan timeout: If neither a child match nor sustained adult presence is confirmed, prompt for PIN
        mainHandler.postDelayed({
            if (!isVerified && !isFinishing) {
                showFallbackUI()
            }
        }, SCAN_TIMEOUT_MS)
    }

    private fun showFallbackUI() {
        binding.cardQuickFeedback.visibility = View.GONE
        binding.cardFallbackDialog.visibility = View.VISIBLE
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                showFallbackUI()
                return@addListener
            }

            val cameraSelector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                showFallbackUI()
                return@addListener
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                analyzeFrame(imageProxy)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageAnalysis
                )
            } catch (e: Exception) {
                showFallbackUI()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (isVerified || isFinishing) {
            imageProxy.close()
            return
        }

        val bitmap = try {
            imageProxy.toBitmap()
        } catch (e: Exception) {
            fallbackImageProxyToBitmap(imageProxy)
        }
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        imageProxy.close()

        if (bitmap == null) return

        val rotated = if (rotationDegrees != 0) {
            rotateBitmap(bitmap, rotationDegrees.toFloat())
        } else {
            bitmap
        }

        faceDetector.processImage(rotated).addOnSuccessListener { faces ->
            if (isVerified || isFinishing || faces.isEmpty()) return@addOnSuccessListener

            val face = faces[0]
            if (face.boundingBox.width() < 50 || face.boundingBox.height() < 50) {
                return@addOnSuccessListener
            }

            val cropped = faceEmbeddingHelper.cropFace(rotated, face.boundingBox)
            val candidateEmbedding = faceEmbeddingHelper.extractEmbedding(cropped)

            val matchingChild = prefsManager.findMatchingChild(candidateEmbedding, faceEmbeddingHelper)

            if (matchingChild != null) {
                // Registered child recognized -> block access and show lock overlay
                isVerified = true
                consecutiveAdultFrames = 0
                mainHandler.post {
                    prefsManager.auditLedger.logEvent(
                        AuditLedger.EVENT_APP_BLOCKED,
                        "Biometric verification: Child ${matchingChild.name} restricted for package $targetPackage"
                    )
                    val overlayIntent = Intent(this, LockOverlayActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(LockOverlayActivity.EXTRA_BLOCKED_PACKAGE, targetPackage)
                    }
                    startActivity(overlayIntent)
                    finish()
                }
            } else {
                // Face detected is not a stored child face.
                // Require consecutive frames to ensure exposure/lighting has stabilized.
                consecutiveAdultFrames++
                if (consecutiveAdultFrames >= requiredAdultFrames) {
                    isVerified = true
                    mainHandler.post {
                        prefsManager.grantAdultAccess(targetPackage, 30 * 60 * 1000L)
                        finish()
                    }
                }
            }
        }.addOnFailureListener {
            // Keep analyzing next frames
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

    private fun fallbackImageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val planes = imageProxy.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

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
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            null
        }
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

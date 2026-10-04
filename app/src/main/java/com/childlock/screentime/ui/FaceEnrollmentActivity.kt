package com.childlock.screentime.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.childlock.screentime.R
import com.childlock.screentime.data.ChildFaceProfile
import com.childlock.screentime.data.PreferencesManager
import com.childlock.screentime.databinding.ActivityFaceEnrollmentBinding
import com.childlock.screentime.databinding.ItemChildProfileBinding
import com.childlock.screentime.face.FaceDetectorHelper
import com.childlock.screentime.face.FaceEmbeddingHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FaceEnrollmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFaceEnrollmentBinding
    private lateinit var prefsManager: PreferencesManager
    private lateinit var faceDetector: FaceDetectorHelper
    private lateinit var faceEmbeddingHelper: FaceEmbeddingHelper
    private lateinit var profileAdapter: ChildProfileAdapter

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var cameraLensFacing = CameraSelector.LENS_FACING_FRONT

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required to register child face", Toast.LENGTH_LONG).show()
            showProfileListView()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.childlock.screentime.BuildConfig.DEBUG) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        binding = ActivityFaceEnrollmentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PreferencesManager(this)
        faceDetector = FaceDetectorHelper()
        faceEmbeddingHelper = FaceEmbeddingHelper(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setupRecyclerView()
        setupListeners()

        // Show profile list by default
        showProfileListView()
    }

    private fun setupRecyclerView() {
        profileAdapter = ChildProfileAdapter(emptyList()) { profile ->
            confirmDeleteProfile(profile)
        }
        binding.rvProfiles.layoutManager = LinearLayoutManager(this)
        binding.rvProfiles.adapter = profileAdapter
    }

    private fun setupListeners() {
        binding.toolbar.setNavigationOnClickListener {
            if (binding.layoutCameraEnrollment.visibility == View.VISIBLE) {
                showProfileListView()
            } else {
                finish()
            }
        }

        binding.btnStartEnrollment.setOnClickListener {
            showCameraEnrollmentView()
        }

        binding.btnCancelCamera.setOnClickListener {
            showProfileListView()
        }

        binding.btnCaptureFace.setOnClickListener {
            captureAndEnrollFace()
        }

        binding.btnSwitchCamera.setOnClickListener {
            cameraLensFacing = if (cameraLensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            startCamera()
            val cameraLabel = if (cameraLensFacing == CameraSelector.LENS_FACING_FRONT) "Front" else "Back"
            Toast.makeText(this, "Switched to $cameraLabel camera", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showProfileListView() {
        stopCamera()
        binding.layoutCameraEnrollment.visibility = View.GONE
        binding.layoutProfileList.visibility = View.VISIBLE
        binding.toolbar.title = "Child Face Profiles (Up to 5)"
        loadProfilesUI()
    }

    private fun showCameraEnrollmentView() {
        val currentCount = prefsManager.getChildFacesCount()
        if (currentCount >= PreferencesManager.MAX_CHILD_FACES) {
            Toast.makeText(this, "Maximum 5 child faces reached. Remove one to add new.", Toast.LENGTH_LONG).show()
            return
        }

        binding.layoutProfileList.visibility = View.GONE
        binding.layoutCameraEnrollment.visibility = View.VISIBLE
        binding.toolbar.title = "Enroll Child Face (${currentCount + 1}/5)"

        binding.etChildName.setText("Child ${currentCount + 1}")
        binding.tvFaceFeedback.text = "Position child's face inside the circle."
        binding.pbEnrolling.visibility = View.GONE
        binding.btnCaptureFace.isEnabled = true

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun loadProfilesUI() {
        val profiles = prefsManager.getChildProfiles()
        val count = profiles.size
        binding.tvProfileCountHeader.text = "Stored Faces: $count of ${PreferencesManager.MAX_CHILD_FACES}"

        if (profiles.isEmpty()) {
            binding.rvProfiles.visibility = View.GONE
            binding.tvEmptyProfiles.visibility = View.VISIBLE
        } else {
            binding.rvProfiles.visibility = View.VISIBLE
            binding.tvEmptyProfiles.visibility = View.GONE
            profileAdapter.updateData(profiles)
        }

        if (count >= PreferencesManager.MAX_CHILD_FACES) {
            binding.btnStartEnrollment.isEnabled = false
            binding.btnStartEnrollment.text = "Maximum Faces Stored (5/5)"
        } else {
            binding.btnStartEnrollment.isEnabled = true
            binding.btnStartEnrollment.text = "+ Enroll New Child Face (${count}/5)"
        }
    }

    private fun confirmDeleteProfile(profile: ChildFaceProfile) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove Child Face")
            .setMessage("Are you sure you want to remove the biometric profile for \"${profile.name}\"? This child will no longer be restricted from opening apps.")
            .setPositiveButton("Remove") { _, _ ->
                val removed = prefsManager.deleteChildFaceProfile(profile.id)
                if (removed) {
                    Toast.makeText(this, "Profile \"${profile.name}\" removed", Toast.LENGTH_SHORT).show()
                    loadProfilesUI()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(cameraLensFacing)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (exc: Exception) {
                Toast.makeText(this, "Failed to start camera: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun captureAndEnrollFace() {
        val capture = imageCapture ?: return
        binding.pbEnrolling.visibility = View.VISIBLE
        binding.btnCaptureFace.isEnabled = false
        binding.tvFaceFeedback.text = "Processing biometric embedding..."

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    processCapturedImage(imageProxy)
                }

                override fun onError(exception: ImageCaptureException) {
                    binding.pbEnrolling.visibility = View.GONE
                    binding.btnCaptureFace.isEnabled = true
                    binding.tvFaceFeedback.text = "Capture failed: ${exception.message}"
                }
            }
        )
    }

    private fun processCapturedImage(imageProxy: ImageProxy) {
        val bitmap = imageProxyToBitmap(imageProxy)
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        imageProxy.close()

        val rotatedBitmap = if (rotationDegrees != 0) {
            rotateBitmap(bitmap, rotationDegrees.toFloat())
        } else {
            bitmap
        }

        faceDetector.processImage(rotatedBitmap).addOnSuccessListener { faces ->
            binding.pbEnrolling.visibility = View.GONE
            binding.btnCaptureFace.isEnabled = true

            if (faces.isEmpty()) {
                binding.tvFaceFeedback.text = "No face detected. Please ensure child's face is centered in good lighting."
                return@addOnSuccessListener
            }

            if (faces.size > 1) {
                binding.tvFaceFeedback.text = "Multiple faces detected. Please register only child's face alone."
                return@addOnSuccessListener
            }

            val face = faces[0]
            val croppedFace = faceEmbeddingHelper.cropFace(rotatedBitmap, face.boundingBox)
            val embedding = faceEmbeddingHelper.extractEmbedding(croppedFace)

            val childName = binding.etChildName.text?.toString()?.trim().let {
                if (it.isNullOrBlank()) "Child ${prefsManager.getChildFacesCount() + 1}" else it
            }

            val saved = prefsManager.saveChildFace(childName, embedding)
            if (saved) {
                Toast.makeText(this, "\"$childName\" face registered successfully!", Toast.LENGTH_LONG).show()
                showProfileListView()
            } else {
                Toast.makeText(this, "Maximum 5 child faces reached.", Toast.LENGTH_SHORT).show()
                showProfileListView()
            }
        }.addOnFailureListener { e ->
            binding.pbEnrolling.visibility = View.GONE
            binding.btnCaptureFace.isEnabled = true
            binding.tvFaceFeedback.text = "Face processing error: ${e.message}"
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val planeProxy = imageProxy.planes[0]
        val buffer: ByteBuffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.layoutCameraEnrollment.visibility == View.VISIBLE) {
            showProfileListView()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        cameraExecutor.shutdown()
        faceDetector.stop()
        faceEmbeddingHelper.close()
    }

    private inner class ChildProfileAdapter(
        private var profiles: List<ChildFaceProfile>,
        private val onDeleteClick: (ChildFaceProfile) -> Unit
    ) : RecyclerView.Adapter<ChildProfileAdapter.ProfileViewHolder>() {

        fun updateData(newProfiles: List<ChildFaceProfile>) {
            profiles = newProfiles
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
            val itemBinding = ItemChildProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ProfileViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
            holder.bind(profiles[position])
        }

        override fun getItemCount(): Int = profiles.size

        inner class ProfileViewHolder(private val itemBinding: ItemChildProfileBinding) : RecyclerView.ViewHolder(itemBinding.root) {
            fun bind(profile: ChildFaceProfile) {
                itemBinding.tvProfileName.text = profile.name
                val dateStr = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault()).format(Date(profile.timestamp))
                itemBinding.tvProfileDate.text = "Restricted Face • Enrolled $dateStr"
                itemBinding.btnDeleteProfile.setOnClickListener {
                    onDeleteClick(profile)
                }
            }
        }
    }
}

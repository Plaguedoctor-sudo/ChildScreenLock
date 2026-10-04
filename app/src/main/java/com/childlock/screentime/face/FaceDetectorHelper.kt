package com.childlock.screentime.face

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions

class FaceDetectorHelper {

    private val detector: FaceDetector

    init {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.20f)
            .build()
        detector = FaceDetection.getClient(options)
    }

    fun processImage(bitmap: Bitmap, rotationDegrees: Int = 0): Task<List<Face>> {
        val image = InputImage.fromBitmap(bitmap, rotationDegrees)
        return detector.process(image)
    }

    fun processInputImage(image: InputImage): Task<List<Face>> {
        return detector.process(image)
    }

    fun stop() {
        detector.close()
    }
}

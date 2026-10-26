package com.childlock.screentime.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class FaceEmbeddingHelper(private val context: Context) {

    companion object {
        private const val TAG = "FaceEmbeddingHelper"
        private const val MODEL_NAME = "mobile_facenet.tflite"
        const val DEFAULT_SIMILARITY_THRESHOLD = 0.60f
    }

    private var tfliteInterpreter: Interpreter? = null
    var embeddingDim = 192
        private set
    var inputImageSize = 112 // MobileFaceNet standard input dimension
        private set

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val assetManager = context.assets
            val inputStream: InputStream = assetManager.open(MODEL_NAME)
            val modelBytes = inputStream.readBytes()
            inputStream.close()

            val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size).apply {
                order(ByteOrder.nativeOrder())
                put(modelBytes)
                rewind()
            }

            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }

            val interpreter = Interpreter(modelBuffer, options)
            tfliteInterpreter = interpreter

            val inputShape = interpreter.getInputTensor(0).shape() // e.g. [1, 112, 112, 3]
            inputImageSize = if (inputShape.size >= 3) inputShape[1] else 112

            val outputShape = interpreter.getOutputTensor(0).shape() // e.g. [1, 192] or [1, 128]
            embeddingDim = if (outputShape.size >= 2) outputShape[1] else 192

            Log.i(TAG, "MobileFaceNet model loaded successfully. Input: ${inputImageSize}x$inputImageSize, Dim: $embeddingDim")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load MobileFaceNet: ${e.message}. Using fallback feature extractor.", e)
            tfliteInterpreter = null
            embeddingDim = 192
        }
    }

    /**
     * Crops face from the full bitmap based on bounding box with 15% margin padding.
     * Preserves facial context (jawline, temples, forehead) for high-accuracy MobileFaceNet matching.
     */
    fun cropFace(bitmap: Bitmap, boundingBox: Rect): Bitmap {
        val marginX = (boundingBox.width() * 0.15f).toInt()
        val marginY = (boundingBox.height() * 0.15f).toInt()

        val left = max(0, boundingBox.left - marginX)
        val top = max(0, boundingBox.top - marginY)
        val right = min(bitmap.width, boundingBox.right + marginX)
        val bottom = min(bitmap.height, boundingBox.bottom + marginY)

        val width = max(1, right - left)
        val height = max(1, bottom - top)

        val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
        return Bitmap.createScaledBitmap(cropped, inputImageSize, inputImageSize, true)
    }

    /**
     * Extracts an embedding vector from the face bitmap.
     */
    fun extractEmbedding(faceBitmap: Bitmap): FloatArray {
        val scaled = if (faceBitmap.width != inputImageSize || faceBitmap.height != inputImageSize) {
            Bitmap.createScaledBitmap(faceBitmap, inputImageSize, inputImageSize, true)
        } else {
            faceBitmap
        }

        val interpreter = tfliteInterpreter
        if (interpreter != null) {
            return runModelInference(scaled, interpreter)
        }

        return extractSpatialFeatureVector(scaled)
    }

    private fun runModelInference(bitmap: Bitmap, interpreter: Interpreter): FloatArray {
        val byteBuffer = ByteBuffer.allocateDirect(1 * inputImageSize * inputImageSize * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
        }

        val intValues = IntArray(inputImageSize * inputImageSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until inputImageSize) {
            for (j in 0 until inputImageSize) {
                val p = intValues[pixel++]
                // MobileFaceNet standard normalization: (pixel - 127.5) / 128.0 => [-1, 1]
                byteBuffer.putFloat((((p shr 16) and 0xFF) - 127.5f) / 128.0f)
                byteBuffer.putFloat((((p shr 8) and 0xFF) - 127.5f) / 128.0f)
                byteBuffer.putFloat(((p and 0xFF) - 127.5f) / 128.0f)
            }
        }

        val outputArray = Array(1) { FloatArray(embeddingDim) }
        interpreter.run(byteBuffer, outputArray)
        return l2Normalize(outputArray[0])
    }

    /**
     * Spatial gradient and multi-channel fallback in case TFLite cannot initialize.
     */
    private fun extractSpatialFeatureVector(bitmap: Bitmap): FloatArray {
        val features = FloatArray(embeddingDim)
        val gridSize = 8
        val cellW = max(1, bitmap.width / gridSize)
        val cellH = max(1, bitmap.height / gridSize)
        var featureIdx = 0

        for (gy in 0 until gridSize) {
            for (gx in 0 until gridSize) {
                var sumR = 0.0
                var sumG = 0.0
                var sumB = 0.0
                var count = 0

                val startY = gy * cellH
                val endY = min(bitmap.height, (gy + 1) * cellH)
                val startX = gx * cellW
                val endX = min(bitmap.width, (gx + 1) * cellW)

                for (y in startY until endY) {
                    for (x in startX until endX) {
                        val pixel = bitmap.getPixel(x, y)
                        sumR += ((pixel shr 16) and 0xFF)
                        sumG += ((pixel shr 8) and 0xFF)
                        sumB += (pixel and 0xFF)
                        count++
                    }
                }

                if (count > 0 && featureIdx + 2 < embeddingDim) {
                    features[featureIdx++] = (sumR / (count * 255.0)).toFloat()
                    features[featureIdx++] = (sumG / (count * 255.0)).toFloat()
                    features[featureIdx++] = (sumB / (count * 255.0)).toFloat()
                }
            }
        }

        return l2Normalize(features)
    }

    fun calculateCosineSimilarity(emb1: FloatArray, emb2: FloatArray): Float {
        if (emb1.isEmpty() || emb2.isEmpty() || emb1.size != emb2.size) return 0f
        var dot = 0f
        var norm1 = 0f
        var norm2 = 0f

        for (i in emb1.indices) {
            dot += emb1[i] * emb2[i]
            norm1 += emb1[i] * emb1[i]
            norm2 += emb2[i] * emb2[i]
        }

        val denominator = sqrt(norm1) * sqrt(norm2)
        return if (denominator > 0f) dot / denominator else 0f
    }

    fun isMatch(emb1: FloatArray, emb2: FloatArray, threshold: Float = DEFAULT_SIMILARITY_THRESHOLD): Boolean {
        return calculateCosineSimilarity(emb1, emb2) >= threshold
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSquares = 0f
        for (v in vector) {
            sumSquares += v * v
        }
        val norm = sqrt(sumSquares)
        if (norm == 0f) return vector
        val result = FloatArray(vector.size)
        for (i in vector.indices) {
            result[i] = vector[i] / norm
        }
        return result
    }

    fun close() {
        tfliteInterpreter?.close()
        tfliteInterpreter = null
    }
}

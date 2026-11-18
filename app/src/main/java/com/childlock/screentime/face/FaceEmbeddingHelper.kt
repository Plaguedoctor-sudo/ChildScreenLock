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
        private const val MODEL_NAME = "arcface.tflite" // Updated to ArcFace
        const val DEFAULT_SIMILARITY_THRESHOLD = 0.45f // ArcFace embeddings are more dispersed; lower threshold usually works better for verification
    }

    private var tfliteInterpreter: Interpreter? = null
    var embeddingDim = 512 // Upgraded from 192 to 512 for ArcFace
        private set
    var inputImageSize = 112
        private set
    var modelBatchSize = 1
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

            var interpreter = Interpreter(modelBuffer, options)

            // Inspect input shape and adapt to model's batch dimension
            val inputShape = interpreter.getInputTensor(0).shape()
            Log.i(TAG, "Native model input shape: ${inputShape.contentToString()}")
            var batch = if (inputShape.isNotEmpty()) inputShape[0] else 1

            if (batch > 1) {
                try {
                    val singleBatchShape = inputShape.clone()
                    singleBatchShape[0] = 1
                    interpreter.resizeInput(0, singleBatchShape)
                    interpreter.allocateTensors()
                    batch = 1
                    Log.i(TAG, "Resized model input tensor to batch size 1")
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot resize input to batch 1: ${e.message}. Using native batch size $batch")
                    try {
                        interpreter.close()
                    } catch (ignored: Exception) {}
                    modelBuffer.rewind()
                    interpreter = Interpreter(modelBuffer, options)
                    val reloadedShape = interpreter.getInputTensor(0).shape()
                    batch = if (reloadedShape.isNotEmpty()) reloadedShape[0] else 1
                }
            }

            tfliteInterpreter = interpreter
            modelBatchSize = batch

            val finalInputShape = interpreter.getInputTensor(0).shape()
            inputImageSize = if (finalInputShape.size >= 3) finalInputShape[1] else 112

            val outputShape = interpreter.getOutputTensor(0).shape()
            embeddingDim = if (outputShape.size >= 2) outputShape[outputShape.size - 1] else 192

            Log.i(TAG, "MobileFaceNet loaded. Batch: $modelBatchSize, Input: ${inputImageSize}x$inputImageSize, Dim: $embeddingDim")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load MobileFaceNet: ${e.message}. Using fallback feature extractor.", e)
            tfliteInterpreter = null
            modelBatchSize = 1
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

        val left = max(0, min(bitmap.width - 1, boundingBox.left - marginX))
        val top = max(0, min(bitmap.height - 1, boundingBox.top - marginY))
        val right = min(bitmap.width, max(left + 1, boundingBox.right + marginX))
        val bottom = min(bitmap.height, max(top + 1, boundingBox.bottom + marginY))

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
            try {
                return runModelInference(scaled, interpreter)
            } catch (e: Exception) {
                Log.e(TAG, "Inference error: ${e.message}. Falling back to spatial extractor.", e)
            }
        }

        return extractSpatialFeatureVector(scaled)
    }

    private fun runModelInference(bitmap: Bitmap, interpreter: Interpreter): FloatArray {
        val totalBytes = modelBatchSize * inputImageSize * inputImageSize * 3 * 4
        val byteBuffer = ByteBuffer.allocateDirect(totalBytes).apply {
            order(ByteOrder.nativeOrder())
        }

        val intValues = IntArray(inputImageSize * inputImageSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (b in 0 until modelBatchSize) {
            var pixel = 0
            for (i in 0 until inputImageSize) {
                for (j in 0 until inputImageSize) {
                    val p = intValues[pixel++]
                    // ArcFace Standard Normalization:
                    // Typically uses (x - 127.5) / 128.0 or mean subtraction.
                    // Using the standardized TFLite ArcFace preprocessing:
                    byteBuffer.putFloat((((p shr 16) and 0xFF) - 127.5f) / 128.0f)
                    byteBuffer.putFloat((((p shr 8) and 0xFF) - 127.5f) / 128.0f)
                    byteBuffer.putFloat(((p and 0xFF) - 127.5f) / 128.0f)
                }
            }
        }

        val outputArray = Array(modelBatchSize) { FloatArray(embeddingDim) }
        interpreter.run(byteBuffer, outputArray)

        // ArcFace is designed for cosine similarity on L2-normalized vectors
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

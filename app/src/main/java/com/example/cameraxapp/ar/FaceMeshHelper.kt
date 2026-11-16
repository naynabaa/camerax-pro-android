package com.example.cameraxapp.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FaceMeshHelper: Real-time 468 3D Landmark Tracking via Google MediaPipe Tasks Vision
 * with automatic asset validation and safe hardware fallback reporting.
 */
class FaceMeshHelper(
    private val context: Context,
    private val listener: FaceMeshListener
) : ImageAnalysis.Analyzer {

    interface FaceMeshListener {
        fun onFaceLandmarksDetected(
            result: FaceLandmarkerResult,
            inputWidth: Int,
            inputHeight: Int
        )
        fun onError(error: String)
    }

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var isFailed: Boolean = false
        private set

    private var faceLandmarker: FaceLandmarker? = null
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val isProcessing = AtomicBoolean(false)
    private var isFrontFacing = false

    init {
        setupFaceLandmarker()
    }

    private fun hasModelAsset(): Boolean {
        return try {
            context.assets.open("face_landmarker.task").use { stream ->
                stream.available() > 0
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun setupFaceLandmarker() {
        backgroundExecutor.execute {
            try {
                if (!hasModelAsset()) {
                    isReady = false
                    isFailed = true
                    listener.onError("face_landmarker.task missing in assets — using built-in FaceDetector fallback")
                    return@execute
                }

                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath("face_landmarker.task")
                    .build()

                val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumFaces(1)
                    .setMinFaceDetectionConfidence(0.45f)
                    .setMinTrackingConfidence(0.45f)
                    .setMinFacePresenceConfidence(0.45f)
                    .setOutputFaceBlendshapes(true)
                    .setOutputFacialTransformationMatrixes(true)
                    .setResultListener { result: FaceLandmarkerResult, input: MPImage ->
                        isProcessing.set(false)
                        listener.onFaceLandmarksDetected(
                            result,
                            input.width,
                            input.height
                        )
                    }
                    .setErrorListener { error: RuntimeException ->
                        isProcessing.set(false)
                        Log.e(TAG, "MediaPipe FaceLandmarker runtime error: ${error.message}")
                        listener.onError(error.message ?: "Unknown MediaPipe runtime error")
                    }
                    .build()

                faceLandmarker = FaceLandmarker.createFromOptions(context, options)
                isReady = true
                isFailed = false
                Log.d(TAG, "MediaPipe FaceLandmarker 468-mesh initialized successfully.")
            } catch (t: Throwable) {
                isReady = false
                isFailed = true
                Log.e(TAG, "MediaPipe initialization unavailable, falling back to FaceDetector: ${t.message}", t)
                listener.onError("MediaPipe unavailable on device (${t.javaClass.simpleName}) — using FaceDetector fallback")
            }
        }
    }

    fun setFrontFacing(frontFacing: Boolean) {
        this.isFrontFacing = frontFacing
    }

    fun analyzeRotatedBitmap(sourceBitmap: Bitmap, rotationDegrees: Int = 0): Boolean {
        val landmarker = faceLandmarker
        if (!isReady || isFailed || landmarker == null) return false
        if (!isProcessing.compareAndSet(false, true)) return true

        val frameTime = SystemClock.uptimeMillis()
        try {
            val uprightBitmap = if (rotationDegrees != 0) {
                val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(sourceBitmap, 0, 0, sourceBitmap.width, sourceBitmap.height, m, true)
            } else {
                sourceBitmap
            }
            val mpImage = BitmapImageBuilder(uprightBitmap).build()
            landmarker.detectAsync(mpImage, frameTime)
            return true
        } catch (t: Throwable) {
            isProcessing.set(false)
            Log.e(TAG, "MediaPipe analyzeBitmap error: ${t.message}", t)
            return false
        }
    }

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            val rot = imageProxy.imageInfo.rotationDegrees
            analyzeRotatedBitmap(bitmap, rot)
        } catch (t: Throwable) {
            isProcessing.set(false)
            Log.e(TAG, "Error analyzing image frame: ${t.message}", t)
        } finally {
            imageProxy.close()
        }
    }

    fun clear() {
        isReady = false
        backgroundExecutor.execute {
            try {
                faceLandmarker?.close()
            } catch (_: Throwable) {}
            faceLandmarker = null
        }
        backgroundExecutor.shutdown()
    }

    companion object {
        private const val TAG = "FaceMeshHelper"
    }
}

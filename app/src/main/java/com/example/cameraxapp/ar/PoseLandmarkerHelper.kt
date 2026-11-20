package com.example.cameraxapp.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot

/**
 * PoseLandmarkerHelper (Phase 3 Body AR):
 * Uses Google MediaPipe Tasks Vision PoseLandmarker (pose_landmarker_lite.task) in LIVE_STREAM mode
 * to detect 33 full-body landmarks, classify pose stances (STANDING, ARMS_RAISED, T_POSE, POWER_STANCE),
 * and produce a downsampled foreground silhouette alpha Bitmap from MediaPipe's segmentation mask.
 */
class PoseLandmarkerHelper(
    private val context: Context,
    private val listener: PoseLandmarkerListener
) {
    interface PoseLandmarkerListener {
        fun onPoseDetected(
            landmarks: List<PointF>,
            stance: String,
            segmentationMask: Bitmap?,
            inputWidth: Int,
            inputHeight: Int
        )
        fun onPoseTrackingNotice(message: String)
    }

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var isFailed: Boolean = false
        private set

    private var poseLandmarker: PoseLandmarker? = null
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val isProcessing = AtomicBoolean(false)

    init {
        setupPoseLandmarker()
    }

    private fun hasModelAsset(): Boolean {
        return try {
            context.assets.open("pose_landmarker_lite.task").use { it.available() > 0 }
        } catch (_: Throwable) {
            false
        }
    }

    private fun setupPoseLandmarker() {
        backgroundExecutor.execute {
            try {
                if (!hasModelAsset()) {
                    isReady = false
                    isFailed = true
                    listener.onPoseTrackingNotice("pose_landmarker_lite.task missing in assets — build online to auto-download MediaPipe Body AR model")
                    return@execute
                }

                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath("pose_landmarker_lite.task")
                    .build()

                val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumPoses(1)
                    .setMinPoseDetectionConfidence(0.45f)
                    .setMinPosePresenceConfidence(0.45f)
                    .setMinTrackingConfidence(0.45f)
                    .setOutputSegmentationMasks(true)
                    .setResultListener { result: PoseLandmarkerResult, input: MPImage ->
                        isProcessing.set(false)
                        val poses = result.landmarks()
                        if (poses.isEmpty() || poses[0].size < 33) {
                            listener.onPoseDetected(emptyList(), "STANDING", null, input.width, input.height)
                            return@setResultListener
                        }
                        val pts = poses[0].map { PointF(it.x(), it.y()) }
                        val stance = classifyStance(pts)
                        val segBitmap = extractSegmentationMaskBitmap(result)
                        listener.onPoseDetected(pts, stance, segBitmap, input.width, input.height)
                    }
                    .setErrorListener { error: RuntimeException ->
                        isProcessing.set(false)
                        Log.e(TAG, "MediaPipe PoseLandmarker runtime error: ${error.message}")
                        listener.onPoseTrackingNotice(error.message ?: "PoseLandmarker runtime error")
                    }
                    .build()

                poseLandmarker = PoseLandmarker.createFromOptions(context, options)
                isReady = true
                isFailed = false
                Log.d(TAG, "MediaPipe 33-point PoseLandmarker initialized successfully.")
            } catch (t: Throwable) {
                isReady = false
                isFailed = true
                Log.w(TAG, "PoseLandmarker init unavailable: ${t.message}", t)
                listener.onPoseTrackingNotice("PoseLandmarker unavailable (${t.javaClass.simpleName})")
            }
        }
    }

    private fun extractSegmentationMaskBitmap(result: PoseLandmarkerResult): Bitmap? {
        return try {
            val optMasks = result.segmentationMasks()
            if (!optMasks.isPresent) return null
            val maskImg = optMasks.get().firstOrNull() ?: return null
            val mw = maskImg.width
            val mh = maskImg.height
            if (mw <= 0 || mh <= 0) return null

            val floatBuf = ByteBufferExtractor.extract(maskImg).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val targetW = minOf(96, mw)
            val targetH = minOf(128, mh)
            val pixels = IntArray(targetW * targetH)
            val stepX = mw.toFloat() / targetW.toFloat()
            val stepY = mh.toFloat() / targetH.toFloat()

            for (ty in 0 until targetH) {
                val sy = (ty * stepY).toInt().coerceIn(0, mh - 1)
                val rowOff = sy * mw
                val dstOff = ty * targetW
                for (tx in 0 until targetW) {
                    val sx = (tx * stepX).toInt().coerceIn(0, mw - 1)
                    val conf = floatBuf.get(rowOff + sx)
                    // Cubic smoothstep over 0.38..0.74 for silkier feathered human silhouette cutout
                    val t = ((conf - 0.38f) / 0.36f).coerceIn(0f, 1f)
                    val alphaNorm = t * t * (3f - 2f * t)
                    val a = (alphaNorm * 255f).toInt()
                    pixels[dstOff + tx] = (a shl 24) or 0x00FFFFFF
                }
            }
            Bitmap.createBitmap(pixels, targetW, targetH, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            null
        }
    }

    fun analyzeRotatedBitmap(sourceBitmap: Bitmap, rotationDegrees: Int = 0): Boolean {
        val landmarker = poseLandmarker
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
            Log.e(TAG, "PoseLandmarker analyzeBitmap error: ${t.message}", t)
            return false
        }
    }

    fun clear() {
        isReady = false
        backgroundExecutor.execute {
            try {
                poseLandmarker?.close()
            } catch (_: Throwable) {}
            poseLandmarker = null
        }
        backgroundExecutor.shutdown()
    }

    companion object {
        private const val TAG = "PoseLandmarkerHelper"

        /**
         * Classifies body stance from 33 MediaPipe Pose landmarks for pose-reactive AR wings & FX.
         */
        fun classifyStance(pts: List<PointF>): String {
            if (pts.size < 33) return "STANDING"
            val lShoulder = pts[11]
            val rShoulder = pts[12]
            val lWrist = pts[15]
            val rWrist = pts[16]
            val lHip = pts[23]
            val rHip = pts[24]
            val lAnkle = pts[27]
            val rAnkle = pts[28]

            val shoulderW = hypot((rShoulder.x - lShoulder.x).toDouble(), (rShoulder.y - lShoulder.y).toDouble()).toFloat().coerceAtLeast(0.06f)
            val wristSpread = hypot((rWrist.x - lWrist.x).toDouble(), (rWrist.y - lWrist.y).toDouble()).toFloat()
            val ankleSpread = hypot((rAnkle.x - lAnkle.x).toDouble(), (rAnkle.y - lAnkle.y).toDouble()).toFloat()
            val shoulderMidY = (lShoulder.y + rShoulder.y) * 0.5f
            val hipMidY = (lHip.y + rHip.y) * 0.5f

            val bothWristsAboveShoulders = lWrist.y < shoulderMidY - shoulderW * 0.25f && rWrist.y < shoulderMidY - shoulderW * 0.25f
            val armsHorizontal = abs(lWrist.y - lShoulder.y) < shoulderW * 0.45f &&
                abs(rWrist.y - rShoulder.y) < shoulderW * 0.45f &&
                wristSpread > shoulderW * 2.15f
            val wideStance = ankleSpread > shoulderW * 1.15f && (lWrist.y < hipMidY || rWrist.y < hipMidY)

            return when {
                bothWristsAboveShoulders -> "ARMS_RAISED"
                armsHorizontal -> "T_POSE"
                wideStance -> "POWER_STANCE"
                else -> "STANDING"
            }
        }
    }
}

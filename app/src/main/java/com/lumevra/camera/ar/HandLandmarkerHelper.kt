package com.lumevra.camera.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

/**
 * HandLandmarkerHelper (Phase 2 Hand AR):
 * Tracks up to 2 hands with 21 anatomical landmarks each using MediaPipe HandLandmarker,
 * classifies real-time hand gestures (OPEN_PALM, PEACE_SIGN, THUMBS_UP, FIST, POINT_UP,
 * ROCK_ON, OK_SIGN), and dispatches smoothed normalized coordinates to ARFaceOverlayView.
 */
class HandLandmarkerHelper(
    private val context: Context,
    private val listener: HandLandmarkerListener
) {
    data class TrackedHand(
        val landmarks: List<PointF>, // 21 normalized 0..1 landmarks
        val gesture: String,         // OPEN_PALM, PEACE_SIGN, THUMBS_UP, FIST, POINT_UP, ROCK_ON, OK_SIGN
        val handedness: String       // "Left" or "Right"
    )

    interface HandLandmarkerListener {
        fun onHandsDetected(hands: List<TrackedHand>, inputWidth: Int, inputHeight: Int)
        fun onHandTrackingNotice(message: String)
    }

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var isFailed: Boolean = false
        private set

    private var handLandmarker: HandLandmarker? = null
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val isProcessing = AtomicBoolean(false)

    init {
        setupHandLandmarker()
    }

    // Configurable Finger-Snap Sensitivity Thresholds (tuned from ArCalibrationModal to prevent false positives)
    @Volatile var snapEnabled: Boolean = true
    @Volatile var snapPinchThreshold: Float = 0.36f
    @Volatile var snapReleaseThreshold: Float = 0.50f
    @Volatile var snapMaxWindowMs: Long = 680L
    @Volatile var snapCooldownMs: Long = 1800L
    // Nearby Camera Distance Mechanism & Adaptive Hand/Glove Sensitivity
    @Volatile var nearCameraDistanceEnabled: Boolean = true
    @Volatile var minNearHandScale: Float = 0.15f
    @Volatile var maxNearHandScale: Float = 0.55f
    @Volatile var gloveGestureCompensation: Float = 0.12f
    @Volatile var distanceCompensationStrength: Float = 0.65f

    private var lastSnapPinchTimeMs: Long = 0L
    private var lastSnapEmittedTimeMs: Long = 0L

    private fun hasModelAsset(): Boolean {
        return try {
            context.assets.open("hand_landmarker.task").use { it.available() > 0 }
        } catch (_: Throwable) {
            false
        }
    }

    private fun setupHandLandmarker() {
        backgroundExecutor.execute {
            try {
                if (!hasModelAsset()) {
                    isReady = false
                    isFailed = true
                    listener.onHandTrackingNotice("hand_landmarker.task missing in assets — build online to auto-download MediaPipe Hand AR model")
                    return@execute
                }

                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .build()

                val options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.45f)
                    .setMinHandPresenceConfidence(0.45f)
                    .setMinTrackingConfidence(0.45f)
                    .setResultListener { result: HandLandmarkerResult, input: MPImage ->
                        isProcessing.set(false)
                        val hands = mutableListOf<TrackedHand>()
                        val allLm = result.landmarks()
                        val allHanded = result.handednesses()
                        val nowMs = SystemClock.uptimeMillis()
                        for (hIdx in allLm.indices) {
                            val lmList = allLm[hIdx]
                            if (lmList.size < 21) continue
                            val pts = lmList.map { PointF(it.x(), it.y()) }
                            val handedLabel = allHanded.getOrNull(hIdx)?.firstOrNull()?.categoryName() ?: "Right"
                            val wrist = pts[0]
                            val rawPalmSize = hypot((pts[9].x - wrist.x).toDouble(), (pts[9].y - wrist.y).toDouble()).toFloat()
                            val knuckleWidth = hypot((pts[5].x - pts[17].x).toDouble(), (pts[5].y - pts[17].y).toDouble()).toFloat()
                            val apparentScale = Math.max(rawPalmSize, knuckleWidth * 1.18f)
                            val estCm = (13.2f / apparentScale.coerceAtLeast(0.04f)).coerceIn(15f, 220f)
                            val distNorm = ((estCm - 45f) / 105f).coerceIn(0f, 1f)
                            val isWithinNearCameraRange = !nearCameraDistanceEnabled ||
                                (apparentScale >= minNearHandScale && apparentScale <= maxNearHandScale)

                            var gesture = if (isWithinNearCameraRange) classifyGesture(pts) else "NONE"

                            // Real 21-Point Avengers Finger-Snap Detector with distance & glove compensation
                            if (snapEnabled && isWithinNearCameraRange) {
                                val palmSize = rawPalmSize.coerceAtLeast(0.04f)
                                val thumbMiddleDist = hypot((pts[4].x - pts[12].x).toDouble(), (pts[4].y - pts[12].y).toDouble()).toFloat() / palmSize
                                val middleToWrist = hypot((pts[12].x - wrist.x).toDouble(), (pts[12].y - wrist.y).toDouble()).toFloat() / palmSize
                                val effectivePinchThresh = (snapPinchThreshold + gloveGestureCompensation * 0.14f + distanceCompensationStrength * 0.06f * distNorm).coerceIn(0.18f, 0.58f)
                                if (thumbMiddleDist <= effectivePinchThresh) {
                                    lastSnapPinchTimeMs = nowMs
                                } else if (
                                    lastSnapPinchTimeMs > 0L &&
                                    (nowMs - lastSnapPinchTimeMs) in 35L..snapMaxWindowMs &&
                                    (nowMs - lastSnapEmittedTimeMs) >= snapCooldownMs &&
                                    (thumbMiddleDist >= snapReleaseThreshold || middleToWrist < 1.12f)
                                ) {
                                    gesture = "FINGER_SNAP"
                                    lastSnapPinchTimeMs = 0L
                                    lastSnapEmittedTimeMs = nowMs
                                }
                            } else if (!isWithinNearCameraRange) {
                                lastSnapPinchTimeMs = 0L
                            }

                            hands.add(TrackedHand(pts, gesture, handedLabel))
                        }
                        listener.onHandsDetected(hands, input.width, input.height)
                    }
                    .setErrorListener { error: RuntimeException ->
                        isProcessing.set(false)
                        Log.e(TAG, "MediaPipe HandLandmarker runtime error: ${error.message}")
                        listener.onHandTrackingNotice(error.message ?: "HandLandmarker runtime error")
                    }
                    .build()

                handLandmarker = HandLandmarker.createFromOptions(context, options)
                isReady = true
                isFailed = false
                Log.d(TAG, "MediaPipe 21-point HandLandmarker initialized successfully.")
            } catch (t: Throwable) {
                isReady = false
                isFailed = true
                Log.w(TAG, "HandLandmarker init unavailable: ${t.message}", t)
                listener.onHandTrackingNotice("HandLandmarker unavailable (${t.javaClass.simpleName})")
            }
        }
    }

    fun analyzeRotatedBitmap(sourceBitmap: Bitmap, rotationDegrees: Int = 0): Boolean {
        val landmarker = handLandmarker
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
            Log.e(TAG, "HandLandmarker analyzeBitmap error: ${t.message}", t)
            return false
        }
    }

    fun clear() {
        isReady = false
        backgroundExecutor.execute {
            try {
                handLandmarker?.close()
            } catch (_: Throwable) {}
            handLandmarker = null
        }
        backgroundExecutor.shutdown()
    }

    companion object {
        private const val TAG = "HandLandmarkerHelper"

        /**
         * Classifies hand gestures directly from 21 normalized hand landmarks using
         * distance-to-wrist ratios (invariant to hand rotation and camera distance).
         */
        fun classifyGesture(pts: List<PointF>): String {
            if (pts.size < 21) return "NONE"
            val wrist = pts[0]
            val palmSize = hypot((pts[9].x - wrist.x).toDouble(), (pts[9].y - wrist.y).toDouble()).toFloat().coerceAtLeast(0.04f)

            fun dist(a: Int, b: Int): Float =
                hypot((pts[a].x - pts[b].x).toDouble(), (pts[a].y - pts[b].y).toDouble()).toFloat()

            val indexExt = dist(8, 0) > dist(6, 0) * 1.12f
            val middleExt = dist(12, 0) > dist(10, 0) * 1.12f
            val ringExt = dist(16, 0) > dist(14, 0) * 1.12f
            val pinkyExt = dist(20, 0) > dist(18, 0) * 1.10f
            val thumbExt = dist(4, 17) > dist(3, 17) * 1.08f && dist(4, 5) > palmSize * 0.42f
            val pinchDist = dist(4, 8) / palmSize

            return when {
                pinchDist < 0.36f && middleExt && ringExt && pinkyExt -> "OK_SIGN"
                indexExt && middleExt && ringExt && pinkyExt && thumbExt -> "OPEN_PALM"
                indexExt && middleExt && !ringExt && !pinkyExt -> "PEACE_SIGN"
                indexExt && !middleExt && !ringExt && pinkyExt -> "ROCK_ON"
                indexExt && !middleExt && !ringExt && !pinkyExt -> "POINT_UP"
                thumbExt && !indexExt && !middleExt && !ringExt && !pinkyExt && pts[4].y < pts[2].y -> "THUMBS_UP"
                !indexExt && !middleExt && !ringExt && !pinkyExt -> "FIST"
                else -> "OPEN_PALM"
            }
        }
    }
}

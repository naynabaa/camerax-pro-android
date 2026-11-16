package com.example.cameraxapp.effect

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * MotionBlurProcessor: End-to-End Motion Detection & Software Directional Blur Simulation
 *
 * Architecture & CameraX Pipeline Guide:
 * --------------------------------------
 * 1. CAMERA-X IMAGEANALYSIS USE CASE (NON-BLOCKING BACKGROUND ANALYSIS):
 *    - CameraX binds `ImageAnalysis` alongside `Preview` and `ImageCapture` / `VideoCapture`
 *      using `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST` on a dedicated background `ExecutorService`.
 *    - Why `STRATEGY_KEEP_ONLY_LATEST` matters: if a frame takes longer than 16ms to analyze,
 *      CameraX drops queued intermediate frames instead of stalling the 60 FPS viewfinder preview.
 *    - You must ALWAYS call `imageProxy.close()` at the end of the analyzer lambda so CameraX can
 *      recycle the underlying `Image` buffer back to the HAL camera pipeline.
 *
 * 2. Y-PLANE LUMA MOTION DETECTION (ZERO HEAP ALLOCATION):
 *    - CameraX `ImageAnalysis` delivers frames in `YUV_420_888`. Rather than converting every
 *      frame to an ARGB Bitmap, we inspect `imageProxy.planes[0]` directly — the 8-bit grayscale
 *      luminance (Y) plane.
 *    - Respecting `rowStride`, `pixelStride`, and `imageProxy.imageInfo.rotationDegrees`,
 *      we subsample the frame into a compact 48x36 integer block grid (`prevLumaGrid` vs `currLumaGrid`).
 *    - Comparing temporal brightness change `It = Y(t) - Y(t-1)` against spatial luminance
 *      gradients `Ix, Iy` (single-step Lucas-Kanade optical-flow projection) gives us:
 *        a) Normalized motion magnitude (`0.0f..1.0f`), smoothed via an Exponential Moving Average.
 *        b) Dominant 2D screen-space motion direction unit vector `(dirX, dirY)`.
 *        c) Active moving block fraction (how much of the scene is in motion vs stationary).
 *
 * 3. CUSTOM SOFTWARE-BASED DIRECTIONAL BLUR POST-PROCESSING:
 *    - Because Android API 31+ deprecated `RenderScript` / `ScriptIntrinsicBlur`, pure-Kotlin
 *      `IntArray` pixel convolution gives portable, artifact-free directional shutter-drag blur
 *      without GPU readback stalls or deprecated API warnings.
 *    - Using `Bitmap.getPixels(...)` once and `Bitmap.setPixels(...)` once, we perform a symmetric
 *      multi-tap directional integration kernel along `(dirX, dirY)` weighted by a tent/Gaussian
 *      shutter-open envelope. The blur kernel radius scales dynamically with both detected motion
 *      velocity (`metrics.magnitude`) and the user intensity slider (`0f..1f`).
 */
class MotionBlurProcessor {

    data class MotionMetrics(
        val magnitude: Float,
        val dirX: Float,
        val dirY: Float,
        val activeBlockFraction: Float,
        val angleDegrees: Float,
        val simulatedShutterLabel: String
    ) {
        companion object {
            val IDLE = MotionMetrics(
                magnitude = 0.0f,
                dirX = 1.0f,
                dirY = 0.0f,
                activeBlockFraction = 0.0f,
                angleDegrees = 0.0f,
                simulatedShutterLabel = "1/60s"
            )
        }
    }

    // Compact 48x36 Y-plane downsampling grid (~1,728 samples/frame, <0.4ms on background thread)
    private val prevLumaGrid = IntArray(GRID_W * GRID_H)
    private val currLumaGrid = IntArray(GRID_W * GRID_H)
    private var hasPreviousFrame = false

    // Smoothed telemetry state (guarded for analyzer -> UI/capture handoff)
    @Volatile
    private var smoothedMagnitude = 0.0f
    @Volatile
    private var smoothedDirX = 1.0f
    @Volatile
    private var smoothedDirY = 0.0f

    fun reset() {
        hasPreviousFrame = false
        prevLumaGrid.fill(0)
        currLumaGrid.fill(0)
        smoothedMagnitude = 0.0f
        smoothedDirX = 1.0f
        smoothedDirY = 0.0f
    }

    /**
     * Step 1 & 2: Extract Y-plane luminance samples from CameraX ImageProxy and compute
     * temporal frame-to-frame motion magnitude & directional flow vector.
     */
    fun analyzeYPlaneMotion(imageProxy: ImageProxy, isFrontFacing: Boolean): MotionMetrics {
        if (imageProxy.planes.isEmpty()) return MotionMetrics.IDLE

        val yPlane = imageProxy.planes[0]
        val yBuf = yPlane.buffer
        val srcW = imageProxy.width.coerceAtLeast(1)
        val srcH = imageProxy.height.coerceAtLeast(1)
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val bufLimit = yBuf.limit()
        val rotDeg = imageProxy.imageInfo.rotationDegrees

        // Map screen-aligned grid coordinates (gx, gy) back to rotated sensor buffer coordinates
        for (gy in 0 until GRID_H) {
            val ny = (gy + 0.5f) / GRID_H.toFloat()
            val rowBase = gy * GRID_W
            for (gx in 0 until GRID_W) {
                val nxRaw = (gx + 0.5f) / GRID_W.toFloat()
                val nx = if (isFrontFacing) (1.0f - nxRaw) else nxRaw

                val sx: Int
                val sy: Int
                when (rotDeg) {
                    90 -> {
                        sx = (ny * (srcW - 1)).roundToInt().coerceIn(0, srcW - 1)
                        sy = ((1.0f - nx) * (srcH - 1)).roundToInt().coerceIn(0, srcH - 1)
                    }
                    180 -> {
                        sx = ((1.0f - nx) * (srcW - 1)).roundToInt().coerceIn(0, srcW - 1)
                        sy = ((1.0f - ny) * (srcH - 1)).roundToInt().coerceIn(0, srcH - 1)
                    }
                    270 -> {
                        sx = ((1.0f - ny) * (srcW - 1)).roundToInt().coerceIn(0, srcW - 1)
                        sy = (nx * (srcH - 1)).roundToInt().coerceIn(0, srcH - 1)
                    }
                    else -> {
                        sx = (nx * (srcW - 1)).roundToInt().coerceIn(0, srcW - 1)
                        sy = (ny * (srcH - 1)).roundToInt().coerceIn(0, srcH - 1)
                    }
                }

                val bufIdx = sy * rowStride + sx * pixelStride
                currLumaGrid[rowBase + gx] = if (bufIdx in 0 until bufLimit) {
                    yBuf.get(bufIdx).toInt() and 0xFF
                } else {
                    128
                }
            }
        }

        if (!hasPreviousFrame) {
            System.arraycopy(currLumaGrid, 0, prevLumaGrid, 0, GRID_SIZE)
            hasPreviousFrame = true
            return MotionMetrics.IDLE
        }

        // Temporal differencing + spatial gradient projection to estimate motion vector (dx, dy)
        var activeBlocks = 0
        var weightedDeltaSum = 0.0f
        var flowAccumX = 0.0f
        var flowAccumY = 0.0f

        for (gy in 1 until (GRID_H - 1)) {
            val rowOff = gy * GRID_W
            for (gx in 1 until (GRID_W - 1)) {
                val idx = rowOff + gx
                val itDelta = currLumaGrid[idx] - prevLumaGrid[idx]
                val absDelta = abs(itDelta)

                if (absDelta > NOISE_FLOOR) {
                    activeBlocks++
                    val excess = (absDelta - NOISE_FLOOR).toFloat()
                    weightedDeltaSum += excess

                    // Central spatial luminance gradients (Ix, Iy) on the current Y-plane grid
                    val ix = (currLumaGrid[idx + 1] - currLumaGrid[idx - 1]) * 0.5f
                    val iy = (currLumaGrid[idx + GRID_W] - currLumaGrid[idx - GRID_W]) * 0.5f
                    val gradMagSq = ix * ix + iy * iy + 36.0f

                    // Optical flow constraint: Ix * vx + Iy * vy + It = 0 -> v ~ -It * grad / |grad|^2
                    val vx = (-itDelta * ix) / gradMagSq
                    val vy = (-itDelta * iy) / gradMagSq
                    flowAccumX += vx * excess
                    flowAccumY += vy * excess
                }
            }
        }

        // Persist current Y grid for next frame comparison
        System.arraycopy(currLumaGrid, 0, prevLumaGrid, 0, GRID_SIZE)

        val interiorCount = ((GRID_W - 2) * (GRID_H - 2)).toFloat().coerceAtLeast(1f)
        val activeFraction = (activeBlocks / interiorCount).coerceIn(0f, 1f)
        val meanExcess = (weightedDeltaSum / interiorCount).coerceAtLeast(0f)

        // Normalize combined motion energy into 0.0f..1.0f
        val instantMagnitude = ((meanExcess / 26.0f) * 0.65f + (activeFraction * 1.45f) * 0.35f)
            .coerceIn(0f, 1f)

        // Exponential Moving Average prevents jitter in directional blur & viewfinder vectors
        smoothedMagnitude = smoothedMagnitude * 0.68f + instantMagnitude * 0.32f

        val flowLen = hypot(flowAccumX.toDouble(), flowAccumY.toDouble()).toFloat()
        if (flowLen > 1.2f && instantMagnitude > 0.06f) {
            val normX = (flowAccumX / flowLen).coerceIn(-1f, 1f)
            val normY = (flowAccumY / flowLen).coerceIn(-1f, 1f)
            val blendX = smoothedDirX * 0.72f + normX * 0.28f
            val blendY = smoothedDirY * 0.72f + normY * 0.28f
            val blendLen = hypot(blendX.toDouble(), blendY.toDouble()).toFloat().coerceAtLeast(0.001f)
            smoothedDirX = blendX / blendLen
            smoothedDirY = blendY / blendLen
        }

        val angleDeg = Math.toDegrees(atan2(smoothedDirY.toDouble(), smoothedDirX.toDouble())).toFloat()
        val simDenom = when {
            smoothedMagnitude >= 0.72f -> 4
            smoothedMagnitude >= 0.45f -> 8
            smoothedMagnitude >= 0.20f -> 15
            smoothedMagnitude >= 0.07f -> 30
            else -> 60
        }
        val shutterLabel = String.format(Locale.US, "1/%ds SIM", simDenom)

        return MotionMetrics(
            magnitude = smoothedMagnitude,
            dirX = smoothedDirX,
            dirY = smoothedDirY,
            activeBlockFraction = activeFraction,
            angleDegrees = angleDeg,
            simulatedShutterLabel = shutterLabel
        )
    }

    /**
     * Step 3: Custom Software-Based Directional Motion Blur Kernel (Pure Kotlin CPU Post-Processing).
     *
     * Integrates a symmetric multi-tap directional kernel along `(metrics.dirX, metrics.dirY)`
     * over ARGB integer pixel buffers without relying on deprecated RenderScript APIs.
     *
     * @param src Input source Bitmap (either live TextureView frame or full-res captured JPEG).
     * @param metrics Live motion telemetry from ImageAnalysis `analyzeYPlaneMotion()`.
     * @param intensity User shutter-drag intensity slider (0.0f..1.0f).
     * @param forLivePreview Uses faster compact tap budget for responsive 20fps viewfinder preview;
     *                       uses high-precision 15-tap convolution for saved JPEG post-processing.
     */
    fun applyDirectionalMotionBlur(
        src: Bitmap,
        metrics: MotionMetrics,
        intensity: Float,
        forLivePreview: Boolean
    ): Bitmap {
        val safeIntensity = intensity.coerceIn(0f, 1f)
        if (safeIntensity <= 0.02f) {
            return src.copy(Bitmap.Config.ARGB_8888, false)
        }

        val w = src.width.coerceAtLeast(1)
        val h = src.height.coerceAtLeast(1)
        val maxWorkEdge = if (forLivePreview) 360 else 1080
        val scale = if (maxOf(w, h) > maxWorkEdge) maxWorkEdge / maxOf(w, h).toFloat() else 1f
        val workW = (w * scale).roundToInt().coerceAtLeast(64)
        val workH = (h * scale).roundToInt().coerceAtLeast(64)

        val workBmp = if (workW != w || workH != h) {
            Bitmap.createScaledBitmap(src, workW, workH, true)
        } else {
            src
        }

        val n = workW * workH
        val srcPixels = IntArray(n)
        val dstPixels = IntArray(n)
        workBmp.getPixels(srcPixels, 0, workW, 0, 0, workW, workH)
        if (workBmp != src && !workBmp.isRecycled) {
            workBmp.recycle()
        }

        // Normalize motion direction vector (defaults to crisp horizontal shutter drag if still)
        val dirLen = hypot(metrics.dirX.toDouble(), metrics.dirY.toDouble()).toFloat()
        val ux = if (dirLen > 0.001f) metrics.dirX / dirLen else 1.0f
        val uy = if (dirLen > 0.001f) metrics.dirY / dirLen else 0.0f

        // Compute directional shutter-drag streak radius scaled by detected velocity + effect intensity
        val effectiveVelocity = (0.28f + 0.72f * metrics.magnitude.coerceIn(0f, 1f))
        val baseRadiusPx = workW * 0.036f * safeIntensity * effectiveVelocity
        val halfTaps = if (forLivePreview) 4 else 7
        val tapStepPx = (baseRadiusPx / halfTaps.toFloat()).coerceAtLeast(0.85f)

        // Precompute symmetric tent/Gaussian shutter-open weights + floating sample offsets
        val tapCount = halfTaps * 2 + 1
        val offX = IntArray(tapCount)
        val offY = IntArray(tapCount)
        val weights = FloatArray(tapCount)
        var weightSum = 0f

        for (i in 0 until tapCount) {
            val k = i - halfTaps
            offX[i] = (ux * tapStepPx * k).roundToInt()
            offY[i] = (uy * tapStepPx * k).roundToInt()
            // Triangular shutter envelope biased slightly around center for crisp focal anchor
            val weight = 1.0f - (abs(k).toFloat() / (halfTaps + 1.35f))
            weights[i] = weight
            weightSum += weight
        }
        val invWeightSum = 1.0f / weightSum.coerceAtLeast(0.001f)

        val maxX = workW - 1
        val maxY = workH - 1

        for (y in 0 until workH) {
            val rowOut = y * workW
            for (x in 0 until workW) {
                var accR = 0f
                var accG = 0f
                var accB = 0f

                for (t in 0 until tapCount) {
                    val sx = (x + offX[t]).coerceIn(0, maxX)
                    val sy = (y + offY[t]).coerceIn(0, maxY)
                    val px = srcPixels[sy * workW + sx]
                    val wt = weights[t]
                    accR += ((px ushr 16) and 0xFF) * wt
                    accG += ((px ushr 8) and 0xFF) * wt
                    accB += (px and 0xFF) * wt
                }

                val r = (accR * invWeightSum).roundToInt().coerceIn(0, 255)
                val g = (accG * invWeightSum).roundToInt().coerceIn(0, 255)
                val b = (accB * invWeightSum).roundToInt().coerceIn(0, 255)
                dstPixels[rowOut + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val blurredWork = Bitmap.createBitmap(workW, workH, Bitmap.Config.ARGB_8888)
        blurredWork.setPixels(dstPixels, 0, workW, 0, 0, workW, workH)

        return if (!forLivePreview && (workW != w || workH != h)) {
            Bitmap.createScaledBitmap(blurredWork, w, h, true).also {
                if (it != blurredWork && !blurredWork.isRecycled) blurredWork.recycle()
            }
        } else {
            blurredWork
        }
    }

    companion object {
        private const val GRID_W = 48
        private const val GRID_H = 36
        private const val GRID_SIZE = GRID_W * GRID_H
        private const val NOISE_FLOOR = 14
    }
}

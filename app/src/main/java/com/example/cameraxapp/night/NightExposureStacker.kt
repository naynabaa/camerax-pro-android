package com.example.cameraxapp.night

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class NightExposureStacker {

    enum class NightState {
        BRIGHT,
        LOW_LIGHT_DETECTED,
        STABILIZING,
        AUTO_TRIGGERED,
        ARMED_COOLDOWN
    }

    data class LowLightAnalysis(
        val avgLuma: Float,
        val rawLuma: Float,
        val darkPixelRatio: Float,
        val highlightRatio: Float,
        val isLowLight: Boolean,
        val isSceneSteady: Boolean,
        val dwellProgress: Float,
        val recommendedFrameCount: Int,
        val shouldAutoTriggerStack: Boolean,
        val state: NightState
    )

    private var hasSample: Boolean = false
    private var smoothedLuma: Float = 128f
    private var prevRawLuma: Float = 128f
    private var isLowLightActive: Boolean = false
    private var steadyStartMs: Long = 0L
    private var lastAutoTriggerMs: Long = 0L
    private var triggeredInCurrentDarkEpisode: Boolean = false

    @Volatile
    var lastRecommendedFrameCount: Int = 4
        private set

    @Volatile
    var lastAvgLuma: Float = 128f
        private set

    fun reset() {
        hasSample = false
        smoothedLuma = 128f
        prevRawLuma = 128f
        isLowLightActive = false
        steadyStartMs = 0L
        triggeredInCurrentDarkEpisode = false
        lastRecommendedFrameCount = 4
        lastAvgLuma = 128f
    }

    fun markAutoTriggered() {
        lastAutoTriggerMs = System.currentTimeMillis()
        triggeredInCurrentDarkEpisode = true
        steadyStartMs = 0L
    }

    fun clearDarkEpisodeLock() {
        triggeredInCurrentDarkEpisode = false
        steadyStartMs = 0L
    }

    /**
     * Stage 1: Analyze ImageProxy Y-plane luminance on a background Executor to detect dark scenes
     * and decide when to automatically launch multi-frame Night Mode exposure stacking.
     */
    fun analyzeLowLight(imageProxy: ImageProxy): LowLightAnalysis {
        if (imageProxy.planes.isEmpty()) {
            return LowLightAnalysis(
                avgLuma = 128f,
                rawLuma = 128f,
                darkPixelRatio = 0f,
                highlightRatio = 0f,
                isLowLight = false,
                isSceneSteady = true,
                dwellProgress = 0f,
                recommendedFrameCount = 3,
                shouldAutoTriggerStack = false,
                state = NightState.BRIGHT
            )
        }

        val yPlane = imageProxy.planes[0]
        val yBuf = yPlane.buffer
        val w = imageProxy.width.coerceAtLeast(1)
        val h = imageProxy.height.coerceAtLeast(1)
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val limit = yBuf.limit()

        val stepX = max(4, w / 48)
        val stepY = max(4, h / 36)

        var lumaSum = 0L
        var darkCount = 0
        var highCount = 0
        var totalSamples = 0

        var y = 0
        while (y < h) {
            val rowOff = y * rowStride
            var x = 0
            while (x < w) {
                val idx = rowOff + x * pixelStride
                if (idx in 0 until limit) {
                    val luma = yBuf.get(idx).toInt() and 0xFF
                    lumaSum += luma
                    if (luma < 44) darkCount++
                    if (luma >= 235) highCount++
                    totalSamples++
                }
                x += stepX
            }
            y += stepY
        }

        val rawLuma = if (totalSamples > 0) lumaSum.toFloat() / totalSamples.toFloat() else 128f
        val darkRatio = if (totalSamples > 0) darkCount.toFloat() / totalSamples.toFloat() else 0f
        val highRatio = if (totalSamples > 0) highCount.toFloat() / totalSamples.toFloat() else 0f

        val lumaDelta = if (hasSample) abs(rawLuma - prevRawLuma) else 0f
        prevRawLuma = rawLuma

        smoothedLuma = if (hasSample) {
            smoothedLuma * 0.72f + rawLuma * 0.28f
        } else {
            hasSample = true
            rawLuma
        }
        lastAvgLuma = smoothedLuma

        // Hysteresis prevents mode flickering when ambient illumination sits near threshold
        if (!isLowLightActive && (smoothedLuma < ENTER_LOW_LIGHT_LUMA || (darkRatio > 0.62f && smoothedLuma < 72f))) {
            isLowLightActive = true
            steadyStartMs = 0L
        } else if (isLowLightActive && smoothedLuma > EXIT_LOW_LIGHT_LUMA && darkRatio < 0.42f) {
            isLowLightActive = false
            steadyStartMs = 0L
            triggeredInCurrentDarkEpisode = false
        }

        val frameCount = when {
            smoothedLuma < 28f || darkRatio > 0.80f -> 5
            isLowLightActive -> 4
            else -> 3
        }
        lastRecommendedFrameCount = frameCount

        val now = System.currentTimeMillis()
        val isSteady = lumaDelta <= 4.8f
        var dwellProgress = 0f
        var shouldTrigger = false

        if (isLowLightActive) {
            if (!isSteady) {
                steadyStartMs = 0L
            } else {
                if (steadyStartMs == 0L) {
                    steadyStartMs = now
                }
                val dwellMs = (now - steadyStartMs).coerceAtLeast(0L)
                dwellProgress = (dwellMs.toFloat() / LOW_LIGHT_DWELL_MS.toFloat()).coerceIn(0f, 1f)
                val cooldownElapsed = (now - lastAutoTriggerMs) >= AUTO_TRIGGER_COOLDOWN_MS
                if (!triggeredInCurrentDarkEpisode && cooldownElapsed && dwellMs >= LOW_LIGHT_DWELL_MS) {
                    shouldTrigger = true
                }
            }
        }

        val state = when {
            !isLowLightActive -> NightState.BRIGHT
            shouldTrigger -> NightState.AUTO_TRIGGERED
            triggeredInCurrentDarkEpisode -> NightState.ARMED_COOLDOWN
            isSteady && dwellProgress > 0f -> NightState.STABILIZING
            else -> NightState.LOW_LIGHT_DETECTED
        }

        return LowLightAnalysis(
            avgLuma = smoothedLuma,
            rawLuma = rawLuma,
            darkPixelRatio = darkRatio,
            highlightRatio = highRatio,
            isLowLight = isLowLightActive,
            isSceneSteady = isSteady,
            dwellProgress = dwellProgress,
            recommendedFrameCount = frameCount,
            shouldAutoTriggerStack = shouldTrigger,
            state = state
        )
    }

    /**
     * Generates a bracketed sequence of hardware EV compensation indices starting at 0 EV (sharp reference).
     */
    fun buildExposureBracketIndices(minEv: Int, maxEv: Int, frameCount: Int): IntArray {
        val clampedCount = frameCount.coerceIn(3, 5)
        val anchor = 0.coerceIn(minEv, maxEv)
        val posStep1 = max(0, maxEv / 3).coerceIn(minEv, maxEv)
        val posStep2 = max(0, (maxEv * 2) / 3).coerceIn(minEv, maxEv)
        val posStepMax = maxEv.coerceIn(minEv, maxEv)
        val negStep1 = (-max(0, maxEv / 3)).coerceIn(minEv, maxEv)

        return when (clampedCount) {
            5 -> intArrayOf(anchor, negStep1, posStep1, posStep2, posStepMax)
            4 -> intArrayOf(anchor, negStep1, posStep1, posStep2)
            else -> intArrayOf(anchor, negStep1, posStep2)
        }
    }

    /**
     * Decodes a captured CameraX ImageProxy (JPEG or YUV_420_888) into a bounded, upright ARGB Bitmap.
     */
    fun decodeCapturedProxyToBitmap(imageProxy: ImageProxy, maxDim: Int = 1920): Bitmap? {
        val rawBmp: Bitmap? = if (imageProxy.format == ImageFormat.JPEG || imageProxy.planes.size == 1) {
            val buf = imageProxy.planes[0].buffer.duplicate().apply { rewind() }
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } else if (imageProxy.planes.size >= 3) {
            val w = imageProxy.width
            val h = imageProxy.height
            val nv21 = yuv420888ToNv21(imageProxy)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            val baos = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, w, h), 94, baos)
            val jpg = baos.toByteArray()
            val sample = computeInSampleSize(w, h, maxDim)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(jpg, 0, jpg.size, opts)
        } else {
            null
        }

        if (rawBmp == null) return null

        val rotDeg = imageProxy.imageInfo.rotationDegrees
        val curMax = max(rawBmp.width, rawBmp.height).coerceAtLeast(1)
        val downScale = if (curMax > maxDim) maxDim.toFloat() / curMax.toFloat() else 1f
        if (rotDeg == 0 && downScale >= 0.99f) {
            return rawBmp
        }
        val m = Matrix().apply {
            if (rotDeg != 0) postRotate(rotDeg.toFloat())
            if (downScale < 0.99f) postScale(downScale, downScale)
        }
        val rotated = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, m, true)
        if (rotated != rawBmp && !rawBmp.isRecycled) {
            rawBmp.recycle()
        }
        return rotated
    }

    /**
     * Stage 2: Align and fuse multiple bracketed exposures row-by-row with highlight protection,
     * motion ghost rejection, and adaptive shadow recovery LUT.
     */
    fun stackExposureFrames(frames: List<Bitmap>, avgLumaHint: Float = 45f): Bitmap? {
        if (frames.isEmpty()) return null
        val ref = frames[0]
        if (frames.size == 1) {
            return ref.copy(Bitmap.Config.ARGB_8888, true)
        }

        val w = ref.width
        val h = ref.height
        if (w <= 0 || h <= 0) return null

        val count = frames.size
        val shiftsX = IntArray(count)
        val shiftsY = IntArray(count)
        for (k in 1 until count) {
            if (frames[k].width == w && frames[k].height == h) {
                val shift = estimateTranslationalShift(ref, frames[k])
                shiftsX[k] = shift.first
                shiftsY[k] = shift.second
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rows = Array(count) { IntArray(w) }
        val outRow = IntArray(w)

        // Adaptive night tone-curve LUT (stronger lift when scene luma is darker)
        val darkness = ((135f - avgLumaHint) / 110f).coerceIn(0.15f, 1.0f)
        val gamma = (0.86f - darkness * 0.34f).toDouble()
        val shadowLift = darkness * 12.0
        val lut = IntArray(256)
        for (i in 0..255) {
            val n = i / 255.0
            val lifted = Math.pow(n, gamma) * 255.0 + shadowLift * Math.pow(1.0 - n, 2.2)
            lut[i] = lifted.roundToInt().coerceIn(0, 255)
        }

        // Row-by-row fusion keeps peak RAM low even on 12MP sensors
        for (y in 0 until h) {
            ref.getPixels(rows[0], 0, w, 0, y, w, 1)
            for (k in 1 until count) {
                val frame = frames[k]
                if (frame.width == w && frame.height == h) {
                    val sy = (y - shiftsY[k]).coerceIn(0, h - 1)
                    frame.getPixels(rows[k], 0, w, 0, sy, w, 1)
                } else {
                    System.arraycopy(rows[0], 0, rows[k], 0, w)
                }
            }

            for (x in 0 until w) {
                val p0 = rows[0][x]
                val r0 = (p0 ushr 16) and 0xFF
                val g0 = (p0 ushr 8) and 0xFF
                val b0 = p0 and 0xFF
                val l0 = (r0 * 3 + g0 * 6 + b0) / 10f

                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var totalW = 0f

                for (k in 0 until count) {
                    val sx = (x - shiftsX[k]).coerceIn(0, w - 1)
                    val pk = rows[k][sx]
                    val rk = (pk ushr 16) and 0xFF
                    val gk = (pk ushr 8) and 0xFF
                    val bk = pk and 0xFF
                    val lk = (rk * 3 + gk * 6 + bk) / 10f

                    // Highlight & shadow exposure sweet-spot weighting (Mertens-inspired Gaussian curve)
                    val normL = lk / 255f
                    val dist = normL - 0.52f
                    val wellExposedW = exp((-dist * dist * 5.2f).toDouble()).toFloat().coerceAtLeast(0.08f)

                    // Ghost rejection against anchor frame 0 (allows intentional EV lift while rejecting moving objects)
                    val ghostW = if (k == 0) {
                        1.15f
                    } else {
                        val expectedGainLift = 1.0f + k * 0.12f
                        val gainAdjustedDiff = abs(lk - min(255f, l0 * expectedGainLift))
                        when {
                            gainAdjustedDiff < 30f -> 1.0f
                            gainAdjustedDiff < 68f -> (68f - gainAdjustedDiff) / 38f
                            else -> 0.08f
                        }
                    }

                    // Preserve neon highlights from base/underexposed frames if brighter frames clip white
                    val highlightGuard = if (k > 0 && lk >= 248f && l0 < 232f) 0.05f else 1.0f

                    val wPix = wellExposedW * ghostW * highlightGuard
                    sumR += rk * wPix
                    sumG += gk * wPix
                    sumB += bk * wPix
                    totalW += wPix
                }

                val invW = if (totalW > 1e-4f) 1f / totalW else 1f
                val fusedR = (sumR * invW).roundToInt().coerceIn(0, 255)
                val fusedG = (sumG * invW).roundToInt().coerceIn(0, 255)
                val fusedB = (sumB * invW).roundToInt().coerceIn(0, 255)

                outRow[x] = (0xFF shl 24) or (lut[fusedR] shl 16) or (lut[fusedG] shl 8) or lut[fusedB]
            }
            result.setPixels(outRow, 0, w, 0, y, w, 1)
        }

        return result
    }

    /**
     * Estimates (dx, dy) translational camera shake between two frames via downsampled 96x72 SAD correlation.
     */
    private fun estimateTranslationalShift(refBmp: Bitmap, targetBmp: Bitmap): Pair<Int, Int> {
        val tw = 96
        val th = 72
        val w = refBmp.width
        val h = refBmp.height
        val scaleToFullX = w.toFloat() / tw.toFloat()
        val scaleToFullY = h.toFloat() / th.toFloat()

        val smallRef = Bitmap.createScaledBitmap(refBmp, tw, th, true)
        val smallTgt = Bitmap.createScaledBitmap(targetBmp, tw, th, true)
        val pRef = IntArray(tw * th)
        val pTgt = IntArray(tw * th)
        smallRef.getPixels(pRef, 0, tw, 0, 0, tw, th)
        smallTgt.getPixels(pTgt, 0, tw, 0, 0, tw, th)
        smallRef.recycle()
        smallTgt.recycle()

        val lRef = IntArray(tw * th)
        val lTgt = IntArray(tw * th)
        var meanRef = 0.0
        var meanTgt = 0.0
        for (i in pRef.indices) {
            val pr = pRef[i]
            val pt = pTgt[i]
            val lr = (((pr ushr 16) and 0xFF) * 3 + ((pr ushr 8) and 0xFF) * 6 + (pr and 0xFF)) / 10
            val lt = (((pt ushr 16) and 0xFF) * 3 + ((pt ushr 8) and 0xFF) * 6 + (pt and 0xFF)) / 10
            lRef[i] = lr
            lTgt[i] = lt
            meanRef += lr
            meanTgt += lt
        }
        // Brightness-normalized gain cancels EV differences between bracketed frames
        val gain = if (meanTgt > 1.0) (meanRef / meanTgt).toFloat().coerceIn(0.35f, 3.0f) else 1f

        var bestDx = 0
        var bestDy = 0
        var bestSad = Long.MAX_VALUE
        val maxShift = 6

        for (dy in -maxShift..maxShift) {
            for (dx in -maxShift..maxShift) {
                var sumErr = 0L
                var count = 0
                for (y in 10 until (th - 10) step 2) {
                    val ty = y + dy
                    if (ty !in 0 until th) continue
                    for (x in 12 until (tw - 12) step 2) {
                        val tx = x + dx
                        if (tx !in 0 until tw) continue
                        val normalizedTgt = (lTgt[ty * tw + tx] * gain).roundToInt().coerceIn(0, 255)
                        sumErr += abs(lRef[y * tw + x] - normalizedTgt)
                        count++
                    }
                }
                if (count > 0 && sumErr / count < bestSad) {
                    bestSad = sumErr / count
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        val fullDx = (bestDx * scaleToFullX).roundToInt().coerceIn(-24, 24)
        val fullDy = (bestDy * scaleToFullY).roundToInt().coerceIn(-24, 24)
        return Pair(fullDx, fullDy)
    }

    private fun computeInSampleSize(srcW: Int, srcH: Int, maxDim: Int): Int {
        var sample = 1
        val largest = max(srcW, srcH)
        while ((largest / sample) > maxDim * 2) {
            sample *= 2
        }
        return sample
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val nv21 = ByteArray(ySize + (width * height / 2))

        val yBuf = image.planes[0].buffer.duplicate().apply { rewind() }
        val uBuf = image.planes[1].buffer.duplicate().apply { rewind() }
        val vBuf = image.planes[2].buffer.duplicate().apply { rewind() }

        val yRowStride = image.planes[0].rowStride
        val yPixelStride = image.planes[0].pixelStride
        var pos = 0
        for (r in 0 until height) {
            for (c in 0 until width) {
                val idx = r * yRowStride + c * yPixelStride
                nv21[pos++] = if (idx < yBuf.limit()) yBuf.get(idx) else 0
            }
        }

        val vRowStride = image.planes[2].rowStride
        val vPixelStride = image.planes[2].pixelStride
        val uRowStride = image.planes[1].rowStride
        val uPixelStride = image.planes[1].pixelStride
        for (r in 0 until height / 2) {
            for (c in 0 until width / 2) {
                val vi = r * vRowStride + c * vPixelStride
                val ui = r * uRowStride + c * uPixelStride
                nv21[pos++] = if (vi < vBuf.limit()) vBuf.get(vi) else (-128).toByte()
                nv21[pos++] = if (ui < uBuf.limit()) uBuf.get(ui) else (-128).toByte()
            }
        }
        return nv21
    }

    companion object {
        private const val ENTER_LOW_LIGHT_LUMA = 58f
        private const val EXIT_LOW_LIGHT_LUMA = 68f
        private const val LOW_LIGHT_DWELL_MS = 1500L
        private const val AUTO_TRIGGER_COOLDOWN_MS = 14000L
    }
}

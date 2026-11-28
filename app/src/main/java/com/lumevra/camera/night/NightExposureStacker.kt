package com.lumevra.camera.night

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
     * Generates a balanced bracketed sequence of hardware EV compensation indices starting at 0 EV,
     * with dedicated underexposed highlight-recovery frames to stop lamps and bright terraces clipping.
     */
    fun buildExposureBracketIndices(minEv: Int, maxEv: Int, frameCount: Int): IntArray {
        val clampedCount = frameCount.coerceIn(3, 7)
        val anchor = 0.coerceIn(minEv, maxEv)
        val absNeg = abs(minEv).coerceAtLeast(abs(maxEv))
        val negDeep = (-max(1, (absNeg * 2) / 3)).coerceIn(minEv, maxEv)
        val negMid = (-max(1, absNeg / 3)).coerceIn(minEv, maxEv)
        val negLight = (-max(1, absNeg / 5)).coerceIn(minEv, maxEv)
        val posStep1 = max(1, maxEv / 3).coerceIn(minEv, maxEv)
        val posStep2 = max(1, (maxEv * 2) / 3).coerceIn(minEv, maxEv)
        val posMax = maxEv.coerceIn(minEv, maxEv)

        return when {
            clampedCount >= 7 -> intArrayOf(anchor, negDeep, negMid, negLight, posStep1, posStep2, posMax)
            clampedCount >= 5 -> intArrayOf(anchor, negDeep, negMid, posStep1, posStep2)
            clampedCount == 4 -> intArrayOf(anchor, negDeep, posStep1, posStep2)
            else -> intArrayOf(anchor, negDeep, posStep1)
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
            yuvImage.compressToJpeg(Rect(0, 0, w, h), 95, baos)
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
     * Stage 2: 2-Stage Coarse-to-Fine Aligned Multi-Frame Night Fusion:
     * - Pixel-accurate shift estimation with corrected sign (eliminates double-edges & border smears)
     * - Automatic shake rejection for badly misaligned frames
     * - Highlight recovery from underexposed frames (prevents streetlight/terrace white blowout)
     * - Black-point anchored luminance-preserving filmic S-curve (eliminates milky gray sky haze)
     * - Shadow chroma denoise + edge-preserving unsharp mask for crisp night architecture
     */
    fun stackExposureFrames(
        frames: List<Bitmap>,
        avgLumaHint: Float = 45f,
        bracketOffsets: IntArray? = null,
        deepNightSky: Boolean = true
    ): Bitmap? {
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
        val frameTrust = FloatArray(count) { 1.0f }
        val frameRelGain = FloatArray(count) { 1.0f }

        // Measure per-frame brightness & perform 2-stage coarse-to-fine alignment
        val refMeanLuma = estimateBitmapMeanLuma(ref).coerceAtLeast(4f)
        for (k in 1 until count) {
            if (frames[k].width == w && frames[k].height == h) {
                val tgtMean = estimateBitmapMeanLuma(frames[k]).coerceAtLeast(4f)
                frameRelGain[k] = (tgtMean / refMeanLuma).coerceIn(0.28f, 3.4f)
                val align = estimateCoarseToFineShift(ref, frames[k], refMeanLuma / tgtMean)
                shiftsX[k] = align.first
                shiftsY[k] = align.second
                val residualSad = align.third
                // Reject / sharply down-weight badly shaken frames so they never smear the stack
                frameTrust[k] = when {
                    residualSad <= 14f -> 1.0f
                    residualSad <= 24f -> (24f - residualSad) / 10f
                    else -> 0.0f
                }
            } else {
                frameTrust[k] = 0.0f
            }
        }

        // Row-by-row exposure fusion + histogram collection for true black-point sky anchor
        val fusedBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rows = Array(count) { IntArray(w) }
        val outRow = IntArray(w)
        val lumaHist = IntArray(256)

        for (y in 0 until h) {
            ref.getPixels(rows[0], 0, w, 0, y, w, 1)
            val rowValid = BooleanArray(count)
            rowValid[0] = true
            for (k in 1 until count) {
                if (frameTrust[k] > 0.02f) {
                    // CORRECTED SIGN: target[y + dy, x + dx] matches ref[y, x]
                    val sy = y + shiftsY[k]
                    if (sy in 0 until h) {
                        frames[k].getPixels(rows[k], 0, w, 0, sy, w, 1)
                        rowValid[k] = true
                    }
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
                    if (!rowValid[k]) continue
                    val sx = if (k == 0) x else (x + shiftsX[k])
                    // Skip out-of-bounds shifted pixels cleanly so edges NEVER smear into horizontal bands
                    if (sx !in 0 until w) continue

                    val pk = rows[k][sx]
                    val rk = (pk ushr 16) and 0xFF
                    val gk = (pk ushr 8) and 0xFF
                    val bk = pk and 0xFF
                    val lk = (rk * 3 + gk * 6 + bk) / 10f

                    val evHint = bracketOffsets?.getOrNull(k) ?: (if (k == 1) -2 else k - 1)
                    val isUnderExposed = evHint < 0 || frameRelGain[k] < 0.88f
                    val isOverExposed = evHint > 0 || frameRelGain[k] > 1.12f

                    // Mertens-style well-exposedness weight peaking cleanly in midtones
                    val normL = lk / 255f
                    val dist = normL - 0.48f
                    var wellExposedW = exp((-dist * dist * 5.8f).toDouble()).toFloat().coerceAtLeast(0.06f)

                    // Active Highlight Recovery: when anchor is bright/clipped, strongly pull from darker frames
                    if (l0 > 205f) {
                        wellExposedW *= when {
                            isUnderExposed && lk < 238f -> 2.85f
                            isOverExposed -> 0.04f
                            k == 0 && l0 > 242f -> 0.35f
                            else -> 0.85f
                        }
                    } else if (l0 < 48f && isOverExposed && lk < 210f) {
                        // Clean shadow boost from brighter brackets without clipping lights
                        wellExposedW *= 1.35f
                    }

                    // Strict motion & shake ghost rejection against gain-matched anchor pixel
                    val ghostW = if (k == 0) {
                        1.25f
                    } else {
                        val expectedL = min(255f, l0 * frameRelGain[k])
                        val diff = abs(lk - expectedL)
                        when {
                            diff < 22f -> 1.0f
                            diff < 52f -> (52f - diff) / 30f
                            else -> 0.02f
                        }
                    }

                    // Prevent clipped frames from polluting unclipped regions
                    val clipGuard = if (k > 0 && lk >= 250f && l0 < 235f) 0.02f else 1.0f

                    val wPix = wellExposedW * ghostW * clipGuard * frameTrust[k]
                    sumR += rk * wPix
                    sumG += gk * wPix
                    sumB += bk * wPix
                    totalW += wPix
                }

                val invW = if (totalW > 1e-4f) 1f / totalW else 1f
                val fusedR = (sumR * invW).roundToInt().coerceIn(0, 255)
                val fusedG = (sumG * invW).roundToInt().coerceIn(0, 255)
                val fusedB = (sumB * invW).roundToInt().coerceIn(0, 255)
                val fusedL = ((fusedR * 3 + fusedG * 6 + fusedB) / 10).coerceIn(0, 255)
                if ((x and 3) == 0 && (y and 3) == 0) {
                    lumaHist[fusedL]++
                }
                outRow[x] = (0xFF shl 24) or (fusedR shl 16) or (fusedG shl 8) or fusedB
            }
            fusedBmp.setPixels(outRow, 0, w, 0, y, w, 1)
        }

        // Compute 4th-percentile dark floor so milky night sky haze is cleanly anchored to rich blacks
        val histTotal = lumaHist.sum().coerceAtLeast(1)
        val p4Target = (histTotal * (if (deepNightSky) 0.045f else 0.02f)).toInt()
        var accum = 0
        var blackFloor = 0
        for (i in 0..72) {
            accum += lumaHist[i]
            if (accum >= p4Target) {
                blackFloor = i
                break
            }
        }
        val dehazeBlack = (blackFloor * (if (deepNightSky) 0.72f else 0.42f)).coerceIn(0f, 34f)

        // Luminance-preserving Filmic Night Tone Curve (NO flat gray shadow pedestal!)
        val darkness = ((120f - avgLumaHint) / 100f).coerceIn(0.15f, 1.0f)
        val midLiftPower = if (deepNightSky) {
            (0.92f - darkness * 0.14f).toDouble()
        } else {
            (0.84f - darkness * 0.22f).toDouble()
        }
        val toneLumaLut = FloatArray(256)
        val span = (255f - dehazeBlack).coerceAtLeast(140f)
        for (i in 0..255) {
            val anchored = ((i - dehazeBlack) / span).coerceIn(0f, 1f)
            // Smooth S-curve toe keeps deep sky black (0 -> 0) while lifting mid-shadows naturally
            val toe = anchored * anchored * (3f - 2f * anchored)
            val curved = Math.pow(anchored.toDouble(), midLiftPower).toFloat()
            val blendToe = if (deepNightSky) 0.26f else 0.12f
            val targetN = (curved * (1f - blendToe) + toe * blendToe).coerceIn(0f, 1f)
            // Soft shoulder highlight roll-off above 0.86 prevents harsh white clipping
            val finalN = if (targetN > 0.86f) {
                0.86f + (targetN - 0.86f) * (1f - 0.28f * ((targetN - 0.86f) / 0.14f))
            } else {
                targetN
            }
            toneLumaLut[i] = (finalN * 255f).coerceIn(0f, 255f)
        }

        // Stage 3: Shadow Chroma Denoise + Luminance-Preserving Tone Mapping + Edge-Preserving Unsharp Mask
        val finalBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val prevRow = IntArray(w)
        val currRow = IntArray(w)
        val nextRow = IntArray(w)
        val finalRow = IntArray(w)
        val satBoost = if (deepNightSky) 1.08f else 1.04f

        for (y in 0 until h) {
            fusedBmp.getPixels(prevRow, 0, w, 0, (y - 1).coerceAtLeast(0), w, 1)
            fusedBmp.getPixels(currRow, 0, w, 0, y, w, 1)
            fusedBmp.getPixels(nextRow, 0, w, 0, (y + 1).coerceAtMost(h - 1), w, 1)

            for (x in 0 until w) {
                val c = currRow[x]
                val cr = (c ushr 16) and 0xFF
                val cg = (c ushr 8) and 0xFF
                val cb = c and 0xFF
                val lumaC = (cr * 3 + cg * 6 + cb) / 10f

                val xm = (x - 1).coerceAtLeast(0)
                val xp = (x + 1).coerceAtMost(w - 1)
                val pL = currRow[xm]
                val pR = currRow[xp]
                val pT = prevRow[x]
                val pB = nextRow[x]

                val avgR = (((pL ushr 16) and 0xFF) + ((pR ushr 16) and 0xFF) + ((pT ushr 16) and 0xFF) + ((pB ushr 16) and 0xFF)) * 0.25f
                val avgG = (((pL ushr 8) and 0xFF) + ((pR ushr 8) and 0xFF) + ((pT ushr 8) and 0xFF) + ((pB ushr 8) and 0xFF)) * 0.25f
                val avgB = ((pL and 0xFF) + (pR and 0xFF) + (pT and 0xFF) + (pB and 0xFF)) * 0.25f
                val avgL = (avgR * 3f + avgG * 6f + avgB) / 10f

                // Chroma-only denoise in dark/shadow regions removes green/magenta sensor blotches
                var workR = cr.toFloat()
                var workG = cg.toFloat()
                var workB = cb.toFloat()
                if (lumaC < 96f) {
                    val chromaMix = ((96f - lumaC) / 96f) * 0.58f
                    val smoothedR = lumaC + (avgR - avgL)
                    val smoothedG = lumaC + (avgG - avgL)
                    val smoothedB = lumaC + (avgB - avgL)
                    workR = workR * (1f - chromaMix) + smoothedR * chromaMix
                    workG = workG * (1f - chromaMix) + smoothedG * chromaMix
                    workB = workB * (1f - chromaMix) + smoothedB * chromaMix
                }

                // Subtle edge-preserving detail recovery (gated to avoid sharpening flat sky noise)
                val detail = lumaC - avgL
                val sharpDelta = if (abs(detail) > 6.5f && lumaC > 22f) {
                    (detail * 0.34f).coerceIn(-18f, 18f)
                } else {
                    0f
                }

                // Apply luminance-preserving tone curve (keeps colors crisp instead of washed out)
                val idxL = lumaC.roundToInt().coerceIn(0, 255)
                val targetL = (toneLumaLut[idxL] + sharpDelta).coerceIn(0f, 255f)
                val ratio = if (lumaC > 3f) (targetL / lumaC).coerceIn(0.45f, 2.35f) else 1f

                val tr = (workR * ratio)
                val tg = (workG * ratio)
                val tb = (workB * ratio)

                // Mild night saturation polish (tapered in deep shadows so dark sky stays neutral)
                val effSat = 1f + (satBoost - 1f) * ((targetL - 20f) / 110f).coerceIn(0f, 1f)
                val outR = (targetL + (tr - targetL) * effSat).roundToInt().coerceIn(0, 255)
                val outG = (targetL + (tg - targetL) * effSat).roundToInt().coerceIn(0, 255)
                val outB = (targetL + (tb - targetL) * effSat).roundToInt().coerceIn(0, 255)

                finalRow[x] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
            finalBmp.setPixels(finalRow, 0, w, 0, y, w, 1)
        }

        if (!fusedBmp.isRecycled) fusedBmp.recycle()
        return finalBmp
    }

    private fun estimateBitmapMeanLuma(bmp: Bitmap): Float {
        val tw = 48
        val th = 36
        val small = Bitmap.createScaledBitmap(bmp, tw, th, true)
        val px = IntArray(tw * th)
        small.getPixels(px, 0, tw, 0, 0, tw, th)
        small.recycle()
        var sum = 0L
        for (p in px) {
            sum += (((p ushr 16) and 0xFF) * 3 + ((p ushr 8) and 0xFF) * 6 + (p and 0xFF)) / 10
        }
        return sum.toFloat() / px.size.coerceAtLeast(1).toFloat()
    }

    /**
     * 2-Stage Coarse-to-Fine Alignment:
     * Stage 1: Coarse search at 120x90 (~12-16px/step) catches large handheld drift.
     * Stage 2: Fine 1px-accurate local refinement on a 256x192 center-crop patch at full resolution
     *          eliminates double-edges and returns residual SAD for shake rejection.
     * Sign convention: ref[y, x] matches target[y + dy, x + dx].
     */
    private fun estimateCoarseToFineShift(
        refBmp: Bitmap,
        targetBmp: Bitmap,
        gainToMatchRef: Float
    ): Triple<Int, Int, Float> {
        val w = refBmp.width
        val h = refBmp.height
        val gain = gainToMatchRef.coerceIn(0.32f, 3.2f)

        // Stage 1: Coarse 120x90 SAD search
        val cw = 120
        val ch = 90
        val scaleX = w.toFloat() / cw.toFloat()
        val scaleY = h.toFloat() / ch.toFloat()

        val smallRef = Bitmap.createScaledBitmap(refBmp, cw, ch, true)
        val smallTgt = Bitmap.createScaledBitmap(targetBmp, cw, ch, true)
        val pRef = IntArray(cw * ch)
        val pTgt = IntArray(cw * ch)
        smallRef.getPixels(pRef, 0, cw, 0, 0, cw, ch)
        smallTgt.getPixels(pTgt, 0, cw, 0, 0, cw, ch)
        smallRef.recycle()
        smallTgt.recycle()

        val lRef = IntArray(cw * ch)
        val lTgt = IntArray(cw * ch)
        for (i in pRef.indices) {
            val pr = pRef[i]
            val pt = pTgt[i]
            lRef[i] = (((pr ushr 16) and 0xFF) * 3 + ((pr ushr 8) and 0xFF) * 6 + (pr and 0xFF)) / 10
            lTgt[i] = (((pt ushr 16) and 0xFF) * 3 + ((pt ushr 8) and 0xFF) * 6 + (pt and 0xFF)) / 10
        }

        var coarseDx = 0
        var coarseDy = 0
        var bestCoarseSad = Long.MAX_VALUE
        val maxCoarse = 6
        for (dy in -maxCoarse..maxCoarse) {
            for (dx in -maxCoarse..maxCoarse) {
                var sumErr = 0L
                var cnt = 0
                for (y in 10 until (ch - 10) step 2) {
                    val ty = y + dy
                    if (ty !in 0 until ch) continue
                    for (x in 12 until (cw - 12) step 2) {
                        val tx = x + dx
                        if (tx !in 0 until cw) continue
                        val normT = (lTgt[ty * cw + tx] * gain).roundToInt().coerceIn(0, 255)
                        sumErr += abs(lRef[y * cw + x] - normT)
                        cnt++
                    }
                }
                if (cnt > 0 && sumErr / cnt < bestCoarseSad) {
                    bestCoarseSad = sumErr / cnt
                    coarseDx = dx
                    coarseDy = dy
                }
            }
        }

        val initDx = (coarseDx * scaleX).roundToInt().coerceIn(-48, 48)
        val initDy = (coarseDy * scaleY).roundToInt().coerceIn(-48, 48)

        // Stage 2: Full-resolution 1px-accurate local refinement around (initDx, initDy)
        val patchW = min(220, w / 2).coerceAtLeast(64)
        val patchH = min(160, h / 2).coerceAtLeast(64)
        val fineRadius = 10
        val startX = ((w - patchW) / 2).coerceAtLeast(fineRadius + abs(initDx) + 2)
        val startY = ((h - patchH) / 2).coerceAtLeast(fineRadius + abs(initDy) + 2)
        if (startX + patchW + fineRadius + abs(initDx) >= w || startY + patchH + fineRadius + abs(initDy) >= h) {
            return Triple(initDx, initDy, bestCoarseSad.toFloat())
        }

        val refPatch = IntArray(patchW * patchH)
        refBmp.getPixels(refPatch, 0, patchW, startX, startY, patchW, patchH)
        val refLuma = IntArray(patchW * patchH)
        for (i in refPatch.indices) {
            val p = refPatch[i]
            refLuma[i] = (((p ushr 16) and 0xFF) * 3 + ((p ushr 8) and 0xFF) * 6 + (p and 0xFF)) / 10
        }

        val tgtW = patchW + fineRadius * 2
        val tgtH = patchH + fineRadius * 2
        val tgtX0 = (startX + initDx - fineRadius).coerceIn(0, w - tgtW)
        val tgtY0 = (startY + initDy - fineRadius).coerceIn(0, h - tgtH)
        val tgtPatch = IntArray(tgtW * tgtH)
        targetBmp.getPixels(tgtPatch, 0, tgtW, tgtX0, tgtY0, tgtW, tgtH)
        val tgtLuma = IntArray(tgtW * tgtH)
        for (i in tgtPatch.indices) {
            val p = tgtPatch[i]
            tgtLuma[i] = (((p ushr 16) and 0xFF) * 3 + ((p ushr 8) and 0xFF) * 6 + (p and 0xFF)) / 10
        }

        var bestFineDx = 0
        var bestFineDy = 0
        var bestFineSad = Float.MAX_VALUE

        // Step-2 search first, then ±1px polish for ultra-fast full-res alignment
        for (dy in -fineRadius..fineRadius step 2) {
            for (dx in -fineRadius..fineRadius step 2) {
                var err = 0L
                var cnt = 0
                for (py in 0 until patchH step 4) {
                    val ty = py + fineRadius + dy
                    val rOff = py * patchW
                    val tOff = ty * tgtW + fineRadius + dx
                    for (px in 0 until patchW step 4) {
                        val nt = (tgtLuma[tOff + px] * gain).roundToInt().coerceIn(0, 255)
                        err += abs(refLuma[rOff + px] - nt)
                        cnt++
                    }
                }
                val sad = if (cnt > 0) err.toFloat() / cnt.toFloat() else Float.MAX_VALUE
                if (sad < bestFineSad) {
                    bestFineSad = sad
                    bestFineDx = dx
                    bestFineDy = dy
                }
            }
        }

        val centerDx = bestFineDx
        val centerDy = bestFineDy
        for (dy in (centerDy - 1)..(centerDy + 1)) {
            if (dy !in -fineRadius..fineRadius) continue
            for (dx in (centerDx - 1)..(centerDx + 1)) {
                if (dx !in -fineRadius..fineRadius) continue
                var err = 0L
                var cnt = 0
                for (py in 0 until patchH step 3) {
                    val ty = py + fineRadius + dy
                    val rOff = py * patchW
                    val tOff = ty * tgtW + fineRadius + dx
                    for (px in 0 until patchW step 3) {
                        val nt = (tgtLuma[tOff + px] * gain).roundToInt().coerceIn(0, 255)
                        err += abs(refLuma[rOff + px] - nt)
                        cnt++
                    }
                }
                val sad = if (cnt > 0) err.toFloat() / cnt.toFloat() else Float.MAX_VALUE
                if (sad < bestFineSad) {
                    bestFineSad = sad
                    bestFineDx = dx
                    bestFineDy = dy
                }
            }
        }

        val finalDx = ((tgtX0 - startX) + fineRadius + bestFineDx).coerceIn(-56, 56)
        val finalDy = ((tgtY0 - startY) + fineRadius + bestFineDy).coerceIn(-56, 56)
        return Triple(finalDx, finalDy, bestFineSad)
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

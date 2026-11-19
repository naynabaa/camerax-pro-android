package com.example.cameraxapp.panorama

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PanoramaStitcher: Automated Panorama Capture & Stitching Pipeline Using CameraX ImageAnalysis
 *
 * 3-Stage Architecture (Standard Android APIs — Zero External CV Dependencies):
 * -----------------------------------------------------------------------------
 * 1. CAMERA-X IMAGEANALYSIS OVERLAP & ALIGNMENT ENGINE (`analyzeFrame`):
 *    - Samples `imageProxy.planes[0]` (the 8-bit Y luma plane of YUV_420_888) into a compact
 *      64x48 orientation-corrected grid (`currGrid`).
 *    - Runs 2D sliding-window Sum of Absolute Differences (SAD) correlation against the last
 *      captured anchor slice (`anchorGrid`) across horizontal shift `dx` and vertical drift `dy`.
 *    - Computes exact frame-to-frame overlap ratio (`0.0f..1.0f`), optical alignment offset
 *      `(dxNorm, dyNorm)`, match confidence, and real-time UI guidance arrows.
 *
 * 2. AUTOMATED CAPTURE TRIGGER SEQUENCE:
 *    - Triggers automatic slice capture when frame overlap drops into the optimal stitching window
 *      (`overlapRatio <= 0.70f`) with >= 260ms cooldown, or gyro yaw fusion fallback (`>= 7.8 deg`).
 *    - Decodes `ImageProxy` YUV_420_888 directly to lossless ARGB pixels (zero JPEG smear, zero
 *      digital upscaling above native camera resolution).
 *
 * 3. STANDARD ANDROID IMAGE PROCESSING STITCHER (`stitchSlices`):
 *    - Refines adjacent slice seam coordinates via 2-stage coarse-to-fine `refineSeamOffsetPx`.
 *    - Composites slices with a narrow seam feather mask (preventing double-exposure ghosting),
 *      trims exact pitch-drift borders, and applies mild unsharp crispness enhancement.
 */
class PanoramaStitcher {

    enum class PanoramaGuidance {
        READY,
        PAN_RIGHT,
        PAN_LEFT,
        TILT_UP,
        TILT_DOWN,
        SLOW_DOWN,
        AUTO_CAPTURED,
        FINISHING
    }

    data class PanoramaSlice(
        val bitmap: Bitmap,
        val stepDxNorm: Float,
        val stepDyNorm: Float,
        val overlapRatio: Float,
        val deltaYawDeg: Float,
        val pitchDriftDeg: Float
    )

    data class AlignmentResult(
        val dxNorm: Float,
        val dyNorm: Float,
        val overlapRatio: Float,
        val confidence: Float,
        val guidance: PanoramaGuidance,
        val shouldTriggerAutoCapture: Boolean,
        val shouldAutoFinish: Boolean,
        val sliceCount: Int,
        val estimatedProgress: Float,
        val pitchDriftDeg: Float,
        val deltaYawDeg: Float,
        val panDirection: Int
    )

    // Pre-allocated luma grids for zero-GC 30-60 FPS ImageAnalysis overlap tracking
    private val anchorGrid = IntArray(GRID_SIZE)
    private val currGrid = IntArray(GRID_SIZE)

    @Volatile
    var hasAnchorFrame: Boolean = false
        private set

    private var anchorYawDeg: Float = 0f
    private var lastCaptureTimeMs: Long = 0L
    private var capturedCount: Int = 0
    private var panDirection: Int = 0 // 0 = undecided, +1 = panning right, -1 = panning left
    private var cumulativePanNorm: Float = 0f

    fun reset() {
        hasAnchorFrame = false
        anchorGrid.fill(0)
        currGrid.fill(0)
        anchorYawDeg = 0f
        lastCaptureTimeMs = 0L
        capturedCount = 0
        panDirection = 0
        cumulativePanNorm = 0f
    }

    /**
     * Marks the most recently analyzed ImageAnalysis luma grid as the new anchor slice.
     */
    fun lockCurrentFrameAsAnchor(currentYawDeg: Float, stepShiftAbsNorm: Float) {
        System.arraycopy(currGrid, 0, anchorGrid, 0, GRID_SIZE)
        hasAnchorFrame = true
        anchorYawDeg = currentYawDeg
        lastCaptureTimeMs = System.currentTimeMillis()
        capturedCount++
        cumulativePanNorm += stepShiftAbsNorm
    }

    /**
     * Analyzes the incoming CameraX ImageProxy Y-plane on the background executor:
     * detects frame overlap, computes 2D stitch alignment, and returns real-time arrow guidance
     * plus automated capture / auto-finish signals.
     */
    fun analyzeFrame(
        imageProxy: ImageProxy,
        isFrontFacing: Boolean,
        gyroDeltaYawDeg: Float,
        gyroPitchDriftDeg: Float
    ): AlignmentResult {
        extractLumaGrid(imageProxy, isFrontFacing)

        // First slice triggers immediately when user starts Panorama capture
        if (!hasAnchorFrame) {
            return AlignmentResult(
                dxNorm = 0f,
                dyNorm = 0f,
                overlapRatio = 1.0f,
                confidence = 1.0f,
                guidance = PanoramaGuidance.PAN_RIGHT,
                shouldTriggerAutoCapture = true,
                shouldAutoFinish = false,
                sliceCount = 0,
                estimatedProgress = 0f,
                pitchDriftDeg = 0f,
                deltaYawDeg = 0f,
                panDirection = 0
            )
        }

        // Slide overlapping center band of currGrid over anchorGrid to compute optimal (dx, dy)
        var bestDx = 0
        var bestDy = 0
        var bestSad = Float.MAX_VALUE
        var sadAccum = 0f
        var sadSamples = 0

        val minCol = GRID_W / 5
        val maxCol = GRID_W - (GRID_W / 5)
        val minRow = 6
        val maxRow = GRID_H - 6
        val maxShiftX = 28

        for (dy in -4..4) {
            for (dx in -maxShiftX..maxShiftX) {
                if (panDirection > 0 && dx < -3) continue
                if (panDirection < 0 && dx > 3) continue

                var errSum = 0L
                var count = 0
                for (y in minRow until maxRow step 2) {
                    val ay = y + dy
                    if (ay !in 0 until GRID_H) continue
                    val aRow = ay * GRID_W
                    val cRow = y * GRID_W
                    for (x in minCol until maxCol step 2) {
                        val ax = x + dx
                        if (ax !in 0 until GRID_W) continue
                        errSum += abs(currGrid[cRow + x] - anchorGrid[aRow + ax])
                        count++
                    }
                }
                if (count >= 48) {
                    val meanSad = errSum.toFloat() / count.toFloat()
                    sadAccum += meanSad
                    sadSamples++
                    if (meanSad < bestSad) {
                        bestSad = meanSad
                        bestDx = dx
                        bestDy = dy
                    }
                }
            }
        }

        val meanAllSad = if (sadSamples > 0) sadAccum / sadSamples else bestSad
        val confidence = ((meanAllSad - bestSad) / max(meanAllSad, 1f)).coerceIn(0f, 1f)

        val opticalShiftAbs = abs(bestDx).toFloat() / GRID_W.toFloat()
        val opticalDyNorm = bestDy.toFloat() / GRID_H.toFloat()
        val yawSinceAnchor = abs(normalizeAngleDelta(anchorYawDeg, gyroDeltaYawDeg))
        val gyroShiftAbs = (yawSinceAnchor / 36.0f).coerceIn(0f, 0.75f)

        val effectiveShiftAbs = if (confidence >= 0.14f) {
            (opticalShiftAbs * 0.78f + gyroShiftAbs * 0.22f)
        } else {
            max(opticalShiftAbs * 0.35f, gyroShiftAbs)
        }

        if (panDirection == 0 && (abs(bestDx) >= 4 && confidence >= 0.16f || abs(gyroDeltaYawDeg) >= 3.2f)) {
            panDirection = if (abs(gyroDeltaYawDeg) >= 3.2f) {
                if (gyroDeltaYawDeg >= 0f) 1 else -1
            } else {
                if (bestDx >= 0) 1 else -1
            }
        }

        val signedDxNorm = if (panDirection < 0) -effectiveShiftAbs else effectiveShiftAbs
        val overlapRatio = (1.0f - effectiveShiftAbs).coerceIn(0f, 1f)

        val now = System.currentTimeMillis()
        val cooldownElapsed = (now - lastCaptureTimeMs) >= 260L
        val targetSliceTrigger = cooldownElapsed && (
            (confidence >= 0.14f && overlapRatio <= 0.70f) ||
            (yawSinceAnchor >= 7.8f)
        )

        val totalProjectedProgress = ((cumulativePanNorm + effectiveShiftAbs) / TARGET_PAN_NORM)
            .coerceIn(0f, 1f)
        val shouldAutoFinish = (capturedCount >= MAX_SLICES) ||
            (capturedCount >= 5 && totalProjectedProgress >= 0.98f)

        val guidance = when {
            shouldAutoFinish -> PanoramaGuidance.FINISHING
            targetSliceTrigger -> PanoramaGuidance.AUTO_CAPTURED
            abs(gyroPitchDriftDeg) > 4.2f || abs(opticalDyNorm) > 0.065f -> {
                if (gyroPitchDriftDeg > 0f || opticalDyNorm < -0.065f) PanoramaGuidance.TILT_DOWN else PanoramaGuidance.TILT_UP
            }
            overlapRatio < 0.45f -> PanoramaGuidance.SLOW_DOWN
            panDirection < 0 -> PanoramaGuidance.PAN_LEFT
            else -> PanoramaGuidance.PAN_RIGHT
        }

        return AlignmentResult(
            dxNorm = signedDxNorm,
            dyNorm = opticalDyNorm,
            overlapRatio = overlapRatio,
            confidence = confidence,
            guidance = guidance,
            shouldTriggerAutoCapture = targetSliceTrigger,
            shouldAutoFinish = shouldAutoFinish,
            sliceCount = capturedCount,
            estimatedProgress = max(totalProjectedProgress, (abs(gyroDeltaYawDeg) / 115f).coerceIn(0f, 1f)),
            pitchDriftDeg = gyroPitchDriftDeg,
            deltaYawDeg = gyroDeltaYawDeg,
            panDirection = panDirection
        )
    }

    /**
     * Converts CameraX YUV_420_888 ImageProxy directly to an upright lossless ARGB Bitmap.
     * Never digitally upscales above native upright frame height (scale <= 1.0f) and avoids
     * lossy YuvImage JPEG recompression blur.
     */
    fun imageProxyToBitmap(imageProxy: ImageProxy, isFrontFacing: Boolean, targetHeight: Int = 960): Bitmap? {
        if (imageProxy.planes.size < 3) return null
        val rawBmp = yuv420888ToArgbBitmap(imageProxy) ?: return null

        val rotDeg = imageProxy.imageInfo.rotationDegrees.toFloat()
        val rotatedSideways = (imageProxy.imageInfo.rotationDegrees == 90 || imageProxy.imageInfo.rotationDegrees == 270)
        val uprightH = (if (rotatedSideways) rawBmp.width else rawBmp.height).coerceAtLeast(1)
        val scale = min(1.0f, targetHeight.toFloat() / uprightH.toFloat())
        val needsTransform = rotDeg != 0f || isFrontFacing || scale < 0.98f
        if (!needsTransform) {
            return rawBmp
        }

        val matrix = Matrix().apply {
            if (rotDeg != 0f) postRotate(rotDeg)
            if (isFrontFacing) postScale(-1f, 1f)
            if (scale < 0.98f) postScale(scale, scale)
        }
        val finalBmp = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true)
        if (finalBmp != rawBmp && !rawBmp.isRecycled) {
            rawBmp.recycle()
        }
        return finalBmp
    }

    /**
     * Step 3: Standard Android Image Processing Stitcher.
     *
     * Uses 2-stage coarse-to-fine pixel-strip seam alignment and a narrow 32–64px seam feather
     * mask so overlapping textures never smear into double-exposure ghosting, followed by exact
     * vertical border trimming and a mild unsharp crispness pass.
     */
    fun stitchSlices(rawSlices: List<PanoramaSlice>): Bitmap? {
        if (rawSlices.isEmpty()) return null
        val ordered = if (panDirection < 0) rawSlices.reversed() else rawSlices
        if (ordered.size == 1) {
            return sharpenPanoramaBitmap(ordered[0].bitmap.copy(Bitmap.Config.ARGB_8888, true))
        }

        val sliceH = ordered[0].bitmap.height.coerceAtLeast(64)
        val sliceW = ordered[0].bitmap.width.coerceAtLeast(64)

        val xPositions = FloatArray(ordered.size)
        val yPositions = FloatArray(ordered.size)
        xPositions[0] = 0f
        yPositions[0] = 0f

        for (i in 1 until ordered.size) {
            val prevBmp = ordered[i - 1].bitmap
            val currBmp = ordered[i].bitmap
            val roughStepNorm = abs(ordered[i].stepDxNorm).coerceIn(0.16f, 0.52f)
            val roughStepPx = (roughStepNorm * sliceW).roundToInt()

            val refined = refineSeamOffsetPx(prevBmp, currBmp, roughStepPx)
            val stepX = refined.first.toFloat().coerceIn(sliceW * 0.14f, sliceW * 0.58f)
            val gyroDyHint = -(ordered[i].pitchDriftDeg * 2.2f)
            val stepRelY = if (refined.second != 0) refined.second.toFloat() else (gyroDyHint - yPositions[i - 1])

            xPositions[i] = xPositions[i - 1] + stepX
            yPositions[i] = (yPositions[i - 1] + stepRelY).coerceIn(-sliceH * 0.14f, sliceH * 0.14f)
        }

        val totalWidth = min(5120, (xPositions.last() + sliceW).roundToInt().coerceAtLeast(sliceW))
        val canvasBmp = Bitmap.createBitmap(totalWidth, sliceH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        canvas.drawColor(Color.BLACK)

        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        val slicePaint = Paint(Paint.FILTER_BITMAP_FLAG)

        for (i in ordered.indices) {
            val bmp = ordered[i].bitmap
            val drawX = xPositions[i]
            val drawY = yPositions[i]

            if (i == 0) {
                canvas.drawBitmap(bmp, drawX, drawY, slicePaint)
            } else {
                val overlapPx = ((xPositions[i - 1] + ordered[i - 1].bitmap.width) - drawX)
                    .coerceIn(28f, bmp.width * 0.80f)
                val seamCenter = overlapPx * 0.50f
                val featherHalf = min(overlapPx * 0.42f, (overlapPx * 0.16f).coerceIn(14f, 32f))
                val gradStart = (seamCenter - featherHalf).coerceAtLeast(0f)
                val gradEnd = (seamCenter + featherHalf).coerceAtMost(overlapPx)

                val featherSlice = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                val fCanvas = Canvas(featherSlice)
                fCanvas.drawBitmap(bmp, 0f, 0f, slicePaint)

                if (gradStart > 0.5f) {
                    fCanvas.drawRect(0f, 0f, gradStart, bmp.height.toFloat(), clearPaint)
                }
                maskPaint.shader = LinearGradient(
                    gradStart, 0f, gradEnd, 0f,
                    intArrayOf(Color.TRANSPARENT, Color.BLACK),
                    null,
                    Shader.TileMode.CLAMP
                )
                fCanvas.drawRect(gradStart, 0f, gradEnd, bmp.height.toFloat(), maskPaint)

                canvas.drawBitmap(featherSlice, drawX, drawY, slicePaint)
                featherSlice.recycle()
            }
        }
        maskPaint.shader = null

        var validTop = 0
        var validBottom = sliceH
        for (i in ordered.indices) {
            val top = ceil(yPositions[i].toDouble()).toInt()
            val bot = floor((yPositions[i] + ordered[i].bitmap.height).toDouble()).toInt()
            if (top > validTop) validTop = top
            if (bot < validBottom) validBottom = bot
        }
        val topCrop = validTop.coerceIn(0, max(0, sliceH / 3))
        val bottomCrop = validBottom.coerceIn(min(sliceH, topCrop + 64), sliceH)
        val cropH = (bottomCrop - topCrop).coerceAtLeast(64)

        val cleanBmp = if (topCrop > 0 || cropH < sliceH) {
            Bitmap.createBitmap(canvasBmp, 0, topCrop, canvasBmp.width, cropH).also {
                if (it != canvasBmp && !canvasBmp.isRecycled) canvasBmp.recycle()
            }
        } else {
            canvasBmp
        }
        return sharpenPanoramaBitmap(cleanBmp)
    }

    /**
     * Two-stage coarse-to-fine vertical & horizontal pixel-strip alignment refinement.
     */
    private fun refineSeamOffsetPx(prevBmp: Bitmap, currBmp: Bitmap, roughStepPx: Int): Pair<Int, Int> {
        val w = min(prevBmp.width, currBmp.width)
        val h = min(prevBmp.height, currBmp.height)
        if (w < 64 || h < 64) return Pair(roughStepPx, 0)

        val bandW = min(84, w / 3).coerceAtLeast(28)
        val bandH = (h * 0.60f).roundToInt().coerceAtLeast(48)
        val yStart = (h - bandH) / 2
        val currStripX = min(28, max(8, w - bandW - 1))

        val prevRow = IntArray(w)
        val currRow = IntArray(bandW)
        val rowStep = max(2, bandH / 22)

        fun evalOffset(candStep: Int, dy: Int, xStride: Int): Long {
            val prevMatchX = candStep + currStripX
            if (prevMatchX !in 0 until (w - bandW)) return Long.MAX_VALUE
            var err = 0L
            var count = 0
            var ry = yStart
            while (ry < yStart + bandH) {
                val py = (ry + dy).coerceIn(0, h - 1)
                prevBmp.getPixels(prevRow, 0, w, 0, py, w, 1)
                currBmp.getPixels(currRow, 0, bandW, currStripX, ry, bandW, 1)
                var rx = 0
                while (rx < bandW) {
                    val c1 = prevRow[prevMatchX + rx]
                    val c2 = currRow[rx]
                    val l1 = (((c1 ushr 16) and 0xFF) * 3 + ((c1 ushr 8) and 0xFF) * 6 + (c1 and 0xFF)) / 10
                    val l2 = (((c2 ushr 16) and 0xFF) * 3 + ((c2 ushr 8) and 0xFF) * 6 + (c2 and 0xFF)) / 10
                    err += abs(l1 - l2)
                    count++
                    rx += xStride
                }
                ry += rowStep
            }
            return if (count > 0) err / count else Long.MAX_VALUE
        }

        // Stage 1: Coarse search +/- 28px X, +/- 12px Y
        var bestStep = roughStepPx
        var bestDy = 0
        var bestScore = Long.MAX_VALUE
        for (dy in -12..12 step 3) {
            for (dStep in -28..28 step 3) {
                val cand = roughStepPx + dStep
                val score = evalOffset(cand, dy, 4)
                if (score < bestScore) {
                    bestScore = score
                    bestStep = cand
                    bestDy = dy
                }
            }
        }

        // Stage 2: 1px fine refinement around coarse optimum
        val coarseStep = bestStep
        val coarseDy = bestDy
        for (dy in (coarseDy - 3)..(coarseDy + 3)) {
            for (dStep in -4..4) {
                val cand = coarseStep + dStep
                val score = evalOffset(cand, dy, 2)
                if (score < bestScore) {
                    bestScore = score
                    bestStep = cand
                    bestDy = dy
                }
            }
        }
        return Pair(bestStep, bestDy)
    }

    /**
     * Mild 3x3 unsharp-mask crispness pass to restore fine micro-contrast across stitched panoramas.
     */
    private fun sharpenPanoramaBitmap(src: Bitmap, amount: Float = 0.28f): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 8 || h < 8 || amount <= 0.01f) return src
        val px = IntArray(w * h)
        val out = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        for (x in 0 until w) {
            out[x] = px[x]
            out[(h - 1) * w + x] = px[(h - 1) * w + x]
        }
        for (y in 1 until h - 1) {
            val row = y * w
            val rowUp = (y - 1) * w
            val rowDn = (y + 1) * w
            out[row] = px[row]
            out[row + w - 1] = px[row + w - 1]
            for (x in 1 until w - 1) {
                val c = px[row + x]
                val u = px[rowUp + x]
                val d = px[rowDn + x]
                val l = px[row + x - 1]
                val r = px[row + x + 1]

                val cr = (c ushr 16) and 0xFF
                val cg = (c ushr 8) and 0xFF
                val cb = c and 0xFF

                val blurR = (((u ushr 16) and 0xFF) + ((d ushr 16) and 0xFF) + ((l ushr 16) and 0xFF) + ((r ushr 16) and 0xFF)) shr 2
                val blurG = (((u ushr 8) and 0xFF) + ((d ushr 8) and 0xFF) + ((l ushr 8) and 0xFF) + ((r ushr 8) and 0xFF)) shr 2
                val blurB = ((u and 0xFF) + (d and 0xFF) + (l and 0xFF) + (r and 0xFF)) shr 2

                val nr = (cr + ((cr - blurR) * amount)).roundToInt().coerceIn(0, 255)
                val ng = (cg + ((cg - blurG) * amount)).roundToInt().coerceIn(0, 255)
                val nb = (cb + ((cb - blurB) * amount)).roundToInt().coerceIn(0, 255)
                out[row + x] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        val sharpened = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        if (!src.isRecycled) src.recycle()
        return sharpened
    }

    private fun extractLumaGrid(imageProxy: ImageProxy, isFrontFacing: Boolean) {
        if (imageProxy.planes.isEmpty()) return
        val yPlane = imageProxy.planes[0]
        val yBuf = yPlane.buffer
        val srcW = imageProxy.width.coerceAtLeast(1)
        val srcH = imageProxy.height.coerceAtLeast(1)
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val limit = yBuf.limit()
        val rotDeg = imageProxy.imageInfo.rotationDegrees

        for (gy in 0 until GRID_H) {
            val ny = (gy + 0.5f) / GRID_H.toFloat()
            val rowOff = gy * GRID_W
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
                val idx = sy * rowStride + sx * pixelStride
                currGrid[rowOff + gx] = if (idx in 0 until limit) (yBuf.get(idx).toInt() and 0xFF) else 128
            }
        }
    }

    private fun yuv420888ToArgbBitmap(image: ImageProxy): Bitmap? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0 || image.planes.size < 3) return null

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer.duplicate().apply { rewind() }
        val uBuf = uPlane.buffer.duplicate().apply { rewind() }
        val vBuf = vPlane.buffer.duplicate().apply { rewind() }

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val yLimit = yBuf.limit()
        val uLimit = uBuf.limit()
        val vLimit = vBuf.limit()

        val argb = IntArray(width * height)
        for (row in 0 until height) {
            val yRowOff = row * yRowStride
            val uvRow = row ushr 1
            val uRowOff = uvRow * uRowStride
            val vRowOff = uvRow * vRowStride
            val outRow = row * width
            for (col in 0 until width) {
                val yIdx = yRowOff + col * yPixelStride
                val uvCol = col ushr 1
                val uIdx = uRowOff + uvCol * uPixelStride
                val vIdx = vRowOff + uvCol * vPixelStride

                val yVal = if (yIdx in 0 until yLimit) (yBuf.get(yIdx).toInt() and 0xFF) else 0
                val uVal = (if (uIdx in 0 until uLimit) (uBuf.get(uIdx).toInt() and 0xFF) else 128) - 128
                val vVal = (if (vIdx in 0 until vLimit) (vBuf.get(vIdx).toInt() and 0xFF) else 128) - 128

                val yFixed = 1192 * max(0, yVal - 16)
                var r = yFixed + 1634 * vVal
                var g = yFixed - 833 * vVal - 400 * uVal
                var b = yFixed + 2066 * uVal

                r = if (r < 0) 0 else if (r > 262143) 255 else (r shr 10)
                g = if (g < 0) 0 else if (g > 262143) 255 else (g shr 10)
                b = if (b < 0) 0 else if (b > 262143) 255 else (b shr 10)

                argb[outRow + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun normalizeAngleDelta(fromDeg: Float, toDeg: Float): Float {
        return (((toDeg - fromDeg) + 540f) % 360f) - 180f
    }

    companion object {
        private const val GRID_W = 64
        private const val GRID_H = 48
        private const val GRID_SIZE = GRID_W * GRID_H
        private const val TARGET_PAN_NORM = 3.2f
        private const val MAX_SLICES = 14
    }
}

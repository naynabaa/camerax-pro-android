package com.example.cameraxapp.panorama

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import kotlin.math.abs
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
 *      `(dxNorm, dyNorm)`, match confidence, and real-time UI guidance arrows
 *      (`PAN_RIGHT`, `PAN_LEFT`, `TILT_UP`, `TILT_DOWN`, `SLOW_DOWN`).
 *
 * 2. AUTOMATED CAPTURE TRIGGER SEQUENCE:
 *    - Triggers automatic slice capture when frame overlap drops into the optimal stitching window
 *      (`overlapRatio <= 0.72f` — i.e., 28%+ new scene shift since anchor) with >= 260ms cooldown,
 *      with gyro yaw fusion fallback (`>= 7.5 deg`) for low-texture sky/wall sweeps.
 *    - Converts the live `ImageProxy` (YUV_420_888 -> NV21 -> `YuvImage` JPEG -> rotated `Bitmap`)
 *      using standard Android `android.graphics.YuvImage` & `BitmapFactory`.
 *
 * 3. STANDARD ANDROID IMAGE PROCESSING STITCHER (`stitchSlices`):
 *    - Refines adjacent slice seam offsets at pixel resolution (`refineSeamOffsetPx`).
 *    - Composites aligned frames onto a seamless panoramic `Bitmap` via `Canvas`,
 *      `LinearGradient`, and `PorterDuff.Mode.DST_IN` feather alpha blending across overlap seams,
 *      then trims vertical handheld pitch margins.
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

        // Since ax = x + dx, panning right moves landmarks left in currGrid (bestDx > 0), while panning left yields bestDx < 0.
        val opticalShiftAbs = abs(bestDx).toFloat() / GRID_W.toFloat()
        val opticalDyNorm = bestDy.toFloat() / GRID_H.toFloat()
        val yawSinceAnchor = abs(normalizeAngleDelta(anchorYawDeg, gyroDeltaYawDeg))
        val gyroShiftAbs = (yawSinceAnchor / 36.0f).coerceIn(0f, 0.75f)

        // Use visual SAD correlation when scene has detail; blend with gyro displacement on low-texture sky
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
     * Converts CameraX YUV_420_888 ImageProxy to an upright, scaled ARGB Bitmap
     * using standard Android APIs (ImageFormat.NV21 -> YuvImage -> BitmapFactory + Matrix).
     */
    fun imageProxyToBitmap(imageProxy: ImageProxy, isFrontFacing: Boolean, targetHeight: Int = 960): Bitmap? {
        if (imageProxy.planes.size < 3) return null
        val nv21 = yuv420888ToNv21(imageProxy)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val jpegOut = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 92, jpegOut)
        val jpegBytes = jpegOut.toByteArray()
        val rawBmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null

        val rotDeg = imageProxy.imageInfo.rotationDegrees.toFloat()
        val rotatedSideways = (imageProxy.imageInfo.rotationDegrees == 90 || imageProxy.imageInfo.rotationDegrees == 270)
        val uprightH = (if (rotatedSideways) rawBmp.width else rawBmp.height).coerceAtLeast(1)
        val scale = targetHeight.toFloat() / uprightH.toFloat()

        val matrix = Matrix().apply {
            if (rotDeg != 0f) postRotate(rotDeg)
            if (isFrontFacing) postScale(-1f, 1f)
            if (abs(scale - 1f) > 0.02f) postScale(scale, scale)
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
     * Refines per-slice seam coordinates using local pixel-strip correlation and composites slices
     * left-to-right on a single `Canvas` using `LinearGradient` + `PorterDuff.Mode.DST_IN`
     * feather alpha masks to eliminate visible vertical seams.
     */
    fun stitchSlices(rawSlices: List<PanoramaSlice>): Bitmap? {
        if (rawSlices.isEmpty()) return null
        val ordered = if (panDirection < 0) rawSlices.reversed() else rawSlices
        if (ordered.size == 1) {
            return ordered[0].bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        val sliceH = ordered[0].bitmap.height.coerceAtLeast(64)
        val sliceW = ordered[0].bitmap.width.coerceAtLeast(64)

        // Accumulate pixel-refined x/y positions for each slice relative to canvas origin
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
            val stepX = refined.first.toFloat().coerceIn(sliceW * 0.14f, sliceW * 0.56f)
            val driftY = (-(ordered[i].pitchDriftDeg * 3.2f) + refined.second * 0.45f)
                .coerceIn(-sliceH * 0.12f, sliceH * 0.12f)

            xPositions[i] = xPositions[i - 1] + stepX
            yPositions[i] = driftY
        }

        val totalWidth = min(4608, (xPositions.last() + sliceW).roundToInt().coerceAtLeast(sliceW))
        val canvasBmp = Bitmap.createBitmap(totalWidth, sliceH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        canvas.drawColor(Color.BLACK)

        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        val solidMaskPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            color = Color.BLACK
        }

        for (i in ordered.indices) {
            val bmp = ordered[i].bitmap
            val drawX = xPositions[i]
            val drawY = yPositions[i]

            if (i == 0) {
                canvas.drawBitmap(bmp, drawX, drawY, null)
            } else {
                val overlapPx = ((xPositions[i - 1] + ordered[i - 1].bitmap.width) - drawX)
                    .coerceIn(28f, bmp.width * 0.80f)

                val featherSlice = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                val fCanvas = Canvas(featherSlice)
                fCanvas.drawBitmap(bmp, 0f, 0f, null)

                maskPaint.shader = LinearGradient(
                    0f, 0f, overlapPx, 0f,
                    intArrayOf(Color.TRANSPARENT, Color.BLACK),
                    null,
                    Shader.TileMode.CLAMP
                )
                fCanvas.drawRect(0f, 0f, overlapPx, bmp.height.toFloat(), maskPaint)
                fCanvas.drawRect(overlapPx, 0f, bmp.width.toFloat(), bmp.height.toFloat(), solidMaskPaint)

                canvas.drawBitmap(featherSlice, drawX, drawY, null)
                featherSlice.recycle()
            }
        }
        maskPaint.shader = null

        // Auto-crop symmetric top/bottom black wedge borders from handheld pitch variation
        val cropMargin = (sliceH * 0.08f).roundToInt().coerceAtLeast(0)
        val cropH = (sliceH - cropMargin * 2).coerceAtLeast(64)
        val cleanBmp = Bitmap.createBitmap(canvasBmp, 0, cropMargin, canvasBmp.width, cropH)
        if (cleanBmp != canvasBmp && !canvasBmp.isRecycled) {
            canvasBmp.recycle()
        }
        return cleanBmp
    }

    /**
     * Local vertical & horizontal pixel-strip alignment refinement around the rough step offset.
     */
    private fun refineSeamOffsetPx(prevBmp: Bitmap, currBmp: Bitmap, roughStepPx: Int): Pair<Int, Int> {
        val w = min(prevBmp.width, currBmp.width)
        val h = min(prevBmp.height, currBmp.height)
        if (w < 64 || h < 64) return Pair(roughStepPx, 0)

        val bandW = min(64, w / 4).coerceAtLeast(24)
        val bandH = (h * 0.56f).roundToInt().coerceAtLeast(32)
        val yStart = (h - bandH) / 2
        val currStripX = 24.coerceAtMost(w - bandW - 1)

        val prevRow = IntArray(w)
        val currRow = IntArray(bandW)
        val sampleRows = 18
        val rowStep = max(2, bandH / sampleRows)

        var bestStep = roughStepPx
        var bestDy = 0
        var bestScore = Long.MAX_VALUE

        for (dy in -10..10 step 2) {
            for (dStep in -14..14 step 2) {
                val candStep = roughStepPx + dStep
                val prevMatchX = candStep + currStripX
                if (prevMatchX !in 0 until (w - bandW)) continue

                var err = 0L
                var count = 0
                var ry = yStart
                while (ry < yStart + bandH) {
                    val py = (ry + dy).coerceIn(0, h - 1)
                    prevBmp.getPixels(prevRow, 0, w, 0, py, w, 1)
                    currBmp.getPixels(currRow, 0, bandW, currStripX, ry, bandW, 1)
                    for (rx in 0 until bandW step 3) {
                        val c1 = prevRow[prevMatchX + rx]
                        val c2 = currRow[rx]
                        val l1 = (((c1 ushr 16) and 0xFF) * 3 + ((c1 ushr 8) and 0xFF) * 6 + (c1 and 0xFF)) / 10
                        val l2 = (((c2 ushr 16) and 0xFF) * 3 + ((c2 ushr 8) and 0xFF) * 6 + (c2 and 0xFF)) / 10
                        err += abs(l1 - l2)
                        count++
                    }
                    ry += rowStep
                }
                if (count > 0 && err / count < bestScore) {
                    bestScore = err / count
                    bestStep = candStep
                    bestDy = dy
                }
            }
        }
        return Pair(bestStep, bestDy)
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

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4
        val nv21 = ByteArray(ySize + uvSize * 2)

        val yBuf = image.planes[0].buffer.duplicate().apply { rewind() }
        val uBuf = image.planes[1].buffer.duplicate().apply { rewind() }
        val vBuf = image.planes[2].buffer.duplicate().apply { rewind() }

        val yRowStride = image.planes[0].rowStride
        val yPixelStride = image.planes[0].pixelStride
        var pos = 0
        if (yPixelStride == 1 && yRowStride == width) {
            yBuf.get(nv21, 0, min(ySize, yBuf.remaining()))
            pos = ySize
        } else {
            for (row in 0 until height) {
                for (col in 0 until width) {
                    val idx = row * yRowStride + col * yPixelStride
                    nv21[pos++] = if (idx < yBuf.limit()) yBuf.get(idx) else 0
                }
            }
        }

        val vRowStride = image.planes[2].rowStride
        val vPixelStride = image.planes[2].pixelStride
        val uRowStride = image.planes[1].rowStride
        val uPixelStride = image.planes[1].pixelStride

        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val vIdx = row * vRowStride + col * vPixelStride
                val uIdx = row * uRowStride + col * uPixelStride
                nv21[pos++] = if (vIdx < vBuf.limit()) vBuf.get(vIdx) else (-128).toByte()
                nv21[pos++] = if (uIdx < uBuf.limit()) uBuf.get(uIdx) else (-128).toByte()
            }
        }
        return nv21
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

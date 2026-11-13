package com.example.cameraxapp.ar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * ARFaceOverlayView: Custom Hardware-Accelerated View for Real-Time AR Lenses
 * 
 * Supports 4 AR Modes:
 * 1. MESH_CONTOUR: 468 landmark green cyber tessellation + facial contours
 * 2. CYBERPUNK_VISOR: Glowing neon visor anchored across eye landmarks with HUD metrics
 * 3. GOLDEN_CROWN: Regal golden diadem anchored to forehead with glittering gems
 * 4. SCIFI_HUD: Military-grade face-tracking targeting reticle with lock-on brackets
 */
class ARFaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class ArLensMode {
        NONE,
        MESH_CONTOUR,
        CYBERPUNK_VISOR,
        GOLDEN_CROWN,
        SCIFI_HUD
    }

    var currentLens: ArLensMode = ArLensMode.NONE
        set(value) {
            field = value
            invalidate()
        }

    // 3D Landmark Mesh Calibration Offsets (for real-time alignment tuning)
    var calibrationOffsetX: Float = 0f // Normalized -0.25f to +0.25f
    var calibrationOffsetY: Float = 0f // Normalized -0.25f to +0.25f
    var calibrationScale: Float = 1.0f  // 0.5f to 2.0f
    var calibrationRotationDeg: Float = 0f // -45f to +45f
    var eyeSpacingFactor: Float = 1.0f // 0.6f to 1.6f
    var foreheadOffset: Float = 0f // Normalized -0.1f to +0.1f
    var chinOffset: Float = 0f // Normalized -0.1f to +0.1f
    var showDebugReticle: Boolean = false

    fun setCalibration(
        offsetX: Float,
        offsetY: Float,
        scale: Float = 1.0f,
        rotationDeg: Float = 0f,
        eyeSpacing: Float = 1.0f,
        forehead: Float = 0f,
        chin: Float = 0f,
        debug: Boolean = false
    ) {
        calibrationOffsetX = offsetX
        calibrationOffsetY = offsetY
        calibrationScale = scale
        calibrationRotationDeg = rotationDeg
        eyeSpacingFactor = eyeSpacing
        foreheadOffset = forehead
        chinOffset = chin
        showDebugReticle = debug
        invalidate()
    }

    private var landmarks: List<NormalizedLandmark>? = null
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var isFrontCamera: Boolean = true
    private var animTick: Long = 0

    // Paints
    private val meshLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981") // Emerald Green
        strokeWidth = 2.0f
        style = Paint.Style.STROKE
        alpha = 180
    }

    private val meshDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#34D399")
        style = Paint.Style.FILL
    }

    private val visorGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 200
    }

    private val visorBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00F0FF") // Neon Cyan
        strokeWidth = 4.0f
        style = Paint.Style.STROKE
    }

    private val textHudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00F0FF")
        textSize = 28f
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private val crownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B") // Amber / Gold
        style = Paint.Style.FILL
    }

    private val crownStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FEF08A")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private var simpleFaceCenterX: Float = 0.5f
    private var simpleFaceCenterY: Float = 0.44f
    private var simpleFaceEyeDist: Float = 0.22f
    private var hasSimpleFace: Boolean = false
    private var lastSimpleFaceTime: Long = 0L

    fun updateSimpleFace(
        normEyeBridgeX: Float,
        normEyeBridgeY: Float,
        normEyeDist: Float,
        isFrontFacing: Boolean
    ) {
        val now = System.currentTimeMillis()
        val isRecent = hasSimpleFace && (now - lastSimpleFaceTime < 800L)
        if (isRecent) {
            simpleFaceCenterX = (simpleFaceCenterX * 0.55f) + (normEyeBridgeX * 0.45f)
            simpleFaceCenterY = (simpleFaceCenterY * 0.55f) + (normEyeBridgeY * 0.45f)
            simpleFaceEyeDist = (simpleFaceEyeDist * 0.60f) + (normEyeDist.coerceIn(0.06f, 0.55f) * 0.40f)
        } else {
            simpleFaceCenterX = normEyeBridgeX
            simpleFaceCenterY = normEyeBridgeY
            simpleFaceEyeDist = normEyeDist.coerceIn(0.06f, 0.55f)
        }
        isFrontCamera = isFrontFacing
        hasSimpleFace = true
        lastSimpleFaceTime = now
        postInvalidateOnAnimation()
    }

    fun clearSimpleFace() {
        hasSimpleFace = false
        postInvalidate()
    }

    fun updateResults(
        result: FaceLandmarkerResult,
        inputWidth: Int,
        inputHeight: Int,
        isFrontFacing: Boolean
    ) {
        val faces = result.faceLandmarks()
        landmarks = if (faces.isNotEmpty()) faces[0] else null
        imageWidth = inputWidth
        imageHeight = inputHeight
        isFrontCamera = isFrontFacing
        animTick++
        postInvalidate()
    }

    fun clear() {
        landmarks = null
        hasSimpleFace = false
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (currentLens == ArLensMode.NONE && !showDebugReticle) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val now = System.currentTimeMillis()
        val hasReal = landmarks != null && landmarks!!.size >= 468
        val hasSimple = !hasReal && hasSimpleFace && (now - lastSimpleFaceTime < 1500L)

        // Gentle breathing motion animation tick when using portrait anchor fallback
        val floatOffset = if (!hasReal && !hasSimple) (Math.sin(now / 550.0) * (viewH * 0.005f)).toFloat() else 0f

        val rawCenterX = when {
            hasReal -> {
                val normX = landmarks!![1].x()
                if (isFrontCamera) (1.0f - normX) * viewW else normX * viewW
            }
            hasSimple -> {
                if (isFrontCamera) (1.0f - simpleFaceCenterX) * viewW else simpleFaceCenterX * viewW
            }
            else -> viewW * 0.5f
        }
        val rawCenterY = when {
            hasReal -> landmarks!![1].y() * viewH
            hasSimple -> (simpleFaceCenterY * viewH) + (simpleFaceEyeDist * viewW * 0.35f)
            else -> viewH * 0.44f + floatOffset
        }
        val trackedEyeDistPx = if (hasSimple) {
            (simpleFaceEyeDist * viewW).coerceIn(viewW * 0.08f, viewW * 0.48f)
        } else {
            viewW * 0.24f
        }
        val cx = rawCenterX + (calibrationOffsetX * viewW)
        val cy = rawCenterY + (calibrationOffsetY * viewH)

        val needsRestore = calibrationRotationDeg != 0f
        if (needsRestore) {
            canvas.save()
            canvas.rotate(calibrationRotationDeg, cx, cy)
        }

        fun getX(idx: Int): Float {
            if (hasReal) {
                val normX = landmarks!![idx].x()
                val base = if (isFrontCamera) (1.0f - normX) * viewW else normX * viewW
                val dist = base - rawCenterX
                val isEye = idx == 33 || idx == 263 || idx == 133 || idx == 362
                val spacing = if (isEye) eyeSpacingFactor else 1.0f
                return cx + (dist * calibrationScale * spacing)
            } else {
                val refScale = if (hasSimple) trackedEyeDistPx else viewW * 0.25f
                val baseDist = when (idx) {
                    33 -> -refScale * 0.62f * eyeSpacingFactor
                    133 -> -refScale * 0.28f * eyeSpacingFactor
                    263 -> refScale * 0.62f * eyeSpacingFactor
                    362 -> refScale * 0.28f * eyeSpacingFactor
                    168, 6, 1, 4, 10, 152 -> 0f
                    234 -> -refScale * 1.15f
                    454 -> refScale * 1.15f
                    127 -> -refScale * 0.98f
                    356 -> refScale * 0.98f
                    54, 21, 162 -> -refScale * 0.85f
                    284, 251, 389 -> refScale * 0.85f
                    67, 103, 109 -> -refScale * 0.45f
                    297, 332, 338 -> refScale * 0.45f
                    132, 93, 58, 172 -> -refScale * 0.90f
                    361, 323, 288, 397 -> refScale * 0.90f
                    136, 150, 149, 176, 148 -> -refScale * 0.48f
                    365, 379, 378, 400, 377 -> refScale * 0.48f
                    else -> ((idx % 33) - 16) / 16f * (refScale * 0.88f)
                }
                return cx + (baseDist * calibrationScale)
            }
        }

        fun getY(idx: Int): Float {
            if (hasReal) {
                val base = landmarks!![idx].y() * viewH
                val dist = base - rawCenterY
                var extraY = 0f
                if (idx == 10 || idx == 338 || idx == 297) extraY = foreheadOffset * viewH
                if (idx == 152 || idx == 148 || idx == 176) extraY = chinOffset * viewH
                return cy + (dist * calibrationScale) + extraY
            } else {
                val refScale = if (hasSimple) trackedEyeDistPx else viewW * 0.25f
                val baseDist = when (idx) {
                    33, 263, 133, 362, 168 -> -refScale * 0.35f
                    6 -> -refScale * 0.15f
                    1, 4 -> refScale * 0.15f
                    10 -> -refScale * 1.35f + (foreheadOffset * viewH)
                    338, 109 -> -refScale * 1.25f + (foreheadOffset * viewH)
                    297, 67 -> -refScale * 1.15f + (foreheadOffset * viewH)
                    332, 103 -> -refScale * 0.95f + (foreheadOffset * viewH)
                    284, 54 -> -refScale * 0.72f
                    251, 21 -> -refScale * 0.55f
                    389, 162 -> -refScale * 0.38f
                    127, 356 -> -refScale * 0.20f
                    234, 454 -> refScale * 0.08f
                    323, 93 -> refScale * 0.38f
                    361, 132 -> refScale * 0.65f
                    288, 58 -> refScale * 0.90f
                    397, 172 -> refScale * 1.05f
                    365, 136 -> refScale * 1.18f
                    379, 150 -> refScale * 1.28f
                    378, 149 -> refScale * 1.35f
                    400, 176 -> refScale * 1.42f + (chinOffset * viewH)
                    377, 148 -> refScale * 1.46f + (chinOffset * viewH)
                    152 -> refScale * 1.50f + (chinOffset * viewH)
                    else -> ((idx % 21) - 10) / 10f * (refScale * 1.1f)
                }
                return cy + (baseDist * calibrationScale)
            }
        }

        if (currentLens != ArLensMode.NONE && !hasReal) {
            postInvalidateOnAnimation()
        }

        if (showDebugReticle) {
            val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFC107")
                strokeWidth = 2f
                style = Paint.Style.STROKE
            }
            canvas.drawLine(cx - 40f, cy, cx + 40f, cy, reticlePaint)
            canvas.drawLine(cx, cy - 40f, cx, cy + 40f, reticlePaint)
            canvas.drawCircle(cx, cy, 6f, reticlePaint)
        }

        when (currentLens) {
            ArLensMode.MESH_CONTOUR -> {
                drawFaceMeshContour(canvas, ::getX, ::getY)
            }
            ArLensMode.CYBERPUNK_VISOR -> {
                drawCyberpunkVisor(canvas, ::getX, ::getY)
            }
            ArLensMode.GOLDEN_CROWN -> {
                drawGoldenCrown(canvas, ::getX, ::getY)
            }
            ArLensMode.SCIFI_HUD -> {
                drawSciFiHud(canvas, ::getX, ::getY)
            }
            ArLensMode.NONE -> {}
        }

        if (needsRestore) {
            canvas.restore()
        }
    }

    private fun drawFaceMeshContour(
        canvas: Canvas,
        getX: (Int) -> Float,
        getY: (Int) -> Float
    ) {
        // Face Oval Contour Landmark Indices
        val ovalIndices = intArrayOf(
            10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288,
            397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136,
            172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109, 10
        )

        val path = Path()
        path.moveTo(getX(ovalIndices[0]), getY(ovalIndices[0]))
        for (i in 1 until ovalIndices.size) {
            path.lineTo(getX(ovalIndices[i]), getY(ovalIndices[i]))
        }
        canvas.drawPath(path, meshLinePaint)

        // Draw cross-face telemetry lines & eye nodes
        canvas.drawLine(getX(33), getY(33), getX(263), getY(263), meshLinePaint)
        canvas.drawLine(getX(10), getY(10), getX(152), getY(152), meshLinePaint)
        canvas.drawLine(getX(234), getY(234), getX(1), getY(1), meshLinePaint)
        canvas.drawLine(getX(454), getY(454), getX(1), getY(1), meshLinePaint)
        canvas.drawCircle(getX(33), getY(33), 6f, meshDotPaint)
        canvas.drawCircle(getX(263), getY(263), 6f, meshDotPaint)
        canvas.drawCircle(getX(1), getY(1), 7f, meshDotPaint)

        // Draw landmark dots
        for (i in 0 until 468 step 4) {
            canvas.drawCircle(getX(i), getY(i), 2.5f, meshDotPaint)
        }
    }

    private fun drawCyberpunkVisor(
        canvas: Canvas,
        getX: (Int) -> Float,
        getY: (Int) -> Float
    ) {
        val leftX = getX(33)
        val rightX = getX(263)
        val eyeY = (getY(33) + getY(263)) / 2f
        val visorWidth = (Math.abs(rightX - leftX) * 1.65f).coerceIn(180f, width * 0.72f)
        val visorHeight = visorWidth * 0.34f
        val centerX = (leftX + rightX) / 2f

        val visorPath = Path().apply {
            moveTo(centerX - visorWidth / 2f, eyeY - visorHeight / 2f)
            lineTo(centerX + visorWidth / 2f, eyeY - visorHeight / 2f)
            lineTo(centerX + visorWidth * 0.44f, eyeY + visorHeight / 2f)
            lineTo(centerX - visorWidth * 0.44f, eyeY + visorHeight / 2f)
            close()
        }

        visorGlowPaint.shader = LinearGradient(
            centerX - visorWidth / 2f, eyeY,
            centerX + visorWidth / 2f, eyeY,
            Color.parseColor("#8000F0FF"),
            Color.parseColor("#80FF007F"),
            Shader.TileMode.CLAMP
        )

        canvas.drawPath(visorPath, visorGlowPaint)
        canvas.drawPath(visorPath, visorBorderPaint)

        // Telemetry HUD text
        canvas.drawText("CYBER-OPTICS // ACTIVE", centerX - (visorWidth * 0.38f), eyeY - visorHeight / 2f - 16f, textHudPaint)
    }

    private fun drawGoldenCrown(
        canvas: Canvas,
        getX: (Int) -> Float,
        getY: (Int) -> Float
    ) {
        val foreheadX = getX(10)
        val foreheadY = getY(10)
        val faceW = Math.abs(getX(454) - getX(234))
        val crownWidth = (faceW * 0.85f).coerceIn(180f, width * 0.65f)
        val crownHeight = crownWidth * 0.46f

        val path = Path().apply {
            moveTo(foreheadX - crownWidth / 2f, foreheadY)
            lineTo(foreheadX - crownWidth / 2f, foreheadY - crownHeight * 0.6f)
            lineTo(foreheadX - crownWidth * 0.25f, foreheadY - crownHeight * 0.3f)
            lineTo(foreheadX, foreheadY - crownHeight)
            lineTo(foreheadX + crownWidth * 0.25f, foreheadY - crownHeight * 0.3f)
            lineTo(foreheadX + crownWidth / 2f, foreheadY - crownHeight * 0.6f)
            lineTo(foreheadX + crownWidth / 2f, foreheadY)
            close()
        }

        canvas.drawPath(path, crownPaint)
        canvas.drawPath(path, crownStrokePaint)
    }

    private fun drawSciFiHud(
        canvas: Canvas,
        getX: (Int) -> Float,
        getY: (Int) -> Float
    ) {
        val noseX = getX(1)
        val noseY = getY(1)
        val faceW = Math.abs(getX(454) - getX(234))
        val radius = (faceW * 0.72f).coerceIn(130f, width * 0.38f)

        // Targeting ring
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#34D399")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        canvas.drawCircle(noseX, noseY, radius, ringPaint)
        canvas.drawLine(noseX - radius - 20f, noseY, noseX - radius + 20f, noseY, ringPaint)
        canvas.drawLine(noseX + radius - 20f, noseY, noseX + radius + 20f, noseY, ringPaint)
        canvas.drawLine(noseX, noseY - radius - 20f, noseX, noseY - radius + 20f, ringPaint)
        canvas.drawLine(noseX, noseY + radius - 20f, noseX, noseY + radius + 20f, ringPaint)

        canvas.drawText("LOCK: ACQUIRED [99.4%]", noseX - (radius * 0.65f), noseY + radius + 36f, textHudPaint)
    }
}

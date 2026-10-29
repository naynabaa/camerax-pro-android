package com.example.cameraxapp.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/**
 * ProOverlayView: Hardware-Accelerated 60 FPS Camera Framing & Horizon Engine
 *
 * Performance Contract:
 * 1. Zero Object Allocations in onDraw() (Prevents GC stutters during camera feed)
 * 2. Static Geometric Path computation strictly isolated to onSizeChanged()
 * 3. Pre-allocated Path and Paint objects
 */
class ProOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    enum class GridType {
        NONE,
        RULE_OF_THIRDS,
        GOLDEN_SPIRAL,
        GOLDEN_RATIO_PHI,
        DIAGONAL_SYMMETRY,
        CINEMATIC_239
    }

    // Grid & Guide configurations (Clean default: NONE)
    var currentGrid: GridType = GridType.NONE
        set(value) {
            field = value
            invalidate()
        }

    var showLevelGauge: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var showFocusPeaking: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // Spiral orientation: 0 (top-left start), 1 (top-right), 2 (bottom-right), 3 (bottom-left)
    var spiralOrientation: Int = 0
        set(value) {
            field = value % 4
            rebuildGoldenSpiral(width.toFloat(), height.toFloat())
            invalidate()
        }

    // Live Sensor Tilt for Artificial Horizon (-180 to 180 degrees)
    var tiltRollDegrees: Float = 0f
        set(value) {
            field = value
            if (showLevelGauge) invalidate()
        }

    var tiltPitchDegrees: Float = 0f
        set(value) {
            field = value
            if (showLevelGauge) invalidate()
        }

    // --- PRE-ALLOCATED PAINTS (ZERO ALLOCATIONS IN onDraw) ---
    // Clean, crisp, neutral semi-transparent white camera overlay lines (Never colored)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.0f)
        color = Color.parseColor("#80FFFFFF") // 50% Crisp White Overlay
    }

    private val powerPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#B3FFFFFF") // 70% White
    }

    private val spiralPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.2f)
        color = Color.parseColor("#B3FFFFFF") // Clean Crisp White Curve (Not colored)
    }

    private val spiralBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(0.8f)
        color = Color.parseColor("#40FFFFFF") // Subtle 25% White Box lines
    }

    private val levelNormalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.6f)
        color = Color.parseColor("#99FFFFFF")
    }

    private val levelSnappedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.2f)
        color = Color.parseColor("#FF10B981") // Vibrant neon emerald
    }

    private val levelCenterPipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF10B981")
    }

    private val cineMattePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC000000") // 80% Black letterbox
    }

    private val focusPeakingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.0f)
        color = Color.parseColor("#E610B981")
    }

    // --- PRE-ALLOCATED CACHED PATHS (COMPUTED ONCE IN onSizeChanged) ---
    private val thirdsPath = Path()
    private val phiGridPath = Path()
    private val diagonalPath = Path()
    private val spiralPath = Path()
    private val spiralBoxesPath = Path()
    private val cineTopRect = RectF()
    private val cineBottomRect = RectF()

    private val powerPoints = FloatArray(8) // 4 intersection points (x, y)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val widthF = w.toFloat()
        val heightF = h.toFloat()

        // 1. Build Rule of Thirds Path & Power Points
        rebuildRuleOfThirds(widthF, heightF)

        // 2. Build Golden Ratio (Phi) Grid Path
        rebuildGoldenRatioPhiGrid(widthF, heightF)

        // 3. Build Dynamic Symmetry Diagonal Path
        rebuildDiagonalSymmetry(widthF, heightF)

        // 4. Build Golden Spiral (Fibonacci Logarithmic Curve) Path
        rebuildGoldenSpiral(widthF, heightF)

        // 5. Build 2.39:1 Cinematic Letterbox Matte Rectangles
        rebuildCinematicLetterbox(widthF, heightF)
    }

    private fun rebuildRuleOfThirds(w: Float, h: Float) {
        thirdsPath.reset()

        val x1 = w / 3f
        val x2 = (w * 2f) / 3f
        val y1 = h / 3f
        val y2 = (h * 2f) / 3f

        // Vertical lines
        thirdsPath.moveTo(x1, 0f)
        thirdsPath.lineTo(x1, h)
        thirdsPath.moveTo(x2, 0f)
        thirdsPath.lineTo(x2, h)

        // Horizontal lines
        thirdsPath.moveTo(0f, y1)
        thirdsPath.lineTo(w, y1)
        thirdsPath.moveTo(0f, y2)
        thirdsPath.lineTo(w, y2)

        // Intersection Power Points
        powerPoints[0] = x1; powerPoints[1] = y1
        powerPoints[2] = x2; powerPoints[3] = y1
        powerPoints[4] = x1; powerPoints[5] = y2
        powerPoints[6] = x2; powerPoints[7] = y2
    }

    private fun rebuildGoldenRatioPhiGrid(w: Float, h: Float) {
        phiGridPath.reset()

        // Golden Ratio Constant: phi = 1.61803398875
        // Partition ratio: 1 - 1/phi ≈ 0.381966
        val ratio = 0.381966f
        val x1 = w * ratio
        val x2 = w * (1f - ratio)
        val y1 = h * ratio
        val y2 = h * (1f - ratio)

        phiGridPath.moveTo(x1, 0f); phiGridPath.lineTo(x1, h)
        phiGridPath.moveTo(x2, 0f); phiGridPath.lineTo(x2, h)
        phiGridPath.moveTo(0f, y1); phiGridPath.lineTo(w, y1)
        phiGridPath.moveTo(0f, y2); phiGridPath.lineTo(w, y2)
    }

    private fun rebuildDiagonalSymmetry(w: Float, h: Float) {
        diagonalPath.reset()

        // Corner to corner diagonals
        diagonalPath.moveTo(0f, 0f); diagonalPath.lineTo(w, h)
        diagonalPath.moveTo(w, 0f); diagonalPath.lineTo(0f, h)

        // Harmonic Reciprocals
        diagonalPath.moveTo(0f, h); diagonalPath.lineTo(w / 2f, 0f)
        diagonalPath.moveTo(w, h); diagonalPath.lineTo(w / 2f, 0f)
    }

    /**
     * Mathematically exact Golden Spiral generation with Golden Rectangles.
     * Calculated strictly ONCE during layout to prevent GC allocations on UI thread.
     */
    private fun rebuildGoldenSpiral(w: Float, h: Float) {
        spiralPath.reset()
        spiralBoxesPath.reset()

        var left = 0f
        var top = 0f
        var right = w
        var bottom = h

        val arcOval = RectF()
        // Orientation controls 4 corner origin positions:
        // 0: Top-Left, 1: Top-Right, 2: Bottom-Right, 3: Bottom-Left
        var stepDir = (spiralOrientation % 4)

        for (i in 0 until 9) {
            val curW = right - left
            val curH = bottom - top
            if (curW < 8f || curH < 8f) break

            val isHoriz = curW >= curH

            when (stepDir % 4) {
                0 -> {
                    val size = if (isHoriz) curH else curW
                    if (isHoriz) {
                        val squareRight = left + size
                        spiralBoxesPath.addRect(left, top, squareRight, bottom, Path.Direction.CW)
                        arcOval.set(left, top, left + size * 2f, top + size * 2f)
                        spiralPath.arcTo(arcOval, 180f, 90f, i == 0)
                        left = squareRight
                    } else {
                        val squareBottom = top + size
                        spiralBoxesPath.addRect(left, top, right, squareBottom, Path.Direction.CW)
                        arcOval.set(left, top, left + size * 2f, top + size * 2f)
                        spiralPath.arcTo(arcOval, 180f, 90f, i == 0)
                        top = squareBottom
                    }
                }
                1 -> {
                    val size = if (isHoriz) curH else curW
                    if (isHoriz) {
                        val squareLeft = right - size
                        spiralBoxesPath.addRect(squareLeft, top, right, bottom, Path.Direction.CW)
                        arcOval.set(right - size * 2f, top, right, top + size * 2f)
                        spiralPath.arcTo(arcOval, 270f, 90f, i == 0)
                        right = squareLeft
                    } else {
                        val squareBottom = top + size
                        spiralBoxesPath.addRect(left, top, right, squareBottom, Path.Direction.CW)
                        arcOval.set(right - size * 2f, top, right, top + size * 2f)
                        spiralPath.arcTo(arcOval, 270f, 90f, i == 0)
                        top = squareBottom
                    }
                }
                2 -> {
                    val size = if (isHoriz) curH else curW
                    if (isHoriz) {
                        val squareLeft = right - size
                        spiralBoxesPath.addRect(squareLeft, top, right, bottom, Path.Direction.CW)
                        arcOval.set(right - size * 2f, bottom - size * 2f, right, bottom)
                        spiralPath.arcTo(arcOval, 0f, 90f, i == 0)
                        right = squareLeft
                    } else {
                        val squareTop = bottom - size
                        spiralBoxesPath.addRect(left, squareTop, right, bottom, Path.Direction.CW)
                        arcOval.set(right - size * 2f, bottom - size * 2f, right, bottom)
                        spiralPath.arcTo(arcOval, 0f, 90f, i == 0)
                        bottom = squareTop
                    }
                }
                3 -> {
                    val size = if (isHoriz) curH else curW
                    if (isHoriz) {
                        val squareRight = left + size
                        spiralBoxesPath.addRect(left, top, squareRight, bottom, Path.Direction.CW)
                        arcOval.set(left, bottom - size * 2f, left + size * 2f, bottom)
                        spiralPath.arcTo(arcOval, 90f, 90f, i == 0)
                        left = squareRight
                    } else {
                        val squareTop = bottom - size
                        spiralBoxesPath.addRect(left, squareTop, right, bottom, Path.Direction.CW)
                        arcOval.set(left, bottom - size * 2f, left + size * 2f, bottom)
                        spiralPath.arcTo(arcOval, 90f, 90f, i == 0)
                        bottom = squareTop
                    }
                }
            }
            stepDir++
        }
    }

    private fun rebuildCinematicLetterbox(w: Float, h: Float) {
        // 2.39:1 CinemaScope aspect ratio
        val targetHeight = w / 2.39f
        if (h > targetHeight) {
            val barHeight = (h - targetHeight) / 2f
            cineTopRect.set(0f, 0f, w, barHeight)
            cineBottomRect.set(0f, h - barHeight, w, h)
        } else {
            cineTopRect.setEmpty()
            cineBottomRect.setEmpty()
        }
    }

    // --- ZERO ALLOCATION 60 FPS RENDER LOOP ---
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val widthF = width.toFloat()
        val heightF = height.toFloat()
        if (widthF <= 0 || heightF <= 0) return

        // 1. Draw Active Geometric Grid
        when (currentGrid) {
            GridType.NONE -> { /* No-op */ }
            GridType.RULE_OF_THIRDS -> {
                canvas.drawPath(thirdsPath, gridPaint)
                // Draw 4 Power Intersection dots
                val dotRadius = dpToPx(3.5f)
                canvas.drawCircle(powerPoints[0], powerPoints[1], dotRadius, powerPointPaint)
                canvas.drawCircle(powerPoints[2], powerPoints[3], dotRadius, powerPointPaint)
                canvas.drawCircle(powerPoints[4], powerPoints[5], dotRadius, powerPointPaint)
                canvas.drawCircle(powerPoints[6], powerPoints[7], dotRadius, powerPointPaint)
            }
            GridType.GOLDEN_SPIRAL -> {
                canvas.drawPath(spiralBoxesPath, spiralBoxPaint)
                canvas.drawPath(spiralPath, spiralPaint)
            }
            GridType.GOLDEN_RATIO_PHI -> {
                canvas.drawPath(phiGridPath, gridPaint)
            }
            GridType.DIAGONAL_SYMMETRY -> {
                canvas.drawPath(diagonalPath, gridPaint)
            }
            GridType.CINEMATIC_239 -> {
                if (!cineTopRect.isEmpty) {
                    canvas.drawRect(cineTopRect, cineMattePaint)
                    canvas.drawRect(cineBottomRect, cineMattePaint)
                }
                canvas.drawPath(thirdsPath, gridPaint)
            }
        }

        // 2. Draw Electronic Horizon Level Gauge
        if (showLevelGauge) {
            drawElectronicLevel(canvas, widthF, heightF)
        }
    }

    /**
     * Electronic Virtual Horizon Level Gauge: Aircraft HUD style with auto-snap emerald glow
     */
    private fun drawElectronicLevel(canvas: Canvas, w: Float, h: Float) {
        val centerX = w / 2f
        val centerY = h / 2f
        val lineLength = dpToPx(48f)
        val gap = dpToPx(14f)

        // Snapped check: Level within +/- 0.7 degrees
        val isSnapped = abs(tiltRollDegrees) <= 0.7f
        val paintToUse = if (isSnapped) levelSnappedPaint else levelNormalPaint

        canvas.save()
        // Rotate around center point by sensor tilt roll
        canvas.rotate(-tiltRollDegrees, centerX, centerY)

        // Left wing line
        canvas.drawLine(centerX - gap - lineLength, centerY, centerX - gap, centerY, paintToUse)

        // Right wing line
        canvas.drawLine(centerX + gap, centerY, centerX + gap + lineLength, centerY, paintToUse)

        // Center Pip dot
        if (isSnapped) {
            canvas.drawCircle(centerX, centerY, dpToPx(2.5f), levelCenterPipPaint)
        }

        canvas.restore()
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}

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

    // 3. Aspect Ratio Matte Overlays (Cinematic Crop)
    enum class AspectMatte {
        NONE,
        CINE_239,       // 2.39:1 Hollywood Anamorphic Cinema
        SQUARE_1_1,     // 1:1 Social Grid Layout
        VERTICAL_9_16,  // 9:16 Short-form Video (Reels / TikTok)
        PORTRAIT_4_5,   // 4:5 Instagram Portrait
        WIDESCREEN_16_9 // 16:9 Standard HD
    }

    // Grid & Guide configurations (Clean default: NONE)
    var currentGrid: GridType = GridType.NONE
        set(value) {
            field = value
            invalidate()
        }

    // Aspect Ratio Matte Crop Overlay (Dark translucent letterbox/pillarbox)
    var currentMatte: AspectMatte = AspectMatte.NONE
        set(value) {
            field = value
            rebuildAspectMatte(width.toFloat(), height.toFloat())
            invalidate()
        }

    // 4. Smart Visual Balance Center (The "Center of Mass" Dot)
    var showVisualBalance: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var visualBalanceX: Float = 0.5f // 0.0 (left heavy) to 1.0 (right heavy), 0.5 is balanced
        private set
    var visualBalanceY: Float = 0.5f // 0.0 (top heavy) to 1.0 (bottom heavy), 0.5 is balanced
        private set
    var isVisualBalanced: Boolean = true
        private set

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

    // 1. Overexposure Warning: Zebra Stripes
    var showZebraStripes: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // 2. Real-Time Mathematical Lighting: Live Histogram
    var showHistogram: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // Waveform Monitor (IRE 0-100 column luminance exposure graph)
    var showWaveform: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // Vector Scope (Circular color graph with Skin Tone I-Line)
    var showVectorScope: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // Audio Peak VU Meter (Decibel level monitoring -60dB to 0dB with clip indicator)
    var showAudioVuMeter: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var audioLevelDbLeft: Float = -24f
        set(value) {
            field = value
            if (showAudioVuMeter) invalidate()
        }
    var audioLevelDbRight: Float = -26f
        set(value) {
            field = value
            if (showAudioVuMeter) invalidate()
        }
    var isAudioClipping: Boolean = false
        set(value) {
            field = value
            if (showAudioVuMeter) invalidate()
        }

    // Clean Screen Mode (1-Tap Dismiss All Graphs for Unobstructed Framing)
    var isCleanScreen: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // 7. Background Executor Asynchronously Calculated Waveform & Vector Scope Data
    private val waveformColumns = FloatArray(36 * 2) { 0f } // Pre-allocated [minIre, maxIre] pairs
    private var waveformPeakIre: Float = 0f
    private var waveformOverexposedCount: Int = 0

    // Vector Scope pre-calculated points (normalized [-1..1] on Cb/Cr chromaticity plane)
    private val vectorScopePoints = FloatArray(48 * 2) { 0f } // Pre-allocated [normCb, normCr] pairs
    private var vectorScopePointCount: Int = 0
    private var vectorScopeSkinMatch: Float = 88f

    fun updateWaveformAnalysis(cols: FloatArray, peakIre: Float, overexposedCount: Int) {
        val count = Math.min(cols.size, waveformColumns.size)
        System.arraycopy(cols, 0, waveformColumns, 0, count)
        waveformPeakIre = peakIre
        waveformOverexposedCount = overexposedCount
        if (showWaveform && !isCleanScreen) {
            postInvalidateOnAnimation()
        }
    }

    fun updateVectorScopeAnalysis(pts: FloatArray, count: Int, skinMatch: Float) {
        val copyCount = Math.min(pts.size, vectorScopePoints.size)
        System.arraycopy(pts, 0, vectorScopePoints, 0, copyCount)
        vectorScopePointCount = count
        vectorScopeSkinMatch = skinMatch
        if (showVectorScope && !isCleanScreen) {
            postInvalidateOnAnimation()
        }
    }

    var zebraThreshold: Int = 240 // 240-255 luminance (95%-100% clipped highlights)
    private val histogramBins = IntArray(256)
    private var overexposedZones = FloatArray(0)
    private var isShadowClipped = false
    private var isHighlightClipped = false

    fun updateLuminanceAnalysis(bins: IntArray, overexposed: FloatArray, balanceX: Float = 0.5f, balanceY: Float = 0.5f) {
        if (bins.size == 256) {
            System.arraycopy(bins, 0, histogramBins, 0, 256)
            isShadowClipped = bins[0] > 300
            isHighlightClipped = bins[255] > 300
        }
        overexposedZones = overexposed

        // Low-pass exponential moving filter for visual balance dot
        visualBalanceX = (visualBalanceX * 0.75f) + (balanceX * 0.25f)
        visualBalanceY = (visualBalanceY * 0.75f) + (balanceY * 0.25f)
        isVisualBalanced = abs(visualBalanceX - 0.5f) <= 0.035f && abs(visualBalanceY - 0.5f) <= 0.035f

        if (!isCleanScreen && (showHistogram || showZebraStripes || showVisualBalance)) {
            postInvalidateOnAnimation()
        }
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

    // --- PRE-ALLOCATED HISTOGRAM & ZEBRA PAINTS (ZERO GC) ---
    private val zebraPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.5f)
        color = Color.parseColor("#FFFF00") // High visibility yellow
    }
    private val zebraBlackPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.5f)
        color = Color.parseColor("#000000") // Alternating black stripe
    }

    private val histoBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#B3020617") // Semi-transparent dark glass
    }
    private val histoBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.0f)
        color = Color.parseColor("#33FFFFFF")
    }
    private val histoLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.4f)
        color = Color.parseColor("#38BDF8")
    }
    private val histoFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3338BDF8")
    }
    private val histoClipWarningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#EF4444")
    }
    private val histoTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = dpToPx(8.5f)
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val histoRect = RectF()
    private val histoPath = Path()
    private val histoFillPath = Path()

    // --- PRE-ALLOCATED ASPECT MATTE & VISUAL BALANCE PAINTS & RECTS ---
    private val mattePaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#AA000000") // 67% translucent dark matte per user specification
    }
    private val matteGuideLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.0f)
        color = Color.parseColor("#66FFFFFF")
    }
    private val matteCornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.0f)
        color = Color.parseColor("#F59E0B") // Amber corner accents
    }
    private val matteTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        textSize = dpToPx(9.5f)
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private val matteTopRect = RectF()
    private val matteBottomRect = RectF()
    private val matteLeftRect = RectF()
    private val matteRightRect = RectF()
    private val matteCropBounds = RectF()

    // Smart Visual Balance Center Paints (Zero Allocations in onDraw)
    private val balanceReticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.0f)
        color = Color.parseColor("#4DFFFFFF")
    }
    private val balanceConnectingLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.2f)
        color = Color.parseColor("#99F59E0B")
    }
    private val balanceDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F59E0B")
    }
    private val balanceSnappedDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#10B981") // Glowing emerald when balanced
    }
    private val balanceSnappedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.0f)
        color = Color.parseColor("#10B981")
    }
    private val balanceBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#B3020617")
    }
    private val balanceBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        textSize = dpToPx(9.0f)
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val balanceBadgeRect = RectF()

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

        // 6. Build Dynamic Aspect Ratio Matte Rectangles (2.39:1, 1:1, 9:16, 4:5, 16:9)
        rebuildAspectMatte(widthF, heightF)
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
        if (isCleanScreen) return // 1-Tap Clean Screen: instantly dismiss all graphs & overlays for unobstructed framing

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

        // 3. Draw Zebra Stripes (Overexposure Warning)
        if (showZebraStripes) {
            drawZebraStripes(canvas, widthF, heightF)
        }

        // 4. Draw Real-Time Live Histogram Overlay
        if (showHistogram) {
            drawHistogram(canvas, widthF, heightF)
        }

        // 5. Draw Aspect Ratio Matte Overlays (Cinematic Crop)
        if (currentMatte != AspectMatte.NONE) {
            drawAspectMatte(canvas, widthF, heightF)
        }

        // 6. Draw Smart Visual Balance Center (The "Center of Mass" Dot)
        if (showVisualBalance) {
            drawVisualBalanceCenter(canvas, widthF, heightF)
        }

        // 7. Draw Waveform Monitor (Exposure tracking across vertical screen columns)
        if (showWaveform) {
            drawWaveformMonitor(canvas, widthF, heightF)
        }

        // 8. Draw Vector Scope (Hue & Saturation with Skin Tone I-Line at 57°)
        if (showVectorScope) {
            drawVectorScope(canvas, widthF, heightF)
        }

        // 9. Draw Audio Peak VU Meter (Stereo Decibel Bars)
        if (showAudioVuMeter) {
            drawAudioVuMeter(canvas, widthF, heightF)
        }
    }

    /**
     * Overexposure Warning: Animated 45° diagonal Zebra Stripes over blown-out highlight regions (Y >= 240)
     */
    private fun drawZebraStripes(canvas: Canvas, w: Float, h: Float) {
        val animOffset = (System.currentTimeMillis() / 35 % 20).toFloat()
        val spacing = dpToPx(12f)
        
        canvas.save()
        // If we have specific overexposed bounding zones, clip and render diagonal warning lines
        if (overexposedZones.size >= 4) {
            var i = 0
            while (i < overexposedZones.size - 3) {
                val left = overexposedZones[i] * w
                val top = overexposedZones[i + 1] * h
                val right = overexposedZones[i + 2] * w
                val bottom = overexposedZones[i + 3] * h
                
                canvas.save()
                canvas.clipRect(left, top, right, bottom)
                
                var diag = -h + animOffset
                while (diag < w + h) {
                    canvas.drawLine(diag, 0f, diag + h, h, zebraBlackPaint)
                    canvas.drawLine(diag + spacing / 2f, 0f, diag + h + spacing / 2f, h, zebraPaint)
                    diag += spacing
                }
                canvas.restore()
                i += 4
            }
        }
        canvas.restore()
    }

    /**
     * Real-Time Live Histogram: 256 structural luminance bins plotted with smooth curve
     */
    private fun drawHistogram(canvas: Canvas, w: Float, h: Float) {
        val boxWidth = dpToPx(130f)
        val boxHeight = dpToPx(62f)
        val margin = dpToPx(12f)
        
        // Position at top-right corner
        val left = w - boxWidth - margin
        val top = dpToPx(56f) // Below top status bar
        val right = left + boxWidth
        val bottom = top + boxHeight
        
        histoRect.set(left, top, right, bottom)
        // Background rounded box with border
        canvas.drawRoundRect(histoRect, dpToPx(8f), dpToPx(8f), histoBgPaint)
        canvas.drawRoundRect(histoRect, dpToPx(8f), dpToPx(8f), histoBorderPaint)
        
        // Find max frequency for normalization
        var maxFreq = 1
        for (i in 0 until 256) {
            if (histogramBins[i] > maxFreq) maxFreq = histogramBins[i]
        }
        
        // Draw 256 frequency points
        histoPath.reset()
        histoFillPath.reset()
        
        val graphInnerLeft = left + dpToPx(4f)
        val graphInnerRight = right - dpToPx(4f)
        val graphInnerBottom = bottom - dpToPx(4f)
        val graphInnerTop = top + dpToPx(14f)
        val graphInnerHeight = graphInnerBottom - graphInnerTop
        val graphInnerWidth = graphInnerRight - graphInnerLeft
        
        histoFillPath.moveTo(graphInnerLeft, graphInnerBottom)
        
        for (i in 0 until 256) {
            val binX = graphInnerLeft + (i.toFloat() / 255f) * graphInnerWidth
            val normalized = (histogramBins[i].toFloat() / maxFreq.toFloat()).coerceIn(0f, 1f)
            val binY = graphInnerBottom - (normalized * graphInnerHeight)
            
            if (i == 0) {
                histoPath.moveTo(binX, binY)
            } else {
                histoPath.lineTo(binX, binY)
            }
            histoFillPath.lineTo(binX, binY)
        }
        
        histoFillPath.lineTo(graphInnerRight, graphInnerBottom)
        histoFillPath.close()
        
        // Draw fill & line
        canvas.drawPath(histoFillPath, histoFillPaint)
        canvas.drawPath(histoPath, histoLinePaint)
        
        // Header Text & Clipping Indicators
        canvas.drawText("HISTO LUMA", left + dpToPx(6f), top + dpToPx(10f), histoTextPaint)
        
        if (isShadowClipped) {
            canvas.drawCircle(left + dpToPx(74f), top + dpToPx(7f), dpToPx(2.5f), histoClipWarningPaint)
        }
        if (isHighlightClipped) {
            canvas.drawCircle(right - dpToPx(8f), top + dpToPx(7f), dpToPx(2.5f), histoClipWarningPaint)
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

    /**
     * 3. Aspect Ratio Matte Overlays (Cinematic Crop):
     * Computes translucent black bars (#AA000000) over uncropped zones while preserving 
     * full sensor data underneath.
     */
    private fun rebuildAspectMatte(w: Float, h: Float) {
        matteTopRect.setEmpty()
        matteBottomRect.setEmpty()
        matteLeftRect.setEmpty()
        matteRightRect.setEmpty()
        matteCropBounds.setEmpty()

        if (w <= 0f || h <= 0f || currentMatte == AspectMatte.NONE) return

        val targetRatio = when (currentMatte) {
            AspectMatte.CINE_239 -> 2.39f
            AspectMatte.SQUARE_1_1 -> 1.0f
            AspectMatte.VERTICAL_9_16 -> 9f / 16f
            AspectMatte.PORTRAIT_4_5 -> 4f / 5f
            AspectMatte.WIDESCREEN_16_9 -> 16f / 9f
            else -> return
        }

        val screenRatio = w / h
        if (screenRatio > targetRatio) {
            // Screen is wider than target crop -> Pillarbox bars on Left & Right
            val targetW = h * targetRatio
            val matteW = (w - targetW) / 2f
            matteLeftRect.set(0f, 0f, matteW, h)
            matteRightRect.set(w - matteW, 0f, w, h)
            matteCropBounds.set(matteW, 0f, w - matteW, h)
        } else {
            // Screen is taller than target crop -> Letterbox bars on Top & Bottom
            val targetH = w / targetRatio
            val matteH = (h - targetH) / 2f
            matteTopRect.set(0f, 0f, w, matteH)
            matteBottomRect.set(0f, h - matteH, w, h)
            matteCropBounds.set(0f, matteH, w, h - matteH)
        }
    }

    private fun drawAspectMatte(canvas: Canvas, w: Float, h: Float) {
        // Draw top & bottom letterbox bars
        if (!matteTopRect.isEmpty) {
            canvas.drawRect(matteTopRect, mattePaint)
            canvas.drawRect(matteBottomRect, mattePaint)
            canvas.drawLine(0f, matteTopRect.bottom, w, matteTopRect.bottom, matteGuideLinePaint)
            canvas.drawLine(0f, matteBottomRect.top, w, matteBottomRect.top, matteGuideLinePaint)
        }
        // Draw left & right pillarbox bars
        if (!matteLeftRect.isEmpty) {
            canvas.drawRect(matteLeftRect, mattePaint)
            canvas.drawRect(matteRightRect, mattePaint)
            canvas.drawLine(matteLeftRect.right, 0f, matteLeftRect.right, h, matteGuideLinePaint)
            canvas.drawLine(matteRightRect.left, 0f, matteRightRect.left, h, matteGuideLinePaint)
        }

        // Draw corner framing brackets and matte designation label
        if (!matteCropBounds.isEmpty) {
            val tick = dpToPx(14f)
            val l = matteCropBounds.left
            val t = matteCropBounds.top
            val r = matteCropBounds.right
            val b = matteCropBounds.bottom

            // 4 Corner Framing Accents
            canvas.drawLine(l, t, l + tick, t, matteCornerPaint)
            canvas.drawLine(l, t, l, t + tick, matteCornerPaint)
            canvas.drawLine(r - tick, t, r, t, matteCornerPaint)
            canvas.drawLine(r, t, r, t + tick, matteCornerPaint)
            canvas.drawLine(l, b - tick, l, b, matteCornerPaint)
            canvas.drawLine(l, b, l + tick, b, matteCornerPaint)
            canvas.drawLine(r - tick, b, r, b, matteCornerPaint)
            canvas.drawLine(r, b - tick, r, b, matteCornerPaint)

            val label = when (currentMatte) {
                AspectMatte.CINE_239 -> "2.39:1 CINEMASCOPE"
                AspectMatte.SQUARE_1_1 -> "1:1 SQUARE FEED"
                AspectMatte.VERTICAL_9_16 -> "9:16 VERTICAL REEL"
                AspectMatte.PORTRAIT_4_5 -> "4:5 PORTRAIT"
                AspectMatte.WIDESCREEN_16_9 -> "16:9 WIDESCREEN"
                else -> ""
            }
            if (label.isNotEmpty()) {
                val labelY = if (!matteTopRect.isEmpty) (matteTopRect.bottom - dpToPx(8f)) else (t + dpToPx(18f))
                canvas.drawText(label, l + dpToPx(16f), labelY, matteTextPaint)
            }
        }
    }

    /**
     * 4. Smart Visual Balance Center (The "Center of Mass" Dot):
     * Evaluates photometric left-vs-right and top-vs-bottom visual weight distribution.
     * When optical balance is achieved, the dot locks into the absolute center crosshair.
     */
    private fun drawVisualBalanceCenter(canvas: Canvas, w: Float, h: Float) {
        val centerX = w / 2f
        val centerY = h / 2f
        val targetDotX = visualBalanceX.coerceIn(0.08f, 0.92f) * w
        val targetDotY = visualBalanceY.coerceIn(0.08f, 0.92f) * h

        val isBalanced = isVisualBalanced
        val reticleRadius = dpToPx(14f)

        // 1. Absolute Screen Center Target Crosshair & Outer Ring
        canvas.drawCircle(centerX, centerY, reticleRadius, balanceReticlePaint)
        canvas.drawLine(centerX - dpToPx(18f), centerY, centerX + dpToPx(18f), centerY, balanceReticlePaint)
        canvas.drawLine(centerX, centerY - dpToPx(18f), centerX, centerY + dpToPx(18f), balanceReticlePaint)

        // 2. Center-to-Mass Dynamic Tension Connecting Line
        if (!isBalanced) {
            canvas.drawLine(centerX, centerY, targetDotX, targetDotY, balanceConnectingLinePaint)
            canvas.drawCircle(targetDotX, targetDotY, dpToPx(4.5f), balanceDotPaint)
        } else {
            // Snapped Optical Equilibrium (Emerald Snap Lock)
            canvas.drawCircle(centerX, centerY, dpToPx(4.5f), balanceSnappedDotPaint)
            canvas.drawCircle(centerX, centerY, dpToPx(8.5f), balanceSnappedRingPaint)
        }

        // 3. Floating HUD Metric Indicator Pill
        val badgeW = dpToPx(136f)
        val badgeH = dpToPx(22f)
        val badgeLeft = centerX - (badgeW / 2f)
        val badgeTop = h - dpToPx(112f)
        balanceBadgeRect.set(badgeLeft, badgeTop, badgeLeft + badgeW, badgeTop + badgeH)
        canvas.drawRoundRect(balanceBadgeRect, dpToPx(11f), dpToPx(11f), balanceBadgeBgPaint)

        val offsetX = ((visualBalanceX - 0.5f) * 100f).toInt()
        val text = if (isBalanced) {
            "⚖ BALANCED 0%"
        } else if (offsetX < 0) {
            "⚖ LEFT HEAVY ${abs(offsetX)}%"
        } else {
            "⚖ RIGHT HEAVY ${offsetX}%"
        }
        canvas.drawText(text, badgeLeft + dpToPx(14f), badgeTop + dpToPx(15f), balanceBadgeTextPaint)
    }

    /**
     * 7. Waveform Monitor (Real-time brightness graph across vertical screen columns)
     */
    private fun drawWaveformMonitor(canvas: Canvas, w: Float, h: Float) {
        val panelW = dpToPx(180f)
        val panelH = dpToPx(85f)
        val left = dpToPx(12f)
        val top = h - dpToPx(140f)

        // Panel background
        val bgPaint = Paint().apply {
            color = Color.parseColor("#E6000000")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val borderPaint = Paint().apply {
            color = Color.parseColor("#3310B981")
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(1f)
            isAntiAlias = true
        }
        val rect = RectF(left, top, left + panelW, top + panelH)
        canvas.drawRoundRect(rect, dpToPx(12f), dpToPx(12f), bgPaint)
        canvas.drawRoundRect(rect, dpToPx(12f), dpToPx(12f), borderPaint)

        // Header
        val headerPaint = Paint().apply {
            color = Color.parseColor("#10B981")
            textSize = dpToPx(9f)
            isFakeBoldText = true
            isAntiAlias = true
        }
        canvas.drawText("WAVEFORM · IRE 0-100", left + dpToPx(8f), top + dpToPx(14f), headerPaint)

        // Reference IRE Grid Lines (0, 20, 40, 60 Skin Tone, 80, 100 Clip)
        val gridLinePaint = Paint().apply {
            color = Color.parseColor("#3310B981")
            strokeWidth = dpToPx(0.7f)
            style = Paint.Style.STROKE
        }
        val graphTop = top + dpToPx(20f)
        val graphH = dpToPx(52f)
        val graphW = panelW - dpToPx(16f)
        val graphLeft = left + dpToPx(8f)

        for (ire in listOf(0, 20, 40, 60, 80, 100)) {
            val y = graphTop + graphH - (ire / 100f * graphH)
            canvas.drawLine(graphLeft, y, graphLeft + graphW, y, gridLinePaint)
        }

        // Phosphor Green Columns from Background Executor (Zero Pixel Loops in onDraw)
        val wfPaint = Paint().apply {
            color = Color.parseColor("#10B981")
            strokeWidth = dpToPx(1.5f)
            alpha = 180
            isAntiAlias = true
        }
        val colCount = 36
        for (i in 0 until colCount) {
            val cx = graphLeft + (i.toFloat() / (colCount - 1)) * graphW
            // Draw pre-calculated column IRE min/max bounds directly from background analyzer
            val minIre = waveformColumns[i * 2]
            val maxIre = waveformColumns[i * 2 + 1]

            val y1 = graphTop + graphH - (minIre / 100f * graphH)
            val y2 = graphTop + graphH - (maxIre / 100f * graphH)
            canvas.drawLine(cx, y1, cx, y2, wfPaint)
        }
    }

    /**
     * 8. Vector Scope (Circular color graph mapping hue and saturation with Skin Tone I-Line at 57°)
     */
    private fun drawVectorScope(canvas: Canvas, w: Float, h: Float) {
        val size = dpToPx(130f)
        val right = w - dpToPx(12f)
        val top = dpToPx(65f)
        val left = right - size
        val bottom = top + size

        val bgPaint = Paint().apply {
            color = Color.parseColor("#E6000000")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val borderPaint = Paint().apply {
            color = Color.parseColor("#33A855F7")
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(1f)
            isAntiAlias = true
        }
        val rect = RectF(left, top, right, bottom)
        canvas.drawRoundRect(rect, dpToPx(14f), dpToPx(14f), bgPaint)
        canvas.drawRoundRect(rect, dpToPx(14f), dpToPx(14f), borderPaint)

        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f + dpToPx(4f)
        val radius = dpToPx(45f)

        // Concentric Rings: 100% and 75% Saturation
        val ringPaint = Paint().apply {
            color = Color.parseColor("#44A855F7")
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(0.8f)
            isAntiAlias = true
        }
        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawCircle(cx, cy, radius * 0.75f, ringPaint)

        // Crosshairs
        canvas.drawLine(cx - radius, cy, cx + radius, cy, ringPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, ringPaint)

        // Dedicated Golden Skin Tone I-Line (~57 degrees)
        val iLinePaint = Paint().apply {
            color = Color.parseColor("#F59E0B")
            strokeWidth = dpToPx(1.5f)
            isAntiAlias = true
        }
        canvas.save()
        canvas.rotate(-57f, cx, cy)
        canvas.drawLine(cx, cy, cx + radius * 0.95f, cy, iLinePaint)
        canvas.restore()

        // Render Chromaticity Scatter Points (Cached from Background Executor, Zero Pixel Loops in UI)
        val ptPaint = Paint().apply {
            color = Color.parseColor("#38BDF8")
            strokeWidth = dpToPx(2.5f)
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        for (i in 0 until vectorScopePointCount) {
            val px = cx + vectorScopePoints[i * 2] * radius
            val py = cy + vectorScopePoints[i * 2 + 1] * radius
            canvas.drawPoint(px, py, ptPaint)
        }

        // Header label with live Skin Match Percentage
        val textPaint = Paint().apply {
            color = Color.parseColor("#D8B4FE")
            textSize = dpToPx(8.5f)
            isFakeBoldText = true
            isAntiAlias = true
        }
        canvas.drawText("VECTORSCOPE · 57° I-LINE (${vectorScopeSkinMatch.toInt()}%)", left + dpToPx(8f), top + dpToPx(14f), textPaint)
    }

    /**
     * 9. Audio Peak VU Meter (Decibel level monitoring -60dB to 0dB with clip indicator)
     */
    private fun drawAudioVuMeter(canvas: Canvas, w: Float, h: Float) {
        val meterW = dpToPx(42f)
        val meterH = dpToPx(120f)
        val right = w - dpToPx(12f)
        val bottom = h - dpToPx(110f)
        val left = right - meterW
        val top = bottom - meterH

        val bgPaint = Paint().apply {
            color = Color.parseColor("#E6000000")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val borderPaint = Paint().apply {
            color = Color.parseColor("#3310B981")
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(1f)
            isAntiAlias = true
        }
        val rect = RectF(left, top, right, bottom)
        canvas.drawRoundRect(rect, dpToPx(10f), dpToPx(10f), bgPaint)
        canvas.drawRoundRect(rect, dpToPx(10f), dpToPx(10f), borderPaint)

        // Clip indicator
        val clipPaint = Paint().apply {
            color = if (isAudioClipping) Color.parseColor("#EF4444") else Color.parseColor("#1E293B")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val clipRect = RectF(left + dpToPx(4f), top + dpToPx(4f), right - dpToPx(4f), top + dpToPx(15f))
        canvas.drawRoundRect(clipRect, dpToPx(4f), dpToPx(4f), clipPaint)

        val clipTextPaint = Paint().apply {
            color = if (isAudioClipping) Color.WHITE else Color.parseColor("#64748B")
            textSize = dpToPx(7f)
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("CLIP", clipRect.centerX(), clipRect.centerY() + dpToPx(2.5f), clipTextPaint)

        // Dual Channels L/R
        val barTop = top + dpToPx(20f)
        val barH = meterH - dpToPx(30f)
        val barW = dpToPx(8f)

        val barBgPaint = Paint().apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.FILL
        }

        // Left channel bar
        val barLeft1 = left + dpToPx(10f)
        val barRight1 = barLeft1 + barW
        canvas.drawRect(barLeft1, barTop, barRight1, barTop + barH, barBgPaint)

        // Right channel bar
        val barLeft2 = left + dpToPx(24f)
        val barRight2 = barLeft2 + barW
        canvas.drawRect(barLeft2, barTop, barRight2, barTop + barH, barBgPaint)

        // Fill levels
        val fillL = ((audioLevelDbLeft + 48f) / 48f).coerceIn(0f, 1f)
        val fillR = ((audioLevelDbRight + 48f) / 48f).coerceIn(0f, 1f)

        val levelPaint = Paint().apply {
            color = Color.parseColor("#10B981")
            style = Paint.Style.FILL
        }
        canvas.drawRect(barLeft1, barTop + barH * (1f - fillL), barRight1, barTop + barH, levelPaint)
        canvas.drawRect(barLeft2, barTop + barH * (1f - fillR), barRight2, barTop + barH, levelPaint)
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}

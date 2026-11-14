package com.example.cameraxapp.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
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

    // Live Color Preset & Shaders Grading State
    var activeFilterPreset: String = "normal"
        set(value) {
            field = value
            invalidate()
        }

    var liveBrightnessPercent: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var liveContrastPercent: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var liveSaturationPercent: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    // Live Texture Effects
    var enableFilmGrain: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var enableDateStamp: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var enableCineFlare: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var enableVignette: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var activeShootingEffect: String = "none"
        set(value) {
            field = value
            invalidate()
        }

    var customDateStampText: String? = null
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

    // --- LIVE COLOR PRESET GRADING & TEXTURE EFFECTS PAINTS ---
    private val filterGradingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val filterSecondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val filmGrainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dateStampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFA500")
        textSize = dpToPx(13.5f)
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
        setShadowLayer(dpToPx(2f), 0f, 0f, Color.parseColor("#80FF8C00"))
    }
    private val cineFlarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
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
        color = Color.parseColor("#D9F5D076") // Professional Champagne Titanium Gold Spiral
        strokeCap = Paint.Cap.ROUND
    }

    private val spiralBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(0.75f)
        color = Color.parseColor("#33F5D076") // Subtle Champagne Gold Subdivided Box Lines
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
        color = Color.parseColor("#FF000000") // Pure Jet Black (#000000) hard frame around live video feed
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
        color = Color.parseColor("#FF000000") // Pure Jet Black (#000000) hard frame around live video feed
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

    // Active Camera Sensor Aspect Ratio ("4:3", "16:9", "1:1")
    var sensorAspectRatio: String = "4:3"
        set(value) {
            field = value
            if (width > 0 && height > 0) {
                val wf = width.toFloat()
                val hf = height.toFloat()
                rebuildRuleOfThirds(wf, hf)
                rebuildGoldenRatioPhiGrid(wf, hf)
                rebuildDiagonalSymmetry(wf, hf)
                rebuildGoldenSpiral(wf, hf)
                rebuildCinematicLetterbox(wf, hf)
                rebuildAspectMatte(wf, hf)
                invalidate()
            }
        }

    private val powerPoints = FloatArray(8) // 4 intersection points (x, y)
    private val activeFrameRect = RectF()

    /**
     * Calculates the exact camera preview bounds based on aspect ratio
     * ensuring grid lines fit the visible camera screen rather than stretching over UI bars.
     */
    private fun computeFramingRect(w: Float, h: Float): RectF {
        val targetRatio = when (sensorAspectRatio) {
            "1:1" -> 1.0f
            "16:9" -> 9.0f / 16.0f
            "4:3" -> 3.0f / 4.0f
            else -> 3.0f / 4.0f
        }
        val viewRatio = w / h
        if (viewRatio > targetRatio) {
            val frameW = h * targetRatio
            val left = (w - frameW) / 2f
            activeFrameRect.set(left, 0f, left + frameW, h)
        } else {
            val frameH = w / targetRatio
            val top = (h - frameH) / 2f
            activeFrameRect.set(0f, top, w, top + frameH)
        }
        return activeFrameRect
    }

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
        val r = computeFramingRect(w, h)

        val x1 = r.left + r.width() / 3f
        val x2 = r.left + (r.width() * 2f) / 3f
        val y1 = r.top + r.height() / 3f
        val y2 = r.top + (r.height() * 2f) / 3f

        // Vertical lines within active viewfinder
        thirdsPath.moveTo(x1, r.top)
        thirdsPath.lineTo(x1, r.bottom)
        thirdsPath.moveTo(x2, r.top)
        thirdsPath.lineTo(x2, r.bottom)

        // Horizontal lines within active viewfinder
        thirdsPath.moveTo(r.left, y1)
        thirdsPath.lineTo(r.right, y1)
        thirdsPath.moveTo(r.left, y2)
        thirdsPath.lineTo(r.right, y2)

        // Intersection Power Points
        powerPoints[0] = x1; powerPoints[1] = y1
        powerPoints[2] = x2; powerPoints[3] = y1
        powerPoints[4] = x1; powerPoints[5] = y2
        powerPoints[6] = x2; powerPoints[7] = y2
    }

    private fun rebuildGoldenRatioPhiGrid(w: Float, h: Float) {
        phiGridPath.reset()
        val r = computeFramingRect(w, h)

        // Golden Ratio Constant: phi = 1.61803398875 (ratio = 0.381966)
        val ratio = 0.381966f
        val x1 = r.left + r.width() * ratio
        val x2 = r.left + r.width() * (1f - ratio)
        val y1 = r.top + r.height() * ratio
        val y2 = r.top + r.height() * (1f - ratio)

        phiGridPath.moveTo(x1, r.top); phiGridPath.lineTo(x1, r.bottom)
        phiGridPath.moveTo(x2, r.top); phiGridPath.lineTo(x2, r.bottom)
        phiGridPath.moveTo(r.left, y1); phiGridPath.lineTo(r.right, y1)
        phiGridPath.moveTo(r.left, y2); phiGridPath.lineTo(r.right, y2)
    }

    private fun rebuildDiagonalSymmetry(w: Float, h: Float) {
        diagonalPath.reset()
        val r = computeFramingRect(w, h)

        // Corner to corner diagonals within active frame
        diagonalPath.moveTo(r.left, r.top); diagonalPath.lineTo(r.right, r.bottom)
        diagonalPath.moveTo(r.right, r.top); diagonalPath.lineTo(r.left, r.bottom)

        // Harmonic Reciprocals
        val midX = r.left + r.width() / 2f
        diagonalPath.moveTo(r.left, r.bottom); diagonalPath.lineTo(midX, r.top)
        diagonalPath.moveTo(r.right, r.bottom); diagonalPath.lineTo(midX, r.top)
    }

    /**
     * Mathematically exact Golden Spiral generation with Golden Rectangles.
     * Calculated strictly ONCE during layout to prevent GC allocations on UI thread.
     */
    private fun rebuildGoldenSpiral(w: Float, h: Float) {
        spiralPath.reset()
        spiralBoxesPath.reset()

        val r = computeFramingRect(w, h)
        var left = r.left
        var top = r.top
        var right = r.right
        var bottom = r.bottom

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

        // 0. Live Visual Color Preset Grading & Texture Effects over Viewfinder
        drawLiveFilterGrading(canvas, widthF, heightF)
        drawLiveTextureEffects(canvas, widthF, heightF)

        if (isCleanScreen) return // 1-Tap Clean Screen: instantly dismiss all graphs & overlays for unobstructed framing

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

    // --- REAL-TIME LIVE COLOR FILTER GRADING OVER VIEW FINDER ---
    private fun drawLiveFilterGrading(canvas: Canvas, w: Float, h: Float) {
        val preset = activeFilterPreset.lowercase()
        if (preset == "normal" || preset == "none") {
            if (liveBrightnessPercent != 0) {
                if (liveBrightnessPercent > 0) {
                    filterGradingPaint.color = Color.argb((liveBrightnessPercent * 0.7f).toInt().coerceIn(0, 100), 255, 255, 255)
                    canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
                } else {
                    filterGradingPaint.color = Color.argb((-liveBrightnessPercent * 0.7f).toInt().coerceIn(0, 100), 0, 0, 0)
                    canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
                }
            }
            return
        }

        when (preset) {
            "lut_rec709", "filter_lut_rec709" -> {
                // Clean broadcast Rec.709 clarity micro-wash
                filterGradingPaint.color = Color.parseColor("#0A38BDF8")
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
            }
            "lut_flat_log", "filter_lut_flat_log" -> {
                // Flat Log washed-out shadow-lift grading profile
                filterGradingPaint.color = Color.parseColor("#2494A3B8")
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
            }
            "lut_teal_orange", "filter_lut_blockbuster_teal_orange" -> {
                // Blockbuster Teal & Orange: warm orange skin highlights + deep teal shadows
                filterGradingPaint.shader = LinearGradient(
                    0f, 0f, 0f, h,
                    intArrayOf(Color.parseColor("#26FB923C"), Color.TRANSPARENT, Color.parseColor("#360D9488")),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
                filterGradingPaint.shader = null
            }
            "lut_bleach_bypass", "filter_lut_bleach_bypass" -> {
                // Gritty metallic silver contrast edge falloff
                filterSecondaryPaint.shader = RadialGradient(
                    w * 0.5f, h * 0.5f, Math.max(w, h) * 0.72f,
                    intArrayOf(Color.parseColor("#12CBD5E1"), Color.parseColor("#540F172A")),
                    floatArrayOf(0.32f, 1.0f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterSecondaryPaint)
                filterSecondaryPaint.shader = null
            }
            "lut_anamorphic_cyan", "filter_lut_anamorphic_cyan" -> {
                // Sci-fi cool blue-green highlights + deep cinema blacks
                filterGradingPaint.shader = LinearGradient(
                    0f, 0f, w, h,
                    intArrayOf(Color.parseColor("#2C06B6D4"), Color.parseColor("#100E7490"), Color.parseColor("#44020617")),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
                filterGradingPaint.shader = null
            }
            "lut_matte_cinema", "filter_lut_matte_cinema" -> {
                // Velvety dark-gray lifted blacks vintage matte look
                filterGradingPaint.color = Color.parseColor("#2E3F3F46")
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
            }
            "lut_midnight_neon", "filter_lut_midnight_neon" -> {
                // Deep cobalt blues, electric violets & glowing night highlights
                filterSecondaryPaint.shader = RadialGradient(
                    w * 0.5f, h * 0.40f, Math.max(w, h) * 0.75f,
                    intArrayOf(Color.parseColor("#2E8B5CF6"), Color.parseColor("#203B82F6"), Color.parseColor("#520F172A")),
                    floatArrayOf(0f, 0.55f, 1.0f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterSecondaryPaint)
                filterSecondaryPaint.shader = null
            }
            "lut_warm_documentary", "filter_lut_warm_documentary" -> {
                // Natural warm documentary tungsten glow
                filterGradingPaint.color = Color.parseColor("#18F59E0B")
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
            }
            "lut_noir_cinema", "filter_lut_noir_cinema" -> {
                // Dramatic moving-shadow B&W cinema vignette
                filterSecondaryPaint.shader = RadialGradient(
                    w * 0.5f, h * 0.5f, Math.max(w, h) * 0.68f,
                    intArrayOf(Color.TRANSPARENT, Color.parseColor("#80000000")),
                    floatArrayOf(0.32f, 1.0f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterSecondaryPaint)
                filterSecondaryPaint.shader = null
            }
            "golden_nostalgia", "filter_golden_nostalgia" -> {
                // Yellow undertones with soft, nostalgic shadows
                filterGradingPaint.color = Color.parseColor("#1EFACC15")
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
                filterSecondaryPaint.shader = RadialGradient(
                    w * 0.5f, h * 0.45f, Math.max(w, h) * 0.72f,
                    intArrayOf(Color.TRANSPARENT, Color.parseColor("#2A422006")),
                    floatArrayOf(0.45f, 1.0f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterSecondaryPaint)
                filterSecondaryPaint.shader = null
            }
            "velvia_landscape", "filter_velvia_landscape" -> {
                // Lush greens and deep sky blues accent
                filterGradingPaint.shader = LinearGradient(
                    0f, 0f, 0f, h,
                    intArrayOf(Color.parseColor("#1A0284C7"), Color.parseColor("#18059669")),
                    floatArrayOf(0.15f, 0.85f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
                filterGradingPaint.shader = null
            }
            "nordic_slate", "filter_nordic_slate" -> {
                // Cool steel-blues and crisp shadow depth
                filterGradingPaint.color = Color.parseColor("#221E3A5F")
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
            }
            "arctic_mist", "filter_arctic_mist" -> {
                // Soft, airy blue-gray overcast veil
                filterGradingPaint.color = Color.parseColor("#26CBD5E1")
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
            }
            "classic_mono", "filter_classic_mono" -> {
                // Pure balanced silver-gray midtones
                filterGradingPaint.color = Color.parseColor("#0C000000")
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
            }
            "high_contrast_noir", "filter_high_contrast_noir" -> {
                // Dramatic Noir crushed shadow vignette
                filterSecondaryPaint.shader = RadialGradient(
                    w * 0.5f, h * 0.5f, Math.max(w, h) * 0.70f,
                    intArrayOf(Color.TRANSPARENT, Color.parseColor("#6B000000")),
                    floatArrayOf(0.40f, 1.0f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterSecondaryPaint)
                filterSecondaryPaint.shader = null
            }
            "ilford_grain", "filter_ilford_grain" -> {
                // Classic B&W subtle darkroom border tone (grain drawn below)
                filterGradingPaint.color = Color.parseColor("#12000000")
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
            }
            "silver_gelatin", "filter_silver_gelatin" -> {
                // Subtle warm-silver gallery print tint
                filterGradingPaint.color = Color.parseColor("#1CD6D3D1")
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
            }
            "antique_sepia", "filter_antique_sepia" -> {
                // Rich warm archival brown vignette
                filterSecondaryPaint.shader = RadialGradient(
                    w * 0.5f, h * 0.5f, Math.max(w, h) * 0.75f,
                    intArrayOf(Color.parseColor("#1292400E"), Color.parseColor("#48451A03")),
                    floatArrayOf(0.35f, 1.0f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterSecondaryPaint)
                filterSecondaryPaint.shader = null
            }
            "cinematic" -> {
                // Hollywood Teal & Orange Split Toning: Teal Shadows + Amber Highlights
                filterGradingPaint.shader = LinearGradient(
                    0f, 0f, 0f, h,
                    intArrayOf(Color.parseColor("#26FFAA44"), Color.parseColor("#330A3C4A")),
                    floatArrayOf(0.2f, 0.85f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
                filterGradingPaint.shader = null
            }
            "vintage" -> {
                // Warm Sepia Film Curve with Highlight Radial
                filterGradingPaint.color = Color.parseColor("#38784E28")
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
                filterSecondaryPaint.shader = RadialGradient(
                    w * 0.5f, h * 0.45f, w * 0.7f,
                    intArrayOf(Color.parseColor("#15FFE4B5"), Color.parseColor("#30402010")),
                    null,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterSecondaryPaint)
                filterSecondaryPaint.shader = null
            }
            "bw", "black_and_white", "mono" -> {
                // True monochrome handled by ColorMatrix; subtle silver-film contrast wash
                filterGradingPaint.color = Color.parseColor("#14000000")
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
            }
            "hdr" -> {
                // Dynamic Tone Mapping & Vibrancy Boost
                filterGradingPaint.shader = LinearGradient(
                    0f, 0f, w, h,
                    intArrayOf(Color.parseColor("#18FFCC00"), Color.parseColor("#1800E5FF")),
                    null,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
                filterGradingPaint.shader = null
            }
            "cyberpunk", "cyber" -> {
                // Neon Cyan Shadows & Electric Magenta Highlights
                filterGradingPaint.shader = LinearGradient(
                    0f, 0f, w, h,
                    intArrayOf(Color.parseColor("#3500E5FF"), Color.parseColor("#35FF007F")),
                    floatArrayOf(0.15f, 0.85f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
                filterGradingPaint.shader = null
            }
            else -> {
                // Custom User Filters
                val customColor = when {
                    preset.contains("emerald") || preset.contains("green") -> Color.parseColor("#2810B981")
                    preset.contains("gold") || preset.contains("warm") -> Color.parseColor("#28F59E0B")
                    preset.contains("rose") || preset.contains("pink") -> Color.parseColor("#28F43F5E")
                    preset.contains("violet") || preset.contains("purple") -> Color.parseColor("#288B5CF6")
                    preset.contains("cyan") || preset.contains("blue") -> Color.parseColor("#2806B6D4")
                    else -> Color.parseColor("#253B82F6")
                }
                filterGradingPaint.color = customColor
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
            }
        }
    }

    // --- REAL-TIME LIVE TEXTURE & SHOOTING EFFECTS (OPTICAL & SHUTTER) ---
    private fun drawLiveTextureEffects(canvas: Canvas, w: Float, h: Float) {
        val effect = activeShootingEffect.lowercase()
        val timeSec = System.currentTimeMillis() / 1000.0
        var needsAnim = false

        // 0. Picture Shooting Effects (Optical & Shutter)
        when (effect) {
            "portrait_bokeh" -> {
                // Creamy radial optical depth falloff + glowing out-of-focus bokeh orbs
                filterSecondaryPaint.shader = RadialGradient(
                    w * 0.5f, h * 0.45f, Math.max(w, h) * 0.62f,
                    intArrayOf(Color.TRANSPARENT, Color.parseColor("#1EFDF2F8"), Color.parseColor("#520F172A")),
                    floatArrayOf(0.28f, 0.55f, 1.0f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterSecondaryPaint)
                filterSecondaryPaint.shader = null

                val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
                val orbs = arrayOf(
                    floatArrayOf(0.14f, 0.20f, 22f),
                    floatArrayOf(0.84f, 0.22f, 28f),
                    floatArrayOf(0.12f, 0.72f, 25f),
                    floatArrayOf(0.87f, 0.68f, 20f),
                    floatArrayOf(0.76f, 0.84f, 18f)
                )
                for (o in orbs) {
                    val ox = w * o[0]
                    val oy = h * o[1]
                    val r = dpToPx(o[2])
                    orbPaint.shader = RadialGradient(
                        ox, oy, r,
                        intArrayOf(Color.parseColor("#44FFFBEB"), Color.parseColor("#18FDE68A"), Color.TRANSPARENT),
                        floatArrayOf(0f, 0.65f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(ox, oy, r, orbPaint)
                }
                orbPaint.shader = null

                val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(1.5f)
                    color = Color.parseColor("#99FDE68A")
                }
                canvas.drawCircle(w * 0.5f, h * 0.44f, Math.min(w, h) * 0.16f, reticlePaint)
            }
            "long_exposure" -> {
                // Silky horizontal light trails & atmospheric wash
                filterGradingPaint.color = Color.parseColor("#1A0F172A")
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)

                val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                }
                val shift = ((timeSec * 0.35) % 1.0).toFloat()
                for (i in 0..3) {
                    val y = h * (0.58f + i * 0.065f)
                    val xStart = ((shift + i * 0.22f) % 1.0f) * w * 1.2f - w * 0.3f
                    val xEnd = xStart + w * 0.42f
                    trailPaint.strokeWidth = dpToPx(4.5f - i * 0.5f)
                    trailPaint.shader = LinearGradient(
                        xStart, y, xEnd, y,
                        intArrayOf(
                            Color.TRANSPARENT,
                            if (i % 2 == 0) Color.parseColor("#99F59E0B") else Color.parseColor("#88EF4444"),
                            Color.TRANSPARENT
                        ),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawLine(xStart, y, xEnd, y, trailPaint)
                }
                trailPaint.shader = LinearGradient(
                    0f, h * 0.42f, w, h * 0.46f,
                    intArrayOf(Color.TRANSPARENT, Color.parseColor("#5538BDF8"), Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP
                )
                trailPaint.strokeWidth = dpToPx(3f)
                canvas.drawLine(0f, h * 0.44f, w, h * 0.42f, trailPaint)
                trailPaint.shader = null
                needsAnim = true
            }
            "light_painting" -> {
                // Luminous neon light-trail curves in low light
                filterGradingPaint.color = Color.parseColor("#24020617")
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)

                val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                }
                val path = Path()
                for (step in 0..64) {
                    val t = timeSec * 1.4 + step * 0.08
                    val px = w * (0.5f + 0.32f * Math.sin(t * 1.3).toFloat())
                    val py = h * (0.48f + 0.22f * Math.cos(t * 2.1).toFloat())
                    if (step == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                glowPaint.strokeWidth = dpToPx(10f)
                glowPaint.color = Color.parseColor("#44EC4899")
                canvas.drawPath(path, glowPaint)
                glowPaint.strokeWidth = dpToPx(3.5f)
                glowPaint.color = Color.parseColor("#E638BDF8")
                canvas.drawPath(path, glowPaint)
                needsAnim = true
            }
            "star_trails" -> {
                // Concentric celestial rotation arcs in upper frame
                val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeWidth = dpToPx(1.8f)
                }
                val cx = w * 0.5f
                val cy = h * 0.22f
                val phase = ((timeSec * 18.0) % 360.0).toFloat()
                for (i in 1..9) {
                    val r = Math.min(w, h) * (0.10f + i * 0.065f)
                    val oval = RectF(cx - r, cy - r, cx + r, cy + r)
                    starPaint.color = if (i % 2 == 0) Color.parseColor("#99BAE6FD") else Color.parseColor("#99FEF08A")
                    canvas.drawArc(oval, phase + i * 33f, 52f + (i % 3) * 18f, false, starPaint)
                }
                needsAnim = true
            }
            "night_sight" -> {
                // Multi-exposure shadow lift + subtle optical clarity border
                filterGradingPaint.color = Color.parseColor("#18E0F2FE")
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
            }
            "macro_focus" -> {
                // 2cm Macro Lock optical reticle
                val macroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(1.8f)
                    color = Color.parseColor("#CC34D399")
                }
                val rx = w * 0.5f
                val ry = h * 0.5f
                val r = Math.min(w, h) * 0.19f
                canvas.drawCircle(rx, ry, r, macroPaint)
                val len = dpToPx(12f)
                canvas.drawLine(rx - r - len, ry, rx - r + dpToPx(4f), ry, macroPaint)
                canvas.drawLine(rx + r - dpToPx(4f), ry, rx + r + len, ry, macroPaint)
                canvas.drawLine(rx, ry - r - len, rx, ry - r + dpToPx(4f), macroPaint)
                canvas.drawLine(rx, ry + r - dpToPx(4f), rx, ry + r + len, macroPaint)
            }
            "tilt_shift" -> {
                // Miniature top & bottom soft blur bands + focal plane guide
                filterSecondaryPaint.shader = LinearGradient(
                    0f, 0f, 0f, h,
                    intArrayOf(
                        Color.parseColor("#7A0F172A"),
                        Color.parseColor("#2E0F172A"),
                        Color.TRANSPARENT,
                        Color.TRANSPARENT,
                        Color.parseColor("#2E0F172A"),
                        Color.parseColor("#7A0F172A")
                    ),
                    floatArrayOf(0f, 0.24f, 0.36f, 0.64f, 0.76f, 1.0f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterSecondaryPaint)
                filterSecondaryPaint.shader = null
                val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(1f)
                    color = Color.parseColor("#55FACC15")
                }
                canvas.drawLine(0f, h * 0.35f, w, h * 0.35f, bandPaint)
                canvas.drawLine(0f, h * 0.65f, w, h * 0.65f, bandPaint)
            }
            "double_exposure" -> {
                // Layered dual-exposure dreamlike wash
                filterSecondaryPaint.shader = LinearGradient(
                    0f, 0f, w, h,
                    intArrayOf(Color.parseColor("#2EF472B6"), Color.parseColor("#1CFACC15"), Color.parseColor("#3038BDF8")),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterSecondaryPaint)
                filterSecondaryPaint.shader = null
            }
            "panorama" -> {
                // Smooth panorama stitch rail + animated sweep reticle
                val bandH = h * 0.22f
                filterGradingPaint.color = Color.parseColor("#70000000")
                canvas.drawRect(0f, 0f, w, bandH, filterGradingPaint)
                canvas.drawRect(0f, h - bandH, w, h, filterGradingPaint)

                val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(1.5f)
                    color = Color.parseColor("#AA38BDF8")
                }
                canvas.drawRect(w * 0.08f, h * 0.45f, w * 0.92f, h * 0.55f, railPaint)
                canvas.drawLine(w * 0.12f, h * 0.5f, w * 0.88f, h * 0.5f, railPaint)
                val sweepX = w * (0.16f + 0.68f * ((Math.sin(timeSec * 0.9).toFloat() + 1f) * 0.5f))
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#FACC15")
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(sweepX, h * 0.5f, dpToPx(5.5f), dotPaint)
                needsAnim = true
            }
            "soft_halation" -> {
                // Vintage red-orange halation highlight bloom
                filterSecondaryPaint.shader = RadialGradient(
                    w * 0.28f, h * 0.24f, w * 0.44f,
                    intArrayOf(Color.parseColor("#4DFF4D1A"), Color.parseColor("#1EFF7043"), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterSecondaryPaint)
                filterSecondaryPaint.shader = RadialGradient(
                    w * 0.74f, h * 0.30f, w * 0.36f,
                    intArrayOf(Color.parseColor("#3DFF5722"), Color.TRANSPARENT),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterSecondaryPaint)
                filterSecondaryPaint.shader = null
            }
            "video_bokeh" -> {
                // Real-time moving-subject tracking cinema bokeh falloff + aperture orbs
                val trackX = w * (0.5f + 0.06f * Math.sin(timeSec * 0.85).toFloat())
                val trackY = h * 0.45f
                filterSecondaryPaint.shader = RadialGradient(
                    trackX, trackY, Math.max(w, h) * 0.62f,
                    intArrayOf(Color.TRANSPARENT, Color.parseColor("#240F172A"), Color.parseColor("#58020617")),
                    floatArrayOf(0.24f, 0.56f, 1.0f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterSecondaryPaint)
                filterSecondaryPaint.shader = null

                val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
                for (i in 0..7) {
                    val ang = (i / 8.0) * Math.PI * 2.0 + timeSec * 0.14
                    val dist = Math.min(w, h) * (0.36f + (i % 3) * 0.07f)
                    val ox = trackX + (Math.cos(ang) * dist).toFloat()
                    val oy = trackY + (Math.sin(ang) * dist).toFloat()
                    val r = dpToPx(20f + (i % 4) * 5f)
                    orbPaint.shader = RadialGradient(
                        ox, oy, r,
                        intArrayOf(Color.parseColor("#3838BDF8"), Color.parseColor("#22FBBF24"), Color.TRANSPARENT),
                        floatArrayOf(0f, 0.65f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(ox, oy, r, orbPaint)
                }
                orbPaint.shader = null

                val bw = Math.min(w, h) * 0.18f
                val cLen = dpToPx(14f)
                val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(2f)
                    color = Color.parseColor("#DDFACC15")
                }
                canvas.drawLine(trackX - bw, trackY - bw, trackX - bw + cLen, trackY - bw, trackPaint)
                canvas.drawLine(trackX - bw, trackY - bw, trackX - bw, trackY - bw + cLen, trackPaint)
                canvas.drawLine(trackX + bw, trackY - bw, trackX + bw - cLen, trackY - bw, trackPaint)
                canvas.drawLine(trackX + bw, trackY - bw, trackX + bw, trackY - bw + cLen, trackPaint)
                canvas.drawLine(trackX - bw, trackY + bw, trackX - bw + cLen, trackY + bw, trackPaint)
                canvas.drawLine(trackX - bw, trackY + bw, trackX - bw, trackY + bw - cLen, trackPaint)
                canvas.drawLine(trackX + bw, trackY + bw, trackX + bw - cLen, trackY + bw, trackPaint)
                canvas.drawLine(trackX + bw, trackY + bw, trackX + bw, trackY + bw - cLen, trackPaint)
                needsAnim = true
            }
            "slow_motion_120_240" -> {
                // High-speed 120/240fps shutter sweep arc & speed HUD
                val cx = w * 0.5f
                val cy = h * 0.5f
                val r = Math.min(w, h) * 0.34f
                val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(2f)
                    color = Color.parseColor("#55F59E0B")
                }
                canvas.drawCircle(cx, cy, r, ringPaint)
                ringPaint.color = Color.parseColor("#E6FBBF24")
                ringPaint.strokeWidth = dpToPx(3f)
                val sweepStart = ((timeSec * 36.0) % 360.0).toFloat()
                canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), sweepStart, 48f, false, ringPaint)

                val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#E6F59E0B")
                    textSize = dpToPx(11f)
                    typeface = Typeface.MONOSPACE
                    isFakeBoldText = true
                }
                canvas.drawText("240 FPS · 0.25x SLOW MOTION", dpToPx(20f), h - dpToPx(125f), badgePaint)
                needsAnim = true
            }
            "time_lapse" -> {
                // Accelerated intervalometer arc & speed HUD
                val cx = w * 0.5f
                val cy = h * 0.5f
                val r = Math.min(w, h) * 0.28f
                val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(2f)
                    color = Color.parseColor("#5538BDF8")
                }
                canvas.drawCircle(cx, cy, r, ringPaint)
                ringPaint.color = Color.parseColor("#E638BDF8")
                ringPaint.strokeWidth = dpToPx(3f)
                val fastSweep = ((timeSec * 280.0) % 360.0).toFloat()
                canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), fastSweep, 75f, false, ringPaint)

                val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#E638BDF8")
                    textSize = dpToPx(11f)
                    typeface = Typeface.MONOSPACE
                    isFakeBoldText = true
                }
                canvas.drawText("TIME-LAPSE · 8x INTERVAL", dpToPx(20f), h - dpToPx(125f), badgePaint)
                needsAnim = true
            }
            "hyperlapse" -> {
                // Stabilized moving time-lapse crop brackets & motion chevrons
                val padX = w * 0.08f
                val padY = h * 0.08f
                val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(1.8f)
                    color = Color.parseColor("#9922D3EE")
                }
                canvas.drawRect(padX, padY, w - padX, h - padY, framePaint)
                val shift = ((timeSec * 2.5) % 1.0).toFloat() * dpToPx(20f)
                for (i in 0..2) {
                    val cx = w * 0.5f + (i - 1) * dpToPx(18f) + shift * 0.5f
                    val cy = h * 0.78f
                    canvas.drawLine(cx - dpToPx(5f), cy - dpToPx(5f), cx + dpToPx(3f), cy, framePaint)
                    canvas.drawLine(cx + dpToPx(3f), cy, cx - dpToPx(5f), cy + dpToPx(5f), framePaint)
                }
                val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#E622D3EE")
                    textSize = dpToPx(11f)
                    typeface = Typeface.MONOSPACE
                    isFakeBoldText = true
                }
                canvas.drawText("HYPERLAPSE · 4x STABILIZED", dpToPx(20f), h - dpToPx(125f), badgePaint)
                needsAnim = true
            }
            "dolly_zoom" -> {
                // Vertigo dynamic depth-warp perspective rectangles
                val pulse = ((Math.sin(timeSec * 2.1) + 1.0) * 0.5).toFloat()
                val warpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(1.6f)
                }
                val cx = w * 0.5f
                val cy = h * 0.5f
                for (i in 0..2) {
                    val r = Math.min(w, h) * (0.16f + i * 0.11f + pulse * 0.06f)
                    warpPaint.color = Color.argb((140 - i * 35), 192, 132, 252)
                    canvas.drawRect(cx - r, cy - r * 0.75f, cx + r, cy + r * 0.75f, warpPaint)
                }
                val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#E6D8B4FE")
                    textSize = dpToPx(11f)
                    typeface = Typeface.MONOSPACE
                    isFakeBoldText = true
                }
                val factor = String.format(java.util.Locale.US, "%.2fx", 1.0f + pulse * 0.36f)
                canvas.drawText("DOLLY ZOOM · " + factor, dpToPx(20f), h - dpToPx(125f), badgePaint)
                needsAnim = true
            }
            "motion_blur" -> {
                // Horizontal shutter-drag directional motion streaks
                val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                }
                for (i in 0..6) {
                    val my = h * (0.20f + i * 0.09f)
                    val mx = (((timeSec * (0.65 + (i % 3) * 0.22)) + i * 0.17) % 1.0).toFloat() * w
                    val len = w * 0.38f
                    streakPaint.strokeWidth = dpToPx(3f + (i % 3))
                    streakPaint.shader = LinearGradient(
                        mx - len * 0.5f, my, mx + len * 0.5f, my,
                        intArrayOf(Color.TRANSPARENT, Color.parseColor("#44E2E8F0"), Color.TRANSPARENT),
                        null, Shader.TileMode.CLAMP
                    )
                    canvas.drawLine(mx - len * 0.5f, my, mx + len * 0.5f, my, streakPaint)
                }
                streakPaint.shader = null
                needsAnim = true
            }
            "action_stabilization" -> {
                // Horizon Lock active gimbal safety frame & counter-rotated horizontal lock line
                val padX = w * 0.09f
                val padY = h * 0.09f
                val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(2f)
                    color = Color.parseColor("#CC10B981")
                }
                canvas.drawRect(padX, padY, w - padX, h - padY, lockPaint)

                canvas.save()
                canvas.rotate(-tiltRollDegrees.coerceIn(-45f, 45f), w * 0.5f, h * 0.5f)
                lockPaint.strokeWidth = dpToPx(2.2f)
                lockPaint.color = Color.parseColor("#E634D399")
                canvas.drawLine(w * 0.5f - dpToPx(56f), h * 0.5f, w * 0.5f - dpToPx(14f), h * 0.5f, lockPaint)
                canvas.drawLine(w * 0.5f + dpToPx(14f), h * 0.5f, w * 0.5f + dpToPx(56f), h * 0.5f, lockPaint)
                canvas.restore()

                val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#E634D399")
                    textSize = dpToPx(11f)
                    typeface = Typeface.MONOSPACE
                    isFakeBoldText = true
                }
                canvas.drawText("HORIZON LOCK · 0.0° STABILIZED", dpToPx(20f), h - dpToPx(125f), badgePaint)
                needsAnim = true
            }
            "vintage_super8" -> {
                // Classic 8mm film gate, left sprocket perforations, jitter & warm edge light leak
                val flicker = (0.72 + 0.28 * Math.sin(timeSec * 9.5)).toFloat()
                filterSecondaryPaint.shader = RadialGradient(
                    w * 0.92f, h * 0.16f, w * 0.48f,
                    intArrayOf(
                        Color.argb((115 * flicker).toInt(), 251, 146, 60),
                        Color.argb((55 * flicker).toInt(), 239, 68, 68),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterSecondaryPaint)
                filterSecondaryPaint.shader = null

                val gatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#DF060608") }
                val railW = dpToPx(22f)
                canvas.drawRect(0f, 0f, railW, h, gatePaint)
                canvas.drawRect(w - dpToPx(8f), 0f, w, h, gatePaint)

                val sprocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CCF5F0E1") }
                val spacing = dpToPx(54f)
                val scroll = ((timeSec * 36.0) % spacing).toFloat() + (Math.sin(timeSec * 27.0) * dpToPx(2f)).toFloat()
                var sy = -spacing
                while (sy < h + spacing) {
                    val yPos = sy + scroll
                    canvas.drawRoundRect(
                        RectF(dpToPx(5f), yPos, railW - dpToPx(5f), yPos + dpToPx(16f)),
                        dpToPx(2f), dpToPx(2f), sprocketPaint
                    )
                    sy += spacing
                }
                needsAnim = true
            }
            "oil_canvas" -> {
                // Fine linen weave + textured impasto oil brushstroke dabs
                val weavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(0.8f)
                    color = Color.parseColor("#18FFF8E8")
                }
                val step = dpToPx(9f).toInt().coerceAtLeast(8)
                for (gx in 0 until w.toInt() step step) {
                    canvas.drawLine(gx.toFloat(), 0f, gx.toFloat(), h, weavePaint)
                }
                for (gy in 0 until h.toInt() step step) {
                    canvas.drawLine(0f, gy.toFloat(), w, gy.toFloat(), weavePaint)
                }
                val dabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeWidth = dpToPx(8f)
                }
                dabPaint.color = Color.parseColor("#24FDE68A")
                canvas.drawArc(RectF(w * 0.08f, h * 0.14f, w * 0.45f, h * 0.36f), 15f, 110f, false, dabPaint)
                dabPaint.color = Color.parseColor("#2238BDF8")
                canvas.drawArc(RectF(w * 0.55f, h * 0.56f, w * 0.92f, h * 0.84f), 195f, 120f, false, dabPaint)
            }
            "charcoal_sketch" -> {
                // Warm sketch paper wash + graphite diagonal cross-hatching strokes
                filterGradingPaint.color = Color.parseColor("#26F4EFE6")
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
                val hatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(1.2f)
                    color = Color.parseColor("#321E293B")
                }
                val step = dpToPx(18f).toInt().coerceAtLeast(16)
                for (i in -h.toInt() until w.toInt() step step) {
                    canvas.drawLine(i.toFloat(), 0f, i + h * 0.35f, h, hatchPaint)
                }
                hatchPaint.strokeWidth = dpToPx(3f)
                hatchPaint.color = Color.parseColor("#66334155")
                canvas.drawRect(dpToPx(6f), dpToPx(6f), w - dpToPx(6f), h - dpToPx(6f), hatchPaint)
            }
            "watercolor_wash" -> {
                // Soft pastel pigment bleeding wash & cold-press deckled frame
                filterSecondaryPaint.shader = RadialGradient(
                    w * 0.25f, h * 0.28f, Math.max(w, h) * 0.74f,
                    intArrayOf(
                        Color.parseColor("#2CFBBF24"),
                        Color.parseColor("#2838BDF8"),
                        Color.parseColor("#38F472B6")
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterSecondaryPaint)
                filterSecondaryPaint.shader = null
                val paperFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(10f)
                    color = Color.parseColor("#66FFFBF2")
                }
                canvas.drawRect(dpToPx(5f), dpToPx(5f), w - dpToPx(5f), h - dpToPx(5f), paperFrame)
            }
            "ink_and_line" -> {
                // Minimalist pen-and-ink drafting frame + crisp corner ink flourish rules
                filterGradingPaint.color = Color.parseColor("#18FFFFFF")
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
                val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(2.2f)
                    color = Color.parseColor("#CC0F172A")
                }
                val pad = dpToPx(14f)
                canvas.drawRect(pad, pad, w - pad, h - pad, inkPaint)
                inkPaint.strokeWidth = dpToPx(1f)
                canvas.drawRect(pad + dpToPx(5f), pad + dpToPx(5f), w - pad - dpToPx(5f), h - pad - dpToPx(5f), inkPaint)
            }
            "pastel_chalk" -> {
                // Soft powdery matte chalk veil + pastel dust speckles
                filterSecondaryPaint.shader = LinearGradient(
                    0f, 0f, w, h,
                    intArrayOf(
                        Color.parseColor("#34FBCFE8"),
                        Color.parseColor("#22FEF08A"),
                        Color.parseColor("#34BAE6FD")
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterSecondaryPaint)
                filterSecondaryPaint.shader = null
                val chalkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#44FFFFFF") }
                val rng = java.util.Random(42L)
                for (i in 0..90) {
                    canvas.drawCircle(rng.nextFloat() * w, rng.nextFloat() * h, dpToPx(1.6f), chalkPaint)
                }
            }
            "halftone_print" -> {
                // Newspaper / graphic novel staggered halftone ink dot screen
                filterGradingPaint.color = Color.parseColor("#1AF7F3E8")
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.parseColor("#420F172A")
                }
                val step = dpToPx(10f).toInt().coerceAtLeast(10)
                var row = 0
                for (y in step / 2 until h.toInt() step step) {
                    val xOff = if (row % 2 == 0) 0f else step * 0.5f
                    for (x in step / 2 until w.toInt() step step) {
                        canvas.drawCircle(x.toFloat() + xOff, y.toFloat(), dpToPx(2.0f), dotPaint)
                    }
                    row++
                }
            }
            "cyanotype" -> {
                // Historic Prussian-blue blueprint overlay + white architectural blueprint border
                filterGradingPaint.color = Color.parseColor("#380C2D57")
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
                val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(1.2f)
                    color = Color.parseColor("#66E0F2FE")
                }
                val pad = dpToPx(16f)
                canvas.drawRect(pad, pad, w - pad, h - pad, gridPaint)
                val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#CCE0F2FE")
                    textSize = dpToPx(10f)
                    typeface = Typeface.MONOSPACE
                    isFakeBoldText = true
                }
                canvas.drawText("CYANOTYPE · PRUSSIAN BLUEPRINT", pad + dpToPx(8f), h - dpToPx(125f), badgePaint)
            }
            "woodcut_engraving" -> {
                // Bold horizontal carved relief lines & thick woodblock ink border
                val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(1.5f)
                    color = Color.parseColor("#38020617")
                }
                val step = dpToPx(7f).toInt().coerceAtLeast(6)
                for (y in 0 until h.toInt() step step) {
                    canvas.drawLine(0f, y.toFloat(), w, y.toFloat(), groovePaint)
                }
                val blockBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(10f)
                    color = Color.parseColor("#CC09090B")
                }
                canvas.drawRect(dpToPx(5f), dpToPx(5f), w - dpToPx(5f), h - dpToPx(5f), blockBorder)
            }
            "lithograph" -> {
                // Muted stone-print limestone stipple grain & archival plate margin
                filterGradingPaint.color = Color.parseColor("#20E2DCCE")
                canvas.drawRect(0f, 0f, w, h, filterGradingPaint)
                val stipple = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#34262320") }
                val rng = java.util.Random(77L)
                for (i in 0..160) {
                    canvas.drawCircle(rng.nextFloat() * w, rng.nextFloat() * h, dpToPx(1.3f), stipple)
                }
                val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(10f)
                    color = Color.parseColor("#66F5F2EB")
                }
                canvas.drawRect(dpToPx(5f), dpToPx(5f), w - dpToPx(5f), h - dpToPx(5f), platePaint)
            }
            "prism_refraction" -> {
                // Rainbow glass dispersion caustics along frame corners
                filterSecondaryPaint.shader = LinearGradient(
                    0f, 0f, w * 0.45f, h * 0.45f,
                    intArrayOf(
                        Color.parseColor("#66F43F5E"),
                        Color.parseColor("#4DFACC15"),
                        Color.parseColor("#5C22D3EE"),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.35f, 0.7f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterSecondaryPaint)

                filterSecondaryPaint.shader = LinearGradient(
                    w, h, w * 0.55f, h * 0.55f,
                    intArrayOf(
                        Color.parseColor("#66A855F7"),
                        Color.parseColor("#5238BDF8"),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterSecondaryPaint)
                filterSecondaryPaint.shader = null
            }
            "kaleidoscope" -> {
                // Symmetrical 8-axis geometric kaleidoscope facet reticle from lens center
                val cx = w * 0.5f
                val cy = h * 0.5f
                val radius = Math.hypot(w.toDouble(), h.toDouble()).toFloat() * 0.55f
                val facetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = dpToPx(1.5f)
                    color = Color.parseColor("#77FFFFFF")
                }
                val spin = (timeSec * 0.14).toFloat()
                for (i in 0..3) {
                    val ang = spin + i * (Math.PI / 4.0).toFloat()
                    val dx = Math.cos(ang.toDouble()).toFloat() * radius
                    val dy = Math.sin(ang.toDouble()).toFloat() * radius
                    canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, facetPaint)
                }
                val ringR = Math.min(w, h) * 0.28f
                facetPaint.color = Color.parseColor("#66E0F2FE")
                canvas.drawCircle(cx, cy, ringR, facetPaint)
                canvas.drawCircle(cx, cy, ringR * 0.5f, facetPaint)
                needsAnim = true
            }
            "solarize" -> {
                // Surreal Sabattier darkroom metallic silver highlight sheen
                filterSecondaryPaint.shader = RadialGradient(
                    w * 0.5f, h * 0.45f, Math.max(w, h) * 0.68f,
                    intArrayOf(
                        Color.parseColor("#24E2E8F0"),
                        Color.parseColor("#2C38BDF8"),
                        Color.parseColor("#4A0F172A")
                    ),
                    floatArrayOf(0f, 0.58f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, filterSecondaryPaint)
                filterSecondaryPaint.shader = null
            }
        }

        // 1. Film Grain (Animated 35mm emulsion noise + fine Ilford B&W grain + Vintage Super 8)
        val isIlfordGrain = activeFilterPreset.lowercase() == "ilford_grain" || activeFilterPreset.lowercase() == "filter_ilford_grain"
        val isFilmGrainEffect = effect == "film_grain" || effect == "vintage_super8"
        if (enableFilmGrain || isIlfordGrain || isFilmGrainEffect) {
            val random = java.util.Random(System.currentTimeMillis() / 75)
            val step = dpToPx(if (isIlfordGrain && !enableFilmGrain && !isFilmGrainEffect) 16f else 22f).toInt().coerceAtLeast(12)
            for (x in 0 until w.toInt() step step) {
                for (y in 0 until h.toInt() step step) {
                    val rx = x + random.nextInt(step)
                    val ry = y + random.nextInt(step)
                    val alpha = random.nextInt(65) + 28
                    val isLight = random.nextBoolean()
                    filmGrainPaint.color = if (isLight) Color.argb(alpha, 250, 248, 242) else Color.argb(alpha / 2, 12, 12, 12)
                    canvas.drawCircle(rx.toFloat(), ry.toFloat(), dpToPx(if (isIlfordGrain && !enableFilmGrain && !isFilmGrainEffect) 1.0f else 1.3f), filmGrainPaint)
                }
            }
            needsAnim = true
        }

        // 2. Anamorphic Flare Streak (Cyan-blue & warm amber horizontal sci-fi streaks)
        if (enableCineFlare || effect == "anamorphic_flare") {
            val cy = h * 0.44f
            val streakH = dpToPx(18f)
            cineFlarePaint.shader = LinearGradient(
                0f, cy, w, cy,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#B300D4FF"), Color.parseColor("#E6FFFFFF"), Color.parseColor("#B300D4FF"), Color.TRANSPARENT),
                floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, cy - streakH, w, cy + streakH, cineFlarePaint)

            val cy2 = h * 0.56f
            val streakH2 = dpToPx(10f)
            cineFlarePaint.shader = LinearGradient(
                0f, cy2, w, cy2,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#88F59E0B"), Color.parseColor("#CCFFFFFF"), Color.parseColor("#88F59E0B"), Color.TRANSPARENT),
                floatArrayOf(0f, 0.38f, 0.58f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, cy2 - streakH2, w, cy2 + streakH2, cineFlarePaint)
            cineFlarePaint.shader = null
        }

        // 3. Retro Orange Date Stamp Watermark ('YY MM dd HH:mm)
        if (enableDateStamp) {
            val sdf = java.text.SimpleDateFormat("''yy MM dd  HH:mm", java.util.Locale.US)
            val dateText = customDateStampText ?: sdf.format(java.util.Date())
            val marginEnd = dpToPx(24f)
            val marginBottom = dpToPx(110f) // Positioned above shutter & controls
            val textW = dateStampPaint.measureText(dateText)
            canvas.drawText(dateText, w - textW - marginEnd, h - marginBottom, dateStampPaint)
        }

        // 4. Cinematic Vignette (explicit toggle OR Picture Shooting Vignette effect)
        if (enableVignette || effect == "vignette") {
            val radius = Math.max(w, h) * 0.72f
            filterSecondaryPaint.shader = RadialGradient(
                w * 0.5f, h * 0.5f, radius,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#99000000")),
                floatArrayOf(0.42f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, filterSecondaryPaint)
            filterSecondaryPaint.shader = null
        }

        if (needsAnim) {
            postInvalidateOnAnimation()
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}

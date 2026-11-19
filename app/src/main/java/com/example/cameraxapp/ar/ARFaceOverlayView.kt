package com.example.cameraxapp.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * ARFaceOverlayView: Custom Hardware-Accelerated View for Real-Time AR Lenses
 * 
 * Supports 5 AR Modes:
 * 1. MESH_CONTOUR: 468 landmark green cyber tessellation + facial contours
 * 2. CYBERPUNK_VISOR: Glowing neon visor anchored across eye landmarks with HUD metrics
 * 3. GOLDEN_CROWN: Regal golden diadem anchored to forehead with glittering gems
 * 4. SCIFI_HUD: Military-grade face-tracking targeting reticle with lock-on brackets
 * 5. COMIC_FRAME: Dynamic thick black comic-book panel borders when cartoon shader is active
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
        SCIFI_HUD,
        COMIC_FRAME,
        GLB_3D
    }

    var currentLens: ArLensMode = ArLensMode.NONE
        set(value) {
            field = value
            invalidate()
        }

    // Native 3D .glb AR Lens Engine (hardware OpenGL ES 2.0 offscreen renderer)
    private var glbEngine: GlbArModelEngine? = null
    private var activeGlbAnchor: String = "forehead"
    private var activeGlbModelScale: Float = 1.0f
    private var activeGlbOffsetY: Float = 0f
    private var activeGlbRotY: Float = 0f
    private var activeGlbRotX: Float = 0f
    private var activeGlbAutoSpin: Boolean = true
    private var activeGlbStartMs: Long = System.currentTimeMillis()
    @Volatile
    private var latestGlbBitmap: Bitmap? = null
    private var lastGlbRenderRequestMs: Long = 0L

    fun loadGlbModelFromAsset(
        assetPath: String,
        anchor: String = "forehead",
        modelScale: Float = 1.0f,
        offsetY: Float = 0f,
        rotYDeg: Float = 0f,
        rotXDeg: Float = 0f,
        autoSpin: Boolean = true
    ): Boolean {
        val engine = glbEngine ?: GlbArModelEngine(context.applicationContext).also { glbEngine = it }
        activeGlbAnchor = anchor
        activeGlbModelScale = modelScale.coerceIn(0.25f, 3.0f)
        activeGlbOffsetY = offsetY.coerceIn(-1.0f, 1.0f)
        activeGlbRotY = rotYDeg
        activeGlbRotX = rotXDeg
        activeGlbAutoSpin = autoSpin
        activeGlbStartMs = System.currentTimeMillis()
        latestGlbBitmap = null
        val ok = engine.loadFromAsset(assetPath)
        if (ok) {
            currentLens = ArLensMode.GLB_3D
            postInvalidateOnAnimation()
        }
        return ok
    }

    fun loadGlbModelFromFile(
        filePath: String,
        anchor: String = "forehead",
        modelScale: Float = 1.0f,
        offsetY: Float = 0f,
        rotYDeg: Float = 0f,
        rotXDeg: Float = 0f,
        autoSpin: Boolean = true
    ): Boolean {
        val engine = glbEngine ?: GlbArModelEngine(context.applicationContext).also { glbEngine = it }
        activeGlbAnchor = anchor
        activeGlbModelScale = modelScale.coerceIn(0.25f, 3.0f)
        activeGlbOffsetY = offsetY.coerceIn(-1.0f, 1.0f)
        activeGlbRotY = rotYDeg
        activeGlbRotX = rotXDeg
        activeGlbAutoSpin = autoSpin
        activeGlbStartMs = System.currentTimeMillis()
        latestGlbBitmap = null
        val ok = engine.loadFromFile(filePath)
        if (ok) {
            currentLens = ArLensMode.GLB_3D
            postInvalidateOnAnimation()
        }
        return ok
    }

    fun releaseGlbEngine() {
        glbEngine?.release()
        glbEngine = null
        latestGlbBitmap = null
    }

    var isCartoonShaderActive: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    data class ComicWordPalette(
        val topColor: Int,
        val bottomColor: Int,
        val extrudeColor: Int,
        val burstColor: Int
    )

    var powPalette = ComicWordPalette(
        Color.parseColor("#FFFBEB"),
        Color.parseColor("#FACC15"),
        Color.parseColor("#EF4444"),
        Color.parseColor("#DC2626")
    )
    var bamPalette = ComicWordPalette(
        Color.WHITE,
        Color.parseColor("#FF5E3A"),
        Color.parseColor("#0284C7"),
        Color.parseColor("#FACC15")
    )
    var zapPalette = ComicWordPalette(
        Color.parseColor("#ECFCCB"),
        Color.parseColor("#22D3EE"),
        Color.parseColor("#7C3AED"),
        Color.parseColor("#2563EB")
    )

    fun setComicOnomatopoeiaPalette(
        powTop: String, powBottom: String, powExtrude: String, powBurst: String,
        bamTop: String, bamBottom: String, bamExtrude: String, bamBurst: String,
        zapTop: String, zapBottom: String, zapExtrude: String, zapBurst: String
    ) {
        powPalette = ComicWordPalette(Color.parseColor(powTop), Color.parseColor(powBottom), Color.parseColor(powExtrude), Color.parseColor(powBurst))
        bamPalette = ComicWordPalette(Color.parseColor(bamTop), Color.parseColor(bamBottom), Color.parseColor(bamExtrude), Color.parseColor(bamBurst))
        zapPalette = ComicWordPalette(Color.parseColor(zapTop), Color.parseColor(zapBottom), Color.parseColor(zapExtrude), Color.parseColor(zapBurst))
        invalidate()
    }

    private var comicEntryStartMs: Long = 0L
    private var wasFaceDetectedForComic: Boolean = false
    var onComicFaceAcquired: (() -> Unit)? = null

    enum class ComicSpeechBubbleStyle {
        SPEECH,
        THOUGHT,
        SHOUT
    }

    var enableComicOnomatopoeia: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    comicEntryStartMs = System.currentTimeMillis()
                    postInvalidateOnAnimation()
                }
                invalidate()
            }
        }

    var enableComicSpeechBubble: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    comicEntryStartMs = System.currentTimeMillis()
                    postInvalidateOnAnimation()
                }
                invalidate()
            }
        }

    var speechBubbleStyle: ComicSpeechBubbleStyle = ComicSpeechBubbleStyle.SPEECH
        set(value) {
            if (field != value) {
                field = value
                if (enableComicSpeechBubble) {
                    comicEntryStartMs = System.currentTimeMillis()
                    postInvalidateOnAnimation()
                }
                invalidate()
            }
        }

    var speechBubbleText: String = "HERO MODE!"
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var comicLabelDepth: Float = 1.0f
        set(value) {
            val clamped = value.coerceIn(0f, 2.5f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    // Freehand Adjustable & Touch-Draggable Screen AR Text Overlays (Independent of Other Lenses)
    enum class ArTextStyle {
        NEON,
        COMIC,
        PILL,
        OUTLINE
    }

    data class ArTextItem(
        val id: String,
        var text: String,
        var normX: Float = 0.5f,
        var normY: Float = 0.34f,
        var scale: Float = 1.0f,
        var colorHex: String = "#FACC15",
        var style: ArTextStyle = ArTextStyle.NEON,
        var rotationDeg: Float = 0f
    )

    val arTextItems: MutableList<ArTextItem> = mutableListOf()
    var selectedTextId: String? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var onTextItemSelected: ((ArTextItem?) -> Unit)? = null
    var onTextItemEditRequested: ((ArTextItem) -> Unit)? = null
    var onOverlayDirty: (() -> Unit)? = null

    private var draggingTextId: String? = null
    private var isDraggingText: Boolean = false
    private var isScalingText: Boolean = false

    fun hasArTextItems(): Boolean = arTextItems.isNotEmpty()

    fun hasAnyVisibleOverlay(): Boolean =
        currentLens != ArLensMode.NONE ||
            showDebugReticle ||
            enableComicOnomatopoeia ||
            enableComicSpeechBubble ||
            arTextItems.isNotEmpty()

    fun addOrSelectArText(
        text: String,
        scale: Float = 1.0f,
        colorHex: String = "#FACC15",
        style: ArTextStyle = ArTextStyle.NEON,
        rotationDeg: Float = 0f
    ): ArTextItem {
        val clean = text.trim().ifEmpty { "HELLO AR!" }
        val idx = arTextItems.size
        val item = ArTextItem(
            id = "ar_txt_${System.currentTimeMillis()}_${idx}",
            text = clean,
            normX = (0.5f + ((idx % 3) - 1) * 0.08f).coerceIn(0.16f, 0.84f),
            normY = (0.28f + (idx % 4) * 0.11f).coerceIn(0.16f, 0.84f),
            scale = scale.coerceIn(0.55f, 2.4f),
            colorHex = colorHex,
            style = style,
            rotationDeg = rotationDeg.coerceIn(-35f, 35f)
        )
        arTextItems.add(item)
        selectedTextId = item.id
        onTextItemSelected?.invoke(item)
        onOverlayDirty?.invoke()
        invalidate()
        return item
    }

    fun updateSelectedArText(
        text: String? = null,
        scale: Float? = null,
        colorHex: String? = null,
        style: ArTextStyle? = null,
        rotationDeg: Float? = null
    ): Boolean {
        val target = arTextItems.firstOrNull { it.id == selectedTextId } ?: arTextItems.lastOrNull() ?: return false
        text?.let { target.text = it.trim().ifEmpty { target.text } }
        scale?.let { target.scale = it.coerceIn(0.55f, 2.4f) }
        colorHex?.let { target.colorHex = it }
        style?.let { target.style = it }
        rotationDeg?.let { target.rotationDeg = it.coerceIn(-35f, 35f) }
        selectedTextId = target.id
        onOverlayDirty?.invoke()
        invalidate()
        return true
    }

    fun removeSelectedArText(): Boolean {
        val id = selectedTextId ?: arTextItems.lastOrNull()?.id ?: return false
        val removed = arTextItems.removeAll { it.id == id }
        if (removed) {
            selectedTextId = arTextItems.lastOrNull()?.id
            onTextItemSelected?.invoke(arTextItems.firstOrNull { it.id == selectedTextId })
            onOverlayDirty?.invoke()
            invalidate()
        }
        return removed
    }

    fun clearArTextItems() {
        if (arTextItems.isEmpty()) return
        arTextItems.clear()
        selectedTextId = null
        draggingTextId = null
        onTextItemSelected?.invoke(null)
        onOverlayDirty?.invoke()
        invalidate()
    }

    private fun findHitArTextItem(x: Float, y: Float): ArTextItem? {
        if (arTextItems.isEmpty() || width <= 0 || height <= 0) return null
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val hitPad = 42f
        for (i in arTextItems.indices.reversed()) {
            val item = arTextItems[i]
            val bounds = computeTextItemBounds(item, viewW, viewH)
            val cx = item.normX * viewW
            val cy = item.normY * viewH
            val rad = -Math.toRadians(item.rotationDeg.toDouble())
            val dx = x - cx
            val dy = y - cy
            val lx = (dx * Math.cos(rad) - dy * Math.sin(rad)).toFloat()
            val ly = (dx * Math.sin(rad) + dy * Math.cos(rad)).toFloat()
            if (Math.abs(lx) <= bounds.width() * 0.5f + hitPad && Math.abs(ly) <= bounds.height() * 0.5f + hitPad) {
                return item
            }
        }
        return null
    }

    private val textScaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                val target = arTextItems.firstOrNull { it.id == draggingTextId }
                    ?: findHitArTextItem(detector.focusX, detector.focusY)
                    ?: return false
                draggingTextId = target.id
                selectedTextId = target.id
                isScalingText = true
                onTextItemSelected?.invoke(target)
                invalidate()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val activeId = draggingTextId ?: selectedTextId ?: return false
                val item = arTextItems.firstOrNull { it.id == activeId } ?: return false
                item.scale = (item.scale * detector.scaleFactor).coerceIn(0.55f, 2.4f)
                onOverlayDirty?.invoke()
                invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScalingText = false
                onOverlayDirty?.invoke()
                invalidate()
            }
        }
    )

    private val textGestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                val hit = findHitArTextItem(e.x, e.y)
                if (hit != null) {
                    draggingTextId = hit.id
                    selectedTextId = hit.id
                    isDraggingText = true
                    onTextItemSelected?.invoke(hit)
                    invalidate()
                    return true
                }
                draggingTextId = null
                isDraggingText = false
                if (selectedTextId != null) {
                    selectedTextId = null
                    invalidate()
                }
                return false
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (isScalingText || textScaleDetector.isInProgress) return false
                val activeId = draggingTextId ?: return false
                val item = arTextItems.firstOrNull { it.id == activeId } ?: return false
                val viewW = width.toFloat().coerceAtLeast(1f)
                val viewH = height.toFloat().coerceAtLeast(1f)
                // distanceX/Y are previous - current, so subtracting moves text smoothly with finger
                item.normX = (item.normX - distanceX / viewW).coerceIn(0.06f, 0.94f)
                item.normY = (item.normY - distanceY / viewH).coerceIn(0.06f, 0.94f)
                onOverlayDirty?.invoke()
                invalidate()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val hit = findHitArTextItem(e.x, e.y) ?: return
                draggingTextId = hit.id
                selectedTextId = hit.id
                isDraggingText = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onTextItemSelected?.invoke(hit)
                invalidate()
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val hit = findHitArTextItem(e.x, e.y) ?: return false
                selectedTextId = hit.id
                onTextItemEditRequested?.invoke(hit)
                invalidate()
                return true
            }
        }
    )

    /**
     * GestureDetector + ScaleGestureDetector touch handler for AR text objects.
     * Captures drag (onScroll) and pinch-to-resize (onScale) ONLY when touching an AR text box,
     * allowing tap-to-focus, camera zoom, and all other AR lenses to work seamlessly elsewhere.
     */
    fun handleArTextTouch(event: MotionEvent): Boolean {
        if (arTextItems.isEmpty() || width <= 0 || height <= 0) return false
        val scaleHandled = textScaleDetector.onTouchEvent(event)
        val gestureHandled = if (!textScaleDetector.isInProgress) {
            textGestureDetector.onTouchEvent(event)
        } else {
            false
        }
        val wasManipulating = isDraggingText || isScalingText || draggingTextId != null

        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (wasManipulating) {
                draggingTextId = null
                isDraggingText = false
                isScalingText = false
                onOverlayDirty?.invoke()
                invalidate()
                return true
            }
        }
        return gestureHandled || (scaleHandled && isScalingText) || isDraggingText || isScalingText
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return handleArTextTouch(event) || super.onTouchEvent(event)
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
        if (faces.isNotEmpty()) {
            landmarks = faces[0]
            hasSimpleFace = false
        } else {
            landmarks = null
        }
        imageWidth = inputWidth.coerceAtLeast(1)
        imageHeight = inputHeight.coerceAtLeast(1)
        isFrontCamera = isFrontFacing
        animTick++
        postInvalidate()
    }

    fun clearLandmarksOnly() {
        landmarks = null
        postInvalidate()
    }

    fun clear() {
        landmarks = null
        hasSimpleFace = false
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawCompositeOverlay(canvas, width.toFloat(), height.toFloat(), includeSelectionOutline = true)
    }

    fun drawCompositeOverlay(
        canvas: Canvas,
        viewW: Float,
        viewH: Float,
        includeSelectionOutline: Boolean = false
    ) {
        if (viewW <= 0f || viewH <= 0f) return
        val hasText = arTextItems.isNotEmpty()
        if (currentLens == ArLensMode.NONE && !showDebugReticle && !enableComicOnomatopoeia && !enableComicSpeechBubble && !hasText) return

        var skipFaceLayer = false
        if (currentLens == ArLensMode.COMIC_FRAME) {
            if (isCartoonShaderActive) {
                drawComicFrameBorders(canvas, viewW, viewH)
            } else if (showDebugReticle) {
                val standbyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#FACC15")
                    strokeWidth = 3f
                    style = Paint.Style.STROKE
                    pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
                }
                canvas.drawRect(18f, 18f, viewW - 18f, viewH - 18f, standbyPaint)
                canvas.drawText("COMIC FRAME // STANDBY (ENABLE TOON/COMIC SHADER)", 28f, 52f, textHudPaint)
            }
            if (!showDebugReticle && !enableComicOnomatopoeia && !enableComicSpeechBubble) {
                skipFaceLayer = true
            }
        }

        if (!skipFaceLayer && (currentLens != ArLensMode.NONE || showDebugReticle || enableComicOnomatopoeia || enableComicSpeechBubble)) {
            drawFaceTrackedOverlays(canvas, viewW, viewH, syncGlb = !includeSelectionOutline)
        }

        if (hasText) {
            drawCustomArTextItems(canvas, viewW, viewH, includeSelectionOutline)
        }
    }

    fun renderExportOverlayBitmap(targetW: Int, targetH: Int): Bitmap? {
        if (!hasAnyVisibleOverlay() || targetW <= 0 || targetH <= 0) return null
        val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        drawCompositeOverlay(c, targetW.toFloat(), targetH.toFloat(), includeSelectionOutline = false)
        return bmp
    }

    private fun computeTextItemBounds(item: ArTextItem, viewW: Float, viewH: Float): RectF {
        val minDim = Math.max(240f, Math.min(viewW, viewH))
        val fontSize = (minDim * 0.056f * item.scale.coerceIn(0.55f, 2.4f)).coerceAtLeast(22f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        val label = item.text.trim().ifEmpty { "TEXT" }
        val textW = Math.max(fontSize * 1.8f, p.measureText(label))
        val padX = Math.max(18f, fontSize * 0.48f)
        val padY = Math.max(12f, fontSize * 0.34f)
        val halfW = (textW + padX * 2f) * 0.5f
        val halfH = (fontSize + padY * 2f) * 0.5f
        return RectF(-halfW, -halfH, halfW, halfH)
    }

    fun drawCustomArTextItems(
        canvas: Canvas,
        viewW: Float,
        viewH: Float,
        includeSelectionOutline: Boolean = false
    ) {
        if (arTextItems.isEmpty() || viewW <= 0f || viewH <= 0f) return
        val minDim = Math.max(240f, Math.min(viewW, viewH))
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val boxBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        for (item in arTextItems) {
            val label = item.text.trim()
            if (label.isEmpty()) continue
            val fontSize = (minDim * 0.056f * item.scale.coerceIn(0.55f, 2.4f)).coerceAtLeast(22f)
            textPaint.textSize = fontSize
            strokePaint.textSize = fontSize

            val rect = computeTextItemBounds(item, viewW, viewH)
            val cx = item.normX.coerceIn(0.06f, 0.94f) * viewW
            val cy = item.normY.coerceIn(0.06f, 0.94f) * viewH
            val parsedColor = try { Color.parseColor(item.colorHex) } catch (_: Exception) { Color.parseColor("#FACC15") }
            val cornerR = Math.min(24f, Math.max(10f, fontSize * 0.34f))
            val textBaselineY = -(textPaint.descent() + textPaint.ascent()) * 0.5f

            canvas.save()
            canvas.translate(cx, cy)
            if (item.rotationDeg != 0f) {
                canvas.rotate(item.rotationDeg)
            }

            when (item.style) {
                ArTextStyle.COMIC -> {
                    val shadowRect = RectF(rect.left + 6f, rect.top + 7f, rect.right + 6f, rect.bottom + 7f)
                    boxPaint.color = Color.parseColor("#06080D")
                    canvas.drawRoundRect(shadowRect, cornerR * 0.6f, cornerR * 0.6f, boxPaint)
                    boxPaint.color = parsedColor
                    canvas.drawRoundRect(rect, cornerR * 0.6f, cornerR * 0.6f, boxPaint)
                    boxBorderPaint.color = Color.parseColor("#06080D")
                    boxBorderPaint.strokeWidth = Math.max(4f, fontSize * 0.11f)
                    canvas.drawRoundRect(rect, cornerR * 0.6f, cornerR * 0.6f, boxBorderPaint)
                    textPaint.clearShadowLayer()
                    textPaint.color = Color.parseColor("#06080D")
                    canvas.drawText(label, 0f, textBaselineY, textPaint)
                }
                ArTextStyle.PILL -> {
                    boxPaint.color = Color.argb(200, 6, 10, 20)
                    canvas.drawRoundRect(rect, rect.height() * 0.5f, rect.height() * 0.5f, boxPaint)
                    boxBorderPaint.color = parsedColor
                    boxBorderPaint.strokeWidth = Math.max(3f, fontSize * 0.08f)
                    canvas.drawRoundRect(rect, rect.height() * 0.5f, rect.height() * 0.5f, boxBorderPaint)
                    textPaint.clearShadowLayer()
                    textPaint.color = Color.WHITE
                    canvas.drawText(label, 0f, textBaselineY, textPaint)
                }
                ArTextStyle.OUTLINE -> {
                    strokePaint.color = Color.parseColor("#06080D")
                    strokePaint.strokeWidth = Math.max(6f, fontSize * 0.18f)
                    canvas.drawText(label, 0f, textBaselineY, strokePaint)
                    textPaint.clearShadowLayer()
                    textPaint.color = parsedColor
                    canvas.drawText(label, 0f, textBaselineY, textPaint)
                }
                ArTextStyle.NEON -> {
                    strokePaint.color = Color.argb(215, 4, 8, 16)
                    strokePaint.strokeWidth = Math.max(4f, fontSize * 0.10f)
                    canvas.drawText(label, 0f, textBaselineY, strokePaint)
                    textPaint.color = parsedColor
                    textPaint.setShadowLayer(Math.max(12f, fontSize * 0.45f), 0f, 0f, parsedColor)
                    canvas.drawText(label, 0f, textBaselineY, textPaint)
                    textPaint.clearShadowLayer()
                }
            }

            if (includeSelectionOutline && item.id == selectedTextId) {
                val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#22D3EE")
                    strokeWidth = 3f
                    style = Paint.Style.STROKE
                    pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
                }
                val guideRect = RectF(rect.left - 10f, rect.top - 10f, rect.right + 10f, rect.bottom + 10f)
                canvas.drawRect(guideRect, guidePaint)
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#22D3EE")
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(guideRect.left, guideRect.top, 7f, dotPaint)
                canvas.drawCircle(guideRect.right, guideRect.top, 7f, dotPaint)
                canvas.drawCircle(guideRect.left, guideRect.bottom, 7f, dotPaint)
                canvas.drawCircle(guideRect.right, guideRect.bottom, 7f, dotPaint)
            }

            canvas.restore()
        }
    }

    fun drawFaceTrackedOverlays(canvas: Canvas, viewW: Float, viewH: Float, syncGlb: Boolean = false) {
        if (viewW <= 0f || viewH <= 0f) return

        val now = System.currentTimeMillis()
        val hasReal = landmarks != null && landmarks!!.size >= 468
        val hasSimple = !hasReal && hasSimpleFace && (now - lastSimpleFaceTime < 1500L)

        // Match PreviewView scaleType="fillCenter" aspect ratio crop so landmarks align on tall displays (e.g. 20:9 Moto Edge)
        val inW = imageWidth.toFloat().coerceAtLeast(1f)
        val inH = imageHeight.toFloat().coerceAtLeast(1f)
        val cropScale = if (inW > 1f && inH > 1f) Math.max(viewW / inW, viewH / inH) else 1f
        val scaledImgW = if (inW > 1f && inH > 1f) inW * cropScale else viewW
        val scaledImgH = if (inW > 1f && inH > 1f) inH * cropScale else viewH
        val cropOffX = (scaledImgW - viewW) * 0.5f
        val cropOffY = (scaledImgH - viewH) * 0.5f

        fun projectNormX(normX: Float): Float {
            val mx = if (isFrontCamera) (1.0f - normX) else normX
            return mx * scaledImgW - cropOffX
        }
        fun projectNormY(normY: Float): Float {
            return normY * scaledImgH - cropOffY
        }

        // Gentle breathing motion animation tick when using portrait anchor fallback
        val floatOffset = if (!hasReal && !hasSimple) (Math.sin(now / 550.0) * (viewH * 0.005f)).toFloat() else 0f

        val rawCenterX = when {
            hasReal -> projectNormX(landmarks!![1].x())
            hasSimple -> {
                if (isFrontCamera) (1.0f - simpleFaceCenterX) * viewW else simpleFaceCenterX * viewW
            }
            else -> viewW * 0.5f
        }
        val rawCenterY = when {
            hasReal -> projectNormY(landmarks!![1].y())
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
                val base = projectNormX(landmarks!![idx].x())
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
                val base = projectNormY(landmarks!![idx].y())
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

        val hasAnyTrackedFace = hasReal || hasSimple
        val anyComicGraphicActive = enableComicOnomatopoeia || enableComicSpeechBubble
        if (anyComicGraphicActive) {
            if (hasAnyTrackedFace && !wasFaceDetectedForComic) {
                comicEntryStartMs = now
                post { onComicFaceAcquired?.invoke() }
            }
            wasFaceDetectedForComic = hasAnyTrackedFace
        } else {
            wasFaceDetectedForComic = false
        }

        val comicEntryAgeMs = if (anyComicGraphicActive && comicEntryStartMs > 0L) (now - comicEntryStartMs) else 1000L
        if (((currentLens != ArLensMode.NONE && currentLens != ArLensMode.COMIC_FRAME) || anyComicGraphicActive) &&
            (!hasReal || (anyComicGraphicActive && comicEntryAgeMs in 0L..700L))
        ) {
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
            ArLensMode.GLB_3D -> {
                drawGlb3dModel(canvas, viewW, viewH, cx, cy, ::getX, ::getY, now, syncGlb)
            }
            ArLensMode.COMIC_FRAME, ArLensMode.NONE -> {}
        }

        if (enableComicSpeechBubble) {
            val faceW = Math.abs(getX(454) - getX(234)).coerceAtLeast(viewW * 0.28f)
            val faceH = Math.abs(getY(152) - getY(10)).coerceAtLeast(viewH * 0.25f)
            val mouthY = (getY(1) + getY(152)) * 0.5f
            drawComicSpeechBubble(canvas, viewW, viewH, cx, getY(10), mouthY, faceW, faceH, now, comicEntryAgeMs)
        } else if (enableComicOnomatopoeia) {
            val faceW = Math.abs(getX(454) - getX(234)).coerceAtLeast(viewW * 0.28f)
            val faceH = Math.abs(getY(152) - getY(10)).coerceAtLeast(viewH * 0.25f)
            drawComicOnomatopoeiaLabels(canvas, viewW, viewH, cx, getY(10), faceW, faceH, now, comicEntryAgeMs)
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

    fun drawComicFrameBorders(
        canvas: Canvas,
        viewW: Float,
        viewH: Float
    ) {
        if (viewW <= 0f || viewH <= 0f) return
        val minDim = Math.min(viewW, viewH)
        val outerMargin = Math.max(8f, minDim * 0.018f)
        val borderWidth = Math.max(14f, minDim * 0.025f)
        val innerRuleWidth = Math.max(3f, minDim * 0.0045f)
        val cornerAccent = Math.max(24f, minDim * 0.048f)

        // 1. Newsprint cream-white outer gutter margin so the thick black comic panel ink pops crisply
        val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FAF7EE")
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.MITER
            strokeWidth = outerMargin * 2f + borderWidth * 0.45f
        }
        canvas.drawRect(
            outerMargin * 0.5f,
            outerMargin * 0.5f,
            viewW - outerMargin * 0.5f,
            viewH - outerMargin * 0.5f,
            gutterPaint
        )

        // 2. Primary thick, bold black comic-book panel border
        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#06080D")
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.MITER
            strokeWidth = borderWidth
        }
        val halfBorder = borderWidth * 0.5f
        canvas.drawRect(
            outerMargin + halfBorder,
            outerMargin + halfBorder,
            viewW - (outerMargin + halfBorder),
            viewH - (outerMargin + halfBorder),
            panelPaint
        )

        // 3. Crisp inner ink hairline rule separated by a clean gutter gap
        val innerInset = outerMargin + borderWidth + Math.max(6f, minDim * 0.01f)
        val innerRulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#06080D")
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.MITER
            strokeWidth = innerRuleWidth
        }
        canvas.drawRect(innerInset, innerInset, viewW - innerInset, viewH - innerInset, innerRulePaint)

        // 4. Inked comic panel corner gussets at all 4 inner corners
        val gussetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#06080D")
            style = Paint.Style.FILL
        }
        val rightEdge = viewW - innerInset
        val bottomEdge = viewH - innerInset
        fun drawGusset(cxCorner: Float, cyCorner: Float, dx: Float, dy: Float) {
            val gPath = Path().apply {
                moveTo(cxCorner, cyCorner)
                lineTo(cxCorner + dx * cornerAccent, cyCorner)
                lineTo(cxCorner + dx * (cornerAccent * 0.52f), cyCorner + dy * (cornerAccent * 0.52f))
                lineTo(cxCorner, cyCorner + dy * cornerAccent)
                close()
            }
            canvas.drawPath(gPath, gussetPaint)
        }
        drawGusset(innerInset, innerInset, 1f, 1f)
        drawGusset(rightEdge, innerInset, -1f, 1f)
        drawGusset(innerInset, bottomEdge, 1f, -1f)
        drawGusset(rightEdge, bottomEdge, -1f, -1f)
    }

    private fun drawComicOnomatopoeiaLabels(
        canvas: Canvas,
        viewW: Float,
        viewH: Float,
        cx: Float,
        foreheadY: Float,
        faceW: Float,
        faceH: Float,
        nowMs: Long,
        entryAgeMs: Long = 1000L
    ) {
        val animSec = nowMs / 1000.0
        val baseSize = (faceW * 0.19f).coerceIn(36f, 108f)
        val comicTypeface = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)

        data class CalloutSpec(
            val word: String,
            val x: Float,
            val y: Float,
            val tiltDeg: Float,
            val scaleMult: Float,
            val topColor: Int,
            val bottomColor: Int,
            val extrudeColor: Int,
            val burstColor: Int,
            val phase: Double,
            val entryDelayMs: Long
        )

        fun clampX(v: Float) = v.coerceIn(baseSize * 1.4f, viewW - baseSize * 1.4f)
        fun clampY(v: Float) = v.coerceIn(baseSize * 1.15f, viewH - baseSize * 0.9f)

        val items = listOf(
            CalloutSpec(
                word = "POW!",
                x = clampX(cx - faceW * 0.56f + (Math.sin(animSec * 2.1) * 8.0).toFloat()),
                y = clampY(foreheadY - faceH * 0.25f + (Math.sin(animSec * 2.7 + 0.4) * 14.0).toFloat()),
                tiltDeg = -14f + (Math.sin(animSec * 1.9) * 2.5).toFloat(),
                scaleMult = 1.0f,
                topColor = powPalette.topColor,
                bottomColor = powPalette.bottomColor,
                extrudeColor = powPalette.extrudeColor,
                burstColor = powPalette.burstColor,
                phase = 0.0,
                entryDelayMs = 0L
            ),
            CalloutSpec(
                word = "BAM!",
                x = clampX(cx + (Math.cos(animSec * 1.7) * 10.0).toFloat()),
                y = clampY(foreheadY - faceH * 0.48f + (Math.sin(animSec * 2.4 + 1.9) * 15.0).toFloat()),
                tiltDeg = 4f + (Math.cos(animSec * 2.0) * 2.0).toFloat(),
                scaleMult = 1.12f,
                topColor = bamPalette.topColor,
                bottomColor = bamPalette.bottomColor,
                extrudeColor = bamPalette.extrudeColor,
                burstColor = bamPalette.burstColor,
                phase = 1.8,
                entryDelayMs = 90L
            ),
            CalloutSpec(
                word = "ZAP!",
                x = clampX(cx + faceW * 0.56f + (Math.sin(animSec * 2.3 + 2.4) * 8.0).toFloat()),
                y = clampY(foreheadY - faceH * 0.21f + (Math.cos(animSec * 2.9 + 1.1) * 14.0).toFloat()),
                tiltDeg = 15f + (Math.sin(animSec * 2.1 + 1.4) * 2.5).toFloat(),
                scaleMult = 0.96f,
                topColor = zapPalette.topColor,
                bottomColor = zapPalette.bottomColor,
                extrudeColor = zapPalette.extrudeColor,
                burstColor = zapPalette.burstColor,
                phase = 3.1,
                entryDelayMs = 180L
            )
        )

        val burstPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val burstStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#06080D")
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#06080D")
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            textAlign = Paint.Align.CENTER
            typeface = comicTypeface
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            textAlign = Paint.Align.CENTER
            typeface = comicTypeface
        }

        for (item in items) {
            val popDurationMs = 460f
            val localElapsed = entryAgeMs - item.entryDelayMs
            if (localElapsed < 0L) continue
            val popScale: Float
            val tiltKick: Float
            if (localElapsed < popDurationMs.toLong()) {
                val u = localElapsed.toFloat() / popDurationMs
                popScale = when {
                    u < 0.42f -> {
                        val p = u / 0.42f
                        0.14f + 1.28f * (1f - Math.pow((1f - p).toDouble(), 3.0).toFloat())
                    }
                    u < 0.72f -> {
                        val p = (u - 0.42f) / 0.30f
                        val ease = if (p < 0.5f) 2f * p * p else 1f - Math.pow((-2f * p + 2f).toDouble(), 2.0).toFloat() / 2f
                        1.42f - 0.49f * ease
                    }
                    else -> {
                        val p = (u - 0.72f) / 0.28f
                        0.93f + 0.07f * (1f - (1f - p) * (1f - p))
                    }
                }
                tiltKick = (Math.sin(u * Math.PI) * 9.0).toFloat()
            } else {
                popScale = 1.0f
                tiltKick = 0f
            }

            val fSize = baseSize * item.scaleMult
            val pulse = (1.0 + Math.sin(animSec * 3.1 + item.phase) * 0.035).toFloat() * popScale

            canvas.save()
            canvas.translate(item.x, item.y)
            canvas.rotate(item.tiltDeg + tiltKick)
            canvas.scale(pulse, pulse)

            outlinePaint.textSize = fSize
            fillPaint.textSize = fSize
            val textW = Math.max(fSize * 1.7f, fillPaint.measureText(item.word))
            val rx = textW * 0.66f
            val ry = fSize * 0.72f
            val centerOffsetY = fSize * 0.34f

            // Jagged starburst backing
            val spikes = 12
            val burstPath = Path()
            for (i in 0 until spikes * 2) {
                val ang = i * Math.PI / spikes
                val radMult = if (i % 2 == 0) 1.18f else 0.78f
                val px = (Math.cos(ang) * rx * radMult).toFloat()
                val py = (Math.sin(ang) * ry * radMult).toFloat()
                if (i == 0) burstPath.moveTo(px, py) else burstPath.lineTo(px, py)
            }
            burstPath.close()
            val depthFactor = comicLabelDepth.coerceIn(0f, 2.5f)
            val shadowOffX = depthFactor * Math.max(5f, fSize * 0.14f)
            val shadowOffY = depthFactor * Math.max(7f, fSize * 0.19f)
            val shadowBlur = depthFactor * Math.max(8f, fSize * 0.25f)
            burstPaint.color = item.burstColor
            burstPaint.alpha = 225
            if (depthFactor > 0.02f) {
                val shadowAlpha = ((0.36f + depthFactor * 0.26f).coerceAtMost(0.9f) * 255).toInt()
                burstPaint.setShadowLayer(shadowBlur, shadowOffX, shadowOffY, Color.argb(shadowAlpha, 6, 8, 13))
            } else {
                burstPaint.clearShadowLayer()
            }
            canvas.drawPath(burstPath, burstPaint)
            burstPaint.clearShadowLayer()
            burstStrokePaint.strokeWidth = Math.max(4f, fSize * 0.09f)
            canvas.drawPath(burstPath, burstStrokePaint)

            // 3D stacked extrusion layers scaled with comicLabelDepth
            val extrudeMult = 0.25f + depthFactor * 0.85f
            val depthSteps = if (depthFactor <= 0.05f) 1 else 7
            val stepX = Math.max(0.8f, fSize * 0.032f * extrudeMult)
            val stepY = Math.max(1.0f, fSize * 0.04f * extrudeMult)
            for (d in depthSteps downTo 1) {
                val ox = d * stepX
                val oy = centerOffsetY + d * stepY
                outlinePaint.strokeWidth = Math.max(4.5f, fSize * 0.12f)
                if (d == depthSteps && depthFactor > 0.05f) {
                    val alpha = ((0.28f + depthFactor * 0.24f).coerceAtMost(0.85f) * 255).toInt()
                    outlinePaint.setShadowLayer(shadowBlur * 0.7f, shadowOffX * 0.65f, shadowOffY * 0.65f, Color.argb(alpha, 6, 8, 13))
                } else {
                    outlinePaint.clearShadowLayer()
                }
                fillPaint.shader = null
                fillPaint.color = if (d >= depthSteps - 1) Color.parseColor("#06080D") else item.extrudeColor
                canvas.drawText(item.word, ox, oy, outlinePaint)
                outlinePaint.clearShadowLayer()
                canvas.drawText(item.word, ox, oy, fillPaint)
            }

            // Front comic face with vertical gradient + thick ink border
            outlinePaint.strokeWidth = Math.max(5.5f, fSize * 0.14f)
            canvas.drawText(item.word, 0f, centerOffsetY, outlinePaint)
            fillPaint.shader = LinearGradient(
                0f, centerOffsetY - fSize * 0.7f,
                0f, centerOffsetY + fSize * 0.1f,
                item.topColor,
                item.bottomColor,
                Shader.TileMode.CLAMP
            )
            canvas.drawText(item.word, 0f, centerOffsetY, fillPaint)
            fillPaint.shader = null

            canvas.restore()
        }
    }

    private fun drawComicSpeechBubble(
        canvas: Canvas,
        viewW: Float,
        viewH: Float,
        cx: Float,
        foreheadY: Float,
        mouthY: Float,
        faceW: Float,
        faceH: Float,
        nowMs: Long,
        entryAgeMs: Long = 1000L
    ) {
        val animSec = nowMs / 1000.0
        val depthFactor = comicLabelDepth.coerceIn(0f, 2.5f)
        val popDurationMs = 460f
        val popScale = if (entryAgeMs in 0L until popDurationMs.toLong()) {
            val u = entryAgeMs.toFloat() / popDurationMs
            when {
                u < 0.42f -> {
                    val p = u / 0.42f
                    0.14f + 1.28f * (1f - Math.pow((1f - p).toDouble(), 3.0).toFloat())
                }
                u < 0.72f -> {
                    val p = (u - 0.42f) / 0.30f
                    val ease = if (p < 0.5f) 2f * p * p else 1f - Math.pow((-2f * p + 2f).toDouble(), 2.0).toFloat() / 2f
                    1.42f - 0.49f * ease
                }
                else -> {
                    val p = (u - 0.72f) / 0.28f
                    0.93f + 0.07f * (1f - (1f - p) * (1f - p))
                }
            }
        } else 1.0f

        val fontSize = (faceW * 0.115f).coerceIn(28f, 64f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#06080D")
            textSize = fontSize
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        val label = speechBubbleText.trim().ifEmpty { "HERO MODE!" }.uppercase()
        val textW = Math.max(fontSize * 3.0f, textPaint.measureText(label))
        val rx = textW * 0.5f + fontSize * 1.15f
        val ry = fontSize * 1.5f
        val bx = (cx + faceW * 0.46f + (Math.sin(animSec * 1.9) * 7.0).toFloat()).coerceIn(rx + 24f, viewW - rx - 24f)
        val by = (foreheadY - faceH * 0.20f + (Math.cos(animSec * 2.4) * 9.0).toFloat()).coerceIn(ry + 24f, viewH - ry - 72f)

        val targetX = if (speechBubbleStyle == ComicSpeechBubbleStyle.THOUGHT) cx + faceW * 0.27f else cx + faceW * 0.22f
        val targetY = if (speechBubbleStyle == ComicSpeechBubbleStyle.THOUGHT) foreheadY + faceH * 0.05f else mouthY - faceH * 0.06f

        val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#06080D")
            style = Paint.Style.FILL
        }
        if (depthFactor > 0.02f) {
            val alpha = ((0.34f + depthFactor * 0.25f).coerceAtMost(0.88f) * 255).toInt()
            inkPaint.setShadowLayer(depthFactor * 10f, depthFactor * 7f, depthFactor * 9f, Color.argb(alpha, 6, 8, 13))
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val inkPad = Math.max(5f, fontSize * 0.16f)

        canvas.save()
        canvas.translate(bx, by)
        canvas.scale(popScale, popScale)

        val relX = targetX - bx
        val relY = targetY - by

        when (speechBubbleStyle) {
            ComicSpeechBubbleStyle.THOUGHT -> {
                for (i in 0 until 3) {
                    val t = 0.48f + i * 0.18f
                    val dx = (-rx * 0.26f) + (relX + rx * 0.26f) * t
                    val dy = (ry * 0.72f) + (relY - ry * 0.72f) * t
                    val dr = fontSize * (0.34f - i * 0.08f)
                    canvas.drawCircle(dx, dy, dr + inkPad * 0.8f, inkPaint)
                    fillPaint.color = Color.parseColor("#F8FAFC")
                    canvas.drawCircle(dx, dy, dr, fillPaint)
                }
                val lobes = 14
                for (i in 0 until lobes) {
                    val ang = (i.toDouble() / lobes) * Math.PI * 2.0
                    val lx = (Math.cos(ang) * rx * 0.84f).toFloat()
                    val ly = (Math.sin(ang) * ry * 0.80f).toFloat()
                    val lr = Math.min(rx, ry) * (if (i % 2 == 0) 0.44f else 0.36f)
                    canvas.drawCircle(lx, ly, lr + inkPad, inkPaint)
                }
                fillPaint.shader = LinearGradient(0f, -ry, 0f, ry, Color.WHITE, Color.parseColor("#E2E8F0"), Shader.TileMode.CLAMP)
                canvas.drawOval(-rx * 0.82f, -ry * 0.76f, rx * 0.82f, ry * 0.76f, fillPaint)
                for (i in 0 until lobes) {
                    val ang = (i.toDouble() / lobes) * Math.PI * 2.0
                    val lx = (Math.cos(ang) * rx * 0.84f).toFloat()
                    val ly = (Math.sin(ang) * ry * 0.80f).toFloat()
                    val lr = Math.min(rx, ry) * (if (i % 2 == 0) 0.44f else 0.36f)
                    canvas.drawCircle(lx, ly, lr, fillPaint)
                }
                fillPaint.shader = null
            }
            ComicSpeechBubbleStyle.SHOUT -> {
                val spikes = 18
                fun buildBurst(pad: Float): Path {
                    val p = Path()
                    for (i in 0 until spikes * 2) {
                        val ang = i * Math.PI / spikes
                        val mult = if (i % 2 == 0) 1.24f else 0.83f
                        val px = (Math.cos(ang) * (rx + pad) * mult).toFloat()
                        val py = (Math.sin(ang) * (ry + pad) * mult).toFloat()
                        if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
                    }
                    p.close()
                    return p
                }
                val tailPath = Path().apply {
                    moveTo(-rx * 0.3f, ry * 0.65f)
                    lineTo(relX, relY)
                    lineTo(-rx * 0.05f, ry * 0.72f)
                    close()
                }
                canvas.drawPath(tailPath, inkPaint)
                canvas.drawPath(buildBurst(inkPad), inkPaint)
                fillPaint.shader = LinearGradient(0f, -ry, 0f, ry, Color.parseColor("#FEF08A"), Color.parseColor("#F97316"), Shader.TileMode.CLAMP)
                canvas.drawPath(tailPath, fillPaint)
                canvas.drawPath(buildBurst(0f), fillPaint)
                fillPaint.shader = null
            }
            ComicSpeechBubbleStyle.SPEECH -> {
                val tailPath = Path().apply {
                    moveTo(-rx * 0.38f, ry * 0.72f)
                    lineTo(relX, relY)
                    lineTo(-rx * 0.06f, ry * 0.72f)
                    close()
                }
                canvas.drawPath(tailPath, inkPaint)
                canvas.drawOval(-(rx + inkPad), -(ry + inkPad), rx + inkPad, ry + inkPad, inkPaint)
                fillPaint.shader = LinearGradient(0f, -ry, 0f, ry, Color.parseColor("#FFFDF4"), Color.parseColor("#F8F1DA"), Shader.TileMode.CLAMP)
                canvas.drawPath(tailPath, fillPaint)
                canvas.drawOval(-rx, -ry, rx, ry, fillPaint)
                fillPaint.shader = null
            }
        }

        canvas.drawText(label, 0f, fontSize * 0.34f, textPaint)
        canvas.restore()
    }

    private fun drawGlb3dModel(
        canvas: Canvas,
        viewW: Float,
        viewH: Float,
        cx: Float,
        cy: Float,
        getX: (Int) -> Float,
        getY: (Int) -> Float,
        nowMs: Long,
        syncGlb: Boolean
    ) {
        val engine = glbEngine ?: return
        if (!engine.hasModelLoaded()) return

        val faceW = Math.abs(getX(454) - getX(234)).coerceAtLeast(viewW * 0.24f)
        val faceH = Math.abs(getY(152) - getY(10)).coerceAtLeast(viewH * 0.24f)
        val eyeLeftX = getX(33)
        val eyeLeftY = getY(33)
        val eyeRightX = getX(263)
        val eyeRightY = getY(263)
        val headRollDeg = Math.toDegrees(Math.atan2((eyeRightY - eyeLeftY).toDouble(), (eyeRightX - eyeLeftX).toDouble())).toFloat()

        val anchorX = cx
        val anchorYBase = when (activeGlbAnchor.lowercase()) {
            "forehead" -> getY(10) - faceH * 0.12f
            "eyes" -> (eyeLeftY + eyeRightY) * 0.5f
            "nose_cheeks" -> getY(1)
            "mouth" -> (getY(1) + getY(152)) * 0.52f
            else -> cy
        }
        val anchorY = anchorYBase + activeGlbOffsetY * faceH * 0.55f
        val baseAnchorScale = when (activeGlbAnchor.lowercase()) {
            "forehead" -> 1.05f
            "eyes" -> 0.92f
            "nose_cheeks" -> 0.74f
            "mouth" -> 0.78f
            else -> 1.30f
        }

        val elapsedSec = ((nowMs - activeGlbStartMs).coerceAtLeast(0L)) / 1000f
        val idleYaw = if (activeGlbAutoSpin) elapsedSec * 38f else (Math.sin(elapsedSec * 1.4).toFloat() * 10f)

        val renderW = (viewW.toInt()).coerceIn(256, 720)
        val renderH = (viewH.toInt()).coerceIn(256, 960)
        val pose = GlbArModelEngine.GlbPose(
            normAnchorX = (anchorX / viewW).coerceIn(0.05f, 0.95f),
            normAnchorY = (anchorY / viewH).coerceIn(0.05f, 0.95f),
            normFaceWidth = (faceW / viewW).coerceIn(0.12f, 1.4f),
            modelScale = baseAnchorScale * activeGlbModelScale,
            yawDeg = activeGlbRotY + idleYaw,
            pitchDeg = activeGlbRotX,
            rollDeg = -(headRollDeg + calibrationRotationDeg)
        )

        val bmp: Bitmap? = if (syncGlb) {
            engine.renderSync(renderW, renderH, pose)
        } else {
            if (nowMs - lastGlbRenderRequestMs >= 30L) {
                lastGlbRenderRequestMs = nowMs
                engine.renderAsync(renderW, renderH, pose) { rendered ->
                    latestGlbBitmap = rendered
                    postInvalidateOnAnimation()
                }
            }
            latestGlbBitmap ?: engine.renderSync(renderW, renderH, pose)?.also { latestGlbBitmap = it }
        }

        if (bmp != null && !bmp.isRecycled) {
            canvas.drawBitmap(bmp, null, RectF(0f, 0f, viewW, viewH), null)
        }
        if (!syncGlb) {
            postInvalidateOnAnimation()
        }
    }
}

package com.example.cameraxapp.ar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
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
 * 3. GOLDEN_CROWN: Regal golden crown anchored to forehead with glittering gems
 * 4. SCIFI_HUD: Military-grade face-tracking targeting reticle with lock-on brackets
 * 5. COMIC_FRAME: Dynamic thick black comic-book panel borders with newsprint gutter & Ben-Day corners
 * 6. CUSTOM_STYLE: Dedicated face-tracked renderers for Cat Ears, Celestial Halo, Cyber Mask, Star Catchlight, Target Reticle, Neon Visor & Horns
 * 7. GLB_3D: Hardware-accelerated OpenGL ES 2.0 3D .glb model overlay
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
        CUSTOM_STYLE,
        GLB_3D
    }

    data class CustomLensStyleConfig(
        val style: String,
        val anchor: String,
        val primaryColorHex: String = "#EC4899",
        val accentColorHex: String = "#38BDF8",
        val telemetryText: String = ""
    )

    var activeCustomLensStyle: CustomLensStyleConfig? = null
        set(value) {
            field = value
            invalidate()
        }

    var currentLens: ArLensMode = ArLensMode.NONE
        set(value) {
            field = value
            invalidate()
        }

    // Phase 2: Hand AR (21-Point Hand Landmarks, Virtual Rings, Bracelets/Holo Watch & Hand-Attached 3D Props)
    enum class HandRingStyle { NONE, DIAMOND_SOLITAIRE, CYBER_BAND, ROYAL_GOLD, EMERALD_SIGNET }
    enum class HandBraceletStyle { NONE, GOLD_CUFF, CYBER_PULSE, BEADED_LUXE, HOLO_WATCH }
    enum class HandPropType { NONE, ENERGY_ORB, HOLO_SHIELD, MAGIC_FLAME, CYBER_GAUNTLET, SPARKLE_WAND, CUSTOM_GLB }
    enum class HandAnchorPoint { PALM, INDEX_TIP, WRIST, RING_FINGER }

    data class OverlayHandData(
        val landmarks: List<PointF>, // 21 normalized 0..1 landmarks
        val gesture: String,
        val handedness: String
    )

    var handArEnabled: Boolean = false
        set(value) { field = value; invalidate() }
    var handShowSkeleton: Boolean = false
        set(value) { field = value; invalidate() }
    var handRingStyle: HandRingStyle = HandRingStyle.NONE
        set(value) { field = value; invalidate() }
    var handRingFingerIndex: Int = 13 // 5=Index, 9=Middle, 13=Ring, 17=Pinky
        set(value) { field = value; invalidate() }
    var handRingColorHex: String = "#FACC15"
        set(value) { field = value; invalidate() }
    var handRingGemColorHex: String = "#38BDF8"
        set(value) { field = value; invalidate() }
    var handBraceletStyle: HandBraceletStyle = HandBraceletStyle.NONE
        set(value) { field = value; invalidate() }
    var handBraceletColorHex: String = "#F59E0B"
        set(value) { field = value; invalidate() }
    var handBraceletAccentHex: String = "#22D3EE"
        set(value) { field = value; invalidate() }
    var handPropType: HandPropType = HandPropType.NONE
        set(value) { field = value; invalidate() }
    var handPropAnchor: HandAnchorPoint = HandAnchorPoint.PALM
        set(value) { field = value; invalidate() }
    var handPropColorHex: String = "#00F0FF"
        set(value) { field = value; invalidate() }
    var handPropAccentHex: String = "#A855F7"
        set(value) { field = value; invalidate() }
    var handPinchScaleEnabled: Boolean = true
        set(value) { field = value; invalidate() }
    var handDualArcEnabled: Boolean = true
        set(value) { field = value; invalidate() }
    var handPalmRealismEnabled: Boolean = true
        set(value) { field = value; invalidate() }
    var handPresetId: String = "off"
    @Volatile
    private var trackedHands: List<OverlayHandData> = emptyList()
    @Volatile
    private var handInputWidth: Int = 1
    @Volatile
    private var handInputHeight: Int = 1
    @Volatile
    private var handIsFrontCamera: Boolean = true
    @Volatile
    private var lastHandUpdateMs: Long = 0L
    @Volatile
    private var handGestureDwellProgress: Float = 0f
    @Volatile
    private var handGestureDwellLabel: String = ""
    @Volatile
    private var lastHandGlbRenderMs: Long = 0L
    @Volatile
    private var latestHandGlbBitmap: Bitmap? = null

    // Pre-allocated zero-GC drawing pools for 60 FPS Hand AR rendering
    private val handBonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val handNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val handPillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(210, 2, 6, 23)
    }
    private val handPillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0F2FE")
        textSize = 24f
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    private val handFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val handStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val handShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(105, 2, 6, 23)
    }
    private val handTempRect = RectF()
    private val handTempPath = Path()
    private val handPointPool: Array<Array<PointF>> = Array(2) { Array(21) { PointF() } }
    private val handPalmPool: Array<PointF> = Array(2) { PointF() }
    private val handBoneLinks = arrayOf(
        intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 4),
        intArrayOf(0, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 8),
        intArrayOf(5, 9), intArrayOf(9, 10), intArrayOf(10, 11), intArrayOf(11, 12),
        intArrayOf(9, 13), intArrayOf(13, 14), intArrayOf(14, 15), intArrayOf(15, 16),
        intArrayOf(13, 17), intArrayOf(17, 18), intArrayOf(18, 19), intArrayOf(19, 20),
        intArrayOf(0, 17)
    )

    // Phase 3: Body AR (33-Point Full-Body Pose, Virtual Wings/Cape/Armor/3D GLB, Kinetic Aura FX & Segmentation Backdrops)
    enum class BodyAccessoryStyle { NONE, CYBER_WINGS, ANGEL_WINGS, NEON_CAPE, SHOULDER_PAULDRON, HOLO_BACKPACK, CUSTOM_GLB }
    enum class BodyEffectType { NONE, NEON_AURA, ELECTRIC_TRAILS, FIRE_AURA, CYBER_GRID_SUIT, STARDUST_HALO }
    enum class BodySegmentationMode { NONE, NEON_OUTLINE, STUDIO_BLUR, CYBER_GRID_BG, COSMIC_VOID, CHROMA_GREEN, SNAP_WIPEOUT }
    enum class SnapWipeoutPhase { ARMED, DISINTEGRATING, VANISHED }

    private class SnapDustParticle(
        var originX: Float,
        var originY: Float,
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var size: Float,
        var rotation: Float,
        var rotSpeed: Float,
        var triggerAt: Float,
        var seed: Float,
        var r: Int,
        var g: Int,
        var b: Int,
        var kind: Int, // 0 = fine ash mist, 1 = irregular flake, 2 = subtle ember
        var life: Float,
        var detached: Boolean
    )

    @Volatile
    var snapPhase: SnapWipeoutPhase = SnapWipeoutPhase.ARMED
        private set
    private var snapPhaseStartMs: Long = 0L
    private var snapLastFrameMs: Long = 0L
    private var snapShockwaveStartMs: Long = 0L
    private var snapOriginNormX: Float = 0.70f
    private var snapOriginNormY: Float = 0.42f
    private var snapWindDir: Float = 1f // +1f = drifting right, -1f = drifting left
    private var snapCleanPlateBmp: Bitmap? = null
    private var snapAutoInpaintBmp: Bitmap? = null
    private var snapFrozenSegMask: Bitmap? = null
    private var snapFrozenFrontCamera: Boolean = true
    private var snapFrozenOffsetX: Float = 0f
    private var snapFrozenOffsetY: Float = 0f
    private var snapFrozenScaledW: Float = 0f
    private var snapFrozenScaledH: Float = 0f
    private val snapFrozenBodyPath = Path()
    private val snapDustParticles = ArrayList<SnapDustParticle>(950)
    private val snapBgDrawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val snapMaskInPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val snapFeatherSilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        maskFilter = BlurMaskFilter(26f, BlurMaskFilter.Blur.NORMAL)
    }
    private val snapFeatherGradPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val snapPocketAddPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
    }
    private val snapPocketCutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val snapParticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val snapEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val snapFlakePath = Path()

    // 6-Stone Infinity Gauntlet AR Hand Overlay (worn on foreground Near-Camera hand during Snap & Wipeout)
    @Volatile
    var snapGauntletEnabled: Boolean = true
        set(value) { field = value; postInvalidateOnAnimation() }
    @Volatile
    var snapDustDensity: Float = 1.35f
    @Volatile
    var snapWindSpeed: Float = 1.3f
    @Volatile
    var snapMinNearHandScale: Float = 0.15f
    private val gauntletShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(88, 16, 8, 2)
        maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
    }
    private val gauntletFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val gauntletStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val gauntletPath = Path()
    private val gauntletFacetPath = Path()
    private val gauntletSmoothPts = Array(21) { PointF() }
    private var gauntletHasSmoothPts: Boolean = false
    private var gauntletLastTrackMs: Long = 0L
    private val gauntletFingerChains = arrayOf(
        intArrayOf(1, 2, 3, 4),
        intArrayOf(5, 6, 7, 8),
        intArrayOf(9, 10, 11, 12),
        intArrayOf(13, 14, 15, 16),
        intArrayOf(17, 18, 19, 20)
    )

    var bodyArEnabled: Boolean = false
        set(value) { field = value; invalidate() }
    var bodyShowSkeleton: Boolean = false
        set(value) { field = value; invalidate() }
    var bodyAccessoryStyle: BodyAccessoryStyle = BodyAccessoryStyle.NONE
        set(value) { field = value; invalidate() }
    var bodyEffectType: BodyEffectType = BodyEffectType.NONE
        set(value) { field = value; invalidate() }
    var bodySegmentationMode: BodySegmentationMode = BodySegmentationMode.NONE
        set(value) { field = value; invalidate() }
    var bodyAccessoryPrimaryHex: String = "#00F0FF"
        set(value) { field = value; invalidate() }
    var bodyAccessoryAccentHex: String = "#EC4899"
        set(value) { field = value; invalidate() }
    var bodyEffectPrimaryHex: String = "#38BDF8"
        set(value) { field = value; invalidate() }
    var bodyEffectAccentHex: String = "#A855F7"
        set(value) { field = value; invalidate() }
    var bodySegColorHex: String = "#00F0FF"
        set(value) { field = value; invalidate() }
    var bodyArmRibbonsEnabled: Boolean = true
        set(value) { field = value; invalidate() }
    var bodySegRimLightEnabled: Boolean = true
        set(value) { field = value; invalidate() }
    var bodyPresetId: String = "off"

    @Volatile
    private var trackedBodyLandmarks: List<PointF> = emptyList()
    @Volatile
    private var trackedBodyStance: String = "STANDING"
    @Volatile
    private var trackedBodySegMask: Bitmap? = null
    @Volatile
    private var bodyInputWidth: Int = 1
    @Volatile
    private var bodyInputHeight: Int = 1
    @Volatile
    private var bodyIsFrontCamera: Boolean = true
    @Volatile
    private var lastBodyUpdateMs: Long = 0L
    @Volatile
    private var bodyPoseDwellProgress: Float = 0f
    @Volatile
    private var bodyPoseDwellLabel: String = ""
    @Volatile
    private var lastBodyGlbRenderMs: Long = 0L
    @Volatile
    private var latestBodyGlbBitmap: Bitmap? = null

    // Zero-GC drawing pools & spring physics state for 60 FPS Phase 3 Body AR rendering
    private val bodyPointPool: Array<PointF> = Array(33) { PointF() }
    private val bodyLeftWristTrail: Array<PointF> = Array(12) { PointF() }
    private val bodyRightWristTrail: Array<PointF> = Array(12) { PointF() }
    private var bodyTrailHead: Int = 0
    private var bodyTrailCount: Int = 0
    private var lastBodyTrailSampleMs: Long = 0L
    private var smoothedBodySpread: Float = 1.0f
    private var lastBodyStanceForWave: String = "STANDING"
    private var bodyShockwaveStartMs: Long = 0L
    private val bodyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bodyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bodyMaskCutoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val bodyTempPath = Path()
    private val bodyTempRect = RectF()
    private val bodySkeletonLinks = arrayOf(
        intArrayOf(11, 12), intArrayOf(11, 23), intArrayOf(12, 24), intArrayOf(23, 24),
        intArrayOf(11, 13), intArrayOf(13, 15), intArrayOf(12, 14), intArrayOf(14, 16),
        intArrayOf(23, 25), intArrayOf(25, 27), intArrayOf(27, 31),
        intArrayOf(24, 26), intArrayOf(26, 28), intArrayOf(28, 32),
        intArrayOf(0, 11), intArrayOf(0, 12)
    )

    fun updateBodyPoseResults(
        landmarks: List<PointF>,
        stance: String,
        segMask: Bitmap?,
        imgW: Int,
        imgH: Int,
        isFront: Boolean
    ) {
        val nowMs = System.currentTimeMillis()
        val prev = trackedBodyLandmarks
        val canSmooth = prev.size >= 33 && landmarks.size >= 33 && (nowMs - lastBodyUpdateMs) < 480L
        val alpha = 0.48f

        val smoothed = if (landmarks.size < 33) {
            emptyList()
        } else if (canSmooth) {
            List(33) { i ->
                val p = prev[i]
                val c = landmarks[i]
                PointF(
                    p.x * (1f - alpha) + c.x * alpha,
                    p.y * (1f - alpha) + c.y * alpha
                )
            }
        } else {
            landmarks
        }

        trackedBodyLandmarks = smoothed
        trackedBodyStance = stance
        if (segMask != null) {
            trackedBodySegMask = segMask
        } else if (smoothed.isEmpty()) {
            trackedBodySegMask = null
        }
        bodyInputWidth = imgW.coerceAtLeast(1)
        bodyInputHeight = imgH.coerceAtLeast(1)
        bodyIsFrontCamera = isFront
        if (smoothed.isNotEmpty()) {
            lastBodyUpdateMs = nowMs
        }
        postInvalidateOnAnimation()
    }

    fun updateBodyPoseDwell(progress: Float, label: String) {
        bodyPoseDwellProgress = progress.coerceIn(0f, 1f)
        bodyPoseDwellLabel = label
        postInvalidateOnAnimation()
    }

    fun clearBodyPose() {
        trackedBodyLandmarks = emptyList()
        trackedBodyStance = "STANDING"
        trackedBodySegMask = null
        bodyPoseDwellProgress = 0f
        bodyPoseDwellLabel = ""
        bodyTrailCount = 0
        bodyTrailHead = 0
        smoothedBodySpread = 1.0f
        lastBodyUpdateMs = 0L
        postInvalidate()
    }

    fun applyBodyArPreset(presetId: String) {
        bodyPresetId = presetId
        when (presetId) {
            "off" -> {
                bodyArEnabled = false
                bodyShowSkeleton = false
                bodyAccessoryStyle = BodyAccessoryStyle.NONE
                bodyEffectType = BodyEffectType.NONE
                bodySegmentationMode = BodySegmentationMode.NONE
            }
            "cyber_angel" -> {
                bodyArEnabled = true
                bodyShowSkeleton = false
                bodyAccessoryStyle = BodyAccessoryStyle.CYBER_WINGS
                bodyAccessoryPrimaryHex = "#00F0FF"
                bodyAccessoryAccentHex = "#EC4899"
                bodyEffectType = BodyEffectType.NEON_AURA
                bodyEffectPrimaryHex = "#38BDF8"
                bodyEffectAccentHex = "#A855F7"
                bodySegmentationMode = BodySegmentationMode.NEON_OUTLINE
                bodySegColorHex = "#00F0FF"
            }
            "hero_cape" -> {
                bodyArEnabled = true
                bodyShowSkeleton = false
                bodyAccessoryStyle = BodyAccessoryStyle.NEON_CAPE
                bodyAccessoryPrimaryHex = "#EF4444"
                bodyAccessoryAccentHex = "#FACC15"
                bodyEffectType = BodyEffectType.FIRE_AURA
                bodyEffectPrimaryHex = "#F97316"
                bodyEffectAccentHex = "#FDE047"
                bodySegmentationMode = BodySegmentationMode.STUDIO_BLUR
                bodySegColorHex = "#F97316"
            }
            "mecha_armor" -> {
                bodyArEnabled = true
                bodyShowSkeleton = true
                bodyAccessoryStyle = BodyAccessoryStyle.SHOULDER_PAULDRON
                bodyAccessoryPrimaryHex = "#38BDF8"
                bodyAccessoryAccentHex = "#F59E0B"
                bodyEffectType = BodyEffectType.CYBER_GRID_SUIT
                bodyEffectPrimaryHex = "#22D3EE"
                bodyEffectAccentHex = "#0EA5E9"
                bodySegmentationMode = BodySegmentationMode.CYBER_GRID_BG
                bodySegColorHex = "#38BDF8"
            }
            "chroma_studio" -> {
                bodyArEnabled = true
                bodyShowSkeleton = false
                bodyAccessoryStyle = BodyAccessoryStyle.NONE
                bodyEffectType = BodyEffectType.NONE
                bodySegmentationMode = BodySegmentationMode.CHROMA_GREEN
                bodySegColorHex = "#22C55E"
            }
            "snap_wipeout" -> {
                bodyArEnabled = true
                bodyShowSkeleton = false
                bodyAccessoryStyle = BodyAccessoryStyle.NONE
                bodyEffectType = BodyEffectType.NONE
                bodySegmentationMode = BodySegmentationMode.SNAP_WIPEOUT
                bodySegColorHex = "#F59E0B"
                resetSnapWipeout()
            }
            else -> {
                bodyArEnabled = true
                bodyShowSkeleton = false
                bodyAccessoryStyle = BodyAccessoryStyle.ANGEL_WINGS
                bodyAccessoryPrimaryHex = "#FDE68A"
                bodyAccessoryAccentHex = "#E0F2FE"
                bodyEffectType = BodyEffectType.STARDUST_HALO
                bodyEffectPrimaryHex = "#FDE047"
                bodyEffectAccentHex = "#C084FC"
                bodySegmentationMode = BodySegmentationMode.COSMIC_VOID
                bodySegColorHex = "#A855F7"
            }
        }
        onOverlayDirty?.invoke()
        invalidate()
    }

    fun isSnapWipeoutArmed(): Boolean = bodyArEnabled && bodySegmentationMode == BodySegmentationMode.SNAP_WIPEOUT

    fun isSnapVanishedOrDissolving(): Boolean =
        snapPhase == SnapWipeoutPhase.DISINTEGRATING || snapPhase == SnapWipeoutPhase.VANISHED

    fun hasSnapCleanPlate(): Boolean = snapCleanPlateBmp != null && !(snapCleanPlateBmp?.isRecycled ?: true)

    fun setSnapCleanPlate(bmp: Bitmap?) {
        try {
            val prev = snapCleanPlateBmp
            if (bmp == null || bmp.isRecycled) {
                snapCleanPlateBmp = null
            } else {
                // Downscale to crisp 540px max side for fast 60fps canvas blitting & low RAM
                val maxSide = Math.max(bmp.width, bmp.height).coerceAtLeast(1)
                val ratio = if (maxSide > 540) 540f / maxSide.toFloat() else 1f
                val tw = (bmp.width * ratio).toInt().coerceAtLeast(64)
                val th = (bmp.height * ratio).toInt().coerceAtLeast(64)
                snapCleanPlateBmp = Bitmap.createScaledBitmap(bmp, tw, th, true)
            }
            if (prev != null && prev != snapCleanPlateBmp && !prev.isRecycled) {
                prev.recycle()
            }
        } catch (_: Throwable) {}
        postInvalidateOnAnimation()
    }

    fun toggleSnapWindDirection(): Float {
        snapWindDir = if (snapWindDir > 0f) -1f else 1f
        return snapWindDir
    }

    fun resetSnapWipeout() {
        snapPhase = SnapWipeoutPhase.ARMED
        snapPhaseStartMs = 0L
        snapShockwaveStartMs = 0L
        snapDustParticles.clear()
        postInvalidateOnAnimation()
    }

    fun triggerSnapWipeout(originNormX: Float, originNormY: Float, liveFrameBmp: Bitmap?): Boolean {
        // Strictly one-time wipeout: ignore repeated finger snaps once disintegrating or vanished (no reverse!)
        if (snapPhase != SnapWipeoutPhase.ARMED) return false

        val nowMs = System.currentTimeMillis()
        snapOriginNormX = originNormX.coerceIn(0.08f, 0.92f)
        snapOriginNormY = originNormY.coerceIn(0.08f, 0.92f)
        snapPhase = SnapWipeoutPhase.DISINTEGRATING
        snapPhaseStartMs = nowMs
        snapLastFrameMs = nowMs
        snapShockwaveStartMs = nowMs

        // Freeze current segmentation mask snapshot as fallback if subject disappears completely
        val curMask = trackedBodySegMask
        if (curMask != null && !curMask.isRecycled) {
            try {
                snapFrozenSegMask = curMask.copy(Bitmap.Config.ARGB_8888, false)
            } catch (_: Throwable) {
                snapFrozenSegMask = curMask
            }
        }
        snapFrozenFrontCamera = bodyIsFrontCamera

        // Compute detected person bounding box first (used for both seamless background inpainting & dust sampling)
        val rawLm = trackedBodyLandmarks
        var minX = 0.22f
        var maxX = 0.78f
        var minY = 0.14f
        var maxY = 0.92f
        if (rawLm.size >= 33) {
            val lShX = if (bodyIsFrontCamera) 1f - rawLm[11].x else rawLm[11].x
            val rShX = if (bodyIsFrontCamera) 1f - rawLm[12].x else rawLm[12].x
            val noseX = if (bodyIsFrontCamera) 1f - rawLm[0].x else rawLm[0].x
            minX = (Math.min(Math.min(lShX, rShX), noseX) - 0.16f).coerceIn(0.04f, 0.75f)
            maxX = (Math.max(Math.max(lShX, rShX), noseX) + 0.16f).coerceIn(minX + 0.18f, 0.96f)
            minY = (rawLm[0].y - 0.13f).coerceIn(0.03f, 0.60f)
            maxY = (Math.max(rawLm[23].y, rawLm[24].y) + 0.34f).coerceIn(minY + 0.25f, 0.98f)
        }
        val spanX = (maxX - minX).coerceAtLeast(0.16f)
        val spanY = (maxY - minY).coerceAtLeast(0.24f)
        val bodyCx = (minX + maxX) * 0.5f
        val bodyCy = (minY + maxY) * 0.48f

        // Build smooth high-resolution segmentation-aware background inpainting (eliminates giant pixelated mosaic blocks!)
        if (!hasSnapCleanPlate() && liveFrameBmp != null && !liveFrameBmp.isRecycled) {
            try {
                val inW = 180
                val inH = 240
                val srcSmall = Bitmap.createScaledBitmap(liveFrameBmp, inW, inH, true)
                val pixels = IntArray(inW * inH)
                srcSmall.getPixels(pixels, 0, inW, 0, 0, inW, inH)
                srcSmall.recycle()

                val maskPixels: IntArray? = if (curMask != null && !curMask.isRecycled) {
                    val mSmall = Bitmap.createScaledBitmap(curMask, inW, inH, true)
                    val mp = IntArray(inW * inH)
                    mSmall.getPixels(mp, 0, inW, 0, 0, inW, inH)
                    mSmall.recycle()
                    mp
                } else null

                val isFg = BooleanArray(inW * inH)
                val bx0 = (minX * inW).toInt().coerceIn(2, inW - 3)
                val bx1 = (maxX * inW).toInt().coerceIn(bx0 + 4, inW - 2)
                val by0 = (minY * inH).toInt().coerceIn(2, inH - 3)
                val by1 = (maxY * inH).toInt().coerceIn(by0 + 4, inH - 2)

                for (y in 0 until inH) {
                    for (x in 0 until inW) {
                        val idx = y * inW + x
                        if (maskPixels != null) {
                            val mx = if (bodyIsFrontCamera) (inW - 1 - x) else x
                            val alpha = Color.alpha(maskPixels[y * inW + mx])
                            isFg[idx] = alpha > 48
                        } else {
                            val nx = (x.toFloat() / inW - bodyCx) / (spanX * 0.52f)
                            val ny = (y.toFloat() / inH - bodyCy) / (spanY * 0.54f)
                            isFg[idx] = (nx * nx + ny * ny) <= 1.05f
                        }
                    }
                }
                // Dilate foreground mask by 6px so zero clothing/skin halo color bleeds into the clean wall fill
                val dilFg = BooleanArray(inW * inH)
                val pad = 6
                for (y in 0 until inH) {
                    for (x in 0 until inW) {
                        var hit = isFg[y * inW + x]
                        if (!hit) {
                            var dy = -pad
                            while (dy <= pad && !hit) {
                                val yy = (y + dy).coerceIn(0, inH - 1)
                                var dx = -pad
                                while (dx <= pad) {
                                    val xx = (x + dx).coerceIn(0, inW - 1)
                                    if (isFg[yy * inW + xx]) {
                                        hit = true
                                        break
                                    }
                                    dx += 2
                                }
                                dy += 2
                            }
                        }
                        dilFg[y * inW + x] = hit
                    }
                }

                // Estimate clean upper ambient wall color above the person
                var ambR = 0L; var ambG = 0L; var ambB = 0L; var ambCnt = 0L
                val topSampleH = (by0.coerceAtLeast(18)).coerceAtMost(inH / 3)
                for (y in 4 until topSampleH) {
                    for (x in 6 until (inW - 6) step 2) {
                        val idx = y * inW + x
                        if (!dilFg[idx]) {
                            val c = pixels[idx]
                            ambR += Color.red(c); ambG += Color.green(c); ambB += Color.blue(c); ambCnt++
                        }
                    }
                }
                val wallR = if (ambCnt > 0) (ambR / ambCnt).toInt() else 138
                val wallG = if (ambCnt > 0) (ambG / ambCnt).toInt() else 138
                val wallB = if (ambCnt > 0) (ambB / ambCnt).toInt() else 132

                // Row-by-row horizontal clean-background interpolation across foreground silhouette
                val outPixels = pixels.clone()
                for (y in 0 until inH) {
                    val rowOff = y * inW
                    var x = 0
                    while (x < inW) {
                        if (!dilFg[rowOff + x]) {
                            x++
                            continue
                        }
                        val runStart = x
                        while (x < inW && dilFg[rowOff + x]) x++
                        val runEnd = x - 1
                        val runLen = (runEnd - runStart + 1).coerceAtLeast(1)

                        val hasLeft = runStart > 2
                        val hasRight = runEnd < inW - 3
                        var lR = wallR; var lG = wallG; var lB = wallB
                        if (hasLeft) {
                            var sr = 0; var sg = 0; var sb = 0; var sc = 0
                            for (k in 1..4) {
                                val sx = (runStart - k).coerceAtLeast(0)
                                val c = pixels[rowOff + sx]
                                sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c); sc++
                            }
                            if (sc > 0) { lR = sr / sc; lG = sg / sc; lB = sb / sc }
                        }
                        var rR = wallR; var rG = wallG; var rB = wallB
                        if (hasRight) {
                            var sr = 0; var sg = 0; var sb = 0; var sc = 0
                            for (k in 1..4) {
                                val sx = (runEnd + k).coerceAtMost(inW - 1)
                                val c = pixels[rowOff + sx]
                                sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c); sc++
                            }
                            if (sc > 0) { rR = sr / sc; rG = sg / sc; rB = sb / sc }
                        }

                        for (fx in runStart..runEnd) {
                            val t = (fx - runStart).toFloat() / runLen.toFloat()
                            val smoothT = t * t * (3f - 2f * t)
                            val baseR: Float
                            val baseG: Float
                            val baseB: Float
                            when {
                                hasLeft && hasRight -> {
                                    baseR = lR * (1f - smoothT) + rR * smoothT
                                    baseG = lG * (1f - smoothT) + rG * smoothT
                                    baseB = lB * (1f - smoothT) + rB * smoothT
                                }
                                hasLeft -> {
                                    baseR = lR * 0.72f + wallR * 0.28f
                                    baseG = lG * 0.72f + wallG * 0.28f
                                    baseB = lB * 0.72f + wallB * 0.28f
                                }
                                hasRight -> {
                                    baseR = rR * 0.72f + wallR * 0.28f
                                    baseG = rG * 0.72f + wallG * 0.28f
                                    baseB = rB * 0.72f + wallB * 0.28f
                                }
                                else -> {
                                    baseR = wallR.toFloat(); baseG = wallG.toFloat(); baseB = wallB.toFloat()
                                }
                            }
                            val edgeDist = Math.min(fx - runStart, runEnd - fx).toFloat() / (runLen * 0.5f).coerceAtLeast(1f)
                            val wallMix = (edgeDist * 0.22f).coerceIn(0f, 0.22f)
                            val grain = (((fx * 37 + y * 91) % 7) - 3)
                            val fr = (baseR * (1f - wallMix) + wallR * wallMix + grain).toInt().coerceIn(0, 255)
                            val fg = (baseG * (1f - wallMix) + wallG * wallMix + grain).toInt().coerceIn(0, 255)
                            val fb = (baseB * (1f - wallMix) + wallB * wallMix + grain).toInt().coerceIn(0, 255)
                            outPixels[rowOff + fx] = Color.argb(255, fr, fg, fb)
                        }
                    }
                }

                // 2-pass separable smoothing over the inpainted interior for buttery seamless room-wall blending
                val tmpPixels = outPixels.clone()
                for (pass in 0 until 2) {
                    val srcArr = if (pass == 0) outPixels else tmpPixels
                    val dstArr = if (pass == 0) tmpPixels else outPixels
                    for (y in 2 until (inH - 2)) {
                        for (x in 2 until (inW - 2)) {
                            val idx = y * inW + x
                            if (!dilFg[idx]) {
                                dstArr[idx] = srcArr[idx]
                                continue
                            }
                            var sr = 0; var sg = 0; var sb = 0
                            for (k in -2..2) {
                                val c = if (pass == 0) srcArr[y * inW + x + k] else srcArr[(y + k) * inW + x]
                                sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c)
                            }
                            dstArr[idx] = Color.argb(255, sr / 5, sg / 5, sb / 5)
                        }
                    }
                }

                val smoothBg = Bitmap.createBitmap(inW, inH, Bitmap.Config.ARGB_8888)
                smoothBg.setPixels(outPixels, 0, inW, 0, 0, inW, inH)
                val prevAuto = snapAutoInpaintBmp
                snapAutoInpaintBmp = smoothBg
                if (prevAuto != null && !prevAuto.isRecycled) prevAuto.recycle()
            } catch (_: Throwable) {}
        }

        // Sample multi-scale volumetric dust & ash flakes strictly inside the subject's silhouette
        snapDustParticles.clear()
        val sampleBmp = if (liveFrameBmp != null && !liveFrameBmp.isRecycled) {
            try { Bitmap.createScaledBitmap(liveFrameBmp, 128, 170, true) } catch (_: Throwable) { null }
        } else null
        val maskSampleBmp = if (curMask != null && !curMask.isRecycled) {
            try { Bitmap.createScaledBitmap(curMask, 128, 170, true) } catch (_: Throwable) { null }
        } else null

        val count = (1250f * snapDustDensity.coerceIn(0.4f, 2.5f)).toInt().coerceIn(600, 2400)
        var attempts = 0
        while (snapDustParticles.size < count && attempts < count * 4) {
            attempts++
            var nx = minX + Math.random().toFloat() * spanX
            var ny = minY + Math.random().toFloat() * spanY
            if (maskSampleBmp != null) {
                val mx = ((if (bodyIsFrontCamera) (1f - nx) else nx) * (maskSampleBmp.width - 1)).toInt().coerceIn(0, maskSampleBmp.width - 1)
                val my = (ny * (maskSampleBmp.height - 1)).toInt().coerceIn(0, maskSampleBmp.height - 1)
                if (Color.alpha(maskSampleBmp.getPixel(mx, my)) < 70) continue
            } else {
                val dx = (nx - bodyCx) / (spanX * 0.49f)
                val dy = (ny - bodyCy) / (spanY * 0.53f)
                if (dx * dx + dy * dy > 1.04f) {
                    nx = bodyCx + (nx - bodyCx) * (0.58f + Math.random().toFloat() * 0.16f)
                    ny = bodyCy + (ny - bodyCy) * (0.72f + Math.random().toFloat() * 0.12f)
                }
            }

            var r = 132
            var g = 122
            var b = 112
            if (sampleBmp != null) {
                val px = (nx * (sampleBmp.width - 1)).toInt().coerceIn(0, sampleBmp.width - 1)
                val py = (ny * (sampleBmp.height - 1)).toInt().coerceIn(0, sampleBmp.height - 1)
                val c = sampleBmp.getPixel(px, py)
                r = Color.red(c)
                g = Color.green(c)
                b = Color.blue(c)
            }
            val roll = Math.random()
            val kind = when {
                roll < 0.08 -> 2 // warm glowing ember spark
                roll < 0.52 -> 0 // volumetric feathery ash smoke puff
                else -> 1        // tumbling jagged ash ribbon/flake
            }
            val windAxis = if (snapWindDir > 0f) (maxX - nx) / spanX else (nx - minX) / spanX
            val vertAxis = (ny - minY) / spanY
            val noise =
                ((Math.sin(nx * 23.1 + ny * 15.7) * 0.5 + 0.5).toFloat() * 0.16f) +
                ((Math.cos(nx * 49.3 - ny * 31.1) * 0.5 + 0.5).toFloat() * 0.10f) +
                Math.random().toFloat() * 0.18f
            val triggerAt = (windAxis * 0.54f + vertAxis * 0.14f + noise - 0.05f).coerceIn(0.0f, 0.92f)
            val speed = when (kind) {
                0 -> 0.11f + Math.random().toFloat() * 0.19f
                2 -> 0.14f + Math.random().toFloat() * 0.22f
                else -> 0.08f + Math.random().toFloat() * 0.16f
            }

            snapDustParticles.add(
                SnapDustParticle(
                    originX = nx,
                    originY = ny,
                    x = nx,
                    y = ny,
                    vx = snapWindDir * speed,
                    vy = -0.032f - Math.random().toFloat() * 0.082f,
                    size = when (kind) {
                        0 -> 6.5f + Math.random().toFloat() * 11.5f
                        2 -> 2.8f + Math.random().toFloat() * 3.6f
                        else -> 3.6f + Math.random().toFloat() * 7.8f
                    },
                    rotation = (Math.random() * 360.0).toFloat(),
                    rotSpeed = ((Math.random() - 0.5) * 210.0).toFloat(),
                    triggerAt = triggerAt,
                    seed = (Math.random() * 100.0).toFloat(),
                    r = if (kind == 2) 252 else ((r * 0.60f + 86f * 0.40f).toInt().coerceIn(32, 235)),
                    g = if (kind == 2) 168 else ((g * 0.60f + 78f * 0.40f).toInt().coerceIn(30, 225)),
                    b = if (kind == 2) 56 else ((b * 0.60f + 70f * 0.40f).toInt().coerceIn(28, 215)),
                    kind = kind,
                    life = 0f,
                    detached = false
                )
            )
        }
        if (sampleBmp != null && !sampleBmp.isRecycled) sampleBmp.recycle()
        if (maskSampleBmp != null && !maskSampleBmp.isRecycled) maskSampleBmp.recycle()
        postInvalidateOnAnimation()
        return true
    }

    // Phase 4: World AR (6-DoF Sensor-Fused Floor/Wall Planes, Tap-to-Place 3D Objects & AR Surface Ruler)
    enum class WorldObjectType { HOLO_CUBE, CYBER_PORTAL, FLOATING_PLANET, NEON_BEACON, AR_TROPHY, CUSTOM_GLB }
    enum class WorldSurfaceMode { AUTO, HORIZONTAL_FLOOR, VERTICAL_WALL }

    data class WorldAnchor(
        val id: String,
        val objectType: WorldObjectType,
        val anchorYawDeg: Float,
        val anchorPitchDeg: Float,
        val normX: Float,
        val normY: Float,
        val distanceM: Float,
        val scale: Float,
        val primaryHex: String,
        val accentHex: String,
        val isFloor: Boolean
    )

    var worldArEnabled: Boolean = false
        set(value) { field = value; invalidate() }
    var worldShowPlaneGrid: Boolean = true
        set(value) { field = value; invalidate() }
    var worldShowReticle: Boolean = true
        set(value) { field = value; invalidate() }
    var worldShowShadows: Boolean = true
        set(value) { field = value; invalidate() }
    var worldShowRuler: Boolean = true
        set(value) { field = value; invalidate() }
    var worldSurfaceMode: WorldSurfaceMode = WorldSurfaceMode.AUTO
        set(value) { field = value; invalidate() }
    var worldActiveObject: WorldObjectType = WorldObjectType.HOLO_CUBE
        set(value) { field = value; invalidate() }
    var worldObjectPrimaryHex: String = "#00E5FF"
        set(value) { field = value; invalidate() }
    var worldObjectAccentHex: String = "#FF2E93"
        set(value) { field = value; invalidate() }
    var worldGridColorHex: String = "#22D3EE"
        set(value) { field = value; invalidate() }
    var worldObjectScale: Float = 1.0f
        set(value) { field = value.coerceIn(0.5f, 2.2f); invalidate() }
    var worldPresetId: String = "off"

    private val worldAnchors: MutableList<WorldAnchor> = mutableListOf()

    @Volatile
    private var worldPitchDeg: Float = 24f
    @Volatile
    private var worldYawDeltaDeg: Float = 0f
    @Volatile
    private var worldRollDeg: Float = 0f
    @Volatile
    private var worldDetectedSurface: String = "HORIZONTAL_FLOOR"
    @Volatile
    private var lastWorldGlbRenderMs: Long = 0L
    @Volatile
    private var latestWorldGlbBitmap: Bitmap? = null

    // Zero-GC paints & reusable geometry pools for 60 FPS Phase 4 World AR rendering
    private val worldFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val worldStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val worldDashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val worldPath = Path()
    private val worldRect = RectF()
    private val worldAnchorScreenPts = Array(8) { PointF() }
    private val worldAnchorDepths = FloatArray(8)
    private val worldCubePts2d = Array(8) { PointF() }

    fun updateWorldOrientation(
        pitchDeg: Float,
        yawDeltaDeg: Float,
        rollDeg: Float,
        detectedSurface: String
    ) {
        worldPitchDeg = pitchDeg
        worldYawDeltaDeg = yawDeltaDeg
        worldRollDeg = rollDeg
        worldDetectedSurface = detectedSurface
        if (worldArEnabled) {
            postInvalidateOnAnimation()
        }
    }

    private fun resolveWorldIsFloor(): Boolean {
        return when (worldSurfaceMode) {
            WorldSurfaceMode.HORIZONTAL_FLOOR -> true
            WorldSurfaceMode.VERTICAL_WALL -> false
            WorldSurfaceMode.AUTO -> worldDetectedSurface == "HORIZONTAL_FLOOR"
        }
    }

    fun placeWorldAnchorAtScreenPoint(screenX: Float, screenY: Float): WorldAnchor? {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val nx = (screenX / w).coerceIn(0.08f, 0.92f)
        val ny = (screenY / h).coerceIn(0.20f, 0.92f)
        val isFloor = resolveWorldIsFloor()
        val distM = if (isFloor) {
            (3.6f - ((ny - 0.35f).coerceIn(0f, 0.6f) / 0.6f) * 2.7f).coerceIn(0.6f, 4.5f)
        } else {
            (1.6f + Math.abs(nx - 0.5f) * 0.9f).coerceIn(0.9f, 3.2f)
        }
        val anchor = WorldAnchor(
            id = "wa_${System.currentTimeMillis()}_${worldAnchors.size}",
            objectType = worldActiveObject,
            anchorYawDeg = worldYawDeltaDeg,
            anchorPitchDeg = worldPitchDeg,
            normX = nx,
            normY = ny,
            distanceM = distM,
            scale = worldObjectScale,
            primaryHex = worldObjectPrimaryHex,
            accentHex = worldObjectAccentHex,
            isFloor = isFloor
        )
        worldAnchors.add(anchor)
        if (worldAnchors.size > 8) {
            worldAnchors.removeAt(0)
        }
        worldPresetId = "custom"
        onOverlayDirty?.invoke()
        invalidate()
        return anchor
    }

    fun dropDefaultWorldAnchor(): WorldAnchor {
        val idx = worldAnchors.size
        val spreadX = (0.50f + ((idx % 3) - 1) * 0.16f).coerceIn(0.18f, 0.82f)
        val spreadY = (0.72f - (idx / 3) * 0.08f).coerceIn(0.45f, 0.84f)
        val isFloor = resolveWorldIsFloor()
        val anchor = WorldAnchor(
            id = "wa_${System.currentTimeMillis()}_$idx",
            objectType = worldActiveObject,
            anchorYawDeg = worldYawDeltaDeg,
            anchorPitchDeg = worldPitchDeg,
            normX = spreadX,
            normY = spreadY,
            distanceM = if (isFloor) 1.55f + idx * 0.22f else 1.85f,
            scale = worldObjectScale,
            primaryHex = worldObjectPrimaryHex,
            accentHex = worldObjectAccentHex,
            isFloor = isFloor
        )
        worldAnchors.add(anchor)
        if (worldAnchors.size > 8) worldAnchors.removeAt(0)
        onOverlayDirty?.invoke()
        invalidate()
        return anchor
    }

    fun clearWorldAnchors() {
        worldAnchors.clear()
        onOverlayDirty?.invoke()
        invalidate()
    }

    fun getWorldAnchorCount(): Int = worldAnchors.size

    fun cycleWorldActiveObject(): WorldObjectType {
        val values = WorldObjectType.values()
        worldActiveObject = values[(worldActiveObject.ordinal + 1) % values.size]
        onOverlayDirty?.invoke()
        invalidate()
        return worldActiveObject
    }

    fun applyWorldArPreset(presetId: String) {
        worldPresetId = presetId
        worldAnchors.clear()
        when (presetId) {
            "off" -> {
                worldArEnabled = false
            }
            "holo_showcase" -> {
                worldArEnabled = true
                worldSurfaceMode = WorldSurfaceMode.HORIZONTAL_FLOOR
                worldShowPlaneGrid = true
                worldShowReticle = true
                worldShowShadows = true
                worldShowRuler = false
                worldActiveObject = WorldObjectType.HOLO_CUBE
                worldObjectPrimaryHex = "#00E5FF"
                worldObjectAccentHex = "#FF2E93"
                worldGridColorHex = "#22D3EE"
                worldAnchors.add(
                    WorldAnchor("wa_cube", WorldObjectType.HOLO_CUBE, worldYawDeltaDeg, worldPitchDeg, 0.50f, 0.72f, 1.45f, 1.05f, "#00E5FF", "#FF2E93", true)
                )
            }
            "cyber_portal" -> {
                worldArEnabled = true
                worldSurfaceMode = WorldSurfaceMode.HORIZONTAL_FLOOR
                worldShowPlaneGrid = true
                worldShowReticle = true
                worldShowShadows = true
                worldShowRuler = false
                worldActiveObject = WorldObjectType.CYBER_PORTAL
                worldObjectPrimaryHex = "#A855F7"
                worldObjectAccentHex = "#22D3EE"
                worldGridColorHex = "#A855F7"
                worldAnchors.add(
                    WorldAnchor("wa_portal", WorldObjectType.CYBER_PORTAL, worldYawDeltaDeg, worldPitchDeg, 0.50f, 0.76f, 2.10f, 1.15f, "#A855F7", "#22D3EE", true)
                )
            }
            "ar_measure" -> {
                worldArEnabled = true
                worldSurfaceMode = WorldSurfaceMode.HORIZONTAL_FLOOR
                worldShowPlaneGrid = true
                worldShowReticle = true
                worldShowShadows = true
                worldShowRuler = true
                worldActiveObject = WorldObjectType.NEON_BEACON
                worldObjectPrimaryHex = "#34D399"
                worldObjectAccentHex = "#06B6D4"
                worldGridColorHex = "#34D399"
                worldAnchors.add(
                    WorldAnchor("wa_m1", WorldObjectType.NEON_BEACON, worldYawDeltaDeg, worldPitchDeg, 0.30f, 0.73f, 1.35f, 0.90f, "#34D399", "#06B6D4", true)
                )
                worldAnchors.add(
                    WorldAnchor("wa_m2", WorldObjectType.NEON_BEACON, worldYawDeltaDeg, worldPitchDeg, 0.70f, 0.73f, 1.35f, 0.90f, "#34D399", "#06B6D4", true)
                )
            }
            "glb_world_stage" -> {
                worldArEnabled = true
                worldSurfaceMode = WorldSurfaceMode.HORIZONTAL_FLOOR
                worldShowPlaneGrid = true
                worldShowReticle = true
                worldShowShadows = true
                worldShowRuler = false
                worldActiveObject = WorldObjectType.CUSTOM_GLB
                worldObjectPrimaryHex = "#00F0FF"
                worldObjectAccentHex = "#F59E0B"
                worldGridColorHex = "#38BDF8"
                worldAnchors.add(
                    WorldAnchor("wa_glb", WorldObjectType.CUSTOM_GLB, worldYawDeltaDeg, worldPitchDeg, 0.50f, 0.72f, 1.50f, 1.10f, "#00F0FF", "#F59E0B", true)
                )
            }
            else -> {
                worldArEnabled = true
                worldSurfaceMode = WorldSurfaceMode.HORIZONTAL_FLOOR
                worldShowPlaneGrid = true
                worldShowReticle = true
                worldShowShadows = true
                worldShowRuler = true
                worldActiveObject = WorldObjectType.FLOATING_PLANET
                worldObjectPrimaryHex = "#38BDF8"
                worldObjectAccentHex = "#F59E0B"
                worldGridColorHex = "#6366F1"
                worldAnchors.add(
                    WorldAnchor("wa_planet", WorldObjectType.FLOATING_PLANET, worldYawDeltaDeg, worldPitchDeg, 0.50f, 0.68f, 1.60f, 1.10f, "#38BDF8", "#F59E0B", true)
                )
            }
        }
        onOverlayDirty?.invoke()
        invalidate()
    }

    fun updateHandResults(
        hands: List<OverlayHandData>,
        imgW: Int,
        imgH: Int,
        isFront: Boolean
    ) {
        val nowMs = System.currentTimeMillis()
        val prevHands = trackedHands
        val canSmooth = prevHands.isNotEmpty() && (nowMs - lastHandUpdateMs) < 420L
        val alpha = 0.55f

        val smoothedHands = if (hands.isEmpty()) {
            emptyList()
        } else {
            hands.take(2).mapIndexed { slotIdx, hand ->
                val prev = if (canSmooth) {
                    prevHands.firstOrNull { it.handedness == hand.handedness && it.landmarks.size >= 21 }
                        ?: prevHands.getOrNull(slotIdx)?.takeIf { it.landmarks.size >= 21 }
                } else {
                    null
                }
                if (prev != null && hand.landmarks.size >= 21) {
                    val blended = List(21) { i ->
                        val p = prev.landmarks[i]
                        val c = hand.landmarks[i]
                        PointF(
                            p.x * (1f - alpha) + c.x * alpha,
                            p.y * (1f - alpha) + c.y * alpha
                        )
                    }
                    OverlayHandData(blended, hand.gesture, hand.handedness)
                } else {
                    hand
                }
            }
        }

        trackedHands = smoothedHands
        handInputWidth = imgW.coerceAtLeast(1)
        handInputHeight = imgH.coerceAtLeast(1)
        handIsFrontCamera = isFront
        if (smoothedHands.isNotEmpty()) {
            lastHandUpdateMs = nowMs
        }
        postInvalidateOnAnimation()
    }

    fun updateHandGestureDwell(progress: Float, label: String) {
        handGestureDwellProgress = progress.coerceIn(0f, 1f)
        handGestureDwellLabel = label
        postInvalidateOnAnimation()
    }

    fun clearHandLandmarks() {
        trackedHands = emptyList()
        handGestureDwellProgress = 0f
        handGestureDwellLabel = ""
        lastHandUpdateMs = 0L
        postInvalidate()
    }

    fun applyHandArPreset(presetId: String) {
        handPresetId = presetId
        when (presetId) {
            "off" -> {
                handArEnabled = false
                handShowSkeleton = false
                handRingStyle = HandRingStyle.NONE
                handBraceletStyle = HandBraceletStyle.NONE
                handPropType = HandPropType.NONE
            }
            "luxury_jewelry" -> {
                handArEnabled = true
                handShowSkeleton = false
                handRingStyle = HandRingStyle.DIAMOND_SOLITAIRE
                handRingFingerIndex = 13
                handRingColorHex = "#FDE047"
                handRingGemColorHex = "#E0F2FE"
                handBraceletStyle = HandBraceletStyle.GOLD_CUFF
                handBraceletColorHex = "#F59E0B"
                handBraceletAccentHex = "#FEF3C7"
                handPropType = HandPropType.NONE
            }
            "cyber_gauntlet" -> {
                handArEnabled = true
                handShowSkeleton = true
                handRingStyle = HandRingStyle.CYBER_BAND
                handRingFingerIndex = 5
                handRingColorHex = "#0EA5E9"
                handRingGemColorHex = "#00F0FF"
                handBraceletStyle = HandBraceletStyle.HOLO_WATCH
                handBraceletColorHex = "#0284C7"
                handBraceletAccentHex = "#00F0FF"
                handPropType = HandPropType.CYBER_GAUNTLET
                handPropAnchor = HandAnchorPoint.PALM
                handPropColorHex = "#00F0FF"
                handPropAccentHex = "#EC4899"
            }
            "arcane_orb" -> {
                handArEnabled = true
                handShowSkeleton = false
                handRingStyle = HandRingStyle.EMERALD_SIGNET
                handRingFingerIndex = 13
                handRingColorHex = "#EAB308"
                handRingGemColorHex = "#10B981"
                handBraceletStyle = HandBraceletStyle.CYBER_PULSE
                handBraceletColorHex = "#7C3AED"
                handBraceletAccentHex = "#22D3EE"
                handPropType = HandPropType.ENERGY_ORB
                handPropAnchor = HandAnchorPoint.PALM
                handPropColorHex = "#22D3EE"
                handPropAccentHex = "#A855F7"
            }
            "pyro_wand" -> {
                handArEnabled = true
                handShowSkeleton = false
                handRingStyle = HandRingStyle.ROYAL_GOLD
                handRingFingerIndex = 5
                handRingColorHex = "#F59E0B"
                handRingGemColorHex = "#F97316"
                handBraceletStyle = HandBraceletStyle.BEADED_LUXE
                handBraceletColorHex = "#D97706"
                handBraceletAccentHex = "#FDE047"
                handPropType = HandPropType.MAGIC_FLAME
                handPropAnchor = HandAnchorPoint.INDEX_TIP
                handPropColorHex = "#F97316"
                handPropAccentHex = "#FACC15"
            }
        }
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
    var handGlbScale: Float = 1.0f
    var handGlbOffsetY: Float = 0f
    var bodyGlbAnchor: String = "upper_back"
    var bodyGlbScale: Float = 1.0f
    var bodyGlbOffsetY: Float = 0f
    var worldGlbOffsetY: Float = 0f
    var snapGlbEnabled: Boolean = false
    var snapGlbAnchor: String = "back_hand"
    var snapGlbScale: Float = 1.0f
    var snapGlbOffsetY: Float = 0f
    @Volatile
    private var latestGlbBitmap: Bitmap? = null
    @Volatile
    private var latestSnapGlbBitmap: Bitmap? = null
    private var lastGlbRenderRequestMs: Long = 0L
    private var lastSnapGlbRenderMs: Long = 0L

    fun getLastGlbError(): String = glbEngine?.lastLoadError ?: ""

    fun testParseGlbFile(filePath: String): String? {
        val engine = glbEngine ?: GlbArModelEngine(context.applicationContext).also { glbEngine = it }
        return engine.validateFile(filePath)
    }

    fun loadGlbModelFromAsset(
        assetPath: String,
        anchor: String = "forehead",
        modelScale: Float = 1.0f,
        offsetY: Float = 0f,
        rotYDeg: Float = 0f,
        rotXDeg: Float = 0f,
        autoSpin: Boolean = true,
        activateFaceLens: Boolean = true
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
        latestHandGlbBitmap = null
        latestBodyGlbBitmap = null
        latestWorldGlbBitmap = null
        latestSnapGlbBitmap = null
        val ok = engine.loadFromAsset(assetPath)
        if (ok) {
            if (activateFaceLens) {
                currentLens = ArLensMode.GLB_3D
            }
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
        autoSpin: Boolean = true,
        activateFaceLens: Boolean = true
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
        latestHandGlbBitmap = null
        latestBodyGlbBitmap = null
        latestWorldGlbBitmap = null
        latestSnapGlbBitmap = null
        val ok = engine.loadFromFile(filePath)
        if (ok) {
            if (activateFaceLens) {
                currentLens = ArLensMode.GLB_3D
            }
            postInvalidateOnAnimation()
        }
        return ok
    }

    fun releaseGlbEngine() {
        glbEngine?.release()
        glbEngine = null
        latestGlbBitmap = null
        latestHandGlbBitmap = null
        latestBodyGlbBitmap = null
        latestWorldGlbBitmap = null
        latestSnapGlbBitmap = null
        for (i in exportOverlayBuffers.indices) {
            exportOverlayBuffers[i]?.let { if (!it.isRecycled) it.recycle() }
            exportOverlayBuffers[i] = null
        }
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

    // Phase 1: Advanced Face AR (Skin Smoothing, Makeup, Iris Lenses, UV Face Masks & 3D Occlusion)
    var faceBeautyEnabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                onOverlayDirty?.invoke()
                invalidate()
            }
        }
    var faceBeautyPresetId: String = "off"
    var skinSmoothing: Float = 0.35f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    var faceOcclusionEnabled: Boolean = true
        set(value) { field = value; invalidate() }
    var lipColorHex: String = "#DC264B"
    var lipOpacity: Float = 0f
    var lipGloss: Float = 0.25f
    var eyeShadowColorHex: String = "#8B5CF6"
    var eyeShadowOpacity: Float = 0f
    var eyelinerOpacity: Float = 0f
    var eyebrowColorHex: String = "#2B1B17"
    var eyebrowOpacity: Float = 0f
    var blushColorHex: String = "#F472B6"
    var blushOpacity: Float = 0f
    var irisColorHex: String = "#22D3EE"
    var irisOpacity: Float = 0f
    var faceMaskPattern: String = "none"
    var faceMaskOpacity: Float = 0.78f
    var faceMaskPrimaryHex: String = "#00F0FF"
    var faceMaskAccentHex: String = "#FACC15"
    var isComparingBeforeAfter: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    private var customMaskBitmap: Bitmap? = null

    fun setCustomFaceMaskBitmap(bitmap: Bitmap?) {
        customMaskBitmap?.let { if (!it.isRecycled && it != bitmap) it.recycle() }
        customMaskBitmap = bitmap
        if (bitmap != null) {
            faceBeautyEnabled = true
            faceBeautyPresetId = "custom"
            faceMaskPattern = "custom_texture"
            if (faceMaskOpacity < 0.45f) faceMaskOpacity = 0.85f
        }
        onOverlayDirty?.invoke()
        invalidate()
    }

    fun clearCustomFaceMask() {
        customMaskBitmap?.let { if (!it.isRecycled) it.recycle() }
        customMaskBitmap = null
        if (faceMaskPattern == "custom_texture") faceMaskPattern = "none"
        onOverlayDirty?.invoke()
        invalidate()
    }

    fun applyFaceBeautyPreset(presetId: String) {
        faceBeautyPresetId = presetId
        when (presetId) {
            "natural_glow" -> {
                faceBeautyEnabled = true
                skinSmoothing = 0.55f
                lipColorHex = "#C84B66"; lipOpacity = 0.36f; lipGloss = 0.40f
                eyeShadowColorHex = "#B4846C"; eyeShadowOpacity = 0.24f; eyelinerOpacity = 0.30f
                eyebrowColorHex = "#3B2820"; eyebrowOpacity = 0.28f
                blushColorHex = "#F48FB1"; blushOpacity = 0.30f
                irisColorHex = "#38BDF8"; irisOpacity = 0f
                faceMaskPattern = "none"; faceMaskOpacity = 0.75f
            }
            "red_carpet" -> {
                faceBeautyEnabled = true
                skinSmoothing = 0.65f
                lipColorHex = "#B91C1C"; lipOpacity = 0.72f; lipGloss = 0.62f
                eyeShadowColorHex = "#6D28D9"; eyeShadowOpacity = 0.48f; eyelinerOpacity = 0.78f
                eyebrowColorHex = "#1E1412"; eyebrowOpacity = 0.45f
                blushColorHex = "#FB7185"; blushOpacity = 0.38f
                irisColorHex = "#10B981"; irisOpacity = 0.32f
                faceMaskPattern = "porcelain_glam"; faceMaskOpacity = 0.35f
                faceMaskPrimaryHex = "#FDE68A"; faceMaskAccentHex = "#F472B6"
            }
            "cyber_neon" -> {
                faceBeautyEnabled = true
                skinSmoothing = 0.40f
                lipColorHex = "#7C3AED"; lipOpacity = 0.62f; lipGloss = 0.70f
                eyeShadowColorHex = "#06B6D4"; eyeShadowOpacity = 0.56f; eyelinerOpacity = 0.85f
                eyebrowColorHex = "#0EA5E9"; eyebrowOpacity = 0.42f
                blushColorHex = "#EC4899"; blushOpacity = 0.24f
                irisColorHex = "#00F0FF"; irisOpacity = 0.82f
                faceMaskPattern = "cyber_circuit"; faceMaskOpacity = 0.85f
                faceMaskPrimaryHex = "#00F0FF"; faceMaskAccentHex = "#EC4899"
            }
            "kitsune_spirit" -> {
                faceBeautyEnabled = true
                skinSmoothing = 0.50f
                lipColorHex = "#DC2626"; lipOpacity = 0.78f; lipGloss = 0.45f
                eyeShadowColorHex = "#EF4444"; eyeShadowOpacity = 0.60f; eyelinerOpacity = 0.92f
                eyebrowColorHex = "#991B1B"; eyebrowOpacity = 0.50f
                blushColorHex = "#F87171"; blushOpacity = 0.35f
                irisColorHex = "#F59E0B"; irisOpacity = 0.75f
                faceMaskPattern = "kitsune_mask"; faceMaskOpacity = 0.88f
                faceMaskPrimaryHex = "#EF4444"; faceMaskAccentHex = "#FEF3C7"
            }
            "golden_circuit" -> {
                faceBeautyEnabled = true
                skinSmoothing = 0.48f
                lipColorHex = "#B45309"; lipOpacity = 0.58f; lipGloss = 0.68f
                eyeShadowColorHex = "#D97706"; eyeShadowOpacity = 0.52f; eyelinerOpacity = 0.72f
                eyebrowColorHex = "#78350F"; eyebrowOpacity = 0.36f
                blushColorHex = "#F59E0B"; blushOpacity = 0.25f
                irisColorHex = "#34D399"; irisOpacity = 0.62f
                faceMaskPattern = "tribal_gold"; faceMaskOpacity = 0.85f
                faceMaskPrimaryHex = "#FACC15"; faceMaskAccentHex = "#F97316"
            }
            else -> {
                faceBeautyEnabled = false
                faceBeautyPresetId = "off"
                lipOpacity = 0f; eyeShadowOpacity = 0f; eyelinerOpacity = 0f
                eyebrowOpacity = 0f; blushOpacity = 0f; irisOpacity = 0f
                faceMaskPattern = "none"
            }
        }
        onOverlayDirty?.invoke()
        invalidate()
    }

    fun hasArTextItems(): Boolean = arTextItems.isNotEmpty()

    fun hasAnyVisibleOverlay(): Boolean =
        currentLens != ArLensMode.NONE ||
            faceBeautyEnabled ||
            handArEnabled ||
            bodyArEnabled ||
            worldArEnabled ||
            snapGauntletEnabled ||
            snapGlbEnabled ||
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

    fun isPointOnArText(rawX: Float, rawY: Float): Boolean {
        if (arTextItems.isEmpty() || width <= 0 || height <= 0 || visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return findHitArTextItem(rawX - loc[0], rawY - loc[1]) != null
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

    @Volatile
    private var landmarks: List<NormalizedLandmark>? = null
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var isFrontCamera: Boolean = true
    private var animTick: Long = 0
    private val exportOverlayBuffers = arrayOfNulls<Bitmap>(2)
    private var exportOverlayBufIdx = 0

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
    private var simpleFaceImgW: Int = 0
    private var simpleFaceImgH: Int = 0
    private var hasSimpleFace: Boolean = false
    private var lastSimpleFaceTime: Long = 0L

    fun updateSimpleFace(
        normEyeBridgeX: Float,
        normEyeBridgeY: Float,
        normEyeDist: Float,
        isFrontFacing: Boolean,
        inputWidth: Int = 0,
        inputHeight: Int = 0
    ) {
        val now = System.currentTimeMillis()
        val isRecent = hasSimpleFace && (now - lastSimpleFaceTime < 800L)
        if (isRecent) {
            simpleFaceCenterX = (simpleFaceCenterX * 0.55f) + (normEyeBridgeX * 0.45f)
            simpleFaceCenterY = (simpleFaceCenterY * 0.55f) + (normEyeBridgeY * 0.45f)
            simpleFaceEyeDist = (simpleFaceEyeDist * 0.60f) + (normEyeDist.coerceIn(0.04f, 0.55f) * 0.40f)
        } else {
            simpleFaceCenterX = normEyeBridgeX
            simpleFaceCenterY = normEyeBridgeY
            simpleFaceEyeDist = normEyeDist.coerceIn(0.04f, 0.55f)
        }
        if (inputWidth > 0 && inputHeight > 0) {
            simpleFaceImgW = inputWidth
            simpleFaceImgH = inputHeight
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
        clearHandLandmarks()
        clearBodyPose()
        latestGlbBitmap = null
        latestHandGlbBitmap = null
        latestBodyGlbBitmap = null
        latestWorldGlbBitmap = null
        latestSnapGlbBitmap = null
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
        if (currentLens == ArLensMode.NONE && !faceBeautyEnabled && !handArEnabled && !bodyArEnabled && !worldArEnabled && !snapGauntletEnabled && !snapGlbEnabled && !showDebugReticle && !enableComicOnomatopoeia && !enableComicSpeechBubble && !hasText) return

        // Phase 4: World AR Layer renders first so floor/wall planes, portals & placed 3D objects ground behind subjects
        if (worldArEnabled) {
            drawWorldArLayer(canvas, viewW, viewH, System.currentTimeMillis(), syncGlb = !includeSelectionOutline)
        }

        // Phase 3: Body AR Layer renders next so segmentation backdrops, wings & capes sit cleanly behind face & hand props
        if (bodyArEnabled) {
            drawBodyArLayer(canvas, viewW, viewH, System.currentTimeMillis(), syncGlb = !includeSelectionOutline)
        }

        var skipFaceLayer = false
        if (currentLens == ArLensMode.COMIC_FRAME) {
            // Always draw comic-book panel borders immediately when Comic Frame lens is selected
            drawComicFrameBorders(canvas, viewW, viewH)
            if (!faceBeautyEnabled && !showDebugReticle && !enableComicOnomatopoeia && !enableComicSpeechBubble) {
                skipFaceLayer = true
            }
        }

        if (!skipFaceLayer && (currentLens != ArLensMode.NONE || faceBeautyEnabled || showDebugReticle || enableComicOnomatopoeia || enableComicSpeechBubble)) {
            drawFaceTrackedOverlays(canvas, viewW, viewH, syncGlb = !includeSelectionOutline)
        }

        // Phase 2: Hand AR Layer (21-point skeleton, virtual rings, wrist bracelets/watch, dual-hand arc & hand-attached 3D props)
        if (handArEnabled) {
            drawHandArLayer(canvas, viewW, viewH, System.currentTimeMillis(), syncGlb = !includeSelectionOutline)
        }

        // Phase 2B: 6-Stone Infinity Gauntlet / Snap 3D GLB on detected hand(s) during Snap & Wipeout
        if ((isSnapWipeoutArmed() || snapGlbEnabled) && snapGauntletEnabled) {
            drawInfinityGauntletLayer(canvas, viewW, viewH, System.currentTimeMillis(), syncGlb = !includeSelectionOutline)
        }

        if (hasText) {
            drawCustomArTextItems(canvas, viewW, viewH, includeSelectionOutline)
        }
    }

    fun renderExportOverlayBitmap(targetW: Int, targetH: Int): Bitmap? {
        if (!hasAnyVisibleOverlay() || targetW <= 0 || targetH <= 0) return null
        val slot = exportOverlayBufIdx and 1
        exportOverlayBufIdx = (slot + 1) and 1
        var bmp = exportOverlayBuffers[slot]
        if (bmp == null || bmp.isRecycled || bmp.width != targetW || bmp.height != targetH) {
            bmp?.let { if (!it.isRecycled) it.recycle() }
            bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            exportOverlayBuffers[slot] = bmp
        } else {
            bmp.eraseColor(Color.TRANSPARENT)
        }
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
        // Snapshot volatile face landmarks once to prevent NullPointerException if analyzer clears them mid-frame
        val lmSnapshot = landmarks
        val hasReal = lmSnapshot != null && lmSnapshot.size >= 468
        val hasSimple = !hasReal && hasSimpleFace && (now - lastSimpleFaceTime < 1500L)

        // When no face is visible in frame (e.g. pointing rear camera at floor/desk), keep the viewfinder clean
        // instead of floating a static dummy crown/visor in mid-air; snap on immediately when a face enters frame.
        val isFaceAnchoredOverlay =
            (currentLens != ArLensMode.NONE && currentLens != ArLensMode.COMIC_FRAME) || faceBeautyEnabled
        if (!hasReal && !hasSimple && isFaceAnchoredOverlay) {
            if (!syncGlb) {
                val hintText = "Point camera at a face"
                val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#E2E8F0")
                    textSize = (viewW * 0.036f).coerceIn(26f, 40f)
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                val textW = txtPaint.measureText(hintText)
                val padX = (viewW * 0.048f).coerceIn(30f, 54f)
                val padY = (viewW * 0.022f).coerceIn(14f, 26f)
                val fm = txtPaint.fontMetrics
                val textH = fm.descent - fm.ascent
                val pillCx = viewW * 0.5f
                val pillCy = viewH * 0.46f
                val pillRect = RectF(
                    pillCx - textW * 0.5f - padX,
                    pillCy - textH * 0.5f - padY,
                    pillCx + textW * 0.5f + padX,
                    pillCy + textH * 0.5f + padY
                )
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(175, 10, 14, 24)
                    style = Paint.Style.FILL
                }
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(120, 34, 211, 238)
                    style = Paint.Style.STROKE
                    strokeWidth = 2.5f
                }
                canvas.drawRoundRect(pillRect, pillRect.height() * 0.5f, pillRect.height() * 0.5f, bgPaint)
                canvas.drawRoundRect(pillRect, pillRect.height() * 0.5f, pillRect.height() * 0.5f, borderPaint)
                canvas.drawText(hintText, pillCx, pillCy - (fm.ascent + fm.descent) * 0.5f, txtPaint)
            }
            return
        }

        // Match PreviewView scaleType="fillCenter" aspect ratio crop so BOTH MediaPipe and FaceDetector landmarks align on tall displays (e.g. 20:9 Moto Edge)
        val inW = when {
            hasReal -> imageWidth.toFloat().coerceAtLeast(1f)
            hasSimple && simpleFaceImgW > 1 -> simpleFaceImgW.toFloat()
            else -> imageWidth.toFloat().coerceAtLeast(1f)
        }
        val inH = when {
            hasReal -> imageHeight.toFloat().coerceAtLeast(1f)
            hasSimple && simpleFaceImgH > 1 -> simpleFaceImgH.toFloat()
            else -> imageHeight.toFloat().coerceAtLeast(1f)
        }
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

        val trackedEyeDistPx = if (hasSimple) {
            (simpleFaceEyeDist * scaledImgW).coerceIn(viewW * 0.04f, viewW * 0.48f)
        } else if (currentLens == ArLensMode.GLB_3D) {
            viewW * 0.16f
        } else {
            viewW * 0.20f
        }
        val rawCenterX = when {
            hasReal -> projectNormX(lmSnapshot!![1].x())
            hasSimple -> projectNormX(simpleFaceCenterX)
            else -> viewW * 0.5f
        }
        val rawCenterY = when {
            hasReal -> projectNormY(lmSnapshot!![1].y())
            hasSimple -> projectNormY(simpleFaceCenterY) + (trackedEyeDistPx * 0.35f)
            else -> viewH * 0.49f + floatOffset
        }
        val cx = rawCenterX + (calibrationOffsetX * viewW)
        val cy = rawCenterY + (calibrationOffsetY * viewH)

        val needsRestore = calibrationRotationDeg != 0f
        if (needsRestore) {
            canvas.save()
            canvas.rotate(calibrationRotationDeg, cx, cy)
        }

        fun getX(idx: Int): Float {
            if (hasReal && lmSnapshot != null && idx in lmSnapshot.indices) {
                val base = projectNormX(lmSnapshot[idx].x())
                val dist = base - rawCenterX
                val isEye = idx == 33 || idx == 263 || idx == 133 || idx == 362
                val spacing = if (isEye) eyeSpacingFactor else 1.0f
                return cx + (dist * calibrationScale * spacing)
            } else {
                val refScale = if (hasSimple) trackedEyeDistPx else if (currentLens == ArLensMode.GLB_3D) viewW * 0.16f else viewW * 0.20f
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
            if (hasReal && lmSnapshot != null && idx in lmSnapshot.indices) {
                val base = projectNormY(lmSnapshot[idx].y())
                val dist = base - rawCenterY
                var extraY = 0f
                if (idx == 10 || idx == 338 || idx == 297) extraY = foreheadOffset * viewH
                if (idx == 152 || idx == 148 || idx == 176) extraY = chinOffset * viewH
                return cy + (dist * calibrationScale) + extraY
            } else {
                val refScale = if (hasSimple) trackedEyeDistPx else if (currentLens == ArLensMode.GLB_3D) viewW * 0.16f else viewW * 0.20f
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
        if (((currentLens != ArLensMode.NONE && currentLens != ArLensMode.COMIC_FRAME) || faceBeautyEnabled || anyComicGraphicActive) &&
            (!hasReal || (anyComicGraphicActive && comicEntryAgeMs in 0L..700L))
        ) {
            postInvalidateOnAnimation()
        }

        // Phase 1: Advanced Face AR Layer (drawn directly on skin beneath 3D lenses/crowns/HUD; hidden during Before/After compare hold)
        if (faceBeautyEnabled && !isComparingBeforeAfter) {
            drawAdvancedFaceArLayer(canvas, viewW, viewH, cx, cy, hasReal, ::getX, ::getY, now)
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
                drawCyberpunkVisor(canvas, viewW, ::getX, ::getY)
            }
            ArLensMode.GOLDEN_CROWN -> {
                drawGoldenCrown(canvas, viewW, ::getX, ::getY)
            }
            ArLensMode.SCIFI_HUD -> {
                drawSciFiHud(canvas, viewW, ::getX, ::getY)
            }
            ArLensMode.CUSTOM_STYLE -> {
                drawCustomStyleLens(canvas, viewW, viewH, cx, cy, ::getX, ::getY, now)
            }
            ArLensMode.GLB_3D -> {
                drawGlb3dModel(canvas, viewW, viewH, cx, cy, ::getX, ::getY, now, syncGlb, hasAnyTrackedFace)
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

    private fun parseHexWithAlpha(hex: String, alphaFactor: Float, fallback: Int): Int {
        val base = try { Color.parseColor(hex) } catch (_: Exception) { fallback }
        val a = (alphaFactor.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(base), Color.green(base), Color.blue(base))
    }

    private fun buildClosedLandmarkPath(indices: IntArray, getX: (Int) -> Float, getY: (Int) -> Float): Path {
        val path = Path()
        if (indices.isEmpty()) return path
        path.moveTo(getX(indices[0]), getY(indices[0]))
        for (i in 1 until indices.size) {
            path.lineTo(getX(indices[i]), getY(indices[i]))
        }
        path.close()
        return path
    }

    private fun applyMultiplyBlend(paint: Paint) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            paint.blendMode = BlendMode.MULTIPLY
        } else {
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        }
    }

    private fun drawAdvancedFaceArLayer(
        canvas: Canvas,
        viewW: Float,
        viewH: Float,
        cx: Float,
        cy: Float,
        hasReal: Boolean,
        getX: (Int) -> Float,
        getY: (Int) -> Float,
        nowMs: Long
    ) {
        val leftEyeOuterX = getX(33)
        val leftEyeInnerX = getX(133)
        val rightEyeOuterX = getX(263)
        val rightEyeInnerX = getX(362)
        val leftEyeCx = (leftEyeOuterX + leftEyeInnerX) * 0.5f
        val rightEyeCx = (rightEyeOuterX + rightEyeInnerX) * 0.5f
        val leftEyeCy = (getY(33) + getY(133)) * 0.5f
        val rightEyeCy = (getY(263) + getY(362)) * 0.5f
        val eyeSpan = Math.abs(rightEyeCx - leftEyeCx).coerceAtLeast(viewW * 0.10f)
        val faceW = Math.abs(getX(454) - getX(234)).coerceAtLeast(eyeSpan * 2.15f)
        val foreheadY = getY(10)
        val chinY = getY(152)
        val faceH = Math.abs(chinY - foreheadY).coerceAtLeast(eyeSpan * 2.35f)
        val noseY = getY(1)
        val mouthY = if (hasReal) (getY(13) + getY(14)) * 0.5f else (noseY + chinY) * 0.48f
        val eyeRx = (Math.abs(leftEyeInnerX - leftEyeOuterX) * 0.56f).coerceAtLeast(eyeSpan * 0.22f)
        val eyeRy = (eyeRx * 0.52f).coerceAtLeast(5f)

        val faceOvalIndices = intArrayOf(
            10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288,
            397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136,
            172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109
        )
        val faceOvalPath = buildClosedLandmarkPath(faceOvalIndices, getX, getY)

        // 1. Epidermal Skin Smoothing Glow (clipped strictly inside the 36-point face oval)
        if (skinSmoothing > 0.02f) {
            canvas.save()
            canvas.clipPath(faceOvalPath)
            val smoothPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = RadialGradient(
                    cx, cy - faceH * 0.06f, (faceW * 0.68f).coerceAtLeast(24f),
                    intArrayOf(
                        Color.argb((skinSmoothing * 78f).toInt().coerceIn(0, 110), 255, 240, 230),
                        Color.argb((skinSmoothing * 42f).toInt().coerceIn(0, 75), 255, 224, 208),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.65f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawPath(faceOvalPath, smoothPaint)
            canvas.restore()
        }

        // 2. Cheek Blush (soft radial contours with natural MULTIPLY skin blending)
        if (blushOpacity > 0.02f) {
            val lCheekX = if (hasReal) getX(117) else cx - faceW * 0.30f
            val lCheekY = if (hasReal) getY(117) else noseY + faceH * 0.04f
            val rCheekX = if (hasReal) getX(346) else cx + faceW * 0.30f
            val rCheekY = if (hasReal) getY(346) else noseY + faceH * 0.04f
            val cheekR = (faceW * 0.22f).coerceAtLeast(18f)
            val cCore = parseHexWithAlpha(blushColorHex, blushOpacity * 0.48f, Color.MAGENTA)
            val cMid = parseHexWithAlpha(blushColorHex, blushOpacity * 0.20f, Color.MAGENTA)
            val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                applyMultiplyBlend(this)
            }
            for (pt in arrayOf(floatArrayOf(lCheekX, lCheekY), floatArrayOf(rCheekX, rCheekY))) {
                blushPaint.shader = RadialGradient(
                    pt[0], pt[1], cheekR,
                    intArrayOf(cCore, cMid, Color.TRANSPARENT),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(pt[0], pt[1], cheekR, blushPaint)
            }
        }

        // 3. Sculpted Eyebrows (468-landmark brow polygons or smooth curves)
        if (eyebrowOpacity > 0.02f) {
            val browPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = parseHexWithAlpha(eyebrowColorHex, eyebrowOpacity * 0.68f, Color.DKGRAY)
            }
            if (hasReal) {
                val leftBrow = intArrayOf(70, 63, 105, 66, 107, 55, 65, 52, 53, 46)
                val rightBrow = intArrayOf(300, 293, 334, 296, 336, 285, 295, 282, 283, 276)
                canvas.drawPath(buildClosedLandmarkPath(leftBrow, getX, getY), browPaint)
                canvas.drawPath(buildClosedLandmarkPath(rightBrow, getX, getY), browPaint)
            } else {
                val thick = (eyeRy * 0.48f).coerceAtLeast(3f)
                for (side in intArrayOf(-1, 1)) {
                    val ex = if (side < 0) leftEyeCx else rightEyeCx
                    val ey = (if (side < 0) leftEyeCy else rightEyeCy) - eyeRy * 2.1f
                    val p = Path().apply {
                        moveTo(ex - side * eyeRx * 1.05f, ey + thick * 0.2f)
                        quadTo(ex + side * eyeRx * 0.25f, ey - thick * 1.4f, ex + side * eyeRx * 1.35f, ey + thick * 0.35f)
                        quadTo(ex + side * eyeRx * 0.25f, ey - thick * 0.35f, ex - side * eyeRx * 1.05f, ey + thick * 0.9f)
                        close()
                    }
                    canvas.drawPath(p, browPaint)
                }
            }
        }

        // 4. Upper Eyelid Eyeshadow & Winged Eyeliner
        if (eyeShadowOpacity > 0.02f || eyelinerOpacity > 0.02f) {
            for (side in intArrayOf(-1, 1)) {
                val ex = if (side < 0) leftEyeCx else rightEyeCx
                val ey = if (side < 0) leftEyeCy else rightEyeCy
                val innerX = if (side < 0) leftEyeInnerX else rightEyeInnerX
                val outerX = if (side < 0) leftEyeOuterX else rightEyeOuterX

                if (eyeShadowOpacity > 0.02f) {
                    val shPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        applyMultiplyBlend(this)
                        shader = RadialGradient(
                            ex, ey - eyeRy * 0.65f, (eyeRx * 1.55f).coerceAtLeast(14f),
                            intArrayOf(
                                parseHexWithAlpha(eyeShadowColorHex, eyeShadowOpacity * 0.68f, Color.MAGENTA),
                                parseHexWithAlpha(eyeShadowColorHex, eyeShadowOpacity * 0.25f, Color.MAGENTA),
                                Color.TRANSPARENT
                            ),
                            floatArrayOf(0f, 0.62f, 1f),
                            Shader.TileMode.CLAMP
                        )
                    }
                    val shPath = Path().apply {
                        moveTo(innerX, ey)
                        quadTo(ex, ey - eyeRy * 2.35f, outerX + side * eyeRx * 0.45f, ey - eyeRy * 0.65f)
                        quadTo(ex, ey - eyeRy * 0.78f, innerX, ey)
                        close()
                    }
                    canvas.drawPath(shPath, shPaint)
                }

                if (eyelinerOpacity > 0.02f) {
                    val linerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        strokeWidth = (eyeRy * 0.30f).coerceAtLeast(2.5f)
                        color = Color.argb((eyelinerOpacity * 235f).toInt().coerceIn(0, 255), 10, 10, 16)
                    }
                    val linerPath = Path().apply {
                        moveTo(innerX, ey)
                        quadTo(ex, ey - eyeRy * 1.15f, outerX, ey - eyeRy * 0.06f)
                        lineTo(outerX + side * eyeRx * 0.46f, ey - eyeRy * 0.66f)
                    }
                    canvas.drawPath(linerPath, linerPaint)
                }
            }
        }

        // 5. Colored Iris Contact Lenses + Catchlight Sparkles
        if (irisOpacity > 0.02f) {
            val irisR = (eyeRy * 0.72f).coerceAtLeast(4.5f)
            val irisCore = parseHexWithAlpha(irisColorHex, irisOpacity * 0.88f, Color.CYAN)
            val irisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val catchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(215, 255, 255, 255)
            }
            for (eyePt in arrayOf(floatArrayOf(leftEyeCx, leftEyeCy), floatArrayOf(rightEyeCx, rightEyeCy))) {
                canvas.save()
                val clipOval = Path().apply {
                    addOval(RectF(eyePt[0] - eyeRx * 0.95f, eyePt[1] - eyeRy * 0.88f, eyePt[0] + eyeRx * 0.95f, eyePt[1] + eyeRy * 0.88f), Path.Direction.CW)
                }
                canvas.clipPath(clipOval)
                irisPaint.shader = RadialGradient(
                    eyePt[0], eyePt[1], irisR,
                    intArrayOf(Color.argb(210, 10, 12, 18), irisCore, irisCore, Color.argb(200, 8, 12, 22)),
                    floatArrayOf(0f, 0.42f, 0.84f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(eyePt[0], eyePt[1], irisR, irisPaint)
                canvas.drawCircle(eyePt[0] - irisR * 0.28f, eyePt[1] - irisR * 0.26f, (irisR * 0.22f).coerceAtLeast(1.5f), catchPaint)
                canvas.restore()
            }
        }

        // 6. Sculpted Lipstick (468-landmark outer/inner lip ring with realistic MULTIPLY skin blending + gloss)
        if (lipOpacity > 0.02f) {
            val lipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = parseHexWithAlpha(lipColorHex, lipOpacity * 0.78f, Color.RED)
                applyMultiplyBlend(this)
            }
            val mouthHalfW = if (hasReal) Math.abs(getX(291) - getX(61)) * 0.5f else faceW * 0.17f
            val mouthH = (faceH * 0.075f).coerceAtLeast(8f)
            if (hasReal) {
                val outerLips = intArrayOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291, 375, 321, 405, 314, 17, 84, 181, 91, 146)
                val innerLips = intArrayOf(78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95)
                val lipPath = buildClosedLandmarkPath(outerLips, getX, getY).apply {
                    fillType = Path.FillType.EVEN_ODD
                    addPath(buildClosedLandmarkPath(innerLips, getX, getY))
                }
                canvas.drawPath(lipPath, lipPaint)
            } else {
                val lipOval = RectF(cx - mouthHalfW, mouthY - mouthH * 0.55f, cx + mouthHalfW, mouthY + mouthH * 0.65f)
                canvas.drawOval(lipOval, lipPaint)
            }
            if (lipGloss > 0.05f) {
                val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.argb((lipGloss * lipOpacity * 185f).toInt().coerceIn(0, 190), 255, 255, 255)
                }
                canvas.drawOval(
                    RectF(cx - mouthHalfW * 0.36f, mouthY + mouthH * 0.12f, cx + mouthHalfW * 0.36f, mouthY + mouthH * 0.38f),
                    glossPaint
                )
            }
        }

        // 7. Procedural UV-Style Face Masks or Custom PNG Face Texture (eyes & mouth cutouts preserved)
        if (faceMaskPattern != "none" && faceMaskOpacity > 0.02f) {
            canvas.save()
            val maskClipPath = Path(faceOvalPath).apply {
                fillType = Path.FillType.EVEN_ODD
                addOval(RectF(leftEyeCx - eyeRx * 1.02f, leftEyeCy - eyeRy * 1.15f, leftEyeCx + eyeRx * 1.02f, leftEyeCy + eyeRy * 1.15f), Path.Direction.CW)
                addOval(RectF(rightEyeCx - eyeRx * 1.02f, rightEyeCy - eyeRy * 1.15f, rightEyeCx + eyeRx * 1.02f, rightEyeCy + eyeRy * 1.15f), Path.Direction.CW)
                val mHalfW = faceW * 0.17f
                val mHalfH = (faceH * 0.052f).coerceAtLeast(6f)
                addOval(RectF(cx - mHalfW, mouthY - mHalfH, cx + mHalfW, mouthY + mHalfH), Path.Direction.CW)
            }
            canvas.clipPath(maskClipPath)
            if (faceMaskPattern == "custom_texture") {
                val bmp = customMaskBitmap
                if (bmp != null && !bmp.isRecycled) {
                    val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        alpha = (faceMaskOpacity * 255f).toInt().coerceIn(0, 255)
                    }
                    val dst = RectF(cx - faceW * 0.56f, foreheadY - faceH * 0.08f, cx + faceW * 0.56f, chinY + faceH * 0.05f)
                    canvas.drawBitmap(bmp, null, dst, bmpPaint)
                }
                canvas.restore()
                return
            }
            val primColor = parseHexWithAlpha(faceMaskPrimaryHex, faceMaskOpacity * 0.90f, Color.CYAN)
            val accColor = parseHexWithAlpha(faceMaskAccentHex, faceMaskOpacity * 0.92f, Color.YELLOW)
            val maskStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = (faceW * 0.014f).coerceAtLeast(2.2f)
                color = primColor
            }
            val maskFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = accColor
            }
            when (faceMaskPattern) {
                "cyber_circuit" -> {
                    val pulse = 0.75f + (Math.sin(nowMs / 320.0) * 0.25).toFloat()
                    maskStroke.alpha = (faceMaskOpacity * 225f * pulse).toInt().coerceIn(0, 255)
                    for (side in intArrayOf(-1, 1)) {
                        val s = side.toFloat()
                        val p = Path().apply {
                            moveTo(cx + s * faceW * 0.12f, noseY + faceH * 0.03f)
                            lineTo(cx + s * faceW * 0.28f, noseY + faceH * 0.03f)
                            lineTo(cx + s * faceW * 0.38f, leftEyeCy + faceH * 0.08f)
                            lineTo(cx + s * faceW * 0.45f, leftEyeCy - faceH * 0.05f)
                            moveTo(cx + s * faceW * 0.15f, chinY - faceH * 0.10f)
                            lineTo(cx + s * faceW * 0.33f, mouthY + faceH * 0.06f)
                            lineTo(cx + s * faceW * 0.42f, noseY + faceH * 0.14f)
                            moveTo(cx + s * faceW * 0.06f, foreheadY + faceH * 0.09f)
                            lineTo(cx + s * faceW * 0.22f, foreheadY + faceH * 0.16f)
                            lineTo(cx + s * faceW * 0.35f, foreheadY + faceH * 0.16f)
                        }
                        canvas.drawPath(p, maskStroke)
                        val pinR = (faceW * 0.016f).coerceAtLeast(3f)
                        canvas.drawCircle(cx + s * faceW * 0.12f, noseY + faceH * 0.03f, pinR, maskFill)
                        canvas.drawCircle(cx + s * faceW * 0.38f, leftEyeCy + faceH * 0.08f, pinR, maskFill)
                        canvas.drawCircle(cx + s * faceW * 0.33f, mouthY + faceH * 0.06f, pinR, maskFill)
                    }
                }
                "kitsune_mask" -> {
                    val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = parseHexWithAlpha(faceMaskAccentHex, faceMaskOpacity * 0.28f, Color.WHITE)
                    }
                    canvas.drawPath(faceOvalPath, wash)
                    maskStroke.strokeWidth = (faceW * 0.020f).coerceAtLeast(3f)
                    maskFill.color = primColor
                    val fy = foreheadY + faceH * 0.14f
                    val crest = Path().apply {
                        moveTo(cx, fy - faceH * 0.08f)
                        quadTo(cx + faceW * 0.08f, fy, cx, fy + faceH * 0.07f)
                        quadTo(cx - faceW * 0.08f, fy, cx, fy - faceH * 0.08f)
                        close()
                    }
                    canvas.drawPath(crest, maskFill)
                    for (side in intArrayOf(-1, 1)) {
                        val s = side.toFloat()
                        for (k in 0..2) {
                            val yOff = noseY + faceH * (0.02f + k * 0.055f)
                            val whisker = Path().apply {
                                moveTo(cx + s * faceW * 0.18f, yOff)
                                quadTo(cx + s * faceW * 0.34f, yOff - faceH * 0.015f, cx + s * faceW * (0.45f - k * 0.03f), yOff + faceH * 0.03f)
                            }
                            canvas.drawPath(whisker, maskStroke)
                        }
                    }
                }
                "tribal_gold" -> {
                    canvas.drawLine(cx, foreheadY + faceH * 0.10f, cx, noseY, maskStroke)
                    for (side in intArrayOf(-1, 1)) {
                        val s = side.toFloat()
                        val ex = if (side < 0) leftEyeCx else rightEyeCx
                        val ey = if (side < 0) leftEyeCy else rightEyeCy
                        val arc = Path().apply {
                            moveTo(ex - s * eyeRx * 0.8f, ey + eyeRy * 1.4f)
                            quadTo(ex + s * eyeRx * 0.2f, ey + eyeRy * 2.3f, ex + s * eyeRx * 1.45f, ey + eyeRy * 0.85f)
                            moveTo(cx + s * faceW * 0.05f, foreheadY + faceH * 0.14f)
                            quadTo(cx + s * faceW * 0.22f, foreheadY + faceH * 0.05f, cx + s * faceW * 0.36f, foreheadY + faceH * 0.19f)
                        }
                        canvas.drawPath(arc, maskStroke)
                    }
                    canvas.drawCircle(cx, chinY - faceH * 0.09f, (faceW * 0.022f).coerceAtLeast(3.5f), maskFill)
                }
                "porcelain_glam" -> {
                    val shimmer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = parseHexWithAlpha(faceMaskPrimaryHex, faceMaskOpacity * 0.20f, Color.YELLOW)
                    }
                    canvas.drawPath(faceOvalPath, shimmer)
                    for (side in intArrayOf(-1, 1)) {
                        for (i in 0..3) {
                            canvas.drawCircle(
                                cx + side * faceW * (0.18f + i * 0.065f),
                                noseY + faceH * (0.01f + (i % 2) * 0.035f),
                                (faceW * 0.009f).coerceAtLeast(2f),
                                maskFill
                            )
                        }
                    }
                }
            }
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
        viewW: Float,
        getX: (Int) -> Float,
        getY: (Int) -> Float,
        primaryHex: String = "#00F0FF",
        accentHex: String = "#FF007F",
        telemetry: String = "CYBER-OPTICS // ACTIVE"
    ) {
        val leftX = getX(33)
        val rightX = getX(263)
        val eyeY = (getY(33) + getY(263)) / 2f
        val visorWidth = (Math.abs(rightX - leftX) * 1.65f).coerceIn(viewW * 0.16f, viewW * 0.72f)
        val visorHeight = visorWidth * 0.34f
        val centerX = (leftX + rightX) / 2f

        val pColor = try { Color.parseColor(primaryHex) } catch (_: Exception) { Color.parseColor("#00F0FF") }
        val aColor = try { Color.parseColor(accentHex) } catch (_: Exception) { Color.parseColor("#FF007F") }
        val pFill = Color.argb(135, Color.red(pColor), Color.green(pColor), Color.blue(pColor))
        val aFill = Color.argb(135, Color.red(aColor), Color.green(aColor), Color.blue(aColor))

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
            pFill,
            aFill,
            Shader.TileMode.CLAMP
        )
        visorBorderPaint.color = pColor

        canvas.drawPath(visorPath, visorGlowPaint)
        canvas.drawPath(visorPath, visorBorderPaint)

        // Subtle horizontal scanline accent inside visor
        val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(140, 255, 255, 255)
            strokeWidth = 2f
        }
        canvas.drawLine(
            centerX - visorWidth * 0.42f, eyeY,
            centerX + visorWidth * 0.42f, eyeY,
            scanPaint
        )

        val hudPaint = Paint(textHudPaint).apply { color = pColor }
        canvas.drawText(telemetry.ifBlank { "CYBER-OPTICS // ACTIVE" }, centerX - (visorWidth * 0.38f), eyeY - visorHeight / 2f - 16f, hudPaint)
    }

    private fun drawGoldenCrown(
        canvas: Canvas,
        viewW: Float,
        getX: (Int) -> Float,
        getY: (Int) -> Float
    ) {
        val foreheadX = getX(10)
        val foreheadY = getY(10)
        val faceW = Math.abs(getX(454) - getX(234))
        val crownWidth = (faceW * 0.85f).coerceIn(viewW * 0.16f, viewW * 0.65f)
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

        // Glittering crown jewels
        val gemRadius = (crownWidth * 0.042f).coerceIn(5f, 16f)
        val rubyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EF4444"); style = Paint.Style.FILL }
        val sapphirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#38BDF8"); style = Paint.Style.FILL }
        val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawCircle(foreheadX, foreheadY - crownHeight * 0.46f, gemRadius * 1.15f, rubyPaint)
        canvas.drawCircle(foreheadX - gemRadius * 0.3f, foreheadY - crownHeight * 0.46f - gemRadius * 0.3f, gemRadius * 0.32f, shinePaint)
        canvas.drawCircle(foreheadX - crownWidth * 0.28f, foreheadY - crownHeight * 0.22f, gemRadius * 0.85f, sapphirePaint)
        canvas.drawCircle(foreheadX + crownWidth * 0.28f, foreheadY - crownHeight * 0.22f, gemRadius * 0.85f, sapphirePaint)
    }

    private fun drawSciFiHud(
        canvas: Canvas,
        viewW: Float,
        getX: (Int) -> Float,
        getY: (Int) -> Float,
        primaryHex: String = "#34D399",
        accentHex: String = "#00F0FF",
        telemetry: String = "LOCK: ACQUIRED [99.4%]"
    ) {
        val noseX = getX(1)
        val noseY = getY(1)
        val faceW = Math.abs(getX(454) - getX(234))
        val radius = (faceW * 0.72f).coerceIn(viewW * 0.12f, viewW * 0.38f)

        val pColor = try { Color.parseColor(primaryHex) } catch (_: Exception) { Color.parseColor("#34D399") }
        val aColor = try { Color.parseColor(accentHex) } catch (_: Exception) { Color.parseColor("#00F0FF") }

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pColor
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        canvas.drawCircle(noseX, noseY, radius, ringPaint)
        canvas.drawLine(noseX - radius - 20f, noseY, noseX - radius + 20f, noseY, ringPaint)
        canvas.drawLine(noseX + radius - 20f, noseY, noseX + radius + 20f, noseY, ringPaint)
        canvas.drawLine(noseX, noseY - radius - 20f, noseX, noseY - radius + 20f, ringPaint)
        canvas.drawLine(noseX, noseY + radius - 20f, noseX, noseY + radius + 20f, ringPaint)

        val hudPaint = Paint(textHudPaint).apply { color = aColor }
        canvas.drawText(telemetry.ifBlank { "LOCK: ACQUIRED [99.4%]" }, noseX - (radius * 0.65f), noseY + radius + 36f, hudPaint)
    }

    private fun drawCustomStyleLens(
        canvas: Canvas,
        viewW: Float,
        viewH: Float,
        cx: Float,
        cy: Float,
        getX: (Int) -> Float,
        getY: (Int) -> Float,
        nowMs: Long
    ) {
        val cfg = activeCustomLensStyle ?: return
        val lensStyle = cfg.style.lowercase()
        val anchor = cfg.anchor.lowercase()
        val primaryColor = try { Color.parseColor(cfg.primaryColorHex) } catch (_: Exception) { Color.parseColor("#EC4899") }
        val accentColor = try { Color.parseColor(cfg.accentColorHex) } catch (_: Exception) { Color.parseColor("#38BDF8") }
        val animSec = (nowMs % 60000L) / 1000.0
        val pulse = (0.5 + 0.5 * Math.sin(animSec * 3.2)).toFloat()
        val faceW = Math.abs(getX(454) - getX(234)).coerceIn(viewW * 0.16f, viewW * 0.72f)
        val faceH = Math.abs(getY(152) - getY(10)).coerceIn(viewH * 0.14f, viewH * 0.62f)
        val foreheadX = getX(10)
        val foreheadY = getY(10)
        val eyeLeftX = getX(33)
        val eyeLeftY = getY(33)
        val eyeRightX = getX(263)
        val eyeRightY = getY(263)
        val noseX = getX(1)
        val noseY = getY(1)
        val chinY = getY(152)

        val hudLabelPaint = Paint(textHudPaint).apply {
            color = accentColor
            textSize = (viewW * 0.026f).coerceIn(22f, 34f)
            textAlign = Paint.Align.CENTER
            setShadowLayer(8f, 0f, 0f, primaryColor)
        }

        when (lensStyle) {
            "cat_ears", "animal_ears" -> {
                val earSpan = faceW * 0.44f
                val earW = faceW * 0.34f
                val earH = faceW * 0.42f
                val baseY = foreheadY - faceH * 0.04f

                val outerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(175, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))
                    style = Paint.Style.FILL
                }
                val outerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = primaryColor
                    strokeWidth = (faceW * 0.022f).coerceIn(4f, 10f)
                    style = Paint.Style.STROKE
                    strokeJoin = Paint.Join.ROUND
                    setShadowLayer(14f + pulse * 8f, 0f, 0f, primaryColor)
                }
                val innerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(185, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                    style = Paint.Style.FILL
                }
                val innerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    strokeWidth = 2.5f
                    style = Paint.Style.STROKE
                }

                fun drawCatEar(sign: Float) {
                    val bx = foreheadX + sign * earSpan
                    val twitch = (Math.sin(animSec * 2.6 + sign) * (earH * 0.04f)).toFloat()
                    val outerPath = Path().apply {
                        moveTo(bx - sign * earW * 0.52f, baseY + earH * 0.14f)
                        lineTo(bx + sign * earW * 0.18f, baseY - earH + twitch)
                        lineTo(bx + sign * earW * 0.56f, baseY + earH * 0.22f)
                        close()
                    }
                    val innerPath = Path().apply {
                        moveTo(bx - sign * earW * 0.28f, baseY + earH * 0.08f)
                        lineTo(bx + sign * earW * 0.14f, baseY - earH * 0.68f + twitch)
                        lineTo(bx + sign * earW * 0.34f, baseY + earH * 0.14f)
                        close()
                    }
                    canvas.drawPath(outerPath, outerFill)
                    canvas.drawPath(outerPath, outerStroke)
                    canvas.drawPath(innerPath, innerFill)
                    canvas.drawPath(innerPath, innerStroke)
                }
                drawCatEar(-1f)
                drawCatEar(1f)

                // Subtle neon cat whiskers on cheeks
                val whiskerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accentColor
                    strokeWidth = 3f
                    strokeCap = Paint.Cap.ROUND
                    alpha = 200
                }
                val cheekY = noseY + faceH * 0.08f
                for (sign in floatArrayOf(-1f, 1f)) {
                    val wx0 = noseX + sign * faceW * 0.26f
                    val wx1 = noseX + sign * faceW * 0.58f
                    canvas.drawLine(wx0, cheekY - faceH * 0.04f, wx1, cheekY - faceH * 0.08f, whiskerPaint)
                    canvas.drawLine(wx0, cheekY + faceH * 0.01f, wx1, cheekY + faceH * 0.02f, whiskerPaint)
                    canvas.drawLine(wx0, cheekY + faceH * 0.06f, wx1 * 0.96f + noseX * 0.04f, cheekY + faceH * 0.11f, whiskerPaint)
                }

                if (cfg.telemetryText.isNotBlank()) {
                    canvas.drawText(cfg.telemetryText, foreheadX, baseY - earH - 20f, hudLabelPaint)
                }
            }

            "halo_tiara" -> {
                val floatY = (Math.sin(animSec * 2.2) * (faceH * 0.028f)).toFloat()
                val haloY = foreheadY - faceH * 0.26f + floatY
                val rx = faceW * 0.56f
                val ry = faceW * 0.14f
                val haloRect = RectF(foreheadX - rx, haloY - ry, foreheadX + rx, haloY + ry)

                val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(110, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))
                    style = Paint.Style.STROKE
                    strokeWidth = (faceW * 0.075f).coerceIn(14f, 34f)
                }
                val mainRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = primaryColor
                    style = Paint.Style.STROKE
                    strokeWidth = (faceW * 0.034f).coerceIn(6f, 16f)
                    setShadowLayer(18f, 0f, 0f, primaryColor)
                }
                val coreRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accentColor
                    style = Paint.Style.STROKE
                    strokeWidth = (faceW * 0.012f).coerceIn(2.5f, 6f)
                }

                canvas.drawOval(haloRect, outerGlowPaint)
                canvas.drawOval(haloRect, mainRingPaint)
                canvas.drawOval(haloRect, coreRingPaint)

                // Orbiting celestial 4-point sparkles
                val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accentColor
                    style = Paint.Style.FILL
                    setShadowLayer(10f, 0f, 0f, primaryColor)
                }
                for (i in 0 until 6) {
                    val angle = animSec * 1.4 + i * (Math.PI * 2.0 / 6.0)
                    val sx = foreheadX + (Math.cos(angle) * rx * 1.08f).toFloat()
                    val sy = haloY + (Math.sin(angle) * ry * 1.35f).toFloat()
                    val sSize = (faceW * 0.036f * (0.65f + 0.35f * Math.sin(animSec * 4.0 + i).toFloat())).coerceIn(6f, 18f)
                    drawFourPointStar(canvas, sx, sy, sSize, sparkPaint)
                }

                if (cfg.telemetryText.isNotBlank()) {
                    canvas.drawText(cfg.telemetryText, foreheadX, haloY - ry - 22f, hudLabelPaint)
                }
            }

            "cyber_mask" -> {
                val maskTopY = noseY - faceH * 0.05f
                val maskBottomY = chinY + faceH * 0.06f
                val halfW = faceW * 0.48f

                val armorPath = Path().apply {
                    moveTo(noseX, maskTopY)
                    lineTo(noseX + halfW, maskTopY + faceH * 0.14f)
                    lineTo(noseX + halfW * 0.84f, maskBottomY - faceH * 0.10f)
                    lineTo(noseX + halfW * 0.32f, maskBottomY)
                    lineTo(noseX - halfW * 0.32f, maskBottomY)
                    lineTo(noseX - halfW * 0.84f, maskBottomY - faceH * 0.10f)
                    lineTo(noseX - halfW, maskTopY + faceH * 0.14f)
                    close()
                }
                val armorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(215, 12, 18, 30)
                    style = Paint.Style.FILL
                }
                val armorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = primaryColor
                    strokeWidth = (faceW * 0.02f).coerceIn(4f, 9f)
                    style = Paint.Style.STROKE
                    strokeJoin = Paint.Join.BEVEL
                    setShadowLayer(12f, 0f, 0f, primaryColor)
                }
                canvas.drawPath(armorPath, armorFill)
                canvas.drawPath(armorPath, armorStroke)

                // Central nose ridge & intake grille vents
                val ventPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = primaryColor
                    strokeWidth = 3.5f
                    strokeCap = Paint.Cap.ROUND
                }
                val grillTop = maskTopY + faceH * 0.18f
                for (i in 0 until 4) {
                    val vy = grillTop + i * (faceH * 0.065f)
                    val vw = halfW * (0.34f - i * 0.04f)
                    canvas.drawLine(noseX - vw, vy, noseX + vw, vy, ventPaint)
                }

                // Dual pulsing LED respirator filter pods on left & right cheeks
                val podY = (maskTopY + maskBottomY) * 0.52f
                val podR = (faceW * 0.115f).coerceIn(16f, 44f)
                val podFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(235, 18, 28, 46)
                    style = Paint.Style.FILL
                }
                val podRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accentColor
                    strokeWidth = (faceW * 0.018f).coerceIn(3.5f, 7f)
                    style = Paint.Style.STROKE
                    setShadowLayer(12f + pulse * 6f, 0f, 0f, accentColor)
                }
                val podCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb((150 + (pulse * 105f).toInt()).coerceIn(120, 255), Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                    style = Paint.Style.FILL
                }
                for (sign in floatArrayOf(-1f, 1f)) {
                    val px = noseX + sign * halfW * 0.56f
                    canvas.drawCircle(px, podY, podR, podFill)
                    canvas.drawCircle(px, podY, podR, podRing)
                    canvas.drawCircle(px, podY, podR * 0.42f, podCore)
                }

                if (cfg.telemetryText.isNotBlank()) {
                    canvas.drawText(cfg.telemetryText, noseX, maskBottomY + 34f, hudLabelPaint)
                }
            }

            "floating_prop" -> {
                val starPrimaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = primaryColor
                    style = Paint.Style.FILL
                    setShadowLayer(14f + pulse * 8f, 0f, 0f, primaryColor)
                }
                val starCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                val starAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accentColor
                    style = Paint.Style.FILL
                }
                if (anchor == "eyes") {
                    val eyeDist = Math.abs(eyeRightX - eyeLeftX).coerceAtLeast(faceW * 0.28f)
                    val mainR = (eyeDist * 0.24f * (0.9f + 0.22f * pulse)).coerceIn(14f, 44f)
                    val eyeCenters = arrayOf(Pair(eyeLeftX, eyeLeftY), Pair(eyeRightX, eyeRightY))
                    for ((ex, ey) in eyeCenters) {
                        canvas.save()
                        canvas.translate(ex, ey)
                        canvas.rotate((animSec * 28.0).toFloat())
                        drawFourPointStar(canvas, 0f, 0f, mainR, starPrimaryPaint)
                        drawFourPointStar(canvas, 0f, 0f, mainR * 0.52f, starCorePaint)
                        canvas.restore()
                        drawFourPointStar(canvas, ex + mainR * 0.85f, ey - mainR * 0.65f, mainR * 0.36f, starAccentPaint)
                    }
                    if (cfg.telemetryText.isNotBlank()) {
                        canvas.drawText(cfg.telemetryText, (eyeLeftX + eyeRightX) * 0.5f, Math.min(eyeLeftY, eyeRightY) - mainR * 1.4f, hudLabelPaint)
                    }
                } else {
                    val propY = if (anchor == "forehead") foreheadY - faceH * 0.22f else cy
                    val mainR = (faceW * 0.20f * (0.92f + 0.16f * pulse)).coerceIn(22f, 64f)
                    drawFourPointStar(canvas, foreheadX, propY, mainR, starPrimaryPaint)
                    drawFourPointStar(canvas, foreheadX, propY, mainR * 0.48f, starCorePaint)
                    if (cfg.telemetryText.isNotBlank()) {
                        canvas.drawText(cfg.telemetryText, foreheadX, propY - mainR - 18f, hudLabelPaint)
                    }
                }
            }

            "target_reticle" -> {
                val boxHalfW = faceW * 0.62f
                val boxHalfH = faceH * 0.64f
                val cornerLen = (faceW * 0.18f).coerceIn(22f, 64f)
                val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = primaryColor
                    strokeWidth = (faceW * 0.016f).coerceIn(3.5f, 7f)
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.SQUARE
                }
                val accentTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accentColor
                    strokeWidth = 3f
                    style = Paint.Style.STROKE
                }

                val left = cx - boxHalfW
                val right = cx + boxHalfW
                val top = cy - boxHalfH
                val bottom = cy + boxHalfH
                // Four tactical corner brackets
                canvas.drawLine(left, top, left + cornerLen, top, bracketPaint)
                canvas.drawLine(left, top, left, top + cornerLen, bracketPaint)
                canvas.drawLine(right, top, right - cornerLen, top, bracketPaint)
                canvas.drawLine(right, top, right, top + cornerLen, bracketPaint)
                canvas.drawLine(left, bottom, left + cornerLen, bottom, bracketPaint)
                canvas.drawLine(left, bottom, left, bottom - cornerLen, bracketPaint)
                canvas.drawLine(right, bottom, right - cornerLen, bottom, bracketPaint)
                canvas.drawLine(right, bottom, right, bottom - cornerLen, bracketPaint)

                // Animated inner radar arcs + center crosshair
                val radarR = (faceW * 0.36f).coerceIn(48f, viewW * 0.28f)
                val sweepStart = ((animSec * 70.0) % 360.0).toFloat()
                val radarRect = RectF(cx - radarR, cy - radarR, cx + radarR, cy + radarR)
                canvas.drawArc(radarRect, sweepStart, 55f, false, accentTickPaint)
                canvas.drawArc(radarRect, sweepStart + 180f, 55f, false, accentTickPaint)
                canvas.drawCircle(noseX, noseY, 6f + pulse * 4f, accentTickPaint)

                if (cfg.telemetryText.isNotBlank()) {
                    canvas.drawText(cfg.telemetryText, cx, bottom + 36f, hudLabelPaint)
                }
            }

            "neon_visor" -> {
                drawCyberpunkVisor(
                    canvas = canvas,
                    viewW = viewW,
                    getX = getX,
                    getY = getY,
                    primaryHex = cfg.primaryColorHex,
                    accentHex = cfg.accentColorHex,
                    telemetry = cfg.telemetryText.ifBlank { "NEON VISOR // LOCKED" }
                )
            }

            "neon_horns" -> {
                val hornStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = primaryColor
                    strokeWidth = (faceW * 0.022f).coerceIn(4f, 9f)
                    style = Paint.Style.STROKE
                    setShadowLayer(14f, 0f, 0f, primaryColor)
                }
                val hornFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(180, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                    style = Paint.Style.FILL
                }
                for (sign in floatArrayOf(-1f, 1f)) {
                    val hx = foreheadX + sign * faceW * 0.36f
                    val hy = foreheadY - faceH * 0.04f
                    val hornPath = Path().apply {
                        moveTo(hx - sign * faceW * 0.08f, hy + faceH * 0.05f)
                        quadTo(hx + sign * faceW * 0.16f, hy - faceH * 0.18f, hx + sign * faceW * 0.05f, hy - faceH * 0.36f)
                        quadTo(hx + sign * faceW * 0.28f, hy - faceH * 0.14f, hx + sign * faceW * 0.12f, hy + faceH * 0.07f)
                        close()
                    }
                    canvas.drawPath(hornPath, hornFill)
                    canvas.drawPath(hornPath, hornStroke)
                }
                if (cfg.telemetryText.isNotBlank()) {
                    canvas.drawText(cfg.telemetryText, foreheadX, foreheadY - faceH * 0.40f, hudLabelPaint)
                }
            }

            else -> {
                drawSciFiHud(
                    canvas = canvas,
                    viewW = viewW,
                    getX = getX,
                    getY = getY,
                    primaryHex = cfg.primaryColorHex,
                    accentHex = cfg.accentColorHex,
                    telemetry = cfg.telemetryText.ifBlank { "AR LENS // ACTIVE" }
                )
            }
        }
    }

    private fun drawFourPointStar(canvas: Canvas, x: Float, y: Float, radius: Float, paint: Paint) {
        val inner = radius * 0.26f
        val star = Path().apply {
            moveTo(x, y - radius)
            lineTo(x + inner, y - inner)
            lineTo(x + radius, y)
            lineTo(x + inner, y + inner)
            lineTo(x, y + radius)
            lineTo(x - inner, y + inner)
            lineTo(x - radius, y)
            lineTo(x - inner, y - inner)
            close()
        }
        canvas.drawPath(star, paint)
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

        // 5. Classic Ben-Day halftone dot accents near corners so the comic panel reads vividly on live camera
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(115, 6, 8, 13)
            style = Paint.Style.FILL
        }
        val dotStep = Math.max(12f, minDim * 0.018f)
        val dotR = Math.max(2.2f, dotStep * 0.22f)
        for (row in 0 until 3) {
            for (col in 0 until (3 - row)) {
                val dx = cornerAccent * 0.85f + col * dotStep
                val dy = cornerAccent * 0.85f + row * dotStep
                canvas.drawCircle(innerInset + dx, innerInset + dy, dotR, dotPaint)
                canvas.drawCircle(rightEdge - dx, innerInset + dy, dotR, dotPaint)
                canvas.drawCircle(innerInset + dx, bottomEdge - dy, dotR, dotPaint)
                canvas.drawCircle(rightEdge - dx, bottomEdge - dy, dotR, dotPaint)
            }
        }
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
        syncGlb: Boolean,
        hasTrackedFace: Boolean
    ) {
        val engine = glbEngine ?: return
        if (!engine.hasModelLoaded()) return

        val faceW = Math.abs(getX(454) - getX(234)).coerceAtLeast(viewW * 0.06f)
        val faceH = Math.abs(getY(152) - getY(10)).coerceAtLeast(viewH * 0.06f)
        val eyeLeftX = getX(33)
        val eyeLeftY = getY(33)
        val eyeRightX = getX(263)
        val eyeRightY = getY(263)
        // Always order eyes left-to-right in screen space so front-camera horizontal mirroring
        // never makes dx negative (which caused atan2 to return ±180° and flip the GLB upside down).
        val screenLeftEyeX: Float
        val screenLeftEyeY: Float
        val screenRightEyeX: Float
        val screenRightEyeY: Float
        if (eyeLeftX <= eyeRightX) {
            screenLeftEyeX = eyeLeftX; screenLeftEyeY = eyeLeftY
            screenRightEyeX = eyeRightX; screenRightEyeY = eyeRightY
        } else {
            screenLeftEyeX = eyeRightX; screenLeftEyeY = eyeRightY
            screenRightEyeX = eyeLeftX; screenRightEyeY = eyeLeftY
        }
        val eyeDx = (screenRightEyeX - screenLeftEyeX).coerceAtLeast(1f)
        val eyeDy = screenRightEyeY - screenLeftEyeY
        val headRollDeg = Math.toDegrees(Math.atan2(eyeDy.toDouble(), eyeDx.toDouble())).toFloat().coerceIn(-55f, 55f)
        val eyeMidX = (eyeLeftX + eyeRightX) * 0.5f
        val estYawDeg = if (hasTrackedFace) {
            (((getX(1) - eyeMidX) / faceW.coerceAtLeast(1f)) * 55f).coerceIn(-35f, 35f)
        } else {
            0f
        }

        val anchorKey = activeGlbAnchor.lowercase()
        val anchorX = when (anchorKey) {
            "forehead" -> getX(10)
            "eyes" -> eyeMidX
            "nose_cheeks" -> getX(1)
            else -> cx
        }
        val anchorYBase = when (anchorKey) {
            "forehead" -> getY(10) - faceH * 0.14f
            "eyes" -> (eyeLeftY + eyeRightY) * 0.5f
            "nose_cheeks" -> getY(1)
            "mouth" -> (getY(1) + getY(152)) * 0.52f
            else -> cy
        }
        val anchorY = anchorYBase + activeGlbOffsetY * faceH * 0.55f
        val baseAnchorScale = when (anchorKey) {
            "forehead" -> 0.80f
            "eyes" -> 0.76f
            "nose_cheeks" -> 0.52f
            "mouth" -> 0.54f
            else -> 0.95f
        }

        val elapsedSec = ((nowMs - activeGlbStartMs).coerceAtLeast(0L)) / 1000f
        val idleYaw = when {
            activeGlbAutoSpin -> elapsedSec * 36f
            hasTrackedFace -> estYawDeg
            else -> (Math.sin(elapsedSec * 1.1).toFloat() * 4f)
        }

        // Preserve exact screen aspect ratio so tall displays (e.g. 20:9) never stretch the 3D model vertically
        val maxEdge = 960f
        val downScale = minOf(1f, maxEdge / maxOf(viewW, viewH).coerceAtLeast(1f))
        val renderW = (viewW * downScale).toInt().coerceIn(128, 960)
        val renderH = (viewH * downScale).toInt().coerceIn(128, 960)
        val pose = GlbArModelEngine.GlbPose(
            normAnchorX = (anchorX / viewW).coerceIn(0.05f, 0.95f),
            normAnchorY = (anchorY / viewH).coerceIn(0.05f, 0.95f),
            normFaceWidth = (faceW / viewW).coerceIn(0.06f, 1.35f),
            modelScale = baseAnchorScale * activeGlbModelScale,
            yawDeg = activeGlbRotY + idleYaw,
            pitchDeg = activeGlbRotX,
            rollDeg = -headRollDeg,
            enableHeadOcclusion = faceOcclusionEnabled && !isComparingBeforeAfter,
            normFaceCenterX = (cx / viewW).coerceIn(0.05f, 0.95f),
            normFaceCenterY = (cy / viewH).coerceIn(0.05f, 0.95f),
            normFaceHeight = (faceH / viewH).coerceIn(0.06f, 1.35f)
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

    // =========================================================================
    // PHASE 2: HAND AR RENDERER (ZERO-GC, PALM-AWARE, PINCH-SCALE, DUAL-ARC, GLB)
    // =========================================================================
    private fun drawHandArLayer(
        canvas: Canvas,
        viewW: Float,
        viewH: Float,
        nowMs: Long,
        syncGlb: Boolean
    ) {
        val hands = trackedHands
        val isFresh = hands.isNotEmpty() && (nowMs - lastHandUpdateMs) < 1200L
        if (!isFresh) return

        val timeSec = nowMs / 1000f
        // Map normalized camera coordinates to center-cropped PreviewView coordinates
        val srcW = handInputWidth.toFloat().coerceAtLeast(1f)
        val srcH = handInputHeight.toFloat().coerceAtLeast(1f)
        val scale = Math.max(viewW / srcW, viewH / srcH)
        val scaledW = srcW * scale
        val scaledH = srcH * scale
        val dxOff = (viewW - scaledW) * 0.5f
        val dyOff = (viewH - scaledH) * 0.5f

        val validHandCount = hands.size.coerceAtMost(2)
        var renderedHands = 0

        for (hIdx in 0 until validHandCount) {
            val hand = hands[hIdx]
            if (hand.landmarks.size < 21) continue
            val pts = handPointPool[renderedHands]
            for (i in 0 until 21) {
                val lm = hand.landmarks[i]
                val nx = if (handIsFrontCamera) (1f - lm.x) else lm.x
                pts[i].set(nx * scaledW + dxOff, lm.y * scaledH + dyOff)
            }
            val palmX = (pts[0].x + pts[5].x + pts[9].x + pts[13].x + pts[17].x) / 5f
            val palmY = (pts[0].y + pts[5].y + pts[9].y + pts[13].y + pts[17].y) / 5f
            handPalmPool[renderedHands].set(palmX, palmY)
            renderedHands++

            val handScale = Math.hypot(
                (pts[9].x - pts[0].x).toDouble(),
                (pts[9].y - pts[0].y).toDouble()
            ).toFloat().coerceAtLeast(52f)

            // Detect palm vs. back-of-hand orientation using 2D cross product (Index MCP - Wrist) x (Pinky MCP - Wrist)
            val ax = pts[5].x - pts[0].x
            val ay = pts[5].y - pts[0].y
            val bxCross = pts[17].x - pts[0].x
            val byCross = pts[17].y - pts[0].y
            val cross = ax * byCross - ay * bxCross
            val isRightHand = hand.handedness.equals("Right", ignoreCase = true)
            val isBackOfHand = if (handIsFrontCamera) {
                if (isRightHand) cross < 0f else cross >= 0f
            } else {
                if (isRightHand) cross >= 0f else cross < 0f
            }
            val showTopFace = !handPalmRealismEnabled || isBackOfHand

            // 1. Optional 21-Point Skeleton + Gesture Pill
            if (handShowSkeleton) {
                handBonePaint.color = parseHexWithAlpha(handPropColorHex, 0.82f, Color.CYAN)
                for (link in handBoneLinks) {
                    canvas.drawLine(pts[link[0]].x, pts[link[0]].y, pts[link[1]].x, pts[link[1]].y, handBonePaint)
                }
                for (i in 0 until 21) {
                    val isTip = i == 4 || i == 8 || i == 12 || i == 16 || i == 20
                    handNodePaint.color = when {
                        i == 0 -> Color.parseColor("#FACC15")
                        isTip -> Color.parseColor("#00F0FF")
                        else -> Color.parseColor("#E2E8F0")
                    }
                    canvas.drawCircle(pts[i].x, pts[i].y, if (i == 0 || isTip) 6f else 3.5f, handNodePaint)
                }
                val sideTag = if (isBackOfHand) "BACK" else "PALM"
                val pillText = "${hand.handedness.uppercase()} · $sideTag · ${hand.gesture.replace("_", " ")}"
                val tw = handPillTextPaint.measureText(pillText) + 28f
                handTempRect.set(pts[0].x - tw * 0.5f, pts[0].y + 22f, pts[0].x + tw * 0.5f, pts[0].y + 58f)
                canvas.drawRoundRect(handTempRect, 12f, 12f, handPillBgPaint)
                canvas.drawText(pillText, pts[0].x, pts[0].y + 48f, handPillTextPaint)
            }

            // 2. Wrist Bracelet / Holo Watch (with Palm/Back 3D realism & skin contact shadow)
            if (handBraceletStyle != HandBraceletStyle.NONE) {
                val wrist = pts[0]
                val midMcp = pts[9]
                val vx = wrist.x - midMcp.x
                val vy = wrist.y - midMcp.y
                val vLen = Math.hypot(vx.toDouble(), vy.toDouble()).toFloat().coerceAtLeast(1f)
                val bx = wrist.x + (vx / vLen) * handScale * 0.08f
                val by = wrist.y + (vy / vLen) * handScale * 0.08f
                val deg = Math.toDegrees(Math.atan2(vy.toDouble(), vx.toDouble())).toFloat() - 90f
                val cuffW = handScale * 0.64f
                val cuffH = (handScale * 0.16f).coerceAtLeast(14f)

                canvas.save()
                canvas.translate(bx, by)
                canvas.rotate(deg)
                val colMain = parseHexWithAlpha(handBraceletColorHex, 1f, Color.parseColor("#F59E0B"))
                val colAcc = parseHexWithAlpha(handBraceletAccentHex, 1f, Color.CYAN)

                // Soft skin-contact drop shadow
                handTempRect.set(-cuffW * 0.52f, -cuffH * 0.36f, cuffW * 0.52f, cuffH * 0.62f)
                canvas.drawRoundRect(handTempRect, cuffH * 0.42f, cuffH * 0.42f, handShadowPaint)

                handTempRect.set(-cuffW * 0.5f, -cuffH * 0.5f, cuffW * 0.5f, cuffH * 0.5f)
                when (handBraceletStyle) {
                    HandBraceletStyle.GOLD_CUFF -> {
                        handFillPaint.shader = LinearGradient(-cuffW * 0.5f, 0f, cuffW * 0.5f, 0f, intArrayOf(colMain, if (showTopFace) colAcc else Color.WHITE, colMain), null, Shader.TileMode.CLAMP)
                        canvas.drawRoundRect(handTempRect, cuffH * 0.4f, cuffH * 0.4f, handFillPaint)
                        handFillPaint.shader = null
                    }
                    HandBraceletStyle.CYBER_PULSE -> {
                        handFillPaint.shader = null
                        handFillPaint.color = Color.argb(220, 15, 23, 42)
                        canvas.drawRoundRect(handTempRect, cuffH * 0.4f, cuffH * 0.4f, handFillPaint)
                        handStrokePaint.strokeWidth = 3f
                        handStrokePaint.color = colAcc
                        canvas.drawRoundRect(handTempRect, cuffH * 0.4f, cuffH * 0.4f, handStrokePaint)
                        val pulseX = (((timeSec * 0.9f) % 1f) - 0.5f) * cuffW * 0.8f
                        handFillPaint.color = Color.WHITE
                        canvas.drawCircle(pulseX, 0f, cuffH * 0.25f, handFillPaint)
                    }
                    HandBraceletStyle.BEADED_LUXE -> {
                        handFillPaint.shader = null
                        val beadCount = 7
                        val beadR = (cuffW / beadCount) * 0.54f
                        for (bi in 0 until beadCount) {
                            val t = bi.toFloat() / (beadCount - 1) - 0.5f
                            val cxB = t * (cuffW - beadR * 1.4f)
                            handFillPaint.color = if (showTopFace && bi == beadCount / 2) colAcc else colMain
                            canvas.drawCircle(cxB, 0f, if (showTopFace && bi == beadCount / 2) beadR * 1.15f else beadR, handFillPaint)
                        }
                    }
                    HandBraceletStyle.HOLO_WATCH -> {
                        handFillPaint.shader = null
                        handFillPaint.color = Color.argb(230, 15, 23, 42)
                        canvas.drawRoundRect(handTempRect, cuffH * 0.3f, cuffH * 0.3f, handFillPaint)
                        if (!showTopFace) {
                            // Underside metallic buckle clasp when palm faces camera
                            handTempRect.set(-cuffW * 0.16f, -cuffH * 0.34f, cuffW * 0.16f, cuffH * 0.34f)
                            handFillPaint.color = colMain
                            canvas.drawRoundRect(handTempRect, 4f, 4f, handFillPaint)
                        } else {
                            val dialR = cuffH * 0.92f
                            handFillPaint.color = Color.argb(240, 2, 6, 23)
                            canvas.drawCircle(0f, 0f, dialR, handFillPaint)
                            handStrokePaint.strokeWidth = 3.5f
                            handStrokePaint.color = colAcc
                            canvas.drawCircle(0f, 0f, dialR, handStrokePaint)
                            handStrokePaint.strokeWidth = 2.5f
                            handStrokePaint.color = Color.WHITE
                            canvas.drawLine(0f, 0f, Math.cos(timeSec * 2.8).toFloat() * dialR * 0.68f, Math.sin(timeSec * 2.8).toFloat() * dialR * 0.68f, handStrokePaint)
                        }
                    }
                    HandBraceletStyle.NONE -> {}
                }
                canvas.restore()
            }

            // 3. Virtual Finger Ring (Palm-aware: hides protruding diamond on palm-side)
            if (handRingStyle != HandRingStyle.NONE) {
                val mcpIdx = handRingFingerIndex.coerceIn(5, 17)
                val mcp = pts[mcpIdx]
                val pip = pts[(mcpIdx + 1).coerceAtMost(20)]
                val rx = mcp.x + (pip.x - mcp.x) * 0.42f
                val ry = mcp.y + (pip.y - mcp.y) * 0.42f
                val boneDeg = Math.toDegrees(Math.atan2((pip.y - mcp.y).toDouble(), (pip.x - mcp.x).toDouble())).toFloat() + 90f
                val ringW = handScale * 0.165f
                val ringH = (ringW * (if (showTopFace) 0.36f else 0.27f)).coerceAtLeast(5.5f)
                val bandCol = parseHexWithAlpha(handRingColorHex, 1f, Color.parseColor("#FACC15"))
                val gemCol = parseHexWithAlpha(handRingGemColorHex, 1f, Color.CYAN)

                canvas.save()
                canvas.translate(rx, ry)
                canvas.rotate(boneDeg)
                // Contact shadow beneath ring band
                handTempRect.set(-ringW * 0.56f, -ringH * 0.28f, ringW * 0.56f, ringH * 0.68f)
                canvas.drawOval(handTempRect, handShadowPaint)

                handFillPaint.shader = LinearGradient(-ringW * 0.55f, 0f, ringW * 0.55f, 0f, intArrayOf(bandCol, Color.WHITE, bandCol), null, Shader.TileMode.CLAMP)
                handTempRect.set(-ringW * 0.55f, -ringH * 0.5f, ringW * 0.55f, ringH * 0.5f)
                canvas.drawRoundRect(handTempRect, ringH * 0.45f, ringH * 0.45f, handFillPaint)
                handFillPaint.shader = null

                if (showTopFace) {
                    when (handRingStyle) {
                        HandRingStyle.DIAMOND_SOLITAIRE -> {
                            val gemR = ringW * 0.34f
                            handFillPaint.color = gemCol
                            canvas.drawCircle(0f, -ringH * 0.35f, gemR, handFillPaint)
                            handFillPaint.color = Color.WHITE
                            canvas.drawCircle(-gemR * 0.2f, -ringH * 0.45f, gemR * 0.36f, handFillPaint)
                        }
                        HandRingStyle.CYBER_BAND -> {
                            handStrokePaint.strokeWidth = 3f
                            handStrokePaint.color = gemCol
                            canvas.drawLine(-ringW * 0.46f, 0f, ringW * 0.46f, 0f, handStrokePaint)
                        }
                        HandRingStyle.EMERALD_SIGNET -> {
                            val w = ringW * 0.5f
                            val h = ringH * 1.4f
                            handTempRect.set(-w * 0.5f, -h * 0.5f, w * 0.5f, h * 0.5f)
                            handFillPaint.color = gemCol
                            canvas.drawRoundRect(handTempRect, 4f, 4f, handFillPaint)
                        }
                        HandRingStyle.ROYAL_GOLD -> {
                            handFillPaint.color = gemCol
                            canvas.drawCircle(0f, 0f, ringH * 0.25f, handFillPaint)
                        }
                        HandRingStyle.NONE -> {}
                    }
                }
                canvas.restore()
            }

            // 4. Hand-Attached 3D Energy / Hologram / Custom GLB Prop (with live Pinch-to-Scale)
            if (handPropType != HandPropType.NONE) {
                val anchorX: Float
                val anchorY: Float
                when (handPropAnchor) {
                    HandAnchorPoint.INDEX_TIP -> { anchorX = pts[8].x; anchorY = pts[8].y }
                    HandAnchorPoint.WRIST -> { anchorX = pts[0].x; anchorY = pts[0].y }
                    HandAnchorPoint.RING_FINGER -> { anchorX = (pts[13].x + pts[14].x) * 0.5f; anchorY = (pts[13].y + pts[14].y) * 0.5f }
                    HandAnchorPoint.PALM -> { anchorX = palmX; anchorY = palmY - handScale * 0.08f }
                }
                val gestureBoost = when (hand.gesture) {
                    "OPEN_PALM" -> 1.2f
                    "FIST" -> 0.82f
                    else -> 1.0f
                }
                val pinchBoost = if (handPinchScaleEnabled) {
                    val pinchDist = Math.hypot((pts[4].x - pts[8].x).toDouble(), (pts[4].y - pts[8].y).toDouble()).toFloat()
                    (0.52f + (pinchDist / handScale.coerceAtLeast(36f)) * 0.62f).coerceIn(0.62f, 1.42f)
                } else {
                    1.0f
                }
                val r = handScale * 0.42f * gestureBoost * pinchBoost
                val pCol = parseHexWithAlpha(handPropColorHex, 0.92f, Color.CYAN)
                val aCol = parseHexWithAlpha(handPropAccentHex, 0.85f, Color.MAGENTA)

                when (handPropType) {
                    HandPropType.ENERGY_ORB -> {
                        handFillPaint.shader = RadialGradient(anchorX, anchorY, r * 1.45f, intArrayOf(Color.WHITE, pCol, Color.TRANSPARENT), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
                        canvas.drawCircle(anchorX, anchorY, r * 1.45f, handFillPaint)
                        handFillPaint.shader = null
                        handStrokePaint.strokeWidth = 3.5f
                        for (ri in 0 until 3) {
                            canvas.save()
                            canvas.translate(anchorX, anchorY)
                            canvas.rotate((timeSec * (85f + ri * 35f)) * (if (ri % 2 == 0) 1f else -1f) + ri * 60f)
                            handStrokePaint.color = if (ri % 2 == 0) pCol else aCol
                            handTempRect.set(-r * 1.15f, -r * 0.42f, r * 1.15f, r * 0.42f)
                            canvas.drawOval(handTempRect, handStrokePaint)
                            canvas.restore()
                        }
                    }
                    HandPropType.HOLO_SHIELD -> {
                        val shieldR = r * 1.45f
                        handFillPaint.shader = RadialGradient(anchorX, anchorY, shieldR, intArrayOf(parseHexWithAlpha(handPropAccentHex, 0.35f, Color.CYAN), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                        canvas.drawCircle(anchorX, anchorY, shieldR, handFillPaint)
                        handFillPaint.shader = null
                        handStrokePaint.strokeWidth = 4f
                        handStrokePaint.color = pCol
                        canvas.save()
                        canvas.translate(anchorX, anchorY)
                        canvas.rotate(timeSec * 45f)
                        handTempPath.reset()
                        for (k in 0 until 6) {
                            val a = Math.toRadians(k * 60.0)
                            val hx = (Math.cos(a) * shieldR).toFloat()
                            val hy = (Math.sin(a) * shieldR).toFloat()
                            if (k == 0) handTempPath.moveTo(hx, hy) else handTempPath.lineTo(hx, hy)
                        }
                        handTempPath.close()
                        canvas.drawPath(handTempPath, handStrokePaint)
                        handStrokePaint.strokeWidth = 2.5f
                        handStrokePaint.color = aCol
                        canvas.drawCircle(0f, 0f, shieldR * 0.65f, handStrokePaint)
                        canvas.restore()
                    }
                    HandPropType.MAGIC_FLAME -> {
                        val fh = r * 1.8f
                        val fw = r * 0.9f
                        val flick = (Math.sin(timeSec * 11.0) * fw * 0.1).toFloat()
                        handTempPath.reset()
                        handTempPath.moveTo(anchorX, anchorY + fw * 0.2f)
                        handTempPath.cubicTo(anchorX - fw * 0.7f, anchorY - fh * 0.12f, anchorX - fw * 0.45f, anchorY - fh * 0.65f, anchorX + flick, anchorY - fh)
                        handTempPath.cubicTo(anchorX + fw * 0.45f, anchorY - fh * 0.65f, anchorX + fw * 0.7f, anchorY - fh * 0.12f, anchorX, anchorY + fw * 0.2f)
                        handTempPath.close()
                        handFillPaint.shader = LinearGradient(anchorX, anchorY, anchorX, anchorY - fh, intArrayOf(pCol, aCol, Color.WHITE), null, Shader.TileMode.CLAMP)
                        canvas.drawPath(handTempPath, handFillPaint)
                        handFillPaint.shader = null
                    }
                    HandPropType.CYBER_GAUNTLET -> {
                        handTempPath.reset()
                        handTempPath.moveTo(pts[0].x, pts[0].y)
                        handTempPath.lineTo(pts[5].x, pts[5].y)
                        handTempPath.lineTo(pts[9].x, pts[9].y)
                        handTempPath.lineTo(pts[13].x, pts[13].y)
                        handTempPath.lineTo(pts[17].x, pts[17].y)
                        handTempPath.close()
                        handFillPaint.shader = null
                        handFillPaint.color = parseHexWithAlpha(handPropColorHex, 0.25f, Color.CYAN)
                        canvas.drawPath(handTempPath, handFillPaint)
                        handStrokePaint.strokeWidth = 3.5f
                        handStrokePaint.color = pCol
                        canvas.drawPath(handTempPath, handStrokePaint)
                        handStrokePaint.strokeWidth = 2.5f
                        handStrokePaint.color = aCol
                        for (tip in intArrayOf(8, 12, 16, 20)) {
                            canvas.drawLine(palmX, palmY, pts[tip].x, pts[tip].y, handStrokePaint)
                        }
                        handFillPaint.color = Color.WHITE
                        canvas.drawCircle(palmX, palmY, r * 0.35f, handFillPaint)
                    }
                    HandPropType.SPARKLE_WAND -> {
                        handStrokePaint.strokeWidth = 3.5f
                        handStrokePaint.color = pCol
                        canvas.drawLine(anchorX - r, anchorY, anchorX + r, anchorY, handStrokePaint)
                        canvas.drawLine(anchorX, anchorY - r, anchorX, anchorY + r, handStrokePaint)
                        handFillPaint.shader = null
                        handFillPaint.color = Color.WHITE
                        canvas.drawCircle(anchorX, anchorY, r * 0.28f, handFillPaint)
                    }
                    HandPropType.CUSTOM_GLB -> {
                        val engine = glbEngine
                        if (hIdx == 0 && engine != null && engine.hasModelLoaded()) {
                            val handRollDeg = Math.toDegrees(
                                Math.atan2((pts[9].y - pts[0].y).toDouble(), (pts[9].x - pts[0].x).toDouble())
                            ).toFloat() + 90f
                            val maxEdge = 720f
                            val downScale = minOf(1f, maxEdge / maxOf(viewW, viewH).coerceAtLeast(1f))
                            val renderW = (viewW * downScale).toInt().coerceIn(128, 720)
                            val renderH = (viewH * downScale).toInt().coerceIn(128, 720)
                            val adjAnchorY = (anchorY + handGlbOffsetY * viewH * 0.45f).coerceIn(viewH * 0.05f, viewH * 0.95f)
                            val pose = GlbArModelEngine.GlbPose(
                                normAnchorX = (anchorX / viewW).coerceIn(0.05f, 0.95f),
                                normAnchorY = (adjAnchorY / viewH).coerceIn(0.05f, 0.95f),
                                normFaceWidth = ((handScale * 1.15f * gestureBoost * pinchBoost) / viewW).coerceIn(0.08f, 1.25f),
                                modelScale = (activeGlbModelScale * handGlbScale).coerceIn(0.35f, 3.0f),
                                yawDeg = activeGlbRotY + (timeSec * 42f),
                                pitchDeg = activeGlbRotX,
                                rollDeg = -handRollDeg.coerceIn(-75f, 75f),
                                enableHeadOcclusion = false,
                                normFaceCenterX = (anchorX / viewW).coerceIn(0.05f, 0.95f),
                                normFaceCenterY = (adjAnchorY / viewH).coerceIn(0.05f, 0.95f),
                                normFaceHeight = ((handScale * 1.15f) / viewH).coerceIn(0.08f, 1.25f)
                            )
                            val bmp = if (syncGlb) {
                                engine.renderSync(renderW, renderH, pose)
                            } else {
                                if (nowMs - lastHandGlbRenderMs >= 32L) {
                                    lastHandGlbRenderMs = nowMs
                                    engine.renderAsync(renderW, renderH, pose) { rendered ->
                                        latestHandGlbBitmap = rendered
                                        postInvalidateOnAnimation()
                                    }
                                }
                                latestHandGlbBitmap ?: engine.renderSync(renderW, renderH, pose)?.also { latestHandGlbBitmap = it }
                            }
                            if (bmp != null && !bmp.isRecycled) {
                                handTempRect.set(0f, 0f, viewW, viewH)
                                canvas.drawBitmap(bmp, null, handTempRect, null)
                            }
                        } else {
                            // Holographic 3D cube placeholder when no .glb is loaded yet
                            val s = r * 0.72f
                            val ox = Math.cos(timeSec * 1.4).toFloat() * s * 0.3f
                            val oy = Math.sin(timeSec * 1.4).toFloat() * s * 0.22f
                            handStrokePaint.strokeWidth = 3f
                            handStrokePaint.color = pCol
                            handTempRect.set(anchorX - s * 0.5f - ox * 0.5f, anchorY - s * 0.5f - oy * 0.5f, anchorX + s * 0.5f - ox * 0.5f, anchorY + s * 0.5f - oy * 0.5f)
                            canvas.drawRect(handTempRect, handStrokePaint)
                            handTempRect.set(anchorX - s * 0.5f + ox * 0.5f, anchorY - s * 0.5f + oy * 0.5f, anchorX + s * 0.5f + ox * 0.5f, anchorY + s * 0.5f + oy * 0.5f)
                            canvas.drawRect(handTempRect, handStrokePaint)
                        }
                    }
                    HandPropType.NONE -> {}
                }
            }

            // 5. Gesture Dwell Confirmation Ring (0.35s anti-accidental trigger feedback)
            if (hIdx == 0 && handGestureDwellProgress > 0.04f) {
                val dwellR = (handScale * 0.26f).coerceAtLeast(26f)
                handTempRect.set(palmX - dwellR, palmY - dwellR, palmX + dwellR, palmY + dwellR)
                handStrokePaint.strokeWidth = 7f
                handStrokePaint.color = Color.argb(175, 2, 6, 23)
                canvas.drawOval(handTempRect, handStrokePaint)
                handStrokePaint.strokeWidth = 5.5f
                handStrokePaint.color = if (handGestureDwellProgress >= 0.9f) Color.parseColor("#34D399") else Color.parseColor("#FACC15")
                canvas.drawArc(handTempRect, -90f, 360f * handGestureDwellProgress, false, handStrokePaint)
                if (handGestureDwellLabel.isNotEmpty()) {
                    canvas.drawText(handGestureDwellLabel, palmX, palmY + dwellR + 28f, handPillTextPaint)
                }
            }
        }

        // 6. Dual-Hand Plasma Energy Arc connecting both palms when 2 hands are tracked
        if (renderedHands >= 2 && handDualArcEnabled) {
            val pA = handPalmPool[0]
            val pB = handPalmPool[1]
            val dist = Math.hypot((pB.x - pA.x).toDouble(), (pB.y - pA.y).toDouble()).toFloat()
            if (dist > 30f) {
                val proximity = (420f / dist.coerceAtLeast(90f)).coerceIn(0.45f, 1.55f)
                val pCol = parseHexWithAlpha(handPropColorHex, 0.85f, Color.CYAN)
                val aCol = parseHexWithAlpha(handPropAccentHex, 0.92f, Color.MAGENTA)
                val nx = -(pB.y - pA.y) / dist
                val ny = (pB.x - pA.x) / dist

                handStrokePaint.strokeWidth = 9f * proximity
                handStrokePaint.color = pCol
                canvas.drawLine(pA.x, pA.y, pB.x, pB.y, handStrokePaint)

                val segments = 12
                for (strand in 0..1) {
                    handTempPath.reset()
                    for (si in 0..segments) {
                        val t = si.toFloat() / segments
                        val env = Math.sin(t * Math.PI).toFloat()
                        val jitter = (Math.sin(t * 22.0 + timeSec * (15.0 + strand * 6.0) + strand * 2.0) * 18.0 * env * proximity).toFloat()
                        val px = pA.x + (pB.x - pA.x) * t + nx * jitter
                        val py = pA.y + (pB.y - pA.y) * t + ny * jitter
                        if (si == 0) handTempPath.moveTo(px, py) else handTempPath.lineTo(px, py)
                    }
                    handStrokePaint.strokeWidth = if (strand == 0) 3.5f * proximity else 2.5f * proximity
                    handStrokePaint.color = if (strand == 0) Color.WHITE else aCol
                    canvas.drawPath(handTempPath, handStrokePaint)
                }

                val midX = (pA.x + pB.x) * 0.5f
                val midY = (pA.y + pB.y) * 0.5f
                val orbR = (20f + 6f * Math.sin(timeSec * 8.0).toFloat()) * proximity
                handFillPaint.shader = RadialGradient(midX, midY, orbR * 2.1f, intArrayOf(Color.WHITE, pCol, aCol, Color.TRANSPARENT), floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
                canvas.drawCircle(midX, midY, orbR * 2.1f, handFillPaint)
                handFillPaint.shader = null
            }
        }
        postInvalidateOnAnimation()
    }

    /**
     * Phase 3: Body AR Renderer (33-point full-body skeleton, virtual wings/cape/pauldrons/backpack,
     * body-attached kinetic aura/trails/suit FX, and MediaPipe hardware segmentation backdrops).
     */
    private fun drawBodyArLayer(
        canvas: Canvas,
        viewW: Float,
        viewH: Float,
        nowMs: Long,
        syncGlb: Boolean = false
    ) {
        if (!bodyArEnabled || viewW <= 0f || viewH <= 0f) return
        val rawLm = trackedBodyLandmarks
        val hasLivePose = rawLm.size >= 33 && (nowMs - lastBodyUpdateMs) <= 1200L
        if (!hasLivePose) {
            // Keep Avengers Snap-to-Dust animation running smoothly even if subject vanishes or steps out
            if (bodySegmentationMode == BodySegmentationMode.SNAP_WIPEOUT && snapPhase != SnapWipeoutPhase.ARMED) {
                drawSnapWipeoutEffect(
                    canvas, viewW, viewH, nowMs,
                    snapFrozenOffsetX, snapFrozenOffsetY,
                    snapFrozenScaledW.coerceAtLeast(viewW), snapFrozenScaledH.coerceAtLeast(viewH),
                    viewW * 0.22f, viewW * 0.78f,
                    snapFrozenBodyPath
                )
                postInvalidateOnAnimation()
            }
            return
        }

        // Map normalized MediaPipe pose landmarks using FILL_CENTER projection matching PreviewView
        val imgW = bodyInputWidth.toFloat().coerceAtLeast(1f)
        val imgH = bodyInputHeight.toFloat().coerceAtLeast(1f)
        val scale = Math.max(viewW / imgW, viewH / imgH)
        val scaledW = imgW * scale
        val scaledH = imgH * scale
        val offsetX = (viewW - scaledW) * 0.5f
        val offsetY = (viewH - scaledH) * 0.5f

        val pts = bodyPointPool
        for (i in 0 until 33) {
            val nx = if (bodyIsFrontCamera) (1f - rawLm[i].x) else rawLm[i].x
            pts[i].set(nx * scaledW + offsetX, rawLm[i].y * scaledH + offsetY)
        }

        val timeSec = nowMs / 1000.0
        val lSh = pts[11]
        val rSh = pts[12]
        val lHip = pts[23]
        val rHip = pts[24]
        val nose = pts[0]
        val shoulderMidX = (lSh.x + rSh.x) * 0.5f
        val shoulderMidY = (lSh.y + rSh.y) * 0.5f
        val hipMidX = (lHip.x + rHip.x) * 0.5f
        val hipMidY = (lHip.y + rHip.y) * 0.5f
        val torsoCx = (shoulderMidX + hipMidX) * 0.5f
        val torsoCy = (shoulderMidY + hipMidY) * 0.5f
        val shoulderW = Math.hypot((rSh.x - lSh.x).toDouble(), (rSh.y - lSh.y).toDouble()).toFloat().coerceAtLeast(viewW * 0.14f)
        val torsoH = Math.hypot((hipMidX - shoulderMidX).toDouble(), (hipMidY - shoulderMidY).toDouble()).toFloat().coerceAtLeast(shoulderW * 1.1f)
        val leftX = Math.min(lSh.x, rSh.x)
        val rightX = Math.max(lSh.x, rSh.x)

        // Build anatomical body silhouette path for outline glow & fallback mask cutout
        bodyTempPath.reset()
        val headR = shoulderW * 0.34f
        bodyTempPath.addOval(nose.x - headR, nose.y - headR * 1.2f, nose.x + headR, nose.y + headR * 0.95f, Path.Direction.CW)
        bodyTempPath.moveTo(leftX - shoulderW * 0.18f, shoulderMidY)
        bodyTempPath.lineTo(rightX + shoulderW * 0.18f, shoulderMidY)
        bodyTempPath.lineTo(Math.max(lHip.x, rHip.x) + shoulderW * 0.14f, hipMidY)
        bodyTempPath.lineTo(Math.max(pts[27].x, pts[28].x) + shoulderW * 0.18f, Math.max(pts[27].y, pts[28].y) + shoulderW * 0.12f)
        bodyTempPath.lineTo(Math.min(pts[27].x, pts[28].x) - shoulderW * 0.18f, Math.max(pts[27].y, pts[28].y) + shoulderW * 0.12f)
        bodyTempPath.lineTo(Math.min(lHip.x, rHip.x) - shoulderW * 0.14f, hipMidY)
        bodyTempPath.close()

        // Cache projection & silhouette for seamless Snap Wipeout continuation
        snapFrozenOffsetX = offsetX
        snapFrozenOffsetY = offsetY
        snapFrozenScaledW = scaledW
        snapFrozenScaledH = scaledH
        snapFrozenBodyPath.set(bodyTempPath)

        // 1. Full-Body Segmentation Backdrops & Neon Edge Outline
        if (bodySegmentationMode == BodySegmentationMode.SNAP_WIPEOUT) {
            drawSnapWipeoutEffect(
                canvas, viewW, viewH, nowMs,
                offsetX, offsetY, scaledW, scaledH,
                (leftX - shoulderW * 0.25f).coerceAtLeast(0f),
                (rightX + shoulderW * 0.25f).coerceAtMost(viewW),
                bodyTempPath
            )
        } else if (bodySegmentationMode != BodySegmentationMode.NONE) {
            val segCol = parseHexWithAlpha(bodySegColorHex, 0.92f, Color.CYAN)
            if (bodySegmentationMode == BodySegmentationMode.NEON_OUTLINE) {
                bodyStrokePaint.shader = null
                bodyStrokePaint.strokeWidth = 14f
                bodyStrokePaint.color = parseHexWithAlpha(bodySegColorHex, 0.28f, Color.CYAN)
                canvas.drawPath(bodyTempPath, bodyStrokePaint)
                bodyStrokePaint.strokeWidth = 5f
                bodyStrokePaint.color = segCol
                canvas.drawPath(bodyTempPath, bodyStrokePaint)
            } else {
                // Draw virtual backdrop in an offscreen layer and punch out the person via hardware MediaPipe mask (or silhouette fallback)
                val saveLayer = canvas.saveLayer(0f, 0f, viewW, viewH, null)
                when (bodySegmentationMode) {
                    BodySegmentationMode.STUDIO_BLUR -> {
                        bodyFillPaint.shader = RadialGradient(
                            torsoCx, torsoCy, Math.max(viewW, viewH) * 0.72f,
                            intArrayOf(Color.argb(65, 15, 23, 42), Color.argb(195, 2, 6, 23)),
                            null, Shader.TileMode.CLAMP
                        )
                        canvas.drawRect(0f, 0f, viewW, viewH, bodyFillPaint)
                        bodyFillPaint.shader = null
                    }
                    BodySegmentationMode.CYBER_GRID_BG -> {
                        bodyFillPaint.shader = LinearGradient(
                            0f, 0f, 0f, viewH,
                            intArrayOf(Color.argb(225, 4, 8, 28), Color.argb(225, 24, 10, 56), Color.argb(235, 6, 18, 42)),
                            floatArrayOf(0f, 0.56f, 1f), Shader.TileMode.CLAMP
                        )
                        canvas.drawRect(0f, 0f, viewW, viewH, bodyFillPaint)
                        bodyFillPaint.shader = null
                        val horizonY = viewH * 0.58f
                        bodyStrokePaint.strokeWidth = 2.2f
                        bodyStrokePaint.color = parseHexWithAlpha(bodySegColorHex, 0.55f, Color.CYAN)
                        for (g in -5..5) {
                            canvas.drawLine(viewW * 0.5f + g * viewW * 0.05f, horizonY, viewW * 0.5f + g * viewW * 0.22f, viewH, bodyStrokePaint)
                        }
                        val scroll = ((timeSec * 0.35) % 1.0).toFloat()
                        for (r in 0..6) {
                            val t = (r + scroll) / 7f
                            val gy = horizonY + t * t * (viewH - horizonY)
                            canvas.drawLine(0f, gy, viewW, gy, bodyStrokePaint)
                        }
                    }
                    BodySegmentationMode.COSMIC_VOID -> {
                        bodyFillPaint.shader = RadialGradient(
                            viewW * 0.5f, viewH * 0.35f, Math.max(viewW, viewH) * 0.78f,
                            intArrayOf(Color.argb(215, 59, 7, 100), Color.argb(235, 12, 10, 36), Color.argb(245, 2, 6, 23)),
                            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
                        )
                        canvas.drawRect(0f, 0f, viewW, viewH, bodyFillPaint)
                        bodyFillPaint.shader = null
                        bodyFillPaint.color = Color.argb(210, 248, 250, 252)
                        for (s in 0 until 32) {
                            val sx = (((s * 97 + 19) % 100) / 100f) * viewW
                            val sy = (((s * 53 + 11) % 100) / 100f) * viewH
                            val sr = 1.6f + (s % 3) * 1.1f
                            canvas.drawCircle(sx, sy, sr, bodyFillPaint)
                        }
                    }
                    BodySegmentationMode.CHROMA_GREEN -> {
                        bodyFillPaint.shader = null
                        bodyFillPaint.color = Color.argb(235, 0, 177, 64)
                        canvas.drawRect(0f, 0f, viewW, viewH, bodyFillPaint)
                    }
                    else -> {}
                }

                // Punch out human foreground using real MediaPipe segmentation bitmap when available
                val segBmp = trackedBodySegMask
                if (segBmp != null && !segBmp.isRecycled) {
                    canvas.save()
                    if (bodyIsFrontCamera) {
                        canvas.scale(-1f, 1f, viewW * 0.5f, viewH * 0.5f)
                    }
                    bodyTempRect.set(offsetX, offsetY, offsetX + scaledW, offsetY + scaledH)
                    canvas.drawBitmap(segBmp, null, bodyTempRect, bodyMaskCutoutPaint)
                    canvas.restore()
                } else {
                    canvas.drawPath(bodyTempPath, bodyMaskCutoutPaint)
                }
                canvas.restoreToCount(saveLayer)

                // Tweak 3: Feathered ambient rim light around segmented silhouette for natural backdrop blend
                if (bodySegRimLightEnabled) {
                    if (bodySegmentationMode == BodySegmentationMode.CHROMA_GREEN) {
                        bodyStrokePaint.shader = null
                        bodyStrokePaint.strokeWidth = 6f
                        bodyStrokePaint.color = Color.argb(65, 0, 177, 64)
                        canvas.drawPath(bodyTempPath, bodyStrokePaint)
                    } else {
                        val rimHex = if (bodySegmentationMode == BodySegmentationMode.STUDIO_BLUR) "#FDE68A" else bodySegColorHex
                        bodyStrokePaint.shader = null
                        bodyStrokePaint.strokeWidth = 12f
                        bodyStrokePaint.color = parseHexWithAlpha(rimHex, 0.24f, Color.CYAN)
                        canvas.drawPath(bodyTempPath, bodyStrokePaint)
                        bodyStrokePaint.strokeWidth = 3f
                        bodyStrokePaint.color = Color.argb(135, 255, 255, 255)
                        canvas.drawPath(bodyTempPath, bodyStrokePaint)
                    }
                }
            }
        }

        // 2. Virtual Body Accessories (Spring-Damped Pose-Reactive Wings, Shockwave, Neon Cape, Armor, Jetpack & Custom 3D GLB)
        val targetSpread = when (trackedBodyStance) {
            "ARMS_RAISED" -> 1.32f
            "T_POSE" -> 1.24f
            "POWER_STANCE" -> 1.14f
            else -> 1.0f
        }
        smoothedBodySpread += (targetSpread - smoothedBodySpread) * 0.14f
        if (trackedBodyStance != lastBodyStanceForWave && (trackedBodyStance == "ARMS_RAISED" || trackedBodyStance == "T_POSE")) {
            bodyShockwaveStartMs = nowMs
        }
        lastBodyStanceForWave = trackedBodyStance

        // Tweak 1: Expanding energy shockwave ring when transitioning into ARMS_RAISED or T_POSE
        val waveElapsedMs = nowMs - bodyShockwaveStartMs
        if (bodyShockwaveStartMs > 0L && waveElapsedMs in 0L..700L) {
            val p = waveElapsedMs.toFloat() / 700f
            val easeOut = 1f - (1f - p) * (1f - p)
            val ringR = shoulderW * (0.35f + easeOut * 1.95f)
            bodyStrokePaint.shader = null
            bodyStrokePaint.strokeWidth = ((1f - p) * 10f).coerceAtLeast(2f)
            bodyStrokePaint.color = parseHexWithAlpha(bodyAccessoryPrimaryHex, (1f - p) * 0.85f, Color.CYAN)
            canvas.drawCircle(shoulderMidX, shoulderMidY + torsoH * 0.18f, ringR, bodyStrokePaint)
            bodyStrokePaint.strokeWidth = ((1f - p) * 5f).coerceAtLeast(1.5f)
            bodyStrokePaint.color = parseHexWithAlpha(bodyAccessoryAccentHex, (1f - p) * 0.65f, Color.MAGENTA)
            canvas.drawCircle(shoulderMidX, shoulderMidY + torsoH * 0.18f, ringR * 0.78f, bodyStrokePaint)
        }

        if (bodyAccessoryStyle != BodyAccessoryStyle.NONE) {
            val accP = parseHexWithAlpha(bodyAccessoryPrimaryHex, 0.90f, Color.CYAN)
            val accA = parseHexWithAlpha(bodyAccessoryAccentHex, 0.88f, Color.MAGENTA)
            val stanceBoost = smoothedBodySpread
            val flapFreq = 3.4 * (1.0 + (stanceBoost - 1.0) * 1.35)
            val flap = (Math.sin(timeSec * flapFreq) * (0.08 + (stanceBoost - 1.0) * 0.12)).toFloat()

            when (bodyAccessoryStyle) {
                BodyAccessoryStyle.CYBER_WINGS, BodyAccessoryStyle.ANGEL_WINGS -> {
                    val isAngel = bodyAccessoryStyle == BodyAccessoryStyle.ANGEL_WINGS
                    val wingSpan = shoulderW * 1.62f * stanceBoost
                    val wingH = torsoH * 1.05f * stanceBoost
                    for (side in intArrayOf(-1, 1)) {
                        val rootX = shoulderMidX + side * shoulderW * 0.22f
                        val rootY = shoulderMidY + torsoH * 0.08f
                        canvas.save()
                        canvas.translate(rootX, rootY)
                        canvas.rotate(side * (-14f + flap * 95f))

                        val feathers = if (isAngel) 5 else 4
                        for (f in 0 until feathers) {
                            val ratio = 1f - f * 0.14f
                            val tipX = side * wingSpan * ratio
                            val tipY = -wingH * (0.48f - f * 0.22f)
                            bodyTempPath.reset()
                            bodyTempPath.moveTo(0f, f * 14f)
                            bodyTempPath.quadTo(tipX * 0.55f, tipY - 26f, tipX, tipY)
                            bodyTempPath.quadTo(tipX * 0.55f, tipY + 26f, 0f, f * 14f + 22f)
                            bodyTempPath.close()

                            bodyFillPaint.shader = LinearGradient(
                                0f, 0f, tipX, tipY,
                                intArrayOf(if (isAngel) Color.argb(200, 255, 251, 235) else accP, accA, Color.argb(40, 255, 255, 255)),
                                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
                            )
                            canvas.drawPath(bodyTempPath, bodyFillPaint)
                            bodyFillPaint.shader = null
                            bodyStrokePaint.strokeWidth = if (isAngel) 2.5f else 3.2f
                            bodyStrokePaint.color = if (f % 2 == 0) accP else accA
                            canvas.drawPath(bodyTempPath, bodyStrokePaint)
                        }
                        canvas.restore()
                    }
                }
                BodyAccessoryStyle.NEON_CAPE -> {
                    val sway = (Math.sin(timeSec * 2.8) * shoulderW * 0.16f).toFloat()
                    val capeBottomY = hipMidY + torsoH * 0.88f
                    bodyTempPath.reset()
                    bodyTempPath.moveTo(leftX - shoulderW * 0.08f, shoulderMidY)
                    bodyTempPath.lineTo(rightX + shoulderW * 0.08f, shoulderMidY)
                    bodyTempPath.quadTo(rightX + shoulderW * 0.42f + sway, hipMidY + torsoH * 0.25f, rightX + shoulderW * 0.62f + sway, capeBottomY)
                    bodyTempPath.quadTo(torsoCx + sway * 0.5f, capeBottomY + torsoH * 0.14f, leftX - shoulderW * 0.62f + sway, capeBottomY)
                    bodyTempPath.quadTo(leftX - shoulderW * 0.42f + sway, hipMidY + torsoH * 0.25f, leftX - shoulderW * 0.08f, shoulderMidY)
                    bodyTempPath.close()
                    bodyFillPaint.shader = LinearGradient(
                        torsoCx, shoulderMidY, torsoCx, capeBottomY,
                        intArrayOf(parseHexWithAlpha(bodyAccessoryPrimaryHex, 0.72f, Color.RED), parseHexWithAlpha(bodyAccessoryAccentHex, 0.52f, Color.YELLOW)),
                        null, Shader.TileMode.CLAMP
                    )
                    canvas.drawPath(bodyTempPath, bodyFillPaint)
                    bodyFillPaint.shader = null
                    bodyStrokePaint.strokeWidth = 4f
                    bodyStrokePaint.color = accA
                    canvas.drawPath(bodyTempPath, bodyStrokePaint)
                }
                BodyAccessoryStyle.SHOULDER_PAULDRON -> {
                    val padR = shoulderW * 0.30f
                    for (sh in arrayOf(lSh, rSh)) {
                        bodyTempRect.set(sh.x - padR, sh.y - padR * 0.68f, sh.x + padR, sh.y + padR * 0.52f)
                        bodyFillPaint.shader = LinearGradient(sh.x, sh.y - padR, sh.x, sh.y + padR, intArrayOf(accP, Color.argb(210, 15, 23, 42)), null, Shader.TileMode.CLAMP)
                        canvas.drawRoundRect(bodyTempRect, padR * 0.45f, padR * 0.45f, bodyFillPaint)
                        bodyFillPaint.shader = null
                        bodyStrokePaint.strokeWidth = 3.5f
                        bodyStrokePaint.color = accA
                        canvas.drawRoundRect(bodyTempRect, padR * 0.45f, padR * 0.45f, bodyStrokePaint)
                    }
                    // Chest Arc Reactor Core
                    val coreX = shoulderMidX
                    val coreY = shoulderMidY + torsoH * 0.26f
                    val coreR = shoulderW * 0.14f
                    bodyFillPaint.shader = RadialGradient(coreX, coreY, coreR * 1.9f, intArrayOf(Color.WHITE, accP, Color.TRANSPARENT), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
                    canvas.drawCircle(coreX, coreY, coreR * 1.9f, bodyFillPaint)
                    bodyFillPaint.shader = null
                    bodyStrokePaint.strokeWidth = 3f
                    bodyStrokePaint.color = accA
                    canvas.drawCircle(coreX, coreY, coreR, bodyStrokePaint)
                }
                BodyAccessoryStyle.HOLO_BACKPACK -> {
                    val packW = shoulderW * 0.88f
                    val packH = torsoH * 0.62f
                    bodyTempRect.set(shoulderMidX - packW * 0.5f, shoulderMidY + torsoH * 0.04f, shoulderMidX + packW * 0.5f, shoulderMidY + packH)
                    bodyFillPaint.shader = null
                    bodyFillPaint.color = Color.argb(175, 15, 23, 42)
                    canvas.drawRoundRect(bodyTempRect, 18f, 18f, bodyFillPaint)
                    bodyStrokePaint.strokeWidth = 3.5f
                    bodyStrokePaint.color = accP
                    canvas.drawRoundRect(bodyTempRect, 18f, 18f, bodyStrokePaint)
                    // Dual plasma thruster plumes
                    val plumeLen = (torsoH * 0.42f * stanceBoost * (0.85f + 0.15f * Math.sin(timeSec * 14.0).toFloat()))
                    for (side in intArrayOf(-1, 1)) {
                        val tx = shoulderMidX + side * packW * 0.28f
                        val ty = shoulderMidY + packH
                        bodyTempPath.reset()
                        bodyTempPath.moveTo(tx - 12f, ty)
                        bodyTempPath.lineTo(tx + 12f, ty)
                        bodyTempPath.lineTo(tx, ty + plumeLen)
                        bodyTempPath.close()
                        bodyFillPaint.shader = LinearGradient(tx, ty, tx, ty + plumeLen, intArrayOf(Color.WHITE, accP, accA, Color.TRANSPARENT), floatArrayOf(0f, 0.35f, 0.72f, 1f), Shader.TileMode.CLAMP)
                        canvas.drawPath(bodyTempPath, bodyFillPaint)
                        bodyFillPaint.shader = null
                    }
                }
                BodyAccessoryStyle.CUSTOM_GLB -> {
                    // Custom 3D .glb Model attached to chosen Body anchor (upper_back / chest / shoulders / halo)
                    val anchorX = shoulderMidX
                    val baseAnchorY = when (bodyGlbAnchor.lowercase()) {
                        "chest" -> shoulderMidY + torsoH * 0.34f
                        "shoulders" -> shoulderMidY
                        "halo" -> shoulderMidY - torsoH * 0.42f
                        else -> shoulderMidY + torsoH * 0.16f
                    }
                    val anchorY = (baseAnchorY + bodyGlbOffsetY * viewH * 0.45f).coerceIn(viewH * 0.05f, viewH * 0.95f)
                    val engine = glbEngine
                    if (engine != null && engine.hasModelLoaded()) {
                        val torsoRollDeg = Math.toDegrees(
                            Math.atan2((rSh.y - lSh.y).toDouble(), (rSh.x - lSh.x).toDouble())
                        ).toFloat()
                        val maxEdge = 720f
                        val downScale = minOf(1f, maxEdge / maxOf(viewW, viewH).coerceAtLeast(1f))
                        val renderW = (viewW * downScale).toInt().coerceIn(128, 720)
                        val renderH = (viewH * downScale).toInt().coerceIn(128, 720)
                        val pose = GlbArModelEngine.GlbPose(
                            normAnchorX = (anchorX / viewW).coerceIn(0.05f, 0.95f),
                            normAnchorY = (anchorY / viewH).coerceIn(0.05f, 0.95f),
                            normFaceWidth = ((shoulderW * 1.48f * stanceBoost) / viewW).coerceIn(0.10f, 1.35f),
                            modelScale = (activeGlbModelScale * bodyGlbScale).coerceIn(0.35f, 3.0f),
                            yawDeg = activeGlbRotY + (timeSec.toFloat() * 28f),
                            pitchDeg = activeGlbRotX,
                            rollDeg = -torsoRollDeg.coerceIn(-45f, 45f),
                            enableHeadOcclusion = false,
                            normFaceCenterX = (anchorX / viewW).coerceIn(0.05f, 0.95f),
                            normFaceCenterY = (anchorY / viewH).coerceIn(0.05f, 0.95f),
                            normFaceHeight = ((torsoH * 1.15f) / viewH).coerceIn(0.10f, 1.35f)
                        )
                        val bmp = if (syncGlb) {
                            engine.renderSync(renderW, renderH, pose)
                        } else {
                            if (nowMs - lastBodyGlbRenderMs >= 32L) {
                                lastBodyGlbRenderMs = nowMs
                                engine.renderAsync(renderW, renderH, pose) { rendered ->
                                    latestBodyGlbBitmap = rendered
                                    postInvalidateOnAnimation()
                                }
                            }
                            latestBodyGlbBitmap ?: engine.renderSync(renderW, renderH, pose)?.also { latestBodyGlbBitmap = it }
                        }
                        if (bmp != null && !bmp.isRecycled) {
                            bodyTempRect.set(0f, 0f, viewW, viewH)
                            canvas.drawBitmap(bmp, null, bodyTempRect, null)
                        }
                    } else {
                        // Holographic 3D back-rig wireframe placeholder when no .glb model is loaded
                        val s = shoulderW * 0.85f * stanceBoost
                        val ox = Math.cos(timeSec * 1.3).toFloat() * s * 0.24f
                        val oy = Math.sin(timeSec * 1.3).toFloat() * s * 0.18f
                        bodyStrokePaint.strokeWidth = 3.2f
                        bodyStrokePaint.color = accP
                        bodyTempRect.set(anchorX - s * 0.5f - ox * 0.5f, anchorY - s * 0.5f - oy * 0.5f, anchorX + s * 0.5f - ox * 0.5f, anchorY + s * 0.5f - oy * 0.5f)
                        canvas.drawRect(bodyTempRect, bodyStrokePaint)
                        bodyStrokePaint.color = accA
                        bodyTempRect.set(anchorX - s * 0.5f + ox * 0.5f, anchorY - s * 0.5f + oy * 0.5f, anchorX + s * 0.5f + ox * 0.5f, anchorY + s * 0.5f + oy * 0.5f)
                        canvas.drawRect(bodyTempRect, bodyStrokePaint)
                    }
                }
                BodyAccessoryStyle.NONE -> {}
            }
        }

        // 3. Body-Attached Kinetic Effects
        if (bodyEffectType != BodyEffectType.NONE) {
            val fxP = parseHexWithAlpha(bodyEffectPrimaryHex, 0.85f, Color.CYAN)
            val fxA = parseHexWithAlpha(bodyEffectAccentHex, 0.85f, Color.MAGENTA)
            when (bodyEffectType) {
                BodyEffectType.NEON_AURA -> {
                    val pulse = 1f + 0.08f * Math.sin(timeSec * 4.5).toFloat()
                    bodyStrokePaint.strokeWidth = 14f * pulse
                    bodyStrokePaint.color = parseHexWithAlpha(bodyEffectPrimaryHex, 0.24f, Color.CYAN)
                    canvas.drawPath(bodyTempPath, bodyStrokePaint)
                    bodyStrokePaint.strokeWidth = 4.5f
                    bodyStrokePaint.color = fxA
                    canvas.drawPath(bodyTempPath, bodyStrokePaint)
                }
                BodyEffectType.ELECTRIC_TRAILS -> {
                    val arms = arrayOf(intArrayOf(11, 13, 15), intArrayOf(12, 14, 16))
                    for (arm in arms) {
                        for (seg in 0..1) {
                            val a = pts[arm[seg]]
                            val b = pts[arm[seg + 1]]
                            bodyTempPath.reset()
                            val steps = 6
                            for (k in 0..steps) {
                                val t = k.toFloat() / steps
                                val j = if (k == 0 || k == steps) 0f else (Math.sin(t * 19.0 + timeSec * 22.0 + seg * 3.0) * 16.0).toFloat()
                                val px = a.x + (b.x - a.x) * t + j
                                val py = a.y + (b.y - a.y) * t - j * 0.5f
                                if (k == 0) bodyTempPath.moveTo(px, py) else bodyTempPath.lineTo(px, py)
                            }
                            bodyStrokePaint.strokeWidth = 6f
                            bodyStrokePaint.color = fxP
                            canvas.drawPath(bodyTempPath, bodyStrokePaint)
                            bodyStrokePaint.strokeWidth = 2.5f
                            bodyStrokePaint.color = Color.WHITE
                            canvas.drawPath(bodyTempPath, bodyStrokePaint)
                        }
                    }
                }
                BodyEffectType.FIRE_AURA -> {
                    for (i in 0 until 8) {
                        val phase = ((timeSec * 1.4 + i * 0.19) % 1.0).toFloat()
                        val fx = torsoCx + Math.sin(i * 1.7 + timeSec * 2.5).toFloat() * shoulderW * 0.65f
                        val fy = hipMidY - phase * torsoH * 1.35f
                        val fr = (1f - phase) * shoulderW * 0.26f + 6f
                        bodyFillPaint.shader = RadialGradient(fx, fy, fr, intArrayOf(Color.WHITE, fxA, fxP, Color.TRANSPARENT), floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
                        canvas.drawCircle(fx, fy, fr, bodyFillPaint)
                        bodyFillPaint.shader = null
                    }
                }
                BodyEffectType.CYBER_GRID_SUIT -> {
                    bodyStrokePaint.strokeWidth = 2.4f
                    bodyStrokePaint.color = fxP
                    for (row in 0..4) {
                        val t = row / 4f
                        val y = shoulderMidY + (hipMidY - shoulderMidY) * t
                        val lx = lSh.x + (lHip.x - lSh.x) * t
                        val rx = rSh.x + (rHip.x - rSh.x) * t
                        canvas.drawLine(lx, y, rx, y, bodyStrokePaint)
                    }
                    for (col in 0..3) {
                        val t = col / 3f
                        val sx = lSh.x + (rSh.x - lSh.x) * t
                        val sy = lSh.y + (rSh.y - lSh.y) * t
                        val hx = lHip.x + (rHip.x - lHip.x) * t
                        val hy = lHip.y + (rHip.y - lHip.y) * t
                        canvas.drawLine(sx, sy, hx, hy, bodyStrokePaint)
                    }
                }
                BodyEffectType.STARDUST_HALO -> {
                    for (s in 0 until 14) {
                        val ang = timeSec * 1.4 + s * (Math.PI * 2.0 / 14.0)
                        val ox = torsoCx + Math.cos(ang).toFloat() * shoulderW * 1.05f
                        val oy = torsoCy + Math.sin(ang).toFloat() * torsoH * 0.68f
                        val sr = 4f + 3f * Math.sin(timeSec * 5.0 + s).toFloat()
                        bodyFillPaint.shader = null
                        bodyFillPaint.color = if (s % 2 == 0) fxP else fxA
                        canvas.drawCircle(ox, oy, sr, bodyFillPaint)
                    }
                }
                BodyEffectType.NONE -> {}
            }

            // Tweak 2: Zero-GC Arm-Velocity Kinetic Motion Ribbons trailing behind left (#15) & right (#16) wrists
            if (bodyArmRibbonsEnabled) {
                if (nowMs - lastBodyTrailSampleMs > 480L) {
                    bodyTrailCount = 0
                    bodyTrailHead = 0
                }
                if (nowMs - lastBodyTrailSampleMs >= 16L) {
                    bodyLeftWristTrail[bodyTrailHead].set(pts[15].x, pts[15].y)
                    bodyRightWristTrail[bodyTrailHead].set(pts[16].x, pts[16].y)
                    bodyTrailHead = (bodyTrailHead + 1) % bodyLeftWristTrail.size
                    if (bodyTrailCount < bodyLeftWristTrail.size) bodyTrailCount++
                    lastBodyTrailSampleMs = nowMs
                }
                if (bodyTrailCount >= 3) {
                    val len = bodyLeftWristTrail.size
                    for (trail in arrayOf(bodyLeftWristTrail, bodyRightWristTrail)) {
                        for (i in 1 until bodyTrailCount) {
                            val idxA = (bodyTrailHead - bodyTrailCount + i - 1 + len * 2) % len
                            val idxB = (bodyTrailHead - bodyTrailCount + i + len * 2) % len
                            val a = trail[idxA]
                            val b = trail[idxB]
                            val segSpeed = Math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
                            val frac = i.toFloat() / bodyTrailCount.toFloat()
                            val velBoost = (0.75f + segSpeed * 0.05f).coerceIn(0.75f, 2.2f)
                            bodyStrokePaint.strokeWidth = (frac * 9f * velBoost).coerceAtLeast(2f)
                            bodyStrokePaint.color = if (i % 2 == 0) parseHexWithAlpha(bodyEffectPrimaryHex, frac * 0.78f, Color.CYAN) else parseHexWithAlpha(bodyEffectAccentHex, frac * 0.72f, Color.MAGENTA)
                            canvas.drawLine(a.x, a.y, b.x, b.y, bodyStrokePaint)
                        }
                    }
                }
            }
        }

        // 4. 33-Point Skeleton Telemetry Overlay + Stance Badge
        if (bodyShowSkeleton) {
            bodyStrokePaint.strokeWidth = 3.5f
            bodyStrokePaint.color = Color.parseColor("#38BDF8")
            for (link in bodySkeletonLinks) {
                val pA = pts[link[0]]
                val pB = pts[link[1]]
                canvas.drawLine(pA.x, pA.y, pB.x, pB.y, bodyStrokePaint)
            }
            bodyFillPaint.shader = null
            for (idx in intArrayOf(0, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)) {
                bodyFillPaint.color = if (idx == 11 || idx == 12 || idx == 23 || idx == 24) Color.parseColor("#F472B6") else Color.parseColor("#22D3EE")
                canvas.drawCircle(pts[idx].x, pts[idx].y, 6.5f, bodyFillPaint)
            }
            val label = "33-PT BODY · ${trackedBodyStance.replace("_", " ")}"
            val pillW = handPillTextPaint.measureText(label) + 34f
            val pillY = (nose.y - shoulderW * 0.72f).coerceAtLeast(64f)
            bodyTempRect.set(nose.x - pillW * 0.5f, pillY - 22f, nose.x + pillW * 0.5f, pillY + 14f)
            canvas.drawRoundRect(bodyTempRect, 14f, 14f, handPillBgPaint)
            canvas.drawText(label, nose.x, pillY + 4f, handPillTextPaint)
        }

        // 5. Tweak 4: Hands-Free Full-Body Pose Dwell Confirmation Ring (0.55s anti-accidental trigger)
        if (bodyPoseDwellProgress > 0.04f) {
            val dwellCx = shoulderMidX
            val dwellCy = shoulderMidY + torsoH * 0.22f
            val dwellR = (shoulderW * 0.30f).coerceAtLeast(32f)
            bodyTempRect.set(dwellCx - dwellR, dwellCy - dwellR, dwellCx + dwellR, dwellCy + dwellR)
            bodyStrokePaint.strokeWidth = 8f
            bodyStrokePaint.color = Color.argb(180, 2, 6, 23)
            canvas.drawOval(bodyTempRect, bodyStrokePaint)
            bodyStrokePaint.strokeWidth = 6f
            bodyStrokePaint.color = if (bodyPoseDwellProgress >= 0.9f) Color.parseColor("#34D399") else Color.parseColor("#38BDF8")
            canvas.drawArc(bodyTempRect, -90f, 360f * bodyPoseDwellProgress, false, bodyStrokePaint)
            if (bodyPoseDwellLabel.isNotEmpty()) {
                canvas.drawText(bodyPoseDwellLabel, dwellCx, dwellCy + dwellR + 30f, handPillTextPaint)
            }
        }

        postInvalidateOnAnimation()
    }

    /**
     * Avengers Endgame Snap-to-Dust Wipeout VFX Renderer:
     * Erases the person progressively using real MediaPipe segmentation mask + Clean Background Plate
     * (or auto-inpainted fallback) while 420 RGB-sampled ash flakes & embers drift slowly in curl wind.
     */
    private fun drawSnapWipeoutEffect(
        canvas: Canvas,
        viewW: Float,
        viewH: Float,
        nowMs: Long,
        offsetX: Float,
        offsetY: Float,
        scaledW: Float,
        scaledH: Float,
        bodyMinX: Float,
        bodyMaxX: Float,
        silhouettePath: Path
    ) {
        val dt = if (snapLastFrameMs > 0L) {
            ((nowMs - snapLastFrameMs).toFloat() / 1000f).coerceIn(0.008f, 0.06f)
        } else 0.016f
        snapLastFrameMs = nowMs

        val disintegrateMs = 5400f
        val progress: Float = when (snapPhase) {
            SnapWipeoutPhase.ARMED -> 0f
            SnapWipeoutPhase.VANISHED -> 1f
            SnapWipeoutPhase.DISINTEGRATING -> {
                val p = ((nowMs - snapPhaseStartMs).toFloat() / disintegrateMs).coerceIn(0f, 1f)
                if (p >= 1f) snapPhase = SnapWipeoutPhase.VANISHED
                p
            }
        }

        // 1. Subtle golden armed reticle around person before the one-time Snap fires
        if (snapPhase == SnapWipeoutPhase.ARMED) {
            snapEdgePaint.strokeWidth = 3f
            snapEdgePaint.color = Color.argb(95, 245, 158, 11)
            canvas.drawPath(silhouettePath, snapEdgePaint)
            return
        }

        // 2. Soft FEATHERED organic wind erosion into Clean Background Plate (NO laser-cut edge or bright cut line!)
        val spanX = (bodyMaxX - bodyMinX).coerceAtLeast(viewW * 0.28f)
        val featherBand = (spanX * 0.42f).coerceAtLeast(viewW * 0.14f)
        val travelSpan = spanX + featherBand * 1.35f
        val frontX = if (snapWindDir > 0f) {
            bodyMaxX + featherBand * 0.55f - travelSpan * progress
        } else {
            bodyMinX - featherBand * 0.55f + travelSpan * progress
        }

        if (progress > 0.005f) {
            val bgPlate = snapCleanPlateBmp?.takeIf { !it.isRecycled } ?: snapAutoInpaintBmp?.takeIf { !it.isRecycled }
            val segBmp = trackedBodySegMask?.takeIf { !it.isRecycled } ?: snapFrozenSegMask?.takeIf { !it.isRecycled }
            val isFront = if (trackedBodySegMask != null) bodyIsFrontCamera else snapFrozenFrontCamera

            // Layer 1: Smooth bilinear-filtered room background masked by soft feathered organic alpha wipe
            val saveCount = canvas.saveLayer(0f, 0f, viewW, viewH, null)
            if (bgPlate != null) {
                bodyTempRect.set(0f, 0f, viewW, viewH)
                canvas.drawBitmap(bgPlate, null, bodyTempRect, snapBgDrawPaint)
            } else {
                bodyFillPaint.shader = null
                bodyFillPaint.color = Color.argb(235, 15, 23, 42)
                canvas.drawRect(0f, 0f, viewW, viewH, bodyFillPaint)
            }

            // Apply wide soft feathered gradient mask (and organic radial flaking pockets) when mid-dissolve
            if (progress < 0.985f) {
                val maskLayer = canvas.saveLayer(0f, 0f, viewW, viewH, snapMaskInPaint)
                val halfBand = featherBand * 0.55f
                val gradX0 = if (snapWindDir > 0f) frontX + halfBand else frontX - halfBand
                val gradX1 = if (snapWindDir > 0f) frontX - halfBand else frontX + halfBand
                snapFeatherGradPaint.xfermode = null
                snapFeatherGradPaint.shader = LinearGradient(
                    gradX0, 0f, gradX1, 0f,
                    intArrayOf(
                        Color.argb(255, 255, 255, 255),
                        Color.argb(210, 255, 255, 255),
                        Color.argb(85, 255, 255, 255),
                        Color.argb(0, 255, 255, 255)
                    ),
                    floatArrayOf(0f, 0.34f, 0.68f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, viewW, viewH, snapFeatherGradPaint)

                // Organic feathered ash-erosion pockets breaking up the dissolve front naturally
                for (k in 0 until 26) {
                    val h1 = Math.sin(k * 12.9898 + 78.233) * 43758.5453
                    val randY = (h1 - Math.floor(h1)).toFloat()
                    val h2 = Math.sin(k * 93.9898 + 19.19) * 24634.6345
                    val randOff = ((h2 - Math.floor(h2)).toFloat()) * 2f - 1f
                    val h3 = Math.sin(k * 41.23 + 7.7) * 19283.4
                    val randR = (h3 - Math.floor(h3)).toFloat()

                    val py = randY * viewH
                    val wobble = Math.sin(randY * 14.0 + progress * 4.5).toFloat() * halfBand * 0.35f
                    val px = frontX + randOff * halfBand * 0.92f + wobble
                    val radius = (22f + randR * 44f).coerceAtLeast(12f)
                    val isBite = (k % 2 == 0)
                    val pPaint = if (isBite) snapPocketAddPaint else snapPocketCutPaint
                    pPaint.shader = RadialGradient(
                        px, py, radius,
                        intArrayOf(
                            Color.argb(if (isBite) 170 else 155, 255, 255, 255),
                            Color.argb(if (isBite) 70 else 55, 255, 255, 255),
                            Color.argb(0, 255, 255, 255)
                        ),
                        floatArrayOf(0f, 0.55f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(px, py, radius, pPaint)
                }
                canvas.restoreToCount(maskLayer)
            }

            // Mask to feathered live person segmentation mask or soft-blurred body silhouette
            if (segBmp != null) {
                canvas.save()
                if (isFront) {
                    canvas.scale(-1f, 1f, viewW * 0.5f, viewH * 0.5f)
                }
                bodyTempRect.set(offsetX, offsetY, offsetX + scaledW, offsetY + scaledH)
                canvas.drawBitmap(segBmp, null, bodyTempRect, snapMaskInPaint)
                canvas.restore()
            } else {
                canvas.drawPath(silhouettePath, snapFeatherSilPaint)
            }
            canvas.restoreToCount(saveCount)

            // Soft organic ash-charred flaking edge specks inside the feathered band (zero hard line!)
            if (progress in 0.03f..0.94f) {
                canvas.save()
                canvas.clipPath(silhouettePath)
                for (s in 0 until 52) {
                    val hs1 = Math.sin(s * 27.1 + 3.1) * 18421.7
                    val ry = (hs1 - Math.floor(hs1)).toFloat()
                    val hs2 = Math.sin(s * 61.7 + 9.4) * 31921.3
                    val rx = ((hs2 - Math.floor(hs2)).toFloat()) * 2f - 1f
                    val sx = frontX + rx * featherBand * 0.44f
                    val sy = ry * viewH
                    val speckR = 2.6f + ((s * 7) % 6) * 1.15f
                    val fade = ((1f - Math.abs(rx)).coerceAtLeast(0f) * 105f).toInt().coerceIn(0, 255)
                    snapParticlePaint.shader = null
                    snapParticlePaint.color = if (s % 6 == 0) {
                        Color.argb((fade * 0.85f).toInt(), 218, 138, 52)
                    } else {
                        Color.argb(fade, 48, 44, 40)
                    }
                    canvas.drawCircle(sx, sy, speckR, snapParticlePaint)
                }
                canvas.restore()
            }
        }

        // 3. Simulate & render 3-layer volumetric ash smoke, jagged tumbling ash ribbons & warm embers
        if (snapDustParticles.isNotEmpty()) {
            val timeSec = nowMs * 0.001
            val densityBoost = (0.76f + snapDustDensity.coerceIn(0.4f, 2.5f) * 0.34f).coerceIn(0.78f, 1.55f)
            val windMult = snapWindSpeed.coerceIn(0.3f, 3.0f)
            for (i in 0 until snapDustParticles.size) {
                val p = snapDustParticles[i]
                if (!p.detached && progress >= p.triggerAt) {
                    p.detached = true
                    p.x = p.originX
                    p.y = p.originY
                    p.life = 0.001f
                }
                if (p.detached && p.life < 1.05f) {
                    val gust = 1.0f + 0.42f * Math.sin(timeSec * 1.8 + p.seed).toFloat()
                    val curlY = Math.sin(timeSec * 2.5 + p.originX * 14.0 + p.seed).toFloat() * 0.052f * windMult
                    p.x += p.vx * windMult * gust * dt
                    p.y += (p.vy * windMult + curlY) * dt
                    p.rotation += p.rotSpeed * dt
                    p.life += dt * 0.22f
                }

                if (!p.detached || p.life >= 1.0f) continue
                val alphaF = if (p.life < 0.14f) p.life / 0.14f else ((1f - (p.life - 0.14f) / 0.86f).coerceIn(0f, 1f))
                val px = p.x * viewW
                val py = p.y * viewH
                snapParticlePaint.shader = null

                if (p.kind == 0) {
                    // Volumetric layered smoky ash wisp (outer soft haze + warm charcoal core)
                    val rOuter = p.size * (1.15f + p.life * 1.05f) * (0.85f + snapDustDensity * 0.14f)
                    val outerAlpha = (alphaF * 44f * densityBoost).toInt().coerceIn(0, 140)
                    snapParticlePaint.color = Color.argb(outerAlpha, p.r, p.g, p.b)
                    canvas.drawCircle(px, py, rOuter, snapParticlePaint)
                    val innerAlpha = (alphaF * 82f * densityBoost).toInt().coerceIn(0, 185)
                    snapParticlePaint.color = Color.argb(innerAlpha, (p.r * 0.82f).toInt(), (p.g * 0.80f).toInt(), (p.b * 0.78f).toInt())
                    canvas.drawCircle(px + snapWindDir * rOuter * 0.22f, py - rOuter * 0.12f, rOuter * 0.52f, snapParticlePaint)
                } else if (p.kind == 2) {
                    // Incandescent ember spark with subtle golden bloom
                    val sz = p.size * (1f - p.life * 0.45f)
                    val glowAlpha = (alphaF * 78f).toInt().coerceIn(0, 255)
                    snapParticlePaint.color = Color.argb(glowAlpha, 245, 158, 11)
                    canvas.drawCircle(px, py, sz * 2.1f, snapParticlePaint)
                    val coreAlpha = (alphaF * 235f).toInt().coerceIn(0, 255)
                    snapParticlePaint.color = Color.argb(coreAlpha, 254, 240, 138)
                    canvas.drawCircle(px, py, sz * 0.68f, snapParticlePaint)
                } else {
                    // Tapered irregular jagged ash shard darkening naturally into soot
                    val alphaInt = (alphaF * 195f).toInt().coerceIn(0, 255)
                    val sz = p.size * (1f - p.life * 0.38f)
                    val ashFade = (p.life * 0.75f).coerceAtMost(1f)
                    val rr = (p.r * (1f - ashFade * 0.48f) + 64f * ashFade * 0.48f).toInt().coerceIn(28, 255)
                    val gg = (p.g * (1f - ashFade * 0.48f) + 60f * ashFade * 0.48f).toInt().coerceIn(26, 255)
                    val bb = (p.b * (1f - ashFade * 0.48f) + 56f * ashFade * 0.48f).toInt().coerceIn(24, 255)
                    snapParticlePaint.color = Color.argb(alphaInt, rr, gg, bb)
                    canvas.save()
                    canvas.translate(px, py)
                    canvas.rotate(p.rotation)
                    snapFlakePath.reset()
                    snapFlakePath.moveTo(-sz * 1.35f, -sz * 0.15f)
                    snapFlakePath.lineTo(-sz * 0.25f, -sz * 0.62f)
                    snapFlakePath.lineTo(sz * 1.25f, -sz * 0.08f)
                    snapFlakePath.lineTo(sz * 0.45f, sz * 0.56f)
                    snapFlakePath.lineTo(-sz * 0.78f, sz * 0.40f)
                    snapFlakePath.close()
                    canvas.drawPath(snapFlakePath, snapParticlePaint)
                    canvas.restore()
                }
            }
        }

        // 4. Multi-stage 6-Infinity-Stone harmonic shockwave at finger-snap origin
        val waveElapsed = nowMs - snapShockwaveStartMs
        if (snapShockwaveStartMs > 0L && waveElapsed in 0L..900L) {
            val t = waveElapsed.toFloat() / 900f
            val ease = 1f - (1f - t) * (1f - t)
            val ox = snapOriginNormX * viewW
            val oy = snapOriginNormY * viewH
            val maxR = Math.max(viewW, viewH) * 0.65f

            if (t < 0.16f) {
                val flashAlpha = ((1f - t / 0.16f) * 58f).toInt().coerceIn(0, 255)
                snapParticlePaint.shader = null
                snapParticlePaint.color = Color.argb(flashAlpha, 254, 243, 199)
                canvas.drawRect(0f, 0f, viewW, viewH, snapParticlePaint)
            }

            val ringAlpha = ((1f - t) * 190f).toInt().coerceIn(0, 255)
            snapEdgePaint.strokeWidth = ((1f - t) * 10f).coerceAtLeast(1.8f)
            snapEdgePaint.color = Color.argb(ringAlpha, 250, 204, 21)
            canvas.drawCircle(ox, oy, 18f + maxR * ease, snapEdgePaint)

            if (t > 0.08f) {
                val t2 = ((t - 0.08f) / 0.92f).coerceIn(0f, 1f)
                val ease2 = 1f - (1f - t2) * (1f - t2)
                snapEdgePaint.strokeWidth = ((1f - t2) * 5.5f).coerceAtLeast(1.2f)
                snapEdgePaint.color = Color.argb(((1f - t2) * 125f).toInt().coerceIn(0, 255), 168, 85, 247)
                canvas.drawCircle(ox, oy, 12f + maxR * 0.82f * ease2, snapEdgePaint)
            }
        }
    }

    /**
     * Renders an articulated metallic Uru-gold 6-Stone Infinity Gauntlet directly onto MediaPipe 21-point
     * detected hand(s) during Snap & Wipeout mode, with reactive pinch-charge energy arcs & snap flare.
     */
    private fun drawInfinityGauntletLayer(
        canvas: Canvas,
        viewW: Float,
        viewH: Float,
        nowMs: Long,
        syncGlb: Boolean = false
    ) {
        val hands = trackedHands
        if (hands.isEmpty() || (nowMs - lastHandUpdateMs) >= 900L) {
            gauntletHasSmoothPts = false
            return
        }

        val timeSec = nowMs / 1000f
        val srcW = handInputWidth.toFloat().coerceAtLeast(1f)
        val srcH = handInputHeight.toFloat().coerceAtLeast(1f)
        val scale = Math.max(viewW / srcW, viewH / srcH)
        val scaledW = srcW * scale
        val scaledH = srcH * scale
        val dxOff = (viewW - scaledW) * 0.5f
        val dyOff = (viewH - scaledH) * 0.5f

        val minGate = snapMinNearHandScale.coerceIn(0.08f, 0.28f)
        // Strictly select ONLY the closest foreground hand inside the Near-Camera Distance Zone
        // so the Infinity Gauntlet NEVER jumps onto distant background people's hands!
        var bestHand: OverlayHandData? = null
        var bestScale = 0f
        for (hIdx in hands.indices) {
            val h = hands[hIdx]
            if (h.landmarks.size < 21) continue
            val lm0 = h.landmarks[0]
            val lm9 = h.landmarks[9]
            val lm5 = h.landmarks[5]
            val lm17 = h.landmarks[17]
            val normPalm = Math.hypot((lm9.x - lm0.x).toDouble(), (lm9.y - lm0.y).toDouble()).toFloat()
            val normKnuckle = Math.hypot((lm5.x - lm17.x).toDouble(), (lm5.y - lm17.y).toDouble()).toFloat()
            val appScale = Math.max(normPalm, normKnuckle * 1.18f)
            if (appScale >= minGate * 0.90f && appScale <= 0.65f && appScale > bestScale) {
                bestScale = appScale
                bestHand = h
            }
        }
        val hand = bestHand ?: run {
            gauntletHasSmoothPts = false
            return
        }

        // Smooth 21 landmarks with EMA to eliminate jitter while keeping crisp wrist/finger tracking
        val rawPts = handPointPool[0]
        for (i in 0 until 21) {
            val lm = hand.landmarks[i]
            val nx = if (handIsFrontCamera) (1f - lm.x) else lm.x
            rawPts[i].set(nx * scaledW + dxOff, lm.y * scaledH + dyOff)
        }
        val jumpDist = if (gauntletHasSmoothPts) {
            Math.hypot((rawPts[0].x - gauntletSmoothPts[0].x).toDouble(), (rawPts[0].y - gauntletSmoothPts[0].y).toDouble()).toFloat()
        } else 9999f
        if (!gauntletHasSmoothPts || (nowMs - gauntletLastTrackMs) > 280L || jumpDist > viewW * 0.30f) {
            for (i in 0 until 21) gauntletSmoothPts[i].set(rawPts[i].x, rawPts[i].y)
            gauntletHasSmoothPts = true
        } else {
            val alpha = 0.56f
            for (i in 0 until 21) {
                gauntletSmoothPts[i].x += (rawPts[i].x - gauntletSmoothPts[i].x) * alpha
                gauntletSmoothPts[i].y += (rawPts[i].y - gauntletSmoothPts[i].y) * alpha
            }
        }
        gauntletLastTrackMs = nowMs
        val pts = gauntletSmoothPts

        val snapElapsed = if (snapShockwaveStartMs > 0L) (nowMs - snapShockwaveStartMs) else -1L
        val snapFlare = if (snapElapsed in 0L..1050L) {
            Math.pow((1.0 - snapElapsed.toDouble() / 1050.0), 1.55).toFloat()
        } else 0f

        val stoneCoreColors = intArrayOf(
            Color.parseColor("#F5D0FE"), // Power
            Color.parseColor("#E0F2FE"), // Space
            Color.parseColor("#FECACA"), // Reality
            Color.parseColor("#FED7AA"), // Soul
            Color.parseColor("#A7F3D0"), // Time
            Color.parseColor("#FEF9C3")  // Mind
        )
        val stoneMidColors = intArrayOf(
            Color.parseColor("#9333EA"), // Power (Purple)
            Color.parseColor("#0EA5E9"), // Space (Blue)
            Color.parseColor("#DC2626"), // Reality (Red)
            Color.parseColor("#EA580C"), // Soul (Orange)
            Color.parseColor("#059669"), // Time (Emerald)
            Color.parseColor("#EAB308")  // Mind (Gold)
        )
        // Refined realistic Infinity Stone proportions (seated inside beveled sockets, never oversized cartoon balls)
        val stoneScaleFactors = floatArrayOf(0.052f, 0.054f, 0.051f, 0.048f, 0.050f, 0.088f)
        // Forged brushed Uru-gold metallic palettes (rich specular highlight + warm bronze depth)
        val uruDorsalColors = intArrayOf(
            Color.parseColor("#3E1F07"),
            Color.parseColor("#8C5515"),
            Color.parseColor("#D99B2A"),
            Color.parseColor("#FDE68A"),
            Color.parseColor("#B67518"),
            Color.parseColor("#472207")
        )
        val uruPalmColors = intArrayOf(
            Color.parseColor("#321805"),
            Color.parseColor("#734510"),
            Color.parseColor("#BA7F1B"),
            Color.parseColor("#E7B84A"),
            Color.parseColor("#8A5312"),
            Color.parseColor("#2E1504")
        )
        val uruStops = floatArrayOf(0f, 0.18f, 0.42f, 0.56f, 0.80f, 1f)

        val wrist = pts[0]
        val midMcp = pts[9]
        val handScale = Math.hypot((midMcp.x - wrist.x).toDouble(), (midMcp.y - wrist.y).toDouble()).toFloat().coerceAtLeast(52f)
        val palmCx = (pts[0].x * 1.05f + pts[5].x + pts[9].x * 1.15f + pts[13].x + pts[17].x) / 5.2f
        val palmCy = (pts[0].y * 1.05f + pts[5].y + pts[9].y * 1.15f + pts[13].y + pts[17].y) / 5.2f

        // If user loaded a custom Snap Magic 3D GLB artifact, render it anchored to the snapping hand
        if (snapGlbEnabled) {
            val engine = glbEngine
            if (engine != null && engine.hasModelLoaded()) {
                val snapAx = when (snapGlbAnchor.lowercase()) {
                    "wrist" -> wrist.x
                    else -> palmCx
                }
                val snapAyBase = when (snapGlbAnchor.lowercase()) {
                    "wrist" -> wrist.y
                    "palm" -> palmCy - handScale * 0.08f
                    else -> palmCy
                }
                val snapAy = (snapAyBase + snapGlbOffsetY * viewH * 0.45f).coerceIn(viewH * 0.05f, viewH * 0.95f)
                val handRollDeg = Math.toDegrees(
                    Math.atan2((midMcp.y - wrist.y).toDouble(), (midMcp.x - wrist.x).toDouble())
                ).toFloat() + 90f
                val maxEdge = 720f
                val downScale = minOf(1f, maxEdge / maxOf(viewW, viewH).coerceAtLeast(1f))
                val renderW = (viewW * downScale).toInt().coerceIn(128, 720)
                val renderH = (viewH * downScale).toInt().coerceIn(128, 720)
                val pose = GlbArModelEngine.GlbPose(
                    normAnchorX = (snapAx / viewW).coerceIn(0.05f, 0.95f),
                    normAnchorY = (snapAy / viewH).coerceIn(0.05f, 0.95f),
                    normFaceWidth = ((handScale * 1.32f * (1f + snapFlare * 0.25f)) / viewW).coerceIn(0.08f, 1.3f),
                    modelScale = (activeGlbModelScale * snapGlbScale).coerceIn(0.35f, 3.0f),
                    yawDeg = activeGlbRotY + (timeSec * 30f),
                    pitchDeg = activeGlbRotX,
                    rollDeg = -handRollDeg.coerceIn(-75f, 75f),
                    enableHeadOcclusion = false,
                    normFaceCenterX = (snapAx / viewW).coerceIn(0.05f, 0.95f),
                    normFaceCenterY = (snapAy / viewH).coerceIn(0.05f, 0.95f),
                    normFaceHeight = ((handScale * 1.32f) / viewH).coerceIn(0.08f, 1.3f)
                )
                val bmp = if (syncGlb) {
                    engine.renderSync(renderW, renderH, pose)?.also { latestSnapGlbBitmap = it } ?: latestSnapGlbBitmap
                } else {
                    if (nowMs - lastSnapGlbRenderMs >= 32L) {
                        lastSnapGlbRenderMs = nowMs
                        engine.renderAsync(renderW, renderH, pose) { rendered ->
                            latestSnapGlbBitmap = rendered
                            postInvalidateOnAnimation()
                        }
                    }
                    latestSnapGlbBitmap ?: engine.renderSync(renderW, renderH, pose)?.also { latestSnapGlbBitmap = it }
                }
                if (bmp != null && !bmp.isRecycled) {
                    handTempRect.set(0f, 0f, viewW, viewH)
                    canvas.drawBitmap(bmp, null, handTempRect, null)
                }
                return
            }
        }

        // Detect palm vs dorsal back-of-hand orientation
        val ax = pts[5].x - pts[0].x
        val ay = pts[5].y - pts[0].y
        val bx = pts[17].x - pts[0].x
        val by = pts[17].y - pts[0].y
        val cross = ax * by - ay * bx
        val isRightHand = hand.handedness.equals("Right", ignoreCase = true)
        val isBackOfHand = if (handIsFrontCamera) {
            if (isRightHand) cross < 0f else cross >= 0f
        } else {
            if (isRightHand) cross >= 0f else cross < 0f
        }
        val palette = if (isBackOfHand) uruDorsalColors else uruPalmColors

        // Compute live Pinch Charge + Near-Camera Proximity Surge
        val proximityCharge = ((bestScale - minGate * 0.85f) / (minGate * 0.55f)).coerceIn(0f, 1f)
        val proximityGain = 0.48f + proximityCharge * 0.72f
        val thumbMidRatio = Math.hypot((pts[4].x - pts[12].x).toDouble(), (pts[4].y - pts[12].y).toDouble()).toFloat() / handScale
        val thumbIdxRatio = Math.hypot((pts[4].x - pts[8].x).toDouble(), (pts[4].y - pts[8].y).toDouble()).toFloat() / handScale
        val minPinchRatio = Math.min(thumbMidRatio, thumbIdxRatio)
        val pinchCharge = ((0.60f - minPinchRatio) / 0.34f).coerceIn(0f, 1f)
        val totalPower = ((pinchCharge * 0.85f + proximityCharge * 0.30f) * proximityGain + snapFlare * 1.35f).coerceAtMost(1.65f)

        // 1. Seamless Anatomically Contoured Forearm Vambrace & Layered Wrist Collar
        run {
            val fx = wrist.x - midMcp.x
            val fy = wrist.y - midMcp.y
            val fLen = Math.hypot(fx.toDouble(), fy.toDouble()).toFloat().coerceAtLeast(1f)
            val ux = fx / fLen
            val uy = fy / fLen
            val deg = Math.toDegrees(Math.atan2(uy.toDouble(), ux.toDouble())).toFloat() - 90f
            val cuffLen = handScale * 0.64f
            val cuffTopW = handScale * 0.40f
            val cuffBotW = handScale * 0.48f

            canvas.save()
            canvas.translate(wrist.x - ux * handScale * 0.06f, wrist.y - uy * handScale * 0.06f)
            canvas.rotate(deg)

            // Subtle ambient drop shadow under forearm sleeve
            gauntletPath.reset()
            gauntletPath.moveTo(-cuffTopW, 0f)
            gauntletPath.quadTo(0f, -handScale * 0.08f, cuffTopW, 0f)
            gauntletPath.lineTo(cuffBotW, cuffLen * 0.86f)
            gauntletPath.quadTo(0f, cuffLen * 1.04f, -cuffBotW, cuffLen * 0.86f)
            gauntletPath.close()
            canvas.drawPath(gauntletPath, gauntletShadowPaint)

            // Brushed Uru-gold metallic forearm sleeve
            gauntletFillPaint.shader = LinearGradient(-cuffBotW, 0f, cuffBotW, 0f, palette, uruStops, Shader.TileMode.CLAMP)
            canvas.drawPath(gauntletPath, gauntletFillPaint)

            // Layered horizontal curved armor collar bands & recessed bronze seams
            for (band in 0 until 3) {
                val byPos = cuffLen * (0.18f + band * 0.25f)
                val bw = cuffTopW + (cuffBotW - cuffTopW) * (0.18f + band * 0.25f)
                gauntletPath.reset()
                gauntletPath.moveTo(-bw * 0.94f, byPos)
                gauntletPath.quadTo(0f, byPos + handScale * 0.09f, bw * 0.94f, byPos)
                gauntletStrokePaint.color = Color.argb(140, 46, 21, 4)
                gauntletStrokePaint.strokeWidth = 3.2f
                canvas.drawPath(gauntletPath, gauntletStrokePaint)
                gauntletStrokePaint.color = Color.argb(135, 254, 243, 199)
                gauntletStrokePaint.strokeWidth = 1.4f
                canvas.drawPath(gauntletPath, gauntletStrokePaint)
            }

            // Crisp metallic rim accent
            gauntletPath.reset()
            gauntletPath.moveTo(-cuffTopW, 0f)
            gauntletPath.quadTo(0f, -handScale * 0.08f, cuffTopW, 0f)
            gauntletPath.lineTo(cuffBotW, cuffLen * 0.86f)
            gauntletPath.quadTo(0f, cuffLen * 1.04f, -cuffBotW, cuffLen * 0.86f)
            gauntletPath.close()
            gauntletStrokePaint.color = Color.argb(135, 58, 28, 6)
            gauntletStrokePaint.strokeWidth = 1.8f
            canvas.drawPath(gauntletPath, gauntletStrokePaint)
            canvas.restore()
        }

        // 2. Sculpted Articulated Finger Gauntlet Sleeves with Overlapping Knuckle Hinges
        for (fIdx in gauntletFingerChains.indices) {
            val chain = gauntletFingerChains[fIdx]
            val baseW = when (fIdx) {
                0 -> handScale * 0.132f
                4 -> handScale * 0.092f
                else -> handScale * 0.108f
            }
            for (seg in 0 until chain.size - 1) {
                val a = pts[chain[seg]]
                val b = pts[chain[seg + 1]]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val segLen = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (segLen < 2f) continue
                val nx = -dy / segLen
                val ny = dx / segLen
                val ux = dx / segLen
                val uy = dy / segLen
                val pad = Math.min(6f, segLen * 0.18f)
                val axS = a.x - ux * pad
                val ayS = a.y - uy * pad
                val bxS = b.x + ux * pad
                val byS = b.y + uy * pad
                val wS = baseW * (1f - seg * 0.13f)
                val wE = baseW * (1f - (seg + 1) * 0.15f)
                val midX = (axS + bxS) * 0.5f
                val midY = (ayS + byS) * 0.5f

                // Rounded anatomically contoured armor plate wrapping each phalanx
                gauntletPath.reset()
                gauntletPath.moveTo(axS + nx * wS, ayS + ny * wS)
                gauntletPath.quadTo(midX + nx * ((wS + wE) * 0.54f), midY + ny * ((wS + wE) * 0.54f), bxS + nx * wE, byS + ny * wE)
                gauntletPath.quadTo(bxS + ux * (wE * 0.35f), byS + uy * (wE * 0.35f), bxS - nx * wE, byS - ny * wE)
                gauntletPath.quadTo(midX - nx * ((wS + wE) * 0.54f), midY - ny * ((wS + wE) * 0.54f), axS - nx * wS, ayS - ny * wS)
                gauntletPath.quadTo(axS - ux * (wS * 0.28f), ayS - uy * (wS * 0.28f), axS + nx * wS, ayS + ny * wS)
                gauntletPath.close()

                gauntletFillPaint.shader = LinearGradient(
                    axS - nx * wS, ayS - ny * wS,
                    axS + nx * wS, ayS + ny * wS,
                    palette, uruStops, Shader.TileMode.CLAMP
                )
                canvas.drawPath(gauntletPath, gauntletFillPaint)

                // Subtle specular center ridge down each finger armor segment
                gauntletStrokePaint.color = Color.argb(95, 254, 243, 199)
                gauntletStrokePaint.strokeWidth = (wE * 0.24f).coerceAtLeast(1.2f)
                canvas.drawLine(a.x, a.y, b.x, b.y, gauntletStrokePaint)

                // Recessed joint collar seam & subtle gold energy edge when charging
                gauntletStrokePaint.color = if (totalPower > 0.25f) {
                    Color.argb((75 + totalPower * 80).toInt().coerceIn(0, 210), 253, 224, 71)
                } else {
                    Color.argb(120, 56, 26, 6)
                }
                gauntletStrokePaint.strokeWidth = 1.4f
                canvas.drawPath(gauntletPath, gauntletStrokePaint)

                // Curved knuckle hinge guard over intermediate joints
                if (seg > 0) {
                    val jw = wS * 0.96f
                    gauntletPath.reset()
                    gauntletPath.moveTo(a.x - nx * jw, a.y - ny * jw)
                    gauntletPath.quadTo(a.x + ux * (wS * 0.45f), a.y + uy * (wS * 0.45f), a.x + nx * jw, a.y + ny * jw)
                    gauntletStrokePaint.color = Color.argb(150, 48, 22, 5)
                    gauntletStrokePaint.strokeWidth = 2.4f
                    canvas.drawPath(gauntletPath, gauntletStrokePaint)
                    gauntletStrokePaint.color = Color.argb(140, 254, 240, 138)
                    gauntletStrokePaint.strokeWidth = 1.1f
                    canvas.drawPath(gauntletPath, gauntletStrokePaint)
                }
            }

            // Sculpted rounded metallic fingertip cap
            val tip = pts[chain[chain.size - 1]]
            val capR = (baseW * 0.60f).coerceAtLeast(5f)
            gauntletFillPaint.shader = RadialGradient(
                tip.x - capR * 0.28f, tip.y - capR * 0.28f, capR * 1.15f,
                intArrayOf(Color.parseColor("#FEF9C3"), Color.parseColor("#D99B2A"), Color.parseColor("#6B3B0B")),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(tip.x, tip.y, capR, gauntletFillPaint)
        }

        // 3. Anatomically Contoured Layered Uru-Gold Dorsal / Palm Breastplate
        run {
            val hullIdx = intArrayOf(0, 1, 2, 5, 9, 13, 17)
            val hxArr = FloatArray(hullIdx.size)
            val hyArr = FloatArray(hullIdx.size)
            for (k in hullIdx.indices) {
                val idx = hullIdx[k]
                val p = pts[idx]
                val expand = when (idx) {
                    0 -> 1.06f
                    1, 2 -> 1.08f
                    else -> 1.16f
                }
                hxArr[k] = palmCx + (p.x - palmCx) * expand
                hyArr[k] = palmCy + (p.y - palmCy) * expand
            }
            // Build smooth closed quadratic spline around the hand contour (no sharp clipart polygon corners!)
            gauntletPath.reset()
            val nHull = hullIdx.size
            val startMx = (hxArr[nHull - 1] + hxArr[0]) * 0.5f
            val startMy = (hyArr[nHull - 1] + hyArr[0]) * 0.5f
            gauntletPath.moveTo(startMx, startMy)
            for (k in 0 until nHull) {
                val next = (k + 1) % nHull
                val mx = (hxArr[k] + hxArr[next]) * 0.5f
                val my = (hyArr[k] + hyArr[next]) * 0.5f
                gauntletPath.quadTo(hxArr[k], hyArr[k], mx, my)
            }
            gauntletPath.close()

            // Ambient contact shadow beneath main dorsal plate
            canvas.drawPath(gauntletPath, gauntletShadowPaint)

            // Main forged metallic gradient plate
            gauntletFillPaint.shader = LinearGradient(
                pts[5].x, pts[5].y, pts[17].x, pts[0].y,
                palette, uruStops, Shader.TileMode.CLAMP
            )
            canvas.drawPath(gauntletPath, gauntletFillPaint)
            gauntletStrokePaint.color = Color.argb(145, 52, 23, 5)
            gauntletStrokePaint.strokeWidth = 2.0f
            canvas.drawPath(gauntletPath, gauntletStrokePaint)

            // Raised inner beveled dorsal shield inset for layered 3D armor depth
            gauntletPath.reset()
            for (k in 0 until nHull) {
                val ix = palmCx + (hxArr[k] - palmCx) * 0.74f
                val iy = palmCy + (hyArr[k] - palmCy) * 0.74f
                val next = (k + 1) % nHull
                val ix2 = palmCx + (hxArr[next] - palmCx) * 0.74f
                val iy2 = palmCy + (hyArr[next] - palmCy) * 0.74f
                val mx = (ix + ix2) * 0.5f
                val my = (iy + iy2) * 0.5f
                if (k == 0) {
                    val lastX = palmCx + (hxArr[nHull - 1] - palmCx) * 0.74f
                    val lastY = palmCy + (hyArr[nHull - 1] - palmCy) * 0.74f
                    gauntletPath.moveTo((lastX + ix) * 0.5f, (lastY + iy) * 0.5f)
                }
                gauntletPath.quadTo(ix, iy, mx, my)
            }
            gauntletPath.close()
            gauntletStrokePaint.color = Color.argb(115, 254, 243, 199)
            gauntletStrokePaint.strokeWidth = 1.6f
            canvas.drawPath(gauntletPath, gauntletStrokePaint)

            // Sculpted curved knuckle armor ridge (#5 -> #9 -> #13 -> #17)
            gauntletPath.reset()
            gauntletPath.moveTo(pts[5].x, pts[5].y)
            gauntletPath.quadTo(pts[9].x, pts[9].y, pts[13].x, pts[13].y)
            gauntletPath.lineTo(pts[17].x, pts[17].y)
            gauntletStrokePaint.color = Color.argb(105, 58, 28, 6)
            gauntletStrokePaint.strokeWidth = (handScale * 0.064f).coerceAtLeast(5f)
            canvas.drawPath(gauntletPath, gauntletStrokePaint)
            gauntletStrokePaint.color = Color.argb(135, 254, 240, 138)
            gauntletStrokePaint.strokeWidth = (handScale * 0.024f).coerceAtLeast(2f)
            canvas.drawPath(gauntletPath, gauntletStrokePaint)
        }

        // Compute exact 6 Infinity Stone coordinates
        val stoneX = floatArrayOf(
            pts[5].x,
            pts[9].x,
            pts[13].x,
            pts[17].x,
            pts[2].x * 0.72f + pts[1].x * 0.28f,
            palmCx
        )
        val stoneY = floatArrayOf(
            pts[5].y,
            pts[9].y,
            pts[13].y,
            pts[17].y,
            pts[2].y * 0.72f + pts[1].y * 0.28f,
            palmCy
        )

        // 4. Recessed Filigree Uru Energy Veins radiating from Mind Stone to all 5 knuckle/thumb sockets
        for (sIdx in 0 until 5) {
            val mx = (palmCx + stoneX[sIdx]) * 0.5f
            val my = (palmCy + stoneY[sIdx]) * 0.5f
            val wob = Math.sin(timeSec * 7.5 + sIdx * 1.9).toFloat() * (if (totalPower > 0.20f) 4.5f * totalPower else 0.8f)
            gauntletPath.reset()
            gauntletPath.moveTo(palmCx, palmCy)
            gauntletPath.quadTo(mx + wob, my - wob, stoneX[sIdx], stoneY[sIdx])
            // Dark engraved metallic channel groove
            gauntletStrokePaint.color = Color.argb(125, 48, 22, 5)
            gauntletStrokePaint.strokeWidth = 3.0f
            canvas.drawPath(gauntletPath, gauntletStrokePaint)
            // Living stone energy pulse inside the groove
            if (totalPower > 0.14f) {
                gauntletStrokePaint.color = withAlpha(stoneMidColors[sIdx], ((totalPower * 155f).toInt()).coerceIn(35, 220))
                gauntletStrokePaint.strokeWidth = 1.6f + totalPower * 1.4f
                canvas.drawPath(gauntletPath, gauntletStrokePaint)
            }
        }

        // 5. Recessed Beveled Sockets + Polygonal Faceted Glowing Infinity Stones
        val stoneAlphaScale = if (isBackOfHand) 1f else 0.72f
        for (sIdx in 0 until 6) {
            val sx = stoneX[sIdx]
            val sy = stoneY[sIdx]
            val breathe = 0.90f + 0.12f * Math.sin(timeSec * 3.2 + sIdx * 1.15).toFloat()
            val baseR = (handScale * stoneScaleFactors[sIdx]).coerceAtLeast(5.5f)
            val flareBoost = 0.85f + proximityCharge * 0.35f + pinchCharge * 0.45f + snapFlare * 1.25f
            val glowR = (baseR * 2.95f * breathe * flareBoost).coerceAtLeast(10f)
            val midC = stoneMidColors[sIdx]
            val coreC = stoneCoreColors[sIdx]
            val isMind = (sIdx == 5)
            val rx = if (isMind) baseR * 1.05f else baseR
            val ry = if (isMind) baseR * 1.28f else baseR * 1.08f

            // Soft atmospheric crystal bloom aura
            gauntletFillPaint.shader = RadialGradient(
                sx, sy, glowR,
                intArrayOf(
                    withAlpha(coreC, (195f * stoneAlphaScale).toInt()),
                    withAlpha(midC, (145f * stoneAlphaScale).toInt()),
                    withAlpha(midC, (38f * stoneAlphaScale).toInt()),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.28f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(sx, sy, glowR, gauntletFillPaint)

            // Outer raised burnished gold bezel ring
            gauntletFillPaint.shader = LinearGradient(
                sx - rx * 1.3f, sy - ry * 1.3f, sx + rx * 1.3f, sy + ry * 1.3f,
                intArrayOf(Color.parseColor("#FDE68A"), Color.parseColor("#9A5B13"), Color.parseColor("#3B1604")),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            handTempRect.set(sx - rx * 1.28f, sy - ry * 1.28f, sx + rx * 1.28f, sy + ry * 1.28f)
            canvas.drawOval(handTempRect, gauntletFillPaint)

            // Recessed dark bronze cavity
            gauntletFillPaint.shader = null
            gauntletFillPaint.color = Color.parseColor("#2A1203")
            handTempRect.set(sx - rx * 1.06f, sy - ry * 1.06f, sx + rx * 1.06f, sy + ry * 1.06f)
            canvas.drawOval(handTempRect, gauntletFillPaint)

            // Faceted crystal gem body (multi-stop internal luminescence)
            gauntletFillPaint.shader = RadialGradient(
                sx - rx * 0.25f, sy - ry * 0.28f, Math.max(rx, ry) * 1.15f,
                intArrayOf(Color.WHITE, coreC, midC, Color.parseColor("#1E1B4B")),
                floatArrayOf(0f, 0.28f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            handTempRect.set(sx - rx * 0.92f, sy - ry * 0.92f, sx + rx * 0.92f, sy + ry * 0.92f)
            canvas.drawOval(handTempRect, gauntletFillPaint)

            // Internal crystal diamond facet lines
            gauntletFacetPath.reset()
            gauntletFacetPath.moveTo(sx, sy - ry * 0.76f)
            gauntletFacetPath.lineTo(sx + rx * 0.62f, sy)
            gauntletFacetPath.lineTo(sx, sy + ry * 0.76f)
            gauntletFacetPath.lineTo(sx - rx * 0.62f, sy)
            gauntletFacetPath.close()
            gauntletFillPaint.shader = null
            gauntletFillPaint.color = Color.argb(65, 255, 255, 255)
            canvas.drawPath(gauntletFacetPath, gauntletFillPaint)
            gauntletStrokePaint.color = Color.argb(120, 255, 255, 255)
            gauntletStrokePaint.strokeWidth = 1.0f
            canvas.drawPath(gauntletFacetPath, gauntletStrokePaint)

            // Crisp specular crystal highlight glint
            gauntletFillPaint.color = Color.argb(230, 255, 255, 255)
            canvas.drawCircle(sx - rx * 0.28f, sy - ry * 0.30f, baseR * 0.20f, gauntletFillPaint)

            // Starburst flare rays during finger-snap impact
            if (snapFlare > 0.08f) {
                gauntletStrokePaint.color = withAlpha(coreC, (snapFlare * 225f).toInt())
                gauntletStrokePaint.strokeWidth = 2.2f
                val rayLen = baseR * (2.4f + snapFlare * 3.5f)
                for (rIdx in 0 until 6) {
                    val ang = (rIdx.toDouble() / 6.0) * Math.PI * 2.0 + timeSec * 1.8 + sIdx
                    canvas.drawLine(
                        sx + Math.cos(ang).toFloat() * baseR * 0.6f,
                        sy + Math.sin(ang).toFloat() * baseR * 0.6f,
                        sx + Math.cos(ang).toFloat() * rayLen,
                        sy + Math.sin(ang).toFloat() * rayLen,
                        gauntletStrokePaint
                    )
                }
            }
        }

        // 6. Pinch-Armed Cosmic Energy Arcs & Spark between Thumb #4 & Middle #12
        if (pinchCharge > 0.22f || snapFlare > 0.1f) {
            val snapX = (pts[4].x + pts[12].x) * 0.5f
            val snapY = (pts[4].y + pts[12].y) * 0.5f
            val intensity = Math.max(pinchCharge, snapFlare)
            val arcCols = intArrayOf(
                Color.parseColor("#FACC15"),
                Color.parseColor("#A855F7"),
                Color.parseColor("#38BDF8"),
                Color.parseColor("#10B981")
            )
            for (aIdx in arcCols.indices) {
                val mx = (palmCx + snapX) * 0.5f + Math.sin(timeSec * 16.0 + aIdx * 2.1).toFloat() * 16f * intensity
                val my = (palmCy + snapY) * 0.5f + Math.cos(timeSec * 14.0 + aIdx * 1.7).toFloat() * 16f * intensity
                gauntletPath.reset()
                gauntletPath.moveTo(palmCx, palmCy)
                gauntletPath.quadTo(mx, my, snapX, snapY)
                gauntletStrokePaint.color = withAlpha(arcCols[aIdx], (intensity * 185f).toInt())
                gauntletStrokePaint.strokeWidth = 1.8f + intensity * 1.8f
                canvas.drawPath(gauntletPath, gauntletStrokePaint)
            }
            val sparkR = (14f + intensity * 26f).coerceAtLeast(10f)
            gauntletFillPaint.shader = RadialGradient(
                snapX, snapY, sparkR,
                intArrayOf(
                    Color.argb(245, 255, 255, 255),
                    Color.argb((210f * intensity).toInt(), 250, 204, 21),
                    Color.argb((80f * intensity).toInt(), 168, 85, 247),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.35f, 0.75f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(snapX, snapY, sparkR, gauntletFillPaint)
        }
        gauntletFillPaint.shader = null
    }

    private fun parseSafeColor(hex: String, fallback: Int): Int {
        return try {
            Color.parseColor(hex)
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun drawWorldArLayer(
        canvas: Canvas,
        viewW: Float,
        viewH: Float,
        nowMs: Long,
        syncGlb: Boolean
    ) {
        if (viewW <= 0f || viewH <= 0f) return
        val t = (nowMs % 100000L) / 1000f
        val isFloor = resolveWorldIsFloor()
        val gridCol = parseSafeColor(worldGridColorHex, Color.parseColor("#22D3EE"))
        val minDim = Math.min(viewW, viewH)

        // IMU parallax offsets from WorldArTracker
        val yawShiftX = (-worldYawDeltaDeg / 55f) * viewW * 0.45f
        val horizonY = (viewH * (0.46f - ((worldPitchDeg - 22f) / 90f) * 0.34f)).coerceIn(viewH * 0.16f, viewH * 0.66f)

        // 1. 6-DoF Perspective Plane Grid (Horizontal Floor/Table vs Vertical Wall)
        if (worldShowPlaneGrid) {
            canvas.save()
            val vanishX = viewW * 0.5f + yawShiftX * 0.45f
            worldStrokePaint.strokeWidth = 2.2f
            if (isFloor) {
                val planeTop = horizonY + viewH * 0.04f
                val planeBottom = viewH * 0.99f
                // Soft plane surface wash
                worldFillPaint.shader = LinearGradient(
                    0f, planeTop, 0f, planeBottom,
                    withAlpha(gridCol, 8), withAlpha(gridCol, 42),
                    Shader.TileMode.CLAMP
                )
                worldPath.reset()
                worldPath.moveTo(viewW * 0.14f + yawShiftX * 0.25f, planeTop)
                worldPath.lineTo(viewW * 0.86f + yawShiftX * 0.25f, planeTop)
                worldPath.lineTo(viewW * 1.12f, planeBottom)
                worldPath.lineTo(-viewW * 0.12f, planeBottom)
                worldPath.close()
                canvas.drawPath(worldPath, worldFillPaint)
                worldFillPaint.shader = null

                // Perspective depth lines
                for (i in -5..5) {
                    val f = i / 5f
                    val topX = vanishX + f * viewW * 0.30f
                    val botX = vanishX + f * viewW * 1.15f
                    worldStrokePaint.color = withAlpha(gridCol, if (i == 0) 150 else 85)
                    canvas.drawLine(topX, planeTop, botX, planeBottom, worldStrokePaint)
                }
                // Perspective horizontal rows
                for (r in 0..7) {
                    val frac = r / 7f
                    val persp = frac * frac
                    val y = planeTop + (planeBottom - planeTop) * persp
                    val halfSpan = viewW * (0.30f + persp * 0.82f)
                    worldStrokePaint.color = withAlpha(gridCol, (45 + persp * 115).toInt())
                    canvas.drawLine(vanishX - halfSpan, y, vanishX + halfSpan, y, worldStrokePaint)
                }
                // Animated SLAM feature dots
                worldFillPaint.color = withAlpha(gridCol, 190)
                for (k in 0 until 12) {
                    val phase = (t * 0.55f + k * 0.37f) % 1f
                    val px = viewW * (0.18f + ((k * 73) % 64) / 100f) + yawShiftX * 0.3f
                    val py = planeTop + (planeBottom - planeTop) * (0.18f + ((k * 41) % 72) / 100f)
                    val pulse = 3.0f + Math.sin((phase * Math.PI * 2.0)).toFloat() * 1.6f
                    canvas.drawCircle(px, py, pulse.coerceAtLeast(1.8f), worldFillPaint)
                }
            } else {
                // Vertical Wall Surface Grid
                val padX = viewW * 0.12f
                val padY = viewH * 0.16f
                val wallL = padX + yawShiftX * 0.35f
                val wallR = viewW - padX + yawShiftX * 0.35f
                val wallT = padY
                val wallB = viewH - padY
                worldFillPaint.color = withAlpha(gridCol, 22)
                worldRect.set(wallL, wallT, wallR, wallB)
                canvas.drawRoundRect(worldRect, 22f, 22f, worldFillPaint)
                worldStrokePaint.color = withAlpha(gridCol, 125)
                canvas.drawRoundRect(worldRect, 22f, 22f, worldStrokePaint)
                for (c in 1..5) {
                    val x = wallL + (wallR - wallL) * (c / 6f)
                    worldStrokePaint.color = withAlpha(gridCol, 78)
                    canvas.drawLine(x, wallT, x, wallB, worldStrokePaint)
                }
                for (r in 1..6) {
                    val y = wallT + (wallB - wallT) * (r / 7f)
                    worldStrokePaint.color = withAlpha(gridCol, 78)
                    canvas.drawLine(wallL, y, wallR, y, worldStrokePaint)
                }
            }
            canvas.restore()
        }

        // 2. Placement Reticle
        if (worldShowReticle) {
            val retX = viewW * 0.5f
            val retY = if (isFloor) (horizonY + (viewH - horizonY) * 0.54f) else viewH * 0.52f
            val rOuter = minDim * 0.055f
            val pulse = 1f + Math.sin(t * 4.2).toFloat() * 0.08f
            val rx = rOuter * pulse
            val ry = if (isFloor) rx * 0.44f else rx
            worldStrokePaint.color = withAlpha(gridCol, 220)
            worldStrokePaint.strokeWidth = 3.2f
            worldRect.set(retX - rx, retY - ry, retX + rx, retY + ry)
            canvas.drawOval(worldRect, worldStrokePaint)
            worldFillPaint.color = withAlpha(gridCol, 235)
            canvas.drawCircle(retX, retY, 5.5f, worldFillPaint)
            // Corner ticks
            worldStrokePaint.strokeWidth = 2.6f
            canvas.drawLine(retX - rx * 1.35f, retY, retX - rx * 1.05f, retY, worldStrokePaint)
            canvas.drawLine(retX + rx * 1.05f, retY, retX + rx * 1.35f, retY, worldStrokePaint)
        }

        // 3. Project World-Locked Anchors with IMU Yaw/Pitch Stabilization
        val anchorCount = worldAnchors.size.coerceAtMost(8)
        for (i in 0 until anchorCount) {
            val a = worldAnchors[i]
            val dYaw = a.anchorYawDeg - worldYawDeltaDeg
            val dPitch = worldPitchDeg - a.anchorPitchDeg
            val sx = a.normX * viewW + (dYaw / 48f) * viewW * 0.65f
            val sy = a.normY * viewH + (dPitch / 65f) * viewH * 0.45f
            worldAnchorScreenPts[i].set(sx, sy)
            worldAnchorDepths[i] = a.distanceM
        }

        // 4. AR Surface Ruler (Metric laser lines + cm/m pill badges between consecutive anchors)
        if (worldShowRuler && anchorCount >= 2) {
            for (i in 0 until anchorCount - 1) {
                val p1 = worldAnchorScreenPts[i]
                val p2 = worldAnchorScreenPts[i + 1]
                val a1 = worldAnchors[i]
                val a2 = worldAnchors[i + 1]

                // Estimate physical distance in meters using normalized separation + depth
                val dxNorm = (p2.x - p1.x) / viewW
                val dyNorm = (p2.y - p1.y) / viewH
                val avgDist = (a1.distanceM + a2.distanceM) * 0.5f
                val meters = Math.hypot((dxNorm * avgDist * 1.35).toDouble(), (dyNorm * avgDist * 1.15).toDouble()).toFloat().coerceAtLeast(0.05f)
                val distLabel = if (meters < 1.0f) "${(meters * 100f).toInt()} cm" else String.format("%.2f m", meters)

                worldDashedPaint.strokeWidth = 5.5f
                worldDashedPaint.color = Color.argb(235, 52, 211, 153)
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, worldDashedPaint)

                worldFillPaint.color = Color.parseColor("#34D399")
                canvas.drawCircle(p1.x, p1.y, 7f, worldFillPaint)
                canvas.drawCircle(p2.x, p2.y, 7f, worldFillPaint)

                val mx = (p1.x + p2.x) * 0.5f
                val my = (p1.y + p2.y) * 0.5f - 18f
                val pillW = handPillTextPaint.measureText(distLabel) + 34f
                worldRect.set(mx - pillW * 0.5f, my - 22f, mx + pillW * 0.5f, my + 12f)
                canvas.drawRoundRect(worldRect, 14f, 14f, handPillBgPaint)
                worldStrokePaint.strokeWidth = 2f
                worldStrokePaint.color = Color.parseColor("#34D399")
                canvas.drawRoundRect(worldRect, 14f, 14f, worldStrokePaint)
                canvas.drawText(distLabel, mx, my + 2f, handPillTextPaint)
            }
        }

        // 5. Render Placed 3D World Objects with Grounded Contact Shadows
        for (i in 0 until anchorCount) {
            val anchor = worldAnchors[i]
            val pt = worldAnchorScreenPts[i]
            if (pt.x < -viewW * 0.4f || pt.x > viewW * 1.4f || pt.y < -viewH * 0.3f || pt.y > viewH * 1.35f) continue

            val depthScale = (1.65f / anchor.distanceM.coerceIn(0.6f, 4.5f)).coerceIn(0.45f, 1.55f)
            val objRadius = minDim * 0.095f * anchor.scale * depthScale
            val primaryCol = parseSafeColor(anchor.primaryHex, Color.parseColor("#00E5FF"))
            val accentCol = parseSafeColor(anchor.accentHex, Color.parseColor("#FF2E93"))

            // Grounded contact shadow
            if (worldShowShadows) {
                val shRx = objRadius * 0.92f
                val shRy = if (anchor.isFloor) objRadius * 0.30f else objRadius * 0.72f
                worldFillPaint.shader = RadialGradient(
                    pt.x, pt.y + 4f, shRx.coerceAtLeast(8f),
                    intArrayOf(Color.argb(155, 2, 6, 23), Color.argb(70, 2, 6, 23), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
                worldRect.set(pt.x - shRx, pt.y - shRy + 4f, pt.x + shRx, pt.y + shRy + 4f)
                canvas.drawOval(worldRect, worldFillPaint)
                worldFillPaint.shader = null
            }

            when (anchor.objectType) {
                WorldObjectType.HOLO_CUBE -> {
                    val cy = pt.y - objRadius * 0.98f + Math.sin(t * 2.4 + i).toFloat() * objRadius * 0.12f
                    val half = objRadius * 0.58f
                    val rotY = t * 1.15f + i * 0.8f
                    val rotX = 0.42f + (worldPitchDeg / 90f) * 0.35f
                    val cosY = Math.cos(rotY.toDouble()).toFloat()
                    val sinY = Math.sin(rotY.toDouble()).toFloat()
                    val cosX = Math.cos(rotX.toDouble()).toFloat()
                    val sinX = Math.sin(rotX.toDouble()).toFloat()

                    val signs = arrayOf(
                        floatArrayOf(-1f, -1f, -1f), floatArrayOf(1f, -1f, -1f),
                        floatArrayOf(1f, 1f, -1f), floatArrayOf(-1f, 1f, -1f),
                        floatArrayOf(-1f, -1f, 1f), floatArrayOf(1f, -1f, 1f),
                        floatArrayOf(1f, 1f, 1f), floatArrayOf(-1f, 1f, 1f)
                    )
                    for (v in 0 until 8) {
                        val vx = signs[v][0] * half
                        val vy = signs[v][1] * half
                        val vz = signs[v][2] * half
                        val x1 = vx * cosY - vz * sinY
                        val z1 = vx * sinY + vz * cosY
                        val y2 = vy * cosX - z1 * sinX
                        val z2 = vy * sinX + z1 * cosX
                        val persp = 1f / (1f - z2 / (half * 5.2f))
                        worldCubePts2d[v].set(pt.x + x1 * persp, cy + y2 * persp)
                    }
                    // Glowing inner core
                    worldFillPaint.shader = RadialGradient(
                        pt.x, cy, half * 0.72f,
                        intArrayOf(withAlpha(accentCol, 220), withAlpha(primaryCol, 90), Color.TRANSPARENT),
                        floatArrayOf(0f, 0.55f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(pt.x, cy, half * 0.72f, worldFillPaint)
                    worldFillPaint.shader = null

                    val edges = arrayOf(
                        intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 0),
                        intArrayOf(4, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 4),
                        intArrayOf(0, 4), intArrayOf(1, 5), intArrayOf(2, 6), intArrayOf(3, 7)
                    )
                    worldStrokePaint.strokeWidth = 4.2f
                    worldStrokePaint.color = withAlpha(primaryCol, 240)
                    for (e in edges) {
                        val aPt = worldCubePts2d[e[0]]
                        val bPt = worldCubePts2d[e[1]]
                        canvas.drawLine(aPt.x, aPt.y, bPt.x, bPt.y, worldStrokePaint)
                    }
                }
                WorldObjectType.CYBER_PORTAL -> {
                    val pW = objRadius * 0.95f
                    val pH = objRadius * 1.48f
                    val cy = pt.y - pH * 0.95f
                    // Swirling dimensional vortex
                    worldFillPaint.shader = RadialGradient(
                        pt.x, cy, pH,
                        intArrayOf(withAlpha(accentCol, 210), withAlpha(primaryCol, 140), Color.argb(180, 15, 23, 42)),
                        floatArrayOf(0f, 0.52f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    worldRect.set(pt.x - pW * 0.88f, cy - pH * 0.92f, pt.x + pW * 0.88f, cy + pH * 0.92f)
                    canvas.drawOval(worldRect, worldFillPaint)
                    worldFillPaint.shader = null

                    // Outer glowing stargate rings
                    worldStrokePaint.strokeWidth = 8.5f
                    worldStrokePaint.color = withAlpha(primaryCol, 240)
                    worldRect.set(pt.x - pW, cy - pH, pt.x + pW, cy + pH)
                    canvas.drawOval(worldRect, worldStrokePaint)
                    worldStrokePaint.strokeWidth = 3.5f
                    worldStrokePaint.color = withAlpha(accentCol, 235)
                    canvas.drawArc(worldRect, (t * 90f) % 360f, 130f, false, worldStrokePaint)
                    canvas.drawArc(worldRect, (t * 90f + 180f) % 360f, 130f, false, worldStrokePaint)
                }
                WorldObjectType.FLOATING_PLANET -> {
                    val cy = pt.y - objRadius * 1.12f + Math.sin(t * 2.0 + i).toFloat() * objRadius * 0.10f
                    val pr = objRadius * 0.62f
                    // Back ring arc
                    worldStrokePaint.strokeWidth = 5f
                    worldStrokePaint.color = withAlpha(accentCol, 175)
                    worldRect.set(pt.x - pr * 1.75f, cy - pr * 0.45f, pt.x + pr * 1.75f, cy + pr * 0.45f)
                    canvas.drawArc(worldRect, 180f, 180f, false, worldStrokePaint)
                    // Planet body
                    worldFillPaint.shader = RadialGradient(
                        pt.x - pr * 0.28f, cy - pr * 0.28f, pr * 1.25f,
                        intArrayOf(Color.WHITE, primaryCol, Color.argb(235, 15, 23, 42)),
                        floatArrayOf(0f, 0.45f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(pt.x, cy, pr, worldFillPaint)
                    worldFillPaint.shader = null
                    // Front ring arc
                    worldStrokePaint.color = withAlpha(accentCol, 240)
                    canvas.drawArc(worldRect, 0f, 180f, false, worldStrokePaint)
                    // Orbiting moon
                    val moonAng = t * 1.8f + i
                    val mx = pt.x + Math.cos(moonAng.toDouble()).toFloat() * pr * 1.65f
                    val my = cy + Math.sin(moonAng.toDouble()).toFloat() * pr * 0.55f
                    worldFillPaint.color = Color.parseColor("#FEF08A")
                    canvas.drawCircle(mx, my, pr * 0.18f, worldFillPaint)
                }
                WorldObjectType.NEON_BEACON -> {
                    val bH = objRadius * 1.85f
                    // Vertical hologram beam
                    worldFillPaint.shader = LinearGradient(
                        pt.x, pt.y, pt.x, pt.y - bH,
                        withAlpha(primaryCol, 175), Color.TRANSPARENT,
                        Shader.TileMode.CLAMP
                    )
                    worldRect.set(pt.x - objRadius * 0.22f, pt.y - bH, pt.x + objRadius * 0.22f, pt.y)
                    canvas.drawRect(worldRect, worldFillPaint)
                    worldFillPaint.shader = null
                    // Base rings
                    worldStrokePaint.strokeWidth = 4f
                    worldStrokePaint.color = withAlpha(primaryCol, 240)
                    worldRect.set(pt.x - objRadius * 0.55f, pt.y - objRadius * 0.18f, pt.x + objRadius * 0.55f, pt.y + objRadius * 0.18f)
                    canvas.drawOval(worldRect, worldStrokePaint)
                    // Floating diamond marker
                    val dy = pt.y - bH * 0.72f + Math.sin(t * 3.5 + i).toFloat() * 8f
                    worldPath.reset()
                    worldPath.moveTo(pt.x, dy - 18f)
                    worldPath.lineTo(pt.x + 14f, dy)
                    worldPath.lineTo(pt.x, dy + 18f)
                    worldPath.lineTo(pt.x - 14f, dy)
                    worldPath.close()
                    worldFillPaint.color = withAlpha(accentCol, 235)
                    canvas.drawPath(worldPath, worldFillPaint)
                }
                WorldObjectType.AR_TROPHY -> {
                    val hCup = objRadius * 1.35f
                    // Base pedestal
                    worldFillPaint.color = Color.parseColor("#B45309")
                    worldRect.set(pt.x - objRadius * 0.48f, pt.y - hCup * 0.16f, pt.x + objRadius * 0.48f, pt.y)
                    canvas.drawRoundRect(worldRect, 8f, 8f, worldFillPaint)
                    // Stem
                    worldFillPaint.color = Color.parseColor("#F59E0B")
                    worldRect.set(pt.x - objRadius * 0.12f, pt.y - hCup * 0.46f, pt.x + objRadius * 0.12f, pt.y - hCup * 0.14f)
                    canvas.drawRect(worldRect, worldFillPaint)
                    // Golden chalice bowl
                    worldFillPaint.shader = LinearGradient(
                        pt.x - objRadius * 0.52f, pt.y - hCup,
                        pt.x + objRadius * 0.52f, pt.y - hCup * 0.40f,
                        Color.parseColor("#FEF08A"), Color.parseColor("#D97706"),
                        Shader.TileMode.CLAMP
                    )
                    worldPath.reset()
                    worldPath.moveTo(pt.x - objRadius * 0.55f, pt.y - hCup)
                    worldPath.lineTo(pt.x + objRadius * 0.55f, pt.y - hCup)
                    worldPath.lineTo(pt.x + objRadius * 0.30f, pt.y - hCup * 0.42f)
                    worldPath.lineTo(pt.x - objRadius * 0.30f, pt.y - hCup * 0.42f)
                    worldPath.close()
                    canvas.drawPath(worldPath, worldFillPaint)
                    worldFillPaint.shader = null
                    worldStrokePaint.strokeWidth = 3.5f
                    worldStrokePaint.color = Color.parseColor("#FEF9C3")
                    canvas.drawPath(worldPath, worldStrokePaint)
                }
                WorldObjectType.CUSTOM_GLB -> {
                    val engine = glbEngine
                    val drawSize = (objRadius * 2.15f).coerceIn(110f, viewW * 0.65f)
                    if (engine != null && engine.hasModelLoaded()) {
                        val maxEdge = 720f
                        val downScale = minOf(1f, maxEdge / maxOf(viewW, viewH).coerceAtLeast(1f))
                        val renderW = (viewW * downScale).toInt().coerceIn(128, 720)
                        val renderH = (viewH * downScale).toInt().coerceIn(128, 720)
                        val anchorCenterY = (pt.y - drawSize * 0.42f + worldGlbOffsetY * viewH * 0.45f).coerceIn(viewH * 0.08f, viewH * 0.92f)
                        val pose = GlbArModelEngine.GlbPose(
                            normAnchorX = (pt.x / viewW).coerceIn(0.05f, 0.95f),
                            normAnchorY = (anchorCenterY / viewH).coerceIn(0.05f, 0.95f),
                            normFaceWidth = (drawSize / viewW).coerceIn(0.10f, 1.35f),
                            modelScale = (activeGlbModelScale * anchor.scale * worldObjectScale).coerceIn(0.35f, 3.0f),
                            yawDeg = activeGlbRotY + ((t * 36f) % 360f),
                            pitchDeg = activeGlbRotX + (worldPitchDeg * 0.25f),
                            rollDeg = worldRollDeg * 0.20f,
                            enableHeadOcclusion = false,
                            normFaceCenterX = (pt.x / viewW).coerceIn(0.05f, 0.95f),
                            normFaceCenterY = (anchorCenterY / viewH).coerceIn(0.05f, 0.95f),
                            normFaceHeight = (drawSize / viewH).coerceIn(0.10f, 1.35f)
                        )
                        val bmp = if (syncGlb) {
                            engine.renderSync(renderW, renderH, pose)
                        } else {
                            if (nowMs - lastWorldGlbRenderMs >= 33L) {
                                lastWorldGlbRenderMs = nowMs
                                engine.renderAsync(renderW, renderH, pose) { rendered ->
                                    latestWorldGlbBitmap = rendered
                                    postInvalidateOnAnimation()
                                }
                            }
                            latestWorldGlbBitmap ?: engine.renderSync(renderW, renderH, pose)?.also { latestWorldGlbBitmap = it }
                        }
                        if (bmp != null && !bmp.isRecycled) {
                            worldRect.set(0f, 0f, viewW, viewH)
                            canvas.drawBitmap(bmp, null, worldRect, null)
                        }
                    } else {
                        // Holographic placeholder plinth prompting user to load a .glb
                        val cy = pt.y - objRadius * 0.85f
                        worldStrokePaint.strokeWidth = 3.5f
                        worldStrokePaint.color = withAlpha(primaryCol, 225)
                        worldRect.set(pt.x - objRadius * 0.55f, cy - objRadius * 0.55f, pt.x + objRadius * 0.55f, cy + objRadius * 0.55f)
                        canvas.drawRoundRect(worldRect, 14f, 14f, worldStrokePaint)
                        canvas.drawText("GLB STAGE", pt.x, cy + 6f, handPillTextPaint)
                    }
                }
            }
        }

        // 6. Top-Left World AR Telemetry HUD Badge
        val surfText = if (isFloor) "FLOOR LOCK" else "WALL LOCK"
        val hudText = "WORLD AR · $surfText · ${anchorCount} ANCHOR${if (anchorCount == 1) "" else "S"}"
        val hudW = handPillTextPaint.measureText(hudText) + 34f
        val hudX = (viewW * 0.5f)
        val hudY = 96f
        worldRect.set(hudX - hudW * 0.5f, hudY - 22f, hudX + hudW * 0.5f, hudY + 14f)
        canvas.drawRoundRect(worldRect, 14f, 14f, handPillBgPaint)
        worldStrokePaint.strokeWidth = 2f
        worldStrokePaint.color = withAlpha(gridCol, 200)
        canvas.drawRoundRect(worldRect, 14f, 14f, worldStrokePaint)
        canvas.drawText(hudText, hudX, hudY + 3f, handPillTextPaint)

        postInvalidateOnAnimation()
    }
}

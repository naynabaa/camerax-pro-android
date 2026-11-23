package com.example.cameraxapp

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ExifInterface
import android.media.FaceDetector
import android.media.MediaActionSound
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Chronometer
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.core.CameraEffect
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.cameraxapp.overlay.ProOverlayView
import com.example.cameraxapp.databinding.ActivityMainBinding
import com.example.cameraxapp.filter.FilterManager
import com.example.cameraxapp.filter.OpenGLSurfaceProcessor
import com.example.cameraxapp.effect.MotionBlurProcessor
import com.example.cameraxapp.panorama.PanoramaStitcher
import com.example.cameraxapp.night.NightExposureStacker
import com.example.cameraxapp.power.EcoPowerManager
import com.example.cameraxapp.power.ThermalGuardManager
import com.example.cameraxapp.stabilization.GyroStabilizer
import com.example.cameraxapp.ar.FaceMeshHelper
import com.example.cameraxapp.ar.HandLandmarkerHelper
import com.example.cameraxapp.ar.PoseLandmarkerHelper
import com.example.cameraxapp.ar.WorldArTracker
import com.example.cameraxapp.ar.ARFaceOverlayView
import com.example.cameraxapp.audio.ComicSoundEffectsHelper
import com.example.cameraxapp.font.CameraFontHelper
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import android.content.res.Configuration
import android.view.Surface
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * CameraX Production Camera Activity with Pro Mode & Video Recording
 * 
 * Key Capabilities:
 * 1. Camera Preview (PreviewView + SurfaceProvider)
 * 2. Photo Pro Mode:
 *    - Burst Mode (Rapid multi-capture on long-press)
 *    - Manual Exposure Compensation Slider (setExposureCompensationIndex)
 *    - Countdown Timer (3s, 5s, 10s with visual indicator)
 *    - Grid Lines (Rule of Thirds Overlay)
 * 3. Video Recorder with VideoCapture:
 *    - Start, Pause, Resume, and Stop recording
 *    - Chronometer timer freeze on pause & continue on resume
 *    - Audio recording (RECORD_AUDIO permission)
 * 4. Front / Back Lens toggle (CameraSelector)
 * 5. Scoped Storage MediaStore saving to Pictures/ & Movies/
 */
class MainActivity : AppCompatActivity() {

    enum class CameraMode { SLOW_MO, VIDEO, PHOTO, PORTRAIT, NIGHT, PANORAMA, AR_LENS }

    private lateinit var viewBinding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var recording: Recording? = null
    private var cameraMode = CameraMode.PHOTO

    private var camera: Camera? = null
    private var cameraControl: CameraControl? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var isNightExtensionActive: Boolean = false
    private var preNightExposureIndex: Int = 0
    private var hasSavedPreNightExposure: Boolean = false

    // Intelligent Tap-to-Focus, Continuous AF & Real-Time Auto Blur Clearing
    @Volatile
    private var isAutoBlurClearEnabled: Boolean = true
    @Volatile
    private var isAutoClearBlurLogsEnabled: Boolean = true
    @Volatile
    private var configuredFocusMode: String = "continuous_af"
    @Volatile
    private var isAfAeLocked: Boolean = false
    @Volatile
    private var lastFocusNormX: Float = 0.5f
    @Volatile
    private var lastFocusNormY: Float = 0.5f
    @Volatile
    private var lastBlurCheckMs: Long = 0L
    @Volatile
    private var lastAutoBlurClearMs: Long = 0L
    @Volatile
    private var rollingPeakSharpness: Float = 0f
    @Volatile
    private var consecutiveBlurFrames: Int = 0

    // Default lens is BACK camera
    private var lensFacing: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    // Real-Time Filter, MediaPipe 468-Mesh & Concurrent Multi-Cam PiP Engine
    private lateinit var filterManager: FilterManager
    private var faceMeshHelper: FaceMeshHelper? = null
    private var handLandmarkerHelper: HandLandmarkerHelper? = null
    private var poseLandmarkerHelper: PoseLandmarkerHelper? = null
    private var worldArTracker: WorldArTracker? = null
    @Volatile
    private var isBodyPoseTriggerEnabled: Boolean = true
    @Volatile
    private var lastBodyPoseTriggerMs: Long = 0L
    @Volatile
    private var lastStableBodyStance: String = ""
    @Volatile
    private var stableBodyStanceStartMs: Long = 0L
    @Volatile
    private var stableBodyStanceFired: Boolean = false
    @Volatile
    private var isHandGestureTriggerEnabled: Boolean = true
    @Volatile
    private var lastHandGestureTriggerMs: Long = 0L
    @Volatile
    private var lastStableHandGesture: String = ""
    @Volatile
    private var stableHandGestureStartMs: Long = 0L
    @Volatile
    private var stableHandGestureFired: Boolean = false
    @Volatile
    private var isMediaPipeSupported: Boolean = true
    @Volatile
    private var lastMediaPipeLandmarkTime: Long = 0L
    @Volatile
    private var lastTrackedFaceX: Float = 0.5f
    @Volatile
    private var lastTrackedFaceY: Float = 0.45f
    @Volatile
    private var lastTrackedFaceTimeMs: Long = 0L
    private var openGLProcessor: OpenGLSurfaceProcessor? = null
    private var cameraEffect: CameraEffect? = null
    @Volatile
    private var isPreviewCameraEffectActive: Boolean = false
    private val motionBlurProcessor = MotionBlurProcessor()
    @Volatile
    private var latestMotionMetrics = MotionBlurProcessor.MotionMetrics.IDLE
    private var concurrentCamera: ConcurrentCamera? = null
    private var isMultiCamPipEnabled: Boolean = false
    private var isUpdatingPipSwitch: Boolean = false
    private var pipLastActionDownX: Float = 0f
    private var pipLastActionDownY: Float = 0f

    // Video Recording Pause/Resume state
    private var isRecordingPaused = false
    private var timeWhenStopped: Long = 0

    // Photo Pro Mode States
    private var countdownSeconds = 0 // 0 (Off), 3, 5, 10
    private var isCountingDown = false
    private var isBurstCapturing = false
    private var burstCount = 0
    private var maxBurstBuffer = 30
    private var remainingBurstBuffer = 30
    private val burstHandler = Handler(Looper.getMainLooper())
    private var burstRunnable: Runnable? = null
    private var isGridVisible = true

    // AR Lens Mode Dedicated Hybrid Stage Dock (FACE | HAND | BODY | WORLD | MIX) & Tap-Photo / Hold Video State
    private enum class ArDockTab { LENSES, HAND, BODY, WORLD, MIX, COMIC_TEXT }
    private var currentArDockTab = ArDockTab.LENSES
    private var arComboStreak: Int = 0
    private var isArDockCollapsed: Boolean = false
    private var isSyncingArDockSliders: Boolean = false
    private var lastActiveArLensName: String = "cyberpunk_visor"
    private var isArHoldRecording: Boolean = false
    private var isArRecordingLocked: Boolean = false
    private var arStopOnShutterUp: Boolean = false
    private var arTouchDownRawY: Float = 0f
    private val arHoldHandler = Handler(Looper.getMainLooper())
    private var arHoldRunnable: Runnable? = null

    // Two-Finger AR Viewfinder Gesture State (Place, Size, Tilt)
    private var isTwoFingerArAdjusting: Boolean = false
    private var arGesturePointerId1: Int = MotionEvent.INVALID_POINTER_ID
    private var arGesturePointerId2: Int = MotionEvent.INVALID_POINTER_ID
    private var arGestureStartMidX: Float = 0f
    private var arGestureStartMidY: Float = 0f
    private var arGestureStartDist: Float = 1f
    private var arGestureStartAngle: Double = 0.0
    private var arGestureOrigOffsetX: Float = 0f
    private var arGestureOrigOffsetY: Float = 0f
    private var arGestureOrigScale: Float = 1.0f
    private var arGestureOrigRotDeg: Float = 0f
    private var twoFingerPinchEnabled: Boolean = true
    private var twoFingerPinchSensitivity: Float = 1.0f
    private var twoFingerRotateEnabled: Boolean = true
    private var twoFingerRotateSensitivity: Float = 1.0f
    private var twoFingerDragEnabled: Boolean = true
    private var twoFingerDragSensitivity: Float = 1.0f
    private var invertTwoFingerRotate: Boolean = false

    // Audio Cue, Native Shutter/Record SFX, Comic SFX & Typography Engines
    private var toneGenerator: ToneGenerator? = null
    private var mediaActionSound: MediaActionSound? = null
    private var comicSfxHelper: ComicSoundEffectsHelper? = null
    private var cameraFontHelper: CameraFontHelper? = null
    private var selectedVideoQualityLabel: String = "HD 30FPS"
    private var activeEffectIntensity: Float = 0.85f
    private var comicHalftoneDensity: Float = 1.0f
    private var comicHalftoneDotSize: Float = 1.0f
    private var comicLabelDepth: Float = 1.0f

    // AR Calibration & Comic Studio State
    private var calibOffsetX: Float = 0f
    private var calibOffsetY: Float = 0f
    private var calibScale: Float = 1.0f
    private var calibRotDeg: Float = 0f
    private var calibEyeSpace: Float = 1.0f
    private var calibForehead: Float = 0f
    private var calibChin: Float = 0f
    private var calibDebug: Boolean = false

    private data class ComicPalettePreset(
        val id: String,
        val name: String,
        val powTop: String, val powBottom: String, val powExtrude: String, val powBurst: String,
        val bamTop: String, val bamBottom: String, val bamExtrude: String, val bamBurst: String,
        val zapTop: String, val zapBottom: String, val zapExtrude: String, val zapBurst: String
    )
    private val comicPalettePresets = listOf(
        ComicPalettePreset("silver_age", "SILVER AGE", "#FFFBEB", "#FACC15", "#EF4444", "#DC2626", "#FFFFFF", "#FF5E3A", "#0284C7", "#FACC15", "#ECFCCB", "#22D3EE", "#7C3AED", "#2563EB"),
        ComicPalettePreset("spider_verse", "SPIDER-VERSE", "#FDF2F8", "#F43F5E", "#06B6D4", "#7C3AED", "#FEFCE8", "#FACC15", "#EC4899", "#1D4ED8", "#ECFEFF", "#22D3EE", "#F43F5E", "#F59E0B"),
        ComicPalettePreset("cyber_neon", "CYBER NEON", "#FCE7F3", "#EC4899", "#00F0FF", "#4C1D95", "#EDE9FE", "#A855F7", "#22D3EE", "#BE185D", "#ECFCCB", "#84CC16", "#EC4899", "#0F766E"),
        ComicPalettePreset("manga_noir", "MANGA INK", "#FFFFFF", "#E2E8F0", "#EF4444", "#1E293B", "#FEF2F2", "#EF4444", "#0F172A", "#F8FAFC", "#F8FAFC", "#94A3B8", "#DC2626", "#0F172A")
    )
    private var comicPaletteIndex = 0
    private val comicSfxChoices = listOf("comic_punch", "heavy_slam", "electric_zap", "boing_spring", "laser_burst")
    private var comicSfxPow = "comic_punch"
    private var comicSfxBam = "heavy_slam"
    private var comicSfxZap = "electric_zap"
    private val comicBubblePhrases = listOf("HERO MODE!", "BOOM!!", "ACTION!", "WHAT?!", "PLOT TWIST!", "TO BE CONTINUED...")
    private var comicBubblePhraseIndex = 0

    // AR Batch Queue & Auto-Adapt State
    private var isBatchModeActive = false
    private var isBatchPlaying = false
    private var batchStepIndex = 0
    private val batchLensQueue = mutableListOf<String>()
    private var isAutoAdaptEnabled = false
    private val batchHandler = Handler(Looper.getMainLooper())
    private var batchRunnable: Runnable? = null

    // Web Studio Injected Config Presets (Custom Filters, Lenses & Typography)
    private data class CustomFilterDef(
        val id: String, val name: String, val brightness: Int, val contrast: Int,
        val saturation: Int, val sepia: Int, val grayscale: Int, val tintColor: String
    )
    private val injectedCustomFilters = listOf<CustomFilterDef>(
        
    )
    private data class CustomLensDef(
        val id: String,
        val name: String,
        val anchor: String,
        val style: String,
        val primaryColor: String,
        val accentColor: String,
        val glbAssetPath: String = "",
        val glbFilePath: String = "",
        val modelScale: Float = 1.0f,
        val modelOffsetY: Float = 0f,
        val modelRotationY: Float = 0f,
        val modelRotationX: Float = 0f,
        val autoSpin: Boolean = true,
        val telemetryText: String = ""
    )
    private val injectedCustomLenses = mutableListOf<CustomLensDef>(
        CustomLensDef("lens_neon_cat_ears", "Neon Feline Ears", "forehead", "cat_ears", "#ec4899", "#38bdf8", "", "", 1f, 0f, 0f, 0f, true, "NEKO.SYS // V2.0"),
        CustomLensDef("lens_celestial_halo", "Celestial Halo", "forehead", "halo_tiara", "#fbbf24", "#ffffff", "", "", 1f, 0f, 0f, 0f, true, "AURA LEVEL // 9999"),
        CustomLensDef("lens_cyber_respirator", "Cyber Mask 2077", "mouth", "cyber_mask", "#06b6d4", "#f43f5e", "", "", 1f, 0f, 0f, 0f, true, "O2 FILTER // 100%")
    )
    private data class CustomFontDef(
        val id: String, val name: String, val family: String, val category: String,
        val colorHex: String, val glowHex: String, val sampleText: String, val italic: Boolean
    )
    private val injectedFonts = listOf<CustomFontDef>(
        CustomFontDef("font_jetbrains_mono", "Cyber HUD Monospace", "\"JetBrains Mono\", monospace", "monospace", "#38bdf8", "#0284c7", "CAMERAX PRO · 60FPS · RAW", false),
        CustomFontDef("font_orbitron", "Orbitron Sci-Fi", "\"Orbitron\", sans-serif", "scifi", "#22d3ee", "#06b6d4", "TARGET LOCK // 99.8% · ACTIVE", false),
        CustomFontDef("font_vt323", "90s Camcorder VHS", "\"VT323\", monospace", "retro", "#4ade80", "#16a34a", "PLAY ▶ REC · 00:14:28 · SP", false),
        CustomFontDef("font_chakra_petch", "Tactical Mecha", "\"Chakra Petch\", sans-serif", "tactical", "#fbbf24", "#d97706", "MIL-STD // 810G · APERTURE F/1.8", false),
        CustomFontDef("font_cinzel", "Cinematic Anamorphic", "\"Cinzel\", serif", "cinematic", "#f8fafc", "#94a3b8", "PANAVISION · 2.39:1 · SCENE 04", false),
        CustomFontDef("font_space_grotesk", "Studio Clean Minimal", "\"Space Grotesk\", sans-serif", "editorial", "#ffffff", "#64748b", "STUDIO MASTER · 4K UHD · 10-BIT", false),
        CustomFontDef("font_black_ops", "Black Ops Stencil", "\"Black Ops One\", cursive", "tactical", "#f43f5e", "#be123c", "DEFCON 1 · THERMAL OPTIC ENGAGED", false)
    )
    private var activeFontIndex = injectedFonts.indexOfFirst { it.id == "font_jetbrains_mono" }.coerceAtLeast(0)
    private val dynamicFilterChips = mutableListOf<Pair<TextView, String>>()
    private val dynamicLensChips = mutableListOf<Pair<TextView, String>>()

    // Auto Trigger Interval Loop Recorder (Auto Record -> Auto Pause -> Auto Resume)
    private var isAutoLoopEnabled = false
    private var autoRecordDuration = 5 // seconds of active recording take
    private var autoPauseDuration = 3 // seconds of auto pause standby
    private var autoLoopIteration = 0
    private var autoPhaseRemaining = 0
    private var isAutoLoopPausedPhase = false
    private val autoLoopHandler = Handler(Looper.getMainLooper())
    private var autoLoopRunnable: Runnable? = null

    // Electronic Virtual Horizon & Gyroscope Panorama Tracking (60 FPS)
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val uprightAngles = FloatArray(3)

    // Automated CameraX ImageAnalysis Panorama Engine State
    private val panoramaStitcher = PanoramaStitcher()
    private val panoramaLock = Any()
    @Volatile
    private var isPanoramaCapturing = false
    @Volatile
    private var isPanoramaStitching = false
    private val panoramaSlices = mutableListOf<PanoramaStitcher.PanoramaSlice>()
    private var panoramaStartYawDeg = 0f
    private var panoramaStartPitchDeg = 0f
    private var panoramaDirection = 0
    @Volatile
    private var latestUprightYawDeg = 0f
    @Volatile
    private var latestUprightPitchDeg = 0f
    private val panoramaFallbackHandler = Handler(Looper.getMainLooper())
    private var panoramaFallbackRunnable: Runnable? = null
    private var panoramaSweepDir: PanoramaStitcher.SweepDirection = PanoramaStitcher.SweepDirection.RIGHT
    @Volatile
    private var isPanoramaCloneMode: Boolean = false
    @Volatile
    private var isPanoramaTinyPlanetMode: Boolean = false
    @Volatile
    private var isPanoramaAeLocked: Boolean = false
    @Volatile
    private var requestCloneSliceCapture: Boolean = false
    private var panoLongPressRunnable: Runnable? = null
    private var panoLongPressTriggered: Boolean = false

    // Automated Night Mode Low-Light ImageAnalysis & Multi-Frame Exposure Stacking State
    private val nightExposureStacker = NightExposureStacker()
    @Volatile
    private var isNightStacking = false
    @Volatile
    private var isAutoNightStackEnabled = false
    private var nightFramesMode: String = "auto" // "auto", "3", "5", "7"
    private var nightSkyTone: String = "natural" // "natural" (deep blacks), "bright" (shadow boost)
    @Volatile
    private var nightShutterProgress: Float = 0f
    private var nightLastYawDeg: Float = 0f
    private var nightLastPitchDeg: Float = 0f
    private val nightHandler = Handler(Looper.getMainLooper())

    // Eco Mode BatteryManager Monitor & Dynamic ImageAnalysis/AR Throttler
    private lateinit var ecoPowerManager: EcoPowerManager
    private var lastAppliedEcoTier: EcoPowerManager.EcoTier = EcoPowerManager.EcoTier.NORMAL
    private var savedArLensBeforeEco: String? = null
    private var savedPipBeforeEco: Boolean = false

    // Thermal Monitoring Utility (PowerManager.OnThermalStatusChangedListener & Auto-Scaling)
    private lateinit var thermalGuardManager: ThermalGuardManager
    private var lastAppliedThermalTier: ThermalGuardManager.ThermalTier = ThermalGuardManager.ThermalTier.NOMINAL
    private var savedArLensBeforeThermal: String? = null
    private var savedPipBeforeThermal: Boolean = false

    // Gyro-Stabilization (Software EIS) via Sensor.TYPE_GYROSCOPE -> OpenGLSurfaceProcessor & PreviewView
    private lateinit var gyroStabilizer: GyroStabilizer
    private var isGyroEisEnabled: Boolean = false

    private fun normalizeAngleDelta(fromDeg: Float, toDeg: Float): Float {
        return (((toDeg - fromDeg) + 540f) % 360f) - 180f
    }

    @Volatile
    private var currentDisplayRotation: Int = Surface.ROTATION_0

    private fun readDisplayRotation(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.rotation ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.rotation
            }
        } catch (_: Throwable) {
            Surface.ROTATION_0
        }
    }

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                // Row 2 of rotationMatrix is World Up (+Z) in raw device coordinates: (ux, uy, uz)
                val ux = rotationMatrix[6]
                val uy = rotationMatrix[7]
                val uz = rotationMatrix[8]

                // Map World Up onto the CURRENT screen's (Right, Top) axes for exact 0° horizon roll
                // across Portrait (0), Landscape-Left (90), Upside-Down (180), and Landscape-Right (270).
                val screenRightUp: Float
                val screenTopUp: Float
                when (currentDisplayRotation) {
                    Surface.ROTATION_90 -> {
                        screenRightUp = -uy
                        screenTopUp = ux
                    }
                    Surface.ROTATION_180 -> {
                        screenRightUp = -ux
                        screenTopUp = -uy
                    }
                    Surface.ROTATION_270 -> {
                        screenRightUp = uy
                        screenTopUp = -ux
                    }
                    else -> {
                        screenRightUp = ux
                        screenTopUp = uy
                    }
                }

                val inPlaneMag = Math.hypot(screenRightUp.toDouble(), screenTopUp.toDouble())
                val rollDeg = if (inPlaneMag >= 0.25) {
                    Math.toDegrees(Math.atan2((-screenRightUp).toDouble(), screenTopUp.toDouble())).toFloat()
                } else {
                    0f
                }
                // Orientation-independent camera optical axis (-devZ) pitch & compass yaw:
                val pitchDeg = Math.toDegrees(Math.asin((-uz).coerceIn(-1f, 1f).toDouble())).toFloat()
                val yawDeg = Math.toDegrees(Math.atan2((-rotationMatrix[2]).toDouble(), (-rotationMatrix[5]).toDouble())).toFloat()

                smoothRollDeg = smoothRollDeg * 0.78f + rollDeg * 0.22f
                viewBinding.proOverlayView.tiltRollDegrees = smoothRollDeg
                viewBinding.proOverlayView.tiltPitchDegrees = pitchDeg
                val viewEisControlsRotation = (::gyroStabilizer.isInitialized && gyroStabilizer.isEnabled && cameraEffect == null)
                if (currentShootingEffect == "action_stabilization") {
                    viewBinding.viewFinder.rotation = -smoothRollDeg.coerceIn(-45f, 45f)
                } else if (currentShootingEffect == "hyperlapse") {
                    viewBinding.viewFinder.rotation = -(smoothRollDeg * 0.45f).coerceIn(-18f, 18f)
                } else if (!viewEisControlsRotation && viewBinding.viewFinder.rotation != 0f) {
                    viewBinding.viewFinder.rotation = 0f
                }

                latestUprightYawDeg = yawDeg
                latestUprightPitchDeg = pitchDeg
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private var flashMode: Int = ImageCapture.FLASH_MODE_OFF

    private lateinit var cameraExecutor: ExecutorService
    private var stylizeExecutor: ExecutorService? = null
    private val stylizeHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var isStylizingFrame = false
    private var isStylizeLoopScheduled = false
    private fun shouldRunCpuStylizeLoop(): Boolean {
        val disableHeavyStylize = (::ecoPowerManager.isInitialized && ecoPowerManager.isHeavyMetersDisabled) ||
            (::thermalGuardManager.isInitialized && thermalGuardManager.shouldReduceComplexity)
        return !isFinishing &&
            !isDestroyed &&
            !disableHeavyStylize &&
            !isPreviewCameraEffectActive &&
            (isStylizedLiveEffect(currentShootingEffect) || isComicFilterPreset(currentFilterName))
    }
    private fun syncStylizeLoopState() {
        val needed = shouldRunCpuStylizeLoop()
        if (needed && !isStylizeLoopScheduled) {
            isStylizeLoopScheduled = true
            stylizeHandler.postDelayed(stylizeRunnable, 80L)
        } else if (!needed && isStylizeLoopScheduled) {
            stylizeHandler.removeCallbacks(stylizeRunnable)
            isStylizeLoopScheduled = false
        }
    }
    private val stylizeRunnable = object : Runnable {
        override fun run() {
            val isEcoThrottled = ::ecoPowerManager.isInitialized && ecoPowerManager.isEcoActive
            val isThermalThrottled = ::thermalGuardManager.isInitialized && thermalGuardManager.isThrottled
            if (!shouldRunCpuStylizeLoop()) {
                isStylizeLoopScheduled = false
                return
            }
            if (!isStylizingFrame) {
                val tv = findTextureView(viewBinding.viewFinder)
                if (tv != null && tv.isAvailable) {
                    val vw = tv.width.coerceAtLeast(1)
                    val vh = tv.height.coerceAtLeast(1)
                    val maxEdge = 480
                    val sampleW = if (vw >= vh) maxEdge else Math.max(160, (maxEdge * vw / vh.toFloat()).toInt())
                    val sampleH = if (vh >= vw) maxEdge else Math.max(160, (maxEdge * vh / vw.toFloat()).toInt())
                    val frame = tv.getBitmap(sampleW, sampleH)
                    if (frame != null) {
                        val activeEffect = currentShootingEffect
                        val activeFilter = currentFilterName
                        val targetStyleId = if (isStylizedLiveEffect(activeEffect)) activeEffect else activeFilter
                        isStylizingFrame = true
                        stylizeExecutor?.execute {
                            try {
                                val useGpuComicEngine = targetStyleId in setOf(
                                    "comic_book", "comic", "cartoon",
                                    "cel_shaded_studio", "graphic_novel_noir", "manga_ink",
                                    "pop_art_print", "line_art_minimalist", "ligne_claire"
                                )
                                val gpuStyled = if (useGpuComicEngine) {
                                    filterManager.applyToonEffectToBitmap(frame, targetStyleId, activeEffectIntensity)
                                } else {
                                    null
                                }
                                val styled = gpuStyled ?: renderStylizedEffectBitmap(frame, targetStyleId, forLivePreview = true)
                                runOnUiThread {
                                    val stillValid = !isFinishing && !isDestroyed && !isPreviewCameraEffectActive && (
                                        (isStylizedLiveEffect(currentShootingEffect) && currentShootingEffect == activeEffect) ||
                                        (!isStylizedLiveEffect(currentShootingEffect) && isComicFilterPreset(currentFilterName) && currentFilterName == activeFilter)
                                    )
                                    if (stillValid) {
                                        viewBinding.proOverlayView.updateProcessedEffectBitmap(styled)
                                    } else if (!styled.isRecycled) {
                                        styled.recycle()
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Live stylize frame error", e)
                            } finally {
                                if (!frame.isRecycled) frame.recycle()
                                isStylizingFrame = false
                            }
                        }
                    }
                }
            }
            val baseDelay = if (currentShootingEffect == "stop_motion") 155L else 80L
            val nextDelay = if (isEcoThrottled || isThermalThrottled) Math.max(baseDelay, 200L) else baseDelay
            if (shouldRunCpuStylizeLoop()) {
                isStylizeLoopScheduled = true
                stylizeHandler.postDelayed(this, nextDelay)
            } else {
                isStylizeLoopScheduled = false
            }
        }
    }

    // Runtime Permission Launcher
    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] == true
            if (cameraGranted) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    // AR Calibration JSON Export / Import Storage Access Framework Launchers
    private val exportArCalibrationJsonLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            try {
                val jsonPayload = buildArCalibrationJsonString()
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(jsonPayload.toByteArray(Charsets.UTF_8))
                    out.flush()
                } ?: throw IllegalStateException("Unable to open output stream")
                showHudNotice("Exported AR Calibration JSON")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export AR calibration JSON", e)
                showHudNotice("Export failed: ${e.localizedMessage ?: "I/O error"}", isWarning = true)
            }
        }

    private val importArCalibrationJsonLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            try {
                val rawJson = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                    it.readText()
                } ?: throw IllegalStateException("Unable to read JSON file")
                val applied = applyArCalibrationJsonString(rawJson)
                if (applied) {
                    showHudNotice("Imported Custom AR Calibration JSON")
                } else {
                    showHudNotice("Invalid AR Calibration JSON format", isWarning = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import AR calibration JSON", e)
                showHudNotice("Import failed: ${e.localizedMessage ?: "Invalid JSON"}", isWarning = true)
            }
        }

    // On-device 3D .GLB AR Lens File Picker (Storage Access Framework)
    private val importGlbLensLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            importGlbLensFromUri(uri)
        }

    // Phase 1: On-device Custom PNG/WebP Face Mask Texture Picker (Storage Access Framework)
    private val importFaceMaskLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            loadCustomFaceMaskFromUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        stylizeExecutor = Executors.newSingleThreadExecutor()
        openGLProcessor = OpenGLSurfaceProcessor()

        // Initialize Orientation Sensor for Virtual Horizon Level Gauge
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        // Initialize Eco Mode Power Manager (monitors BatteryManager & OS Power Saver)
        ecoPowerManager = EcoPowerManager(this) { policy ->
            if (::thermalGuardManager.isInitialized) {
                thermalGuardManager.updateBatteryTemperatureFallback(policy.temperatureC)
            }
            runOnUiThread { applyEcoPolicy(policy) }
        }

        // Initialize Thermal Guard Manager (monitors PowerManager thermal status & headroom)
        thermalGuardManager = ThermalGuardManager(this) { policy ->
            runOnUiThread { applyThermalPolicy(policy) }
        }

        // Initialize Gyro-Stabilization (Software EIS) Engine for Preview & VideoCapture
        gyroStabilizer = GyroStabilizer(this) { telemetry ->
            openGLProcessor?.setGyroEisTransform(
                enabled = telemetry.isEnabled,
                overscanZoom = telemetry.overscanZoom,
                transXNorm = telemetry.compXNorm,
                transYNorm = telemetry.compYNorm,
                rollDeg = telemetry.compRollDeg
            )
            runOnUiThread { applyGyroEisTelemetry(telemetry) }
        }

        // Initialize Filter, MediaPipe 468 Face Mesh, Comic SFX & Typography Engines
        filterManager = FilterManager(this)
        comicSfxHelper = ComicSoundEffectsHelper()
        cameraFontHelper = CameraFontHelper(this)
        faceMeshHelper = FaceMeshHelper(this, object : FaceMeshHelper.FaceMeshListener {
            override fun onFaceLandmarksDetected(
                result: FaceLandmarkerResult,
                inputWidth: Int,
                inputHeight: Int
            ) {
                lastMediaPipeLandmarkTime = System.currentTimeMillis()
                isMediaPipeSupported = true
                val isFront = (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA)
                if (result.faceLandmarks().isNotEmpty()) {
                    viewBinding.arFaceOverlayView.updateResults(result, inputWidth, inputHeight, isFront)
                } else {
                    viewBinding.arFaceOverlayView.clearLandmarksOnly()
                }
            }

            override fun onError(error: String) {
                isMediaPipeSupported = false
                Log.w(TAG, "MediaPipe unavailable — using built-in FaceDetector fallback: $error")
            }
        })
        faceMeshHelper?.setFrontFacing(lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA)
        handLandmarkerHelper = HandLandmarkerHelper(this, object : HandLandmarkerHelper.HandLandmarkerListener {
            override fun onHandsDetected(
                hands: List<HandLandmarkerHelper.TrackedHand>,
                inputWidth: Int,
                inputHeight: Int
            ) {
                val isFront = (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA)
                val mapped = hands.map {
                    ARFaceOverlayView.OverlayHandData(it.landmarks, it.gesture, it.handedness)
                }
                viewBinding.arFaceOverlayView.updateHandResults(mapped, inputWidth, inputHeight, isFront)

                val primaryHand = hands.firstOrNull()
                val primaryGesture = primaryHand?.gesture
                val nowMs = System.currentTimeMillis()

                // Avengers Endgame Instant Finger-Snap Trigger when Snap Dust Wipeout is armed!
                if (primaryGesture == "FINGER_SNAP" && viewBinding.arFaceOverlayView.isSnapWipeoutArmed() && (nowMs - lastHandGestureTriggerMs) > 1600L) {
                    lastHandGestureTriggerMs = nowMs
                    val snapPt = primaryHand.landmarks.getOrNull(4)
                    val sx = if (snapPt != null) (if (isFront) 1f - snapPt.x else snapPt.x) else 0.70f
                    val sy = snapPt?.y ?: 0.42f
                    runOnUiThread {
                        triggerSnapWipeoutAction(sx, sy)
                    }
                }

                val isActionableGesture = primaryGesture == "PEACE_SIGN" || primaryGesture == "THUMBS_UP" || primaryGesture == "OPEN_PALM"
                if (isHandGestureTriggerEnabled && isActionableGesture && primaryGesture != null && !viewBinding.arFaceOverlayView.isSnapWipeoutArmed()) {
                    if (primaryGesture != lastStableHandGesture) {
                        lastStableHandGesture = primaryGesture
                        stableHandGestureStartMs = nowMs
                        stableHandGestureFired = false
                    }
                    val dwellDurationMs = 350L
                    val elapsedMs = (nowMs - stableHandGestureStartMs).coerceAtLeast(0L)
                    val inCooldown = (nowMs - lastHandGestureTriggerMs) <= 2400L
                    if (!stableHandGestureFired && !inCooldown) {
                        val progress = (elapsedMs.toFloat() / dwellDurationMs.toFloat()).coerceIn(0f, 1f)
                        viewBinding.arFaceOverlayView.updateHandGestureDwell(progress, "HOLD ${primaryGesture.replace("_", " ")}")
                        if (elapsedMs >= dwellDurationMs) {
                            stableHandGestureFired = true
                            lastHandGestureTriggerMs = nowMs
                            viewBinding.arFaceOverlayView.updateHandGestureDwell(0f, "")
                            runOnUiThread {
                                val hapticConst = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    HapticFeedbackConstants.CONFIRM
                                } else {
                                    HapticFeedbackConstants.LONG_PRESS
                                }
                                viewBinding.arFaceOverlayView.performHapticFeedback(hapticConst)
                                when (primaryGesture) {
                                    "PEACE_SIGN" -> {
                                        showHudNotice("Peace Gesture -> Capturing Photo")
                                        takePhoto()
                                    }
                                    "THUMBS_UP" -> {
                                        showHudNotice("Thumbs Up -> Comic SFX Burst")
                                        if (!isAudioMuted) {
                                            comicSfxHelper?.playComicBurst(comicSfxPow, comicSfxBam, comicSfxZap, 0.75f, isAudioMuted)
                                        }
                                    }
                                    "OPEN_PALM" -> {
                                        showHudNotice("Open Palm -> Hand AR Aura Boost")
                                    }
                                }
                            }
                        }
                    } else {
                        viewBinding.arFaceOverlayView.updateHandGestureDwell(0f, "")
                    }
                } else {
                    lastStableHandGesture = ""
                    stableHandGestureStartMs = 0L
                    stableHandGestureFired = false
                    viewBinding.arFaceOverlayView.updateHandGestureDwell(0f, "")
                }
            }

            override fun onHandTrackingNotice(message: String) {
                Log.w(TAG, "HandLandmarker notice: $message")
            }
        })
        poseLandmarkerHelper = PoseLandmarkerHelper(this, object : PoseLandmarkerHelper.PoseLandmarkerListener {
            override fun onPoseDetected(
                landmarks: List<PointF>,
                stance: String,
                segmentationMask: Bitmap?,
                inputWidth: Int,
                inputHeight: Int
            ) {
                val isFront = (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA)
                viewBinding.arFaceOverlayView.updateBodyPoseResults(
                    landmarks = landmarks,
                    stance = stance,
                    segMask = segmentationMask,
                    imgW = inputWidth,
                    imgH = inputHeight,
                    isFront = isFront
                )

                // Tweak 4: Hands-Free Full-Body Pose Triggers (0.55s anti-accidental dwell + haptic confirm)
                val isActionableStance = landmarks.size >= 33 && (stance == "ARMS_RAISED" || stance == "T_POSE")
                val nowMs = System.currentTimeMillis()
                if (isBodyPoseTriggerEnabled && isActionableStance) {
                    if (stance != lastStableBodyStance) {
                        lastStableBodyStance = stance
                        stableBodyStanceStartMs = nowMs
                        stableBodyStanceFired = false
                    }
                    val dwellDurationMs = 550L
                    val elapsedMs = (nowMs - stableBodyStanceStartMs).coerceAtLeast(0L)
                    val inCooldown = (nowMs - lastBodyPoseTriggerMs) <= 3200L
                    if (!stableBodyStanceFired && !inCooldown) {
                        val progress = (elapsedMs.toFloat() / dwellDurationMs.toFloat()).coerceIn(0f, 1f)
                        val prompt = if (stance == "ARMS_RAISED") "HOLD ARMS UP · PHOTO" else "HOLD T-POSE · NEXT"
                        viewBinding.arFaceOverlayView.updateBodyPoseDwell(progress, prompt)
                        if (elapsedMs >= dwellDurationMs) {
                            stableBodyStanceFired = true
                            lastBodyPoseTriggerMs = nowMs
                            viewBinding.arFaceOverlayView.updateBodyPoseDwell(0f, "")
                            runOnUiThread {
                                val hapticConst = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    HapticFeedbackConstants.CONFIRM
                                } else {
                                    HapticFeedbackConstants.LONG_PRESS
                                }
                                viewBinding.arFaceOverlayView.performHapticFeedback(hapticConst)
                                if (stance == "ARMS_RAISED") {
                                    showHudNotice("Arms Raised -> Hands-Free Photo!")
                                    takePhoto()
                                } else if (stance == "T_POSE") {
                                    val cycle = listOf("cyber_angel", "hero_cape", "mecha_armor", "celestial_stardust", "chroma_studio")
                                    val curIdx = cycle.indexOf(viewBinding.arFaceOverlayView.bodyPresetId)
                                    val nextId = cycle[(curIdx + 1) % cycle.size]
                                    viewBinding.arFaceOverlayView.applyBodyArPreset(nextId)
                                    showHudNotice("T-Pose -> Body AR: ${nextId.replace("_", " ").uppercase()}")
                                }
                            }
                        }
                    } else {
                        viewBinding.arFaceOverlayView.updateBodyPoseDwell(0f, "")
                    }
                } else {
                    lastStableBodyStance = ""
                    stableBodyStanceStartMs = 0L
                    stableBodyStanceFired = false
                    viewBinding.arFaceOverlayView.updateBodyPoseDwell(0f, "")
                }
            }

            override fun onPoseTrackingNotice(message: String) {
                Log.w(TAG, "PoseLandmarker notice: $message")
            }
        })
        worldArTracker = WorldArTracker(this, object : WorldArTracker.WorldOrientationListener {
            override fun onOrientationUpdated(
                pitchDeg: Float,
                yawDeltaDeg: Float,
                rollDeg: Float,
                detectedSurface: String
            ) {
                viewBinding.arFaceOverlayView.updateWorldOrientation(
                    pitchDeg = pitchDeg,
                    yawDeltaDeg = yawDeltaDeg,
                    rollDeg = rollDeg,
                    detectedSurface = detectedSurface
                )
            }
        })
        viewBinding.arFaceOverlayView.onComicFaceAcquired = {
            if (!isAudioMuted) {
                comicSfxHelper?.playComicBurst(comicSfxPow, comicSfxBam, comicSfxZap, 0.55f, isAudioMuted)
            }
        }
        viewBinding.arFaceOverlayView.onOverlayDirty = {
            syncOverlayToVideoProcessor()
        }
        viewBinding.arFaceOverlayView.onTextItemEditRequested = {
            showArTextStudioDialog()
        }

        viewBinding.proOverlayView.sensorAspectRatio = currentAspectRatio
        viewBinding.tvVideoQualityPill.text = selectedVideoQualityLabel
        viewBinding.proOverlayView.showWatermark = false
        viewBinding.proStudioDrawer.switchWatermark.isChecked = false
        viewBinding.proOverlayView.watermarkText = "CAMERAX PRO · STUDIO"
        if (false) {
            setAspectMatteMode(ProOverlayView.AspectMatte.CINE_239)
        }
        applyActiveFontPreset()

        setupDynamicConfigChips()
        setupClickListeners()
        setupPipInteractions()
        setupBurstMode()
        setupExposureSlider()
        setupFilterAndArControls()
        setupProEnhancementControls()
        setupArCalibrationControls()
        setupArLensModeDock()
        setupFloatingModeMenu()
        selectCreativeCategory(CreativeTarget.FILTERS, fromUser = false)
        updateShootingEffectButtons()
        updateActiveInstrumentsCount()
        isLastActionCreative = false
        viewBinding.modeScrollView.post { centerModeTab(cameraMode, smooth = false) }
        syncStylizeLoopState()
        currentDisplayRotation = readDisplayRotation()
        applyOrientationLayout(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isLandscape = (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
        val displayRot = readDisplayRotation()
        currentDisplayRotation = displayRot
        try {
            imageCapture?.targetRotation = displayRot
            videoCapture?.targetRotation = displayRot
            imageAnalysis?.targetRotation = displayRot
        } catch (e: Exception) {
            Log.w(TAG, "Target rotation sync skipped", e)
        }
        applyOrientationLayout(isLandscape)
        viewBinding.modeScrollView.post { centerModeTab(cameraMode, smooth = false) }
        viewBinding.proOverlayView.postInvalidate()
    }

    private fun applyOrientationLayout(isLandscape: Boolean) {
        val dp = resources.displayMetrics.density
        // 1. Top Toolbar: compact top margin in Landscape
        (viewBinding.proModeBar.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)?.let { lp ->
            lp.topMargin = ((if (isLandscape) 6 else 28) * dp).toInt()
            viewBinding.proModeBar.layoutParams = lp
        }
        viewBinding.proModeBar.setPadding(
            ((if (isLandscape) 18 else 14) * dp).toInt(),
            ((if (isLandscape) 4 else 10) * dp).toInt(),
            ((if (isLandscape) 18 else 14) * dp).toInt(),
            ((if (isLandscape) 4 else 10) * dp).toInt()
        )
        // 2. Top Status / Recording Pill: in Landscape align on the same top row between Flash & Eye buttons so it NEVER overlaps Panorama Filmstrip!
        (viewBinding.recordingTimer.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)?.let { lp ->
            if (isLandscape) {
                lp.topToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                lp.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.topMargin = (8 * dp).toInt()
            } else {
                lp.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                lp.topToBottom = viewBinding.proModeBar.id
                lp.topMargin = (8 * dp).toInt()
            }
            viewBinding.recordingTimer.layoutParams = lp
        }
        // 3. Bottom Action Deck & Shutter: scale cleanly in Landscape so shutter ring never clips off the bottom edge!
        (viewBinding.bottomControls.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)?.let { lp ->
            lp.height = ((if (isLandscape) 76 else 100) * dp).toInt()
            viewBinding.bottomControls.layoutParams = lp
        }
        viewBinding.bottomControls.setPadding(
            ((if (isLandscape) 42 else 28) * dp).toInt(),
            0,
            ((if (isLandscape) 42 else 28) * dp).toInt(),
            ((if (isLandscape) 4 else 16) * dp).toInt()
        )
        val shutterPx = ((if (isLandscape) 62 else 78) * dp).toInt()
        (viewBinding.btnCapture.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)?.let { lp ->
            lp.width = shutterPx
            lp.height = shutterPx
            viewBinding.btnCapture.layoutParams = lp
        }
        val sideBtnPx = ((if (isLandscape) 44 else 54) * dp).toInt()
        listOf(viewBinding.btnGallery, viewBinding.btnSwitchCamera, viewBinding.btnPauseResume).forEach { btn ->
            (btn.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)?.let { lp ->
                lp.width = sideBtnPx
                lp.height = sideBtnPx
                btn.layoutParams = lp
            }
        }
        // 4. Mode Carousel & Zoom Controls: sleek height in Landscape for maximum vertical viewfinder
        (viewBinding.modeScrollView.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)?.let { lp ->
            lp.height = ((if (isLandscape) 34 else 48) * dp).toInt()
            viewBinding.modeScrollView.layoutParams = lp
        }
        (viewBinding.zoomControls.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)?.let { lp ->
            lp.height = ((if (isLandscape) 30 else 36) * dp).toInt()
            lp.bottomMargin = ((if (isLandscape) 4 else 12) * dp).toInt()
            viewBinding.zoomControls.layoutParams = lp
        }
        refreshFloatingModeMenu()
    }

    override fun onResume() {
        super.onResume()
        currentDisplayRotation = readDisplayRotation()
        if (::ecoPowerManager.isInitialized) {
            ecoPowerManager.register()
        }
        if (::thermalGuardManager.isInitialized) {
            thermalGuardManager.register()
        }
        if (::gyroStabilizer.isInitialized) {
            gyroStabilizer.register()
        }
        rotationSensor?.let {
            sensorManager.registerListener(sensorEventListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        syncWorldArLifecycleAndPower()
        syncStylizeLoopState()
    }

    override fun onPause() {
        super.onPause()
        stylizeHandler.removeCallbacks(stylizeRunnable)
        isStylizeLoopScheduled = false
        if (::ecoPowerManager.isInitialized) {
            ecoPowerManager.unregister()
        }
        if (::thermalGuardManager.isInitialized) {
            thermalGuardManager.unregister()
        }
        if (::gyroStabilizer.isInitialized) {
            gyroStabilizer.unregister()
        }
        worldArTracker?.stop()
        sensorManager.unregisterListener(sensorEventListener)
    }

    private fun syncWorldArLifecycleAndPower() {
        val ecoLowPower = ::ecoPowerManager.isInitialized && ecoPowerManager.isEcoActive
        val thermalLowPower = ::thermalGuardManager.isInitialized && thermalGuardManager.isThrottled
        worldArTracker?.setLowPowerMode(ecoLowPower || thermalLowPower)

        val arHardBlocked = (::ecoPowerManager.isInitialized && ecoPowerManager.isArDisabled) ||
            (::thermalGuardManager.isInitialized && thermalGuardManager.isArDisabled)
        if (viewBinding.arFaceOverlayView.worldArEnabled && !arHardBlocked) {
            worldArTracker?.start()
        } else {
            worldArTracker?.stop()
        }
    }

    private fun applyEcoPolicy(policy: EcoPowerManager.EcoPolicy) {
        val active = policy.tier != EcoPowerManager.EcoTier.NORMAL
        val tierLabel = when (policy.tier) {
            EcoPowerManager.EcoTier.ULTRA_ECO -> "ULTRA ECO"
            EcoPowerManager.EcoTier.ECO_SAVER -> "ECO"
            EcoPowerManager.EcoTier.NORMAL -> "NORMAL"
        }
        viewBinding.proOverlayView.updateEcoStatus(
            active = active,
            tierLabel = tierLabel,
            batteryPct = policy.batteryPercent,
            fpsCap = policy.targetAnalysisFps
        )

        val prevTier = lastAppliedEcoTier
        lastAppliedEcoTier = policy.tier
        if (policy.tier == prevTier) return

        if (active) {
            if (policy.disableHeavyAr) {
                if (currentArLensName != "none") {
                    savedArLensBeforeEco = currentArLensName
                    setArLens("none", showNotice = false)
                }
                if (viewBinding.arFaceOverlayView.enableComicOnomatopoeia) {
                    viewBinding.arFaceOverlayView.enableComicOnomatopoeia = false
                }
                if (viewBinding.arFaceOverlayView.enableComicSpeechBubble) {
                    viewBinding.arFaceOverlayView.enableComicSpeechBubble = false
                }
                viewBinding.arFaceOverlayView.clear()
            }
            if (policy.disableMultiCamPip && isMultiCamPipEnabled && recording == null) {
                savedPipBeforeEco = true
                setMultiCamPipEnabled(false)
            } else if (!isPanoramaCapturing && !isNightStacking && recording == null) {
                bindCameraUseCases()
            }
            showHudNotice(
                "${tierLabel} (${policy.batteryPercent}%): ImageAnalysis ${policy.targetAnalysisFps} FPS · AR OFF",
                isWarning = true
            )
        } else {
            val restoreLens = savedArLensBeforeEco
            savedArLensBeforeEco = null
            val thermalBlocksAr = ::thermalGuardManager.isInitialized && thermalGuardManager.isArDisabled
            if (!thermalBlocksAr && !restoreLens.isNullOrBlank() && restoreLens != "none") {
                setArLens(restoreLens, showNotice = false)
            }
            val restorePip = savedPipBeforeEco
            savedPipBeforeEco = false
            val thermalBlocksPip = ::thermalGuardManager.isInitialized && thermalGuardManager.isMultiCamDisabled
            if (!thermalBlocksPip && restorePip && recording == null) {
                setMultiCamPipEnabled(true)
            } else if (!isPanoramaCapturing && !isNightStacking && recording == null) {
                bindCameraUseCases()
            }
            showHudNotice("POWER RESTORED (${policy.batteryPercent}%): Full ImageAnalysis & AR Active")
        }
        syncWorldArLifecycleAndPower()
        syncStylizeLoopState()
    }

    private fun applyThermalPolicy(policy: ThermalGuardManager.ThermalPolicy) {
        val throttled = policy.tier.severityRank >= ThermalGuardManager.ThermalTier.MODERATE.severityRank
        val capLabel = when (policy.tier) {
            ThermalGuardManager.ThermalTier.EMERGENCY -> "720p/SD"
            ThermalGuardManager.ThermalTier.CRITICAL -> "720p/HD"
            ThermalGuardManager.ThermalTier.SEVERE -> "1080p/FHD"
            ThermalGuardManager.ThermalTier.MODERATE -> "LITE AI"
            ThermalGuardManager.ThermalTier.NOMINAL -> "FULL"
        }
        viewBinding.proOverlayView.updateThermalStatus(
            throttled = throttled,
            tierLabel = policy.statusLabel,
            fpsCap = policy.targetAnalysisFps,
            captureCapLabel = capLabel
        )

        val prevTier = lastAppliedThermalTier
        lastAppliedThermalTier = policy.tier
        if (policy.tier == prevTier) return

        if (throttled) {
            if (policy.disableHeavyAr) {
                if (currentArLensName != "none") {
                    savedArLensBeforeThermal = currentArLensName
                    setArLens("none", showNotice = false)
                }
                if (viewBinding.arFaceOverlayView.enableComicOnomatopoeia) {
                    viewBinding.arFaceOverlayView.enableComicOnomatopoeia = false
                }
                if (viewBinding.arFaceOverlayView.enableComicSpeechBubble) {
                    viewBinding.arFaceOverlayView.enableComicSpeechBubble = false
                }
                viewBinding.arFaceOverlayView.clear()
            }
            if (policy.disableMultiCamPip && isMultiCamPipEnabled && recording == null) {
                savedPipBeforeThermal = true
                setMultiCamPipEnabled(false)
            } else if (!isPanoramaCapturing && !isNightStacking && recording == null) {
                bindCameraUseCases()
            }
            showHudNotice(
                "THERMAL ${policy.statusLabel}: Analysis ${policy.targetAnalysisFps} FPS · Capture ${capLabel}",
                isWarning = true
            )
        } else {
            val ecoBlocksAr = ::ecoPowerManager.isInitialized && ecoPowerManager.isArDisabled
            val restoreLens = savedArLensBeforeThermal
            savedArLensBeforeThermal = null
            if (!ecoBlocksAr && !restoreLens.isNullOrBlank() && restoreLens != "none") {
                setArLens(restoreLens, showNotice = false)
            }
            val ecoBlocksPip = ::ecoPowerManager.isInitialized && ecoPowerManager.currentPolicy.disableMultiCamPip
            val restorePip = savedPipBeforeThermal
            savedPipBeforeThermal = false
            if (!ecoBlocksPip && restorePip && recording == null) {
                setMultiCamPipEnabled(true)
            } else if (!isPanoramaCapturing && !isNightStacking && recording == null) {
                bindCameraUseCases()
            }
            showHudNotice("THERMAL NOMINAL: Full CameraX Analysis & Capture Resolution Restored")
        }
        syncWorldArLifecycleAndPower()
        syncStylizeLoopState()
    }

    private fun applyGyroEisTelemetry(telemetry: GyroStabilizer.StabilizationTelemetry) {
        viewBinding.proOverlayView.updateGyroEisTelemetry(
            active = telemetry.isEnabled,
            syntheticDemo = telemetry.isSyntheticDemo,
            overscanZoom = telemetry.overscanZoom,
            compXNorm = telemetry.compXNorm,
            compYNorm = telemetry.compYNorm,
            rawXNorm = telemetry.rawDeflectionX,
            rawYNorm = telemetry.rawDeflectionY,
            rollDeg = telemetry.compRollDeg,
            reductionPct = telemetry.shakeReductionPct
        )

        if (telemetry.isEnabled) {
            val modeTxt = if (telemetry.isSyntheticDemo) "SIM" else if (telemetry.gyroAvailable) "GYRO" else "ROT"
            viewBinding.proStudioDrawer.tvGyroEisStatus.text = "$modeTxt EIS · -${telemetry.shakeReductionPct}% shake · ${String.format(Locale.US, "%.1f", telemetry.angularVelocityDegPerSec)}°/s"
        } else {
            viewBinding.proStudioDrawer.tvGyroEisStatus.text = if (::gyroStabilizer.isInitialized && gyroStabilizer.isGyroHardwareAvailable) {
                "Sensor.TYPE_GYROSCOPE · Preview & Video EIS"
            } else {
                "Rotation Vector / Demo Shake · Preview & Video EIS"
            }
        }

        // Apply View-level Preview EIS when CameraEffect is unbound (Photo modes or HAL fallback)
        if (cameraEffect == null && currentShootingEffect != "dolly_zoom" && currentShootingEffect != "macro_focus") {
            if (telemetry.isEnabled) {
                val vw = viewBinding.viewFinder.width.toFloat().coerceAtLeast(1f)
                val vh = viewBinding.viewFinder.height.toFloat().coerceAtLeast(1f)
                viewBinding.viewFinder.scaleX = telemetry.overscanZoom
                viewBinding.viewFinder.scaleY = telemetry.overscanZoom
                viewBinding.viewFinder.translationX = telemetry.compXNorm * vw * 0.5f
                viewBinding.viewFinder.translationY = -telemetry.compYNorm * vh * 0.5f
                if (currentShootingEffect != "action_stabilization" && currentShootingEffect != "hyperlapse") {
                    viewBinding.viewFinder.rotation = telemetry.compRollDeg
                }
            } else {
                viewBinding.viewFinder.translationX = 0f
                viewBinding.viewFinder.translationY = 0f
                if (currentShootingEffect != "action_stabilization" && currentShootingEffect != "hyperlapse") {
                    viewBinding.viewFinder.rotation = 0f
                    viewBinding.viewFinder.scaleX = 1.0f
                    viewBinding.viewFinder.scaleY = 1.0f
                }
            }
        } else if (cameraEffect != null && (viewBinding.viewFinder.translationX != 0f || viewBinding.viewFinder.translationY != 0f)) {
            viewBinding.viewFinder.translationX = 0f
            viewBinding.viewFinder.translationY = 0f
        }
    }

    enum class ShelfCategory { GRIDS, EXPOSURE, OPTICS, AUDIO }
    private var currentShelfTab = ShelfCategory.GRIDS
    private var isAudioMuted = false
    private var currentAspectRatio = "4:3"
    private var currentDeSqueeze = "OFF"
    private var currentFilterName = "normal"
    private var currentArLensName = "none"
    private var currentShootingEffect = "none"
    private var dollyZoomAnimator: android.animation.ValueAnimator? = null
    private var smoothRollDeg: Float = 0f

    enum class CreativeTarget { FILTERS, EFFECTS, ART, TOON }
    private var currentCreativeTarget = CreativeTarget.FILTERS
    private val artEffectIds = setOf(
        "oil_canvas", "charcoal_sketch", "watercolor_wash", "ink_and_line",
        "pastel_chalk", "halftone_print", "cyanotype", "woodcut_engraving",
        "lithograph", "prism_refraction", "kaleidoscope", "solarize",
        "neon_edge", "noir_graphic_novel", "thermal_vision"
    )
    private val toonEffectIds = setOf(
        "cel_shaded_studio", "graphic_novel_noir", "manga_ink", "pop_art_print",
        "line_art_minimalist", "ligne_claire", "clay_vinyl_3d"
    )
    private val filterPresetOrder = mutableListOf(
        "normal",
        "lut_rec709", "lut_flat_log", "lut_teal_orange", "lut_bleach_bypass",
        "lut_anamorphic_cyan", "lut_matte_cinema", "lut_midnight_neon", "lut_warm_documentary", "lut_noir_cinema",
        "golden_nostalgia", "velvia_landscape", "nordic_slate", "arctic_mist",
        "classic_mono", "high_contrast_noir", "ilford_grain", "silver_gelatin", "antique_sepia",
        "cinematic", "vintage", "bw", "cyberpunk", "hdr", "comic_book",
        "portra_400", "fuji_400h", "teal_orange", "noir_contrast", "sunset_warmth",
        "matrix_code", "pastel_dream", "nordic_blue", "super8_vintage"
    )
    private val arLensOrder = mutableListOf(
        "none", "mesh_contour", "cyberpunk_visor", "golden_crown", "scifi_hud", "comic_frame"
    )
    private val toonStyleOrder = listOf(
        "none", "cel_shaded_studio", "graphic_novel_noir", "manga_ink", "pop_art_print",
        "line_art_minimalist", "ligne_claire", "clay_vinyl_3d"
    )
    private val pictureArtOrder = listOf(
        "oil_canvas", "charcoal_sketch", "watercolor_wash", "ink_and_line",
        "pastel_chalk", "halftone_print", "cyanotype", "woodcut_engraving",
        "lithograph", "prism_refraction", "kaleidoscope", "solarize"
    )
    private val videoArtOrder = listOf(
        "neon_edge", "noir_graphic_novel", "thermal_vision"
    )
    private val photoOnlyEffectIds = setOf(
        "portrait_bokeh", "long_exposure", "light_painting", "star_trails",
        "night_sight", "macro_focus", "double_exposure", "panorama"
    )
    private val videoOnlyEffectIds = setOf(
        "video_bokeh", "slow_motion_120_240", "time_lapse", "hyperlapse",
        "anamorphic_flare", "dolly_zoom", "action_stabilization",
        "vintage_super8", "super8_film", "film_16mm", "vhs_tape", "crt_monitor", "stop_motion"
    )
    private var isLastActionCreative = false
    private var currentTextureEffectIndex = 0
    private var isUpdatingEffectSwitches = false
    private var analysisFrameCounter = 0
    private var lastCapturedUri: Uri? = null
    private var lastCapturedMimeType: String? = null

    private fun setupClickListeners() {
        // Lens Flip Switch Listener
        viewBinding.btnSwitchCamera.setOnClickListener { switchCamera() }

        // Bottom-Left Gallery Button Listener - opens ONLY photos & videos captured by this app
        viewBinding.btnGallery.setOnClickListener { openAppMediaGallery() }
        updateGalleryThumbnail()

        // Mode Switcher: Rotating Carousel (Stationary center highlighter, endless rotation)
        viewBinding.tabSlotFarLeft.setOnClickListener {
            if (recording == null && !isPanoramaCapturing) {
                val curIdx = allCameraModes.indexOf(cameraMode)
                setMode(allCameraModes[(curIdx - 2 + allCameraModes.size * 10) % allCameraModes.size], slideDirection = -1)
            }
        }
        viewBinding.tabSlotLeft.setOnClickListener {
            if (recording == null && !isPanoramaCapturing) {
                val curIdx = allCameraModes.indexOf(cameraMode)
                setMode(allCameraModes[(curIdx - 1 + allCameraModes.size * 10) % allCameraModes.size], slideDirection = -1)
            }
        }
        viewBinding.tabSlotRight.setOnClickListener {
            if (recording == null && !isPanoramaCapturing) {
                val curIdx = allCameraModes.indexOf(cameraMode)
                setMode(allCameraModes[(curIdx + 1) % allCameraModes.size], slideDirection = 1)
            }
        }
        viewBinding.tabSlotFarRight.setOnClickListener {
            if (recording == null && !isPanoramaCapturing) {
                val curIdx = allCameraModes.indexOf(cameraMode)
                setMode(allCameraModes[(curIdx + 2) % allCameraModes.size], slideDirection = 1)
            }
        }
        viewBinding.tabSlotCenter.setOnClickListener {
            window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        viewBinding.tabSlowMo.setOnClickListener { setMode(CameraMode.SLOW_MO) }
        viewBinding.tabVideo.setOnClickListener { setMode(CameraMode.VIDEO) }
        viewBinding.tabPhoto.setOnClickListener { setMode(CameraMode.PHOTO) }
        viewBinding.tabPortrait.setOnClickListener { setMode(CameraMode.PORTRAIT) }
        viewBinding.tabNight.setOnClickListener { setMode(CameraMode.NIGHT) }
        viewBinding.tabPanorama.setOnClickListener { setMode(CameraMode.PANORAMA) }
        viewBinding.tabArLens.setOnClickListener { setMode(CameraMode.AR_LENS) }

        // Video Pause/Resume Button
        viewBinding.btnPauseResume.setOnClickListener { toggleVideoPauseResume() }

        // Top Toolbar: Flash Button
        viewBinding.btnFlash.setOnClickListener { toggleFlashMode() }

        // Top Toolbar: Creative Filters / Effects Drawer (strictly Filters, Lens, and Effects only)
        viewBinding.btnFilter.setOnClickListener {
            val isVisible = viewBinding.effectsStudioDrawer.root.visibility == View.VISIBLE
            if (isVisible) {
                viewBinding.effectsStudioDrawer.root.visibility = View.GONE
                isLastActionCreative = false
            } else {
                viewBinding.proStudioDrawer.root.visibility = View.GONE
                viewBinding.layoutGuideFloatingBar.visibility = View.GONE
                viewBinding.effectsStudioDrawer.root.visibility = View.VISIBLE
                selectCreativeCategory(currentCreativeTarget, fromUser = true)
            }
            window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        viewBinding.effectsStudioDrawer.btnCloseEffectsDrawer.setOnClickListener {
            viewBinding.effectsStudioDrawer.root.visibility = View.GONE
            isLastActionCreative = false
            window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        // Top Toolbar: Dedicated Framing Guides & Grids HUD
        viewBinding.btnGuide.setOnClickListener {
            val isVisible = viewBinding.layoutGuideFloatingBar.visibility == View.VISIBLE
            viewBinding.layoutGuideFloatingBar.visibility = if (isVisible) View.GONE else View.VISIBLE
            if (!isVisible) {
                viewBinding.effectsStudioDrawer.root.visibility = View.GONE
                isLastActionCreative = false
                viewBinding.proStudioDrawer.root.visibility = View.GONE
            }
            window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        viewBinding.btnCloseGuideBar.setOnClickListener {
            viewBinding.layoutGuideFloatingBar.visibility = View.GONE
            window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        viewBinding.btnQuickRotateSpiral.setOnClickListener {
            val nextRot = (viewBinding.proOverlayView.spiralOrientation + 1) % 4
            setSpiralOrientation(nextRot)
            window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        // Quick Grid Chips on Floating Guide HUD
        viewBinding.btnQuickGridOff.setOnClickListener { setGridMode(ProOverlayView.GridType.NONE) }
        viewBinding.btnQuickGridThirds.setOnClickListener { setGridMode(ProOverlayView.GridType.RULE_OF_THIRDS) }
        viewBinding.btnQuickGridSpiral.setOnClickListener { setGridMode(ProOverlayView.GridType.GOLDEN_SPIRAL) }
        viewBinding.btnQuickGridPhi.setOnClickListener { setGridMode(ProOverlayView.GridType.GOLDEN_RATIO_PHI) }
        viewBinding.btnQuickGridDiagonal.setOnClickListener { setGridMode(ProOverlayView.GridType.DIAGONAL_SYMMETRY) }
        viewBinding.btnQuickGridCinematic.setOnClickListener { setGridMode(ProOverlayView.GridType.CINEMATIC_239) }

        // Creative Drawer Category Switcher Buttons (FILTERS, EFFECTS, ART, TOON — AR Lenses moved to AR LENS Camera Mode)
        viewBinding.effectsStudioDrawer.btnCategoryFilters.setOnClickListener { selectCreativeCategory(CreativeTarget.FILTERS, fromUser = true) }
        viewBinding.effectsStudioDrawer.btnCategoryEffects.setOnClickListener { selectCreativeCategory(CreativeTarget.EFFECTS, fromUser = true) }
        viewBinding.effectsStudioDrawer.btnCategoryArt.setOnClickListener { selectCreativeCategory(CreativeTarget.ART, fromUser = true) }
        viewBinding.effectsStudioDrawer.btnCategoryToon.setOnClickListener { selectCreativeCategory(CreativeTarget.TOON, fromUser = true) }

        // Top Toolbar: Self-Timer Toggle (Photo Mode)
        viewBinding.btnTimer.setOnClickListener { cycleCountdownTimer() }

        // Top Toolbar: Auto Trigger Interval Loop (Video Mode)
        viewBinding.btnAutoLoop.setOnClickListener { cycleAutoLoopTimer() }

        // Top Toolbar: Aspect Ratio Quick Cycle (4:3 -> 16:9 -> 1:1)
        viewBinding.btnAspectRatio.setOnClickListener { cycleAspectRatio() }

        // Top Toolbar: Clean Screen 1-Tap Toggle (Hide all HUD overlays)
        viewBinding.btnCleanScreen.setOnClickListener { toggleCleanScreen() }
        viewBinding.proStudioDrawer.btnCleanView.setOnClickListener {
            toggleCleanScreen()
            viewBinding.proStudioDrawer.root.visibility = View.GONE
            viewBinding.effectsStudioDrawer.root.visibility = View.GONE
            isLastActionCreative = false
            viewBinding.layoutGuideFloatingBar.visibility = View.GONE
        }

        // Top Toolbar: Pro Studio Drawer Open / Close (Single unified entry point)
        viewBinding.btnProStudio.setOnClickListener {
            toggleProDrawer()
        }
        viewBinding.proStudioDrawer.btnCloseProDrawer.setOnClickListener {
            viewBinding.proStudioDrawer.root.visibility = View.GONE
        }

        // Top Toolbar: Audio Mute Toggle
        viewBinding.btnAudioMute.setOnClickListener { toggleAudioMute() }

        // 4 Category Tabs Bar for Camera Pro Tools
        viewBinding.proStudioDrawer.tabCategoryGrids.setOnClickListener { selectShelfTab(ShelfCategory.GRIDS) }
        viewBinding.proStudioDrawer.tabCategoryExposure.setOnClickListener { selectShelfTab(ShelfCategory.EXPOSURE) }
        viewBinding.proStudioDrawer.tabCategoryOptics.setOnClickListener { selectShelfTab(ShelfCategory.OPTICS) }
        viewBinding.proStudioDrawer.tabCategoryAudio.setOnClickListener { selectShelfTab(ShelfCategory.AUDIO) }

        // TAB 0: Looks, Filters (including Video Cinema LUTs), AR Lenses & Effects
        viewBinding.effectsStudioDrawer.btnFilterNormal.setOnClickListener { setFilterPreset("normal") }
        viewBinding.effectsStudioDrawer.btnFilterLutRec709.setOnClickListener { setFilterPreset("lut_rec709") }
        viewBinding.effectsStudioDrawer.btnFilterLutFlatLog.setOnClickListener { setFilterPreset("lut_flat_log") }
        viewBinding.effectsStudioDrawer.btnFilterLutTealOrange.setOnClickListener { setFilterPreset("lut_teal_orange") }
        viewBinding.effectsStudioDrawer.btnFilterLutBleachBypass.setOnClickListener { setFilterPreset("lut_bleach_bypass") }
        viewBinding.effectsStudioDrawer.btnFilterLutAnamorphicCyan.setOnClickListener { setFilterPreset("lut_anamorphic_cyan") }
        viewBinding.effectsStudioDrawer.btnFilterLutMatteCinema.setOnClickListener { setFilterPreset("lut_matte_cinema") }
        viewBinding.effectsStudioDrawer.btnFilterLutMidnightNeon.setOnClickListener { setFilterPreset("lut_midnight_neon") }
        viewBinding.effectsStudioDrawer.btnFilterLutWarmDoc.setOnClickListener { setFilterPreset("lut_warm_documentary") }
        viewBinding.effectsStudioDrawer.btnFilterLutNoirCinema.setOnClickListener { setFilterPreset("lut_noir_cinema") }
        viewBinding.effectsStudioDrawer.btnFilterGoldenNostalgia.setOnClickListener { setFilterPreset("golden_nostalgia") }
        viewBinding.effectsStudioDrawer.btnFilterVelvia.setOnClickListener { setFilterPreset("velvia_landscape") }
        viewBinding.effectsStudioDrawer.btnFilterNordicSlate.setOnClickListener { setFilterPreset("nordic_slate") }
        viewBinding.effectsStudioDrawer.btnFilterArcticMist.setOnClickListener { setFilterPreset("arctic_mist") }
        viewBinding.effectsStudioDrawer.btnFilterClassicMono.setOnClickListener { setFilterPreset("classic_mono") }
        viewBinding.effectsStudioDrawer.btnFilterNoir.setOnClickListener { setFilterPreset("high_contrast_noir") }
        viewBinding.effectsStudioDrawer.btnFilterIlfordGrain.setOnClickListener { setFilterPreset("ilford_grain") }
        viewBinding.effectsStudioDrawer.btnFilterSilverGelatin.setOnClickListener { setFilterPreset("silver_gelatin") }
        viewBinding.effectsStudioDrawer.btnFilterAntiqueSepia.setOnClickListener { setFilterPreset("antique_sepia") }
        viewBinding.effectsStudioDrawer.btnFilterCinematic.setOnClickListener { setFilterPreset("cinematic") }
        viewBinding.effectsStudioDrawer.btnFilterVintage.setOnClickListener { setFilterPreset("vintage") }
        viewBinding.effectsStudioDrawer.btnFilterBW.setOnClickListener { setFilterPreset("bw") }
        viewBinding.effectsStudioDrawer.btnFilterCyber.setOnClickListener { setFilterPreset("cyberpunk") }
        viewBinding.effectsStudioDrawer.btnFilterHDR.setOnClickListener { setFilterPreset("hdr") }
        viewBinding.effectsStudioDrawer.btnFilterComicBook.setOnClickListener { setFilterPreset("comic_book") }
        viewBinding.effectsStudioDrawer.btnFilterPortra400.setOnClickListener { setFilterPreset("portra_400") }
        viewBinding.effectsStudioDrawer.btnFilterFuji400h.setOnClickListener { setFilterPreset("fuji_400h") }
        viewBinding.effectsStudioDrawer.btnFilterTealOrange.setOnClickListener { setFilterPreset("teal_orange") }
        viewBinding.effectsStudioDrawer.btnFilterNoirContrast.setOnClickListener { setFilterPreset("noir_contrast") }
        viewBinding.effectsStudioDrawer.btnFilterSunsetWarmth.setOnClickListener { setFilterPreset("sunset_warmth") }
        viewBinding.effectsStudioDrawer.btnFilterMatrixCode.setOnClickListener { setFilterPreset("matrix_code") }
        viewBinding.effectsStudioDrawer.btnFilterPastelDream.setOnClickListener { setFilterPreset("pastel_dream") }
        viewBinding.effectsStudioDrawer.btnFilterNordicBlue.setOnClickListener { setFilterPreset("nordic_blue") }
        viewBinding.effectsStudioDrawer.btnFilterSuper8Vintage.setOnClickListener { setFilterPreset("super8_vintage") }

        viewBinding.arLensModeDock.btnArNone.setOnClickListener { setArLens("none") }
        viewBinding.arLensModeDock.btnArMesh.setOnClickListener { setArLens("mesh_contour") }
        viewBinding.arLensModeDock.btnArVisor.setOnClickListener { setArLens("cyberpunk_visor") }
        viewBinding.arLensModeDock.btnArCrown.setOnClickListener { setArLens("golden_crown") }
        viewBinding.arLensModeDock.btnArHud.setOnClickListener { setArLens("scifi_hud") }
        viewBinding.arLensModeDock.btnArComicFrame.setOnClickListener { setArLens("comic_frame") }
        viewBinding.arLensModeDock.btnArImportGlb.setOnClickListener { launchGlbLensPicker() }
        viewBinding.arLensModeDock.btnArComicOnomatopoeia.setOnClickListener { toggleComicOnomatopoeia() }
        viewBinding.arLensModeDock.btnArTextStudio.setOnClickListener { showArTextStudioDialog() }
        viewBinding.arLensModeDock.btnArCalibrate.setOnClickListener { toggleArCalibrationDrawer() }
        viewBinding.arLensModeDock.btnArBatch.setOnClickListener { toggleArBatchMode() }

        viewBinding.effectsStudioDrawer.btnEffectOff.setOnClickListener { setShootingEffect("none") }
        viewBinding.effectsStudioDrawer.btnArtOff.setOnClickListener { setShootingEffect("none") }
        viewBinding.effectsStudioDrawer.btnToonOff.setOnClickListener { setShootingEffect("none") }
        viewBinding.effectsStudioDrawer.btnEffectBokeh.setOnClickListener { setShootingEffect("portrait_bokeh") }
        viewBinding.effectsStudioDrawer.btnEffectLongExp.setOnClickListener { setShootingEffect("long_exposure") }
        viewBinding.effectsStudioDrawer.btnEffectLightPaint.setOnClickListener { setShootingEffect("light_painting") }
        viewBinding.effectsStudioDrawer.btnEffectStarTrails.setOnClickListener { setShootingEffect("star_trails") }
        viewBinding.effectsStudioDrawer.btnEffectNightSight.setOnClickListener { setShootingEffect("night_sight") }
        viewBinding.effectsStudioDrawer.btnEffectMacro.setOnClickListener { setShootingEffect("macro_focus") }
        viewBinding.effectsStudioDrawer.btnEffectTiltShift.setOnClickListener { setShootingEffect("tilt_shift") }
        viewBinding.effectsStudioDrawer.btnEffectDoubleExp.setOnClickListener { setShootingEffect("double_exposure") }
        viewBinding.effectsStudioDrawer.btnEffectPanorama.setOnClickListener { setShootingEffect("panorama") }
        viewBinding.effectsStudioDrawer.btnEffectFilmGrain.setOnClickListener { setShootingEffect("film_grain") }
        viewBinding.effectsStudioDrawer.btnEffectHalation.setOnClickListener { setShootingEffect("soft_halation") }
        viewBinding.effectsStudioDrawer.btnEffectVignette.setOnClickListener { setShootingEffect("vignette") }
        viewBinding.effectsStudioDrawer.btnEffectVideoBokeh.setOnClickListener { setShootingEffect("video_bokeh") }
        viewBinding.effectsStudioDrawer.btnEffectSlowMotion.setOnClickListener { setShootingEffect("slow_motion_120_240") }
        viewBinding.effectsStudioDrawer.btnEffectTimeLapse.setOnClickListener { setShootingEffect("time_lapse") }
        viewBinding.effectsStudioDrawer.btnEffectHyperlapse.setOnClickListener { setShootingEffect("hyperlapse") }
        viewBinding.effectsStudioDrawer.btnEffectAnamorphicFlare.setOnClickListener { setShootingEffect("anamorphic_flare") }
        viewBinding.effectsStudioDrawer.btnEffectDollyZoom.setOnClickListener { setShootingEffect("dolly_zoom") }
        viewBinding.effectsStudioDrawer.btnEffectMotionBlur.setOnClickListener { setShootingEffect("motion_blur") }
        viewBinding.effectsStudioDrawer.btnEffectHorizonLock.setOnClickListener { setShootingEffect("action_stabilization") }
        viewBinding.effectsStudioDrawer.btnEffectSuper8.setOnClickListener { setShootingEffect("vintage_super8") }
        viewBinding.effectsStudioDrawer.btnEffectOilCanvas.setOnClickListener { setShootingEffect("oil_canvas") }
        viewBinding.effectsStudioDrawer.btnEffectCharcoalSketch.setOnClickListener { setShootingEffect("charcoal_sketch") }
        viewBinding.effectsStudioDrawer.btnEffectWatercolorWash.setOnClickListener { setShootingEffect("watercolor_wash") }
        viewBinding.effectsStudioDrawer.btnEffectInkLine.setOnClickListener { setShootingEffect("ink_and_line") }
        viewBinding.effectsStudioDrawer.btnEffectPastelChalk.setOnClickListener { setShootingEffect("pastel_chalk") }
        viewBinding.effectsStudioDrawer.btnEffectHalftonePrint.setOnClickListener { setShootingEffect("halftone_print") }
        viewBinding.effectsStudioDrawer.btnEffectCyanotype.setOnClickListener { setShootingEffect("cyanotype") }
        viewBinding.effectsStudioDrawer.btnEffectWoodcut.setOnClickListener { setShootingEffect("woodcut_engraving") }
        viewBinding.effectsStudioDrawer.btnEffectLithograph.setOnClickListener { setShootingEffect("lithograph") }
        viewBinding.effectsStudioDrawer.btnEffectPrism.setOnClickListener { setShootingEffect("prism_refraction") }
        viewBinding.effectsStudioDrawer.btnEffectKaleidoscope.setOnClickListener { setShootingEffect("kaleidoscope") }
        viewBinding.effectsStudioDrawer.btnEffectSolarize.setOnClickListener { setShootingEffect("solarize") }
        viewBinding.effectsStudioDrawer.btnEffectFilm16mm.setOnClickListener { setShootingEffect("film_16mm") }
        viewBinding.effectsStudioDrawer.btnEffectVhsTape.setOnClickListener { setShootingEffect("vhs_tape") }
        viewBinding.effectsStudioDrawer.btnEffectCrtMonitor.setOnClickListener { setShootingEffect("crt_monitor") }
        viewBinding.effectsStudioDrawer.btnEffectStopMotion.setOnClickListener { setShootingEffect("stop_motion") }
        viewBinding.effectsStudioDrawer.btnEffectNeonEdge.setOnClickListener { setShootingEffect("neon_edge") }
        viewBinding.effectsStudioDrawer.btnEffectNoirNovel.setOnClickListener { setShootingEffect("noir_graphic_novel") }
        viewBinding.effectsStudioDrawer.btnEffectThermalVision.setOnClickListener { setShootingEffect("thermal_vision") }
        viewBinding.effectsStudioDrawer.btnEffectCelShaded.setOnClickListener { setShootingEffect("cel_shaded_studio") }
        viewBinding.effectsStudioDrawer.btnEffectGraphicNoir.setOnClickListener { setShootingEffect("graphic_novel_noir") }
        viewBinding.effectsStudioDrawer.btnEffectMangaInk.setOnClickListener { setShootingEffect("manga_ink") }
        viewBinding.effectsStudioDrawer.btnEffectPopArt.setOnClickListener { setShootingEffect("pop_art_print") }
        viewBinding.effectsStudioDrawer.btnEffectLineArt.setOnClickListener { setShootingEffect("line_art_minimalist") }
        viewBinding.effectsStudioDrawer.btnEffectLigneClaire.setOnClickListener { setShootingEffect("ligne_claire") }
        viewBinding.effectsStudioDrawer.btnEffectClayVinyl.setOnClickListener { setShootingEffect("clay_vinyl_3d") }

        viewBinding.effectsStudioDrawer.switchFilmGrain.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.enableFilmGrain = isChecked
            if (!isUpdatingEffectSwitches) {
                currentCreativeTarget = CreativeTarget.EFFECTS
                isLastActionCreative = true
                if (isChecked) {
                    currentShootingEffect = "film_grain"
                    viewBinding.proOverlayView.activeShootingEffect = "film_grain"
                    isUpdatingEffectSwitches = true
                    viewBinding.effectsStudioDrawer.switchCineFlare.isChecked = false
                    viewBinding.proOverlayView.enableCineFlare = false
                    isUpdatingEffectSwitches = false
                } else if (currentShootingEffect == "film_grain") {
                    currentShootingEffect = "none"
                    viewBinding.proOverlayView.activeShootingEffect = "none"
                }
                syncAllStateToOpenGLProcessor()
                if (!isStylizedLiveEffect(currentShootingEffect) && !isComicFilterPreset(currentFilterName)) {
                    viewBinding.proOverlayView.updateProcessedEffectBitmap(null)
                }
                updateShootingEffectButtons()
                updateEffectIntensityVisibility()
                updateCreativeStatusUI()
                syncStylizeLoopState()
                showHudNotice("35mm Film Grain ${if (isChecked) "ON" else "OFF"}")
            }
        }
        viewBinding.effectsStudioDrawer.switchDateStamp.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.enableDateStamp = isChecked
            if (!isUpdatingEffectSwitches) {
                currentCreativeTarget = CreativeTarget.EFFECTS
                isLastActionCreative = true
                updateCreativeStatusUI()
                showHudNotice("Retro Date Stamp ${if (isChecked) "ON" else "OFF"}")
            }
        }
        viewBinding.effectsStudioDrawer.switchCineFlare.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.enableCineFlare = isChecked
            if (!isUpdatingEffectSwitches) {
                currentCreativeTarget = CreativeTarget.EFFECTS
                isLastActionCreative = true
                if (isChecked) {
                    currentShootingEffect = "anamorphic_flare"
                    viewBinding.proOverlayView.activeShootingEffect = "anamorphic_flare"
                    isUpdatingEffectSwitches = true
                    viewBinding.effectsStudioDrawer.switchFilmGrain.isChecked = false
                    viewBinding.proOverlayView.enableFilmGrain = false
                    isUpdatingEffectSwitches = false
                } else if (currentShootingEffect == "anamorphic_flare") {
                    currentShootingEffect = "none"
                    viewBinding.proOverlayView.activeShootingEffect = "none"
                }
                syncAllStateToOpenGLProcessor()
                if (!isStylizedLiveEffect(currentShootingEffect) && !isComicFilterPreset(currentFilterName)) {
                    viewBinding.proOverlayView.updateProcessedEffectBitmap(null)
                }
                updateShootingEffectButtons()
                updateEffectIntensityVisibility()
                updateCreativeStatusUI()
                syncStylizeLoopState()
                showHudNotice("Anamorphic Flare ${if (isChecked) "ON" else "OFF"}")
            }
        }

        // TAB 1: Framing Grids Symmetrical Buttons
        viewBinding.proStudioDrawer.btnGridOff.setOnClickListener { setGridMode(ProOverlayView.GridType.NONE) }
        viewBinding.proStudioDrawer.btnGridThirds.setOnClickListener { setGridMode(ProOverlayView.GridType.RULE_OF_THIRDS) }
        viewBinding.proStudioDrawer.btnGridPhi.setOnClickListener { setGridMode(ProOverlayView.GridType.GOLDEN_RATIO_PHI) }
        viewBinding.proStudioDrawer.btnGridSpiral.setOnClickListener { setGridMode(ProOverlayView.GridType.GOLDEN_SPIRAL) }
        viewBinding.proStudioDrawer.btnGridDiagonal.setOnClickListener { setGridMode(ProOverlayView.GridType.DIAGONAL_SYMMETRY) }
        viewBinding.proStudioDrawer.btnGridCinematic.setOnClickListener { setGridMode(ProOverlayView.GridType.CINEMATIC_239) }

        // Spiral Rotation Axis (0°, 90°, 180°, 270°)
        viewBinding.proStudioDrawer.btnSpiral0.setOnClickListener { setSpiralOrientation(0) }
        viewBinding.proStudioDrawer.btnSpiral90.setOnClickListener { setSpiralOrientation(1) }
        viewBinding.proStudioDrawer.btnSpiral180.setOnClickListener { setSpiralOrientation(2) }
        viewBinding.proStudioDrawer.btnSpiral270.setOnClickListener { setSpiralOrientation(3) }

        // Aspect Ratio Mattes Symmetrical Buttons
        viewBinding.proStudioDrawer.btnMatteOff.setOnClickListener { setAspectMatteMode(ProOverlayView.AspectMatte.NONE) }
        viewBinding.proStudioDrawer.btnMatte169.setOnClickListener { setAspectMatteMode(ProOverlayView.AspectMatte.WIDESCREEN_16_9) }
        viewBinding.proStudioDrawer.btnMatteCine.setOnClickListener { setAspectMatteMode(ProOverlayView.AspectMatte.CINE_239) }
        viewBinding.proStudioDrawer.btnMatteSquare.setOnClickListener { setAspectMatteMode(ProOverlayView.AspectMatte.SQUARE_1_1) }
        viewBinding.proStudioDrawer.btnMatte916.setOnClickListener { setAspectMatteMode(ProOverlayView.AspectMatte.VERTICAL_9_16) }
        viewBinding.proStudioDrawer.btnMatte45.setOnClickListener { setAspectMatteMode(ProOverlayView.AspectMatte.PORTRAIT_4_5) }

        // Gyro & Balance Switches
        viewBinding.proStudioDrawer.switchHorizonLevel.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.showLevelGauge = isChecked
            viewBinding.proStudioDrawer.tvHorizonLevelStatus.text = if (isChecked) "0.0° Level · Pitch and roll active" else "Horizon Level Gyro: Inactive"
            updateActiveInstrumentsCount()
        }
        viewBinding.proStudioDrawer.switchGyroEis.setOnCheckedChangeListener { _, isChecked ->
            isGyroEisEnabled = isChecked
            if (::gyroStabilizer.isInitialized) {
                // Auto-enable synthetic tremor demo if hardware gyroscope sensor is absent (e.g. emulator)
                if (isChecked && !gyroStabilizer.isGyroHardwareAvailable && !gyroStabilizer.isSyntheticShakeDemo) {
                    gyroStabilizer.setSyntheticShakeDemo(true)
                    viewBinding.proStudioDrawer.btnGyroSimShake.setBackgroundResource(R.drawable.bg_cinema_button_active)
                    viewBinding.proStudioDrawer.btnGyroSimShake.setTextColor(Color.parseColor("#0F172A"))
                }
                gyroStabilizer.setEnabled(isChecked)
            }
            if (!isPanoramaCapturing && !isNightStacking && recording == null) {
                bindCameraUseCases()
            }
            updateActiveInstrumentsCount()
            showHudNotice(
                if (isChecked) "Gyro EIS ON · Preview & Video Stabilized (1.22x Crop)" else "Gyro EIS OFF"
            )
        }
        viewBinding.proStudioDrawer.btnGyroSimShake.setOnClickListener {
            if (!::gyroStabilizer.isInitialized) return@setOnClickListener
            val nextDemo = !gyroStabilizer.isSyntheticShakeDemo
            gyroStabilizer.setSyntheticShakeDemo(nextDemo)
            if (nextDemo && !isGyroEisEnabled) {
                viewBinding.proStudioDrawer.switchGyroEis.isChecked = true
            }
            if (nextDemo) {
                viewBinding.proStudioDrawer.btnGyroSimShake.setBackgroundResource(R.drawable.bg_cinema_button_active)
                viewBinding.proStudioDrawer.btnGyroSimShake.setTextColor(Color.parseColor("#0F172A"))
                showHudNotice("Gyro EIS Shake Simulator ON")
            } else {
                viewBinding.proStudioDrawer.btnGyroSimShake.setBackgroundResource(R.drawable.bg_cinema_button_inactive)
                viewBinding.proStudioDrawer.btnGyroSimShake.setTextColor(Color.parseColor("#94A3B8"))
                showHudNotice("Live Hardware Gyroscope Active")
            }
        }
        viewBinding.proStudioDrawer.switchVisualBalance.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.showVisualBalance = isChecked
            viewBinding.proStudioDrawer.tvVisualBalanceStatus.text = if (isChecked) "Balanced subject framing active" else "Visual Balance Reticle: Inactive"
            updateActiveInstrumentsCount()
        }

        // TAB 2: Exposure Switches & Instruments
        viewBinding.proStudioDrawer.switchZebra.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.showZebraStripes = isChecked
            viewBinding.proStudioDrawer.tvZebraStatus.text = if (isChecked) "Clipping warning (${viewBinding.proOverlayView.zebraThreshold}% IRE)" else "Zebra warning disabled"
            updateActiveInstrumentsCount()
        }
        viewBinding.proStudioDrawer.btnZebraThreshold.setOnClickListener {
            val next = if (viewBinding.proOverlayView.zebraThreshold == 240) 250 else 240
            viewBinding.proOverlayView.zebraThreshold = next
            viewBinding.proStudioDrawer.btnZebraThreshold.text = if (next == 250) "98%" else "95%"
            viewBinding.proStudioDrawer.tvZebraStatus.text = "Clipping warning (${if (next == 250) "98%" else "95%"} IRE)"
            // silent (no popup)
        }
        viewBinding.proStudioDrawer.switchWaveform.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.showWaveform = isChecked
            updateActiveInstrumentsCount()
        }
        viewBinding.proStudioDrawer.switchHistogram.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.showHistogram = isChecked
            updateActiveInstrumentsCount()
        }
        viewBinding.proStudioDrawer.switchVectorScope.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.showVectorScope = isChecked
            updateActiveInstrumentsCount()
        }

        // TAB 3: Optics Lenses (0.6x, 1x, 2x, 5x)
        viewBinding.proStudioDrawer.btnLens06.setOnClickListener { setZoomLevel(0.6f, 0) }
        viewBinding.proStudioDrawer.btnLens1.setOnClickListener { setZoomLevel(1.0f, 1) }
        viewBinding.proStudioDrawer.btnLens2.setOnClickListener { setZoomLevel(2.0f, 2) }
        viewBinding.proStudioDrawer.btnLens5.setOnClickListener { setZoomLevel(5.0f, 3) }

        // De-Squeeze (OFF, 1.33x, 1.55x, 1.8x, 2.0x)
        viewBinding.proStudioDrawer.btnSqueezeOff.setOnClickListener { setDeSqueeze("OFF", 0) }
        viewBinding.proStudioDrawer.btnSqueeze133.setOnClickListener { setDeSqueeze("1.33x", 1) }
        viewBinding.proStudioDrawer.btnSqueeze155.setOnClickListener { setDeSqueeze("1.55x", 2) }
        viewBinding.proStudioDrawer.btnSqueeze180.setOnClickListener { setDeSqueeze("1.8x", 3) }
        viewBinding.proStudioDrawer.btnSqueeze200.setOnClickListener { setDeSqueeze("2.0x", 4) }

        // TAB 4: Audio Switches
        viewBinding.proStudioDrawer.switchAudioVuMeter.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.showAudioVuMeter = isChecked
            updateActiveInstrumentsCount()
        }
        viewBinding.proStudioDrawer.switchMicInput.setOnCheckedChangeListener { _, isChecked ->
            isAudioMuted = !isChecked
            updateAudioMuteUI()
        }
        viewBinding.proStudioDrawer.switchMultiCamPip.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingPipSwitch) return@setOnCheckedChangeListener
            if (recording != null) {
                isUpdatingPipSwitch = true
                viewBinding.proStudioDrawer.switchMultiCamPip.isChecked = isMultiCamPipEnabled
                isUpdatingPipSwitch = false
                Toast.makeText(this, "Stop video recording before changing Multi-Cam PiP", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            setMultiCamPipEnabled(isChecked)
        }

        // Quick Viewfinder Zoom Pills
        viewBinding.btnZoom06.setOnClickListener { setZoomLevel(0.6f, 0) }
        viewBinding.btnZoom1.setOnClickListener { setZoomLevel(1.0f, 1) }
        viewBinding.btnZoom2.setOnClickListener { setZoomLevel(2.0f, 2) }
        viewBinding.btnZoom5.setOnClickListener { setZoomLevel(5.0f, 3) }

        setupViewfinderGestures()
    }

    /**
     * 1. Initialize CameraX ProcessCameraProvider
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val provider = cameraProvider
            if (provider != null) {
                try {
                    val extFuture = ExtensionsManager.getInstanceAsync(this, provider)
                    extFuture.addListener({
                        try {
                            extensionsManager = extFuture.get()
                        } catch (e: Exception) {
                            Log.w(TAG, "CameraX ExtensionsManager unavailable, using computational night mode", e)
                            extensionsManager = null
                        }
                        bindCameraUseCases()
                    }, ContextCompat.getMainExecutor(this))
                } catch (e: Exception) {
                    Log.w(TAG, "ExtensionsManager init failed, binding standard camera", e)
                    bindCameraUseCases()
                }
            } else {
                bindCameraUseCases()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupPipInteractions() {
        viewBinding.btnPipSwap.setOnClickListener {
            switchCamera()
        }
        var dX = 0f
        var dY = 0f
        var moved = false
        viewBinding.pipCardContainer.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    pipLastActionDownX = event.rawX
                    pipLastActionDownY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dist = Math.hypot(
                        (event.rawX - pipLastActionDownX).toDouble(),
                        (event.rawY - pipLastActionDownY).toDouble()
                    )
                    if (dist > 10.0) {
                        moved = true
                        val parentW = (viewBinding.viewFinder.width.takeIf { it > 0 } ?: 1080).toFloat()
                        val parentH = (viewBinding.viewFinder.height.takeIf { it > 0 } ?: 1920).toFloat()
                        val targetX = (event.rawX + dX).coerceIn(12f, (parentW - v.width - 12f).coerceAtLeast(12f))
                        val targetY = (event.rawY + dY).coerceIn(110f, (parentH - v.height - 160f).coerceAtLeast(110f))
                        v.x = targetX
                        v.y = targetY
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        switchCamera()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun findConcurrentFrontBackPair(provider: ProcessCameraProvider): Pair<CameraSelector, CameraSelector>? {
        return try {
            val pairs = provider.availableConcurrentCameraInfos
            for (pair in pairs) {
                val hasBack = CameraSelector.DEFAULT_BACK_CAMERA.filter(pair).isNotEmpty()
                val hasFront = CameraSelector.DEFAULT_FRONT_CAMERA.filter(pair).isNotEmpty()
                if (hasBack && hasFront) {
                    return if (lensFacing == CameraSelector.DEFAULT_BACK_CAMERA) {
                        Pair(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
                    } else {
                        Pair(CameraSelector.DEFAULT_FRONT_CAMERA, CameraSelector.DEFAULT_BACK_CAMERA)
                    }
                }
            }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "ConcurrentCamera capability check failed", t)
            null
        }
    }

    private fun updatePipOverlayVisibility() {
        val showPip = isMultiCamPipEnabled && !viewBinding.proOverlayView.isCleanScreen
        viewBinding.pipCardContainer.visibility = if (showPip) View.VISIBLE else View.GONE
        val secondaryIsFront = (lensFacing == CameraSelector.DEFAULT_BACK_CAMERA)
        viewBinding.tvPipBadge.text = if (secondaryIsFront) "PIP · FRONT" else "PIP · REAR"
        if (!isMultiCamPipEnabled) {
            viewBinding.proStudioDrawer.tvMultiCamPipStatus.text = "Live concurrent dual-camera PiP director view"
        } else {
            viewBinding.proStudioDrawer.tvMultiCamPipStatus.text = if (secondaryIsFront) {
                "Dual-Cam active: Rear main + Front PiP"
            } else {
                "Dual-Cam active: Front main + Rear PiP"
            }
        }
    }

    private fun setMultiCamPipEnabled(enabled: Boolean) {
        isMultiCamPipEnabled = enabled
        updatePipOverlayVisibility()
        updateActiveInstrumentsCount()
        bindCameraUseCases()
    }

    private fun handleConcurrentUnsupportedFallback(reason: String) {
        isMultiCamPipEnabled = false
        concurrentCamera = null
        isUpdatingPipSwitch = true
        viewBinding.proStudioDrawer.switchMultiCamPip.isChecked = false
        isUpdatingPipSwitch = false
        viewBinding.pipCardContainer.visibility = View.GONE
        viewBinding.proStudioDrawer.tvMultiCamPipStatus.text = "Unsupported on device HAL — single camera active"
        updateActiveInstrumentsCount()
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
    }

    /**
     * Bind Preview, ImageCapture, VideoCapture & ImageAnalysis (or ConcurrentCamera PiP) to current lifecycle
     */
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return
        faceMeshHelper?.setFrontFacing(lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA)

        // Preview Use Case
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
        }

        // ImageCapture Use Case (Scales capture mode & resolution under critical thermal conditions)
        val rawCaptureMode = if (cameraMode == CameraMode.NIGHT) {
            ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        } else {
            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
        val desiredCaptureMode = if (::thermalGuardManager.isInitialized) {
            thermalGuardManager.effectiveCaptureMode(rawCaptureMode)
        } else {
            rawCaptureMode
        }
        val imageCaptureBuilder = ImageCapture.Builder()
            .setCaptureMode(desiredCaptureMode)
            .setFlashMode(flashMode)
        if (::thermalGuardManager.isInitialized) {
            thermalGuardManager.currentPolicy.maxImageCaptureResolution?.let { capRes ->
                val capSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(capRes, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    )
                    .build()
                imageCaptureBuilder.setResolutionSelector(capSelector)
            }
        }
        imageCapture = imageCaptureBuilder.build()

        // VideoCapture Use Case with Dynamic + Thermally Capped Quality Selector
        val requestedQuality = resolveVideoQuality()
        val selectedQuality = if (::thermalGuardManager.isInitialized) {
            thermalGuardManager.constrainVideoQuality(requestedQuality)
        } else {
            requestedQuality
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    selectedQuality,
                    FallbackStrategy.lowerQualityOrHigherThan(selectedQuality)
                )
            )
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        // Real-Time Frame Analysis Engine: Live Histogram (Luma & RGB), Zebra Overexposure, Focus Peaking, Waveform & Vector Scope
        // Golden Rule: All pixel math is executed asynchronously on background cameraExecutor thread with pre-allocated buffers.
        val histoBins = IntArray(256)
        val histoBinsR = IntArray(256)
        val histoBinsG = IntArray(256)
        val histoBinsB = IntArray(256)
        val overexposedBuf = FloatArray(48 * 4)
        val emptyFloatArray = FloatArray(0)
        val wfColsArray = FloatArray(36 * 2) // Pre-allocated [minIre, maxIre] pairs
        val vsPointsArray = FloatArray(48 * 2) // Pre-allocated [normCb, normCr] pairs
        val fpPointsArray = FloatArray(180 * 2) // Pre-allocated focus peaking edge [normX, normY] pairs
        var lastLumaAnalysisMs = 0L
        var lastMotionUiMs = 0L
        var lastNightCheckMs = 0L

        val ecoTargetRes = if (::ecoPowerManager.isInitialized) {
            ecoPowerManager.currentPolicy.targetAnalysisResolution
        } else {
            android.util.Size(640, 480)
        }
        val thermalTargetRes = if (::thermalGuardManager.isInitialized) {
            thermalGuardManager.currentPolicy.targetAnalysisResolution
        } else {
            android.util.Size(640, 480)
        }
        val baseAnalysisRes = android.util.Size(
            Math.min(ecoTargetRes.width, thermalTargetRes.width),
            Math.min(ecoTargetRes.height, thermalTargetRes.height)
        )
        val effectiveAnalysisRes = if (cameraMode == CameraMode.PANORAMA && baseAnalysisRes.width >= 640) {
            android.util.Size(1280, 960)
        } else {
            baseAnalysisRes
        }
        val analysisResSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(effectiveAnalysisRes, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()
        imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(analysisResSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    if (::ecoPowerManager.isInitialized && !ecoPowerManager.shouldProcessAnalysisFrame()) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    if (::thermalGuardManager.isInitialized && !thermalGuardManager.shouldProcessAnalysisFrame()) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val nowMs = SystemClock.uptimeMillis()
                    val ecoMetersDisabled = ::ecoPowerManager.isInitialized && ecoPowerManager.isHeavyMetersDisabled
                    val thermalComplexityReduced = ::thermalGuardManager.isInitialized && thermalGuardManager.shouldReduceComplexity
                    val needsLumaMeters = !ecoMetersDisabled && !thermalComplexityReduced && !viewBinding.proOverlayView.isCleanScreen && (
                        viewBinding.proOverlayView.showHistogram ||
                        viewBinding.proOverlayView.showZebraStripes ||
                        viewBinding.proOverlayView.showVisualBalance ||
                        viewBinding.proOverlayView.showWaveform ||
                        viewBinding.proOverlayView.showVectorScope ||
                        viewBinding.proOverlayView.showFocusPeaking
                    )
                    val needsMotionBlurAnalysis = !thermalComplexityReduced && (currentShootingEffect == "motion_blur")

                    if (needsLumaMeters && (nowMs - lastLumaAnalysisMs) >= 68L) {
                        lastLumaAnalysisMs = nowMs
                        try {
                            val plane = imageProxy.planes[0]
                            val buffer = plane.buffer
                            val width = imageProxy.width
                            val height = imageProxy.height
                            val rowStride = plane.rowStride
                            val pixelStride = plane.pixelStride
                            
                            histoBins.fill(0)
                            var overexposedCount = 0
                            
                            val step = 8
                            var clipCount = 0
                            var sumWeightX = 0f
                            var sumWeightY = 0f
                            var totalWeight = 0f

                            // 1. Asynchronous Waveform Extraction across 36 columns on background Executor
                            var peakIre = 0f
                            var overexposedCols = 0
                            if (viewBinding.proOverlayView.showWaveform) {
                                val colWidth = Math.max(1, width / 36)
                                for (col in 0 until 36) {
                                    val startX = col * colWidth
                                    var colMin = 100f
                                    var colMax = 0f
                                    for (y in 0 until height step (step * 2)) {
                                        val rowOffset = y * rowStride
                                        for (x in startX until Math.min(width, startX + colWidth) step (step * 2)) {
                                            val index = rowOffset + x * pixelStride
                                            if (index < buffer.limit()) {
                                                val luma = buffer.get(index).toInt() and 0xFF
                                                val ire = (luma / 255f) * 100f
                                                if (ire < colMin) colMin = ire
                                                if (ire > colMax) colMax = ire
                                            }
                                        }
                                    }
                                    if (colMin > colMax) { colMin = 0f; colMax = 0f }
                                    wfColsArray[col * 2] = colMin
                                    wfColsArray[col * 2 + 1] = colMax
                                    if (colMax > peakIre) peakIre = colMax
                                    if (colMax >= 98f) overexposedCols++
                                }
                            }

                            // 2. Asynchronous Vector Scope Cb/Cr Chrominance Scatter on background Executor
                            var vsPointCount = 0
                            var skinMatchHits = 0
                            if (viewBinding.proOverlayView.showVectorScope && imageProxy.planes.size >= 3) {
                                val uPlane = imageProxy.planes[1]
                                val vPlane = imageProxy.planes[2]
                                val uBuf = uPlane.buffer
                                val vBuf = vPlane.buffer
                                val uRowStride = uPlane.rowStride
                                val uPixelStride = uPlane.pixelStride
                                val vRowStride = vPlane.rowStride
                                val vPixelStride = vPlane.pixelStride

                                val uStep = Math.max(2, width / 12)
                                val vStep = Math.max(2, height / 8)
                                for (y in (height / 6) until (height * 5 / 6) step vStep) {
                                    val uRowOffset = (y / 2) * uRowStride
                                    val vRowOffset = (y / 2) * vRowStride
                                    for (x in (width / 6) until (width * 5 / 6) step uStep) {
                                        if (vsPointCount >= 48) break
                                        val uIdx = uRowOffset + (x / 2) * uPixelStride
                                        val vIdx = vRowOffset + (x / 2) * vPixelStride
                                        if (uIdx < uBuf.limit() && vIdx < vBuf.limit()) {
                                            val uVal = (uBuf.get(uIdx).toInt() and 0xFF) - 128
                                            val vVal = (vBuf.get(vIdx).toInt() and 0xFF) - 128
                                            val normCb = (uVal / 128f).coerceIn(-1f, 1f)
                                            val normCr = -(vVal / 128f).coerceIn(-1f, 1f)
                                            vsPointsArray[vsPointCount * 2] = normCb
                                            vsPointsArray[vsPointCount * 2 + 1] = normCr
                                            vsPointCount++

                                            val angle = (Math.atan2(-normCr.toDouble(), normCb.toDouble()) * 180.0 / Math.PI)
                                            if (angle in 45.0..75.0) skinMatchHits++
                                        }
                                    }
                                }
                            }
                            val skinTonePercent = if (vsPointCount > 0) ((skinMatchHits.toFloat() / vsPointCount) * 100f).coerceIn(45f, 98f) else 88f

                            // 3. 256-Bin Histogram and Optical Center of Mass (Background Loop)
                            for (y in 0 until height step step) {
                                val rowOffset = y * rowStride
                                for (x in 0 until width step step) {
                                    val index = rowOffset + x * pixelStride
                                    if (index < buffer.limit()) {
                                        val luma = buffer.get(index).toInt() and 0xFF
                                        histoBins[luma]++

                                        // Optical visual mass: darker & higher contrast areas carry visual weight
                                        val mass = (255 - luma).coerceAtLeast(15)
                                        sumWeightX += x * mass
                                        sumWeightY += y * mass
                                        totalWeight += mass

                                        if (luma >= 240) {
                                            clipCount++
                                            if (clipCount % 12 == 0 && overexposedCount < 48) {
                                                val nX = x.toFloat() / width.toFloat()
                                                val nY = y.toFloat() / height.toFloat()
                                                val base = overexposedCount * 4
                                                overexposedBuf[base] = nX - 0.04f
                                                overexposedBuf[base + 1] = nY - 0.04f
                                                overexposedBuf[base + 2] = nX + 0.04f
                                                overexposedBuf[base + 3] = nY + 0.04f
                                                overexposedCount++
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // 4. Asynchronous RGB 256-Bin Histogram & Focus Peaking Gradient Analysis
                            if (viewBinding.proOverlayView.showHistogram && viewBinding.proOverlayView.histogramRgbMode && imageProxy.planes.size >= 3) {
                                histoBinsR.fill(0)
                                histoBinsG.fill(0)
                                histoBinsB.fill(0)
                                val uP = imageProxy.planes[1]
                                val vP = imageProxy.planes[2]
                                val uB = uP.buffer
                                val vB = vP.buffer
                                val rgbStep = step * 2
                                for (y in 0 until height step rgbStep) {
                                    val yRow = y * rowStride
                                    val uRow = (y / 2) * uP.rowStride
                                    val vRow = (y / 2) * vP.rowStride
                                    for (x in 0 until width step rgbStep) {
                                        val yi = yRow + x * pixelStride
                                        val ui = uRow + (x / 2) * uP.pixelStride
                                        val vi = vRow + (x / 2) * vP.pixelStride
                                        if (yi < buffer.limit() && ui < uB.limit() && vi < vB.limit()) {
                                            val yv = buffer.get(yi).toInt() and 0xFF
                                            val uv = (uB.get(ui).toInt() and 0xFF) - 128
                                            val vv = (vB.get(vi).toInt() and 0xFF) - 128
                                            val r = (yv + (1.370705f * vv)).toInt().coerceIn(0, 255)
                                            val g = (yv - (0.337633f * uv) - (0.698001f * vv)).toInt().coerceIn(0, 255)
                                            val b = (yv + (1.732446f * uv)).toInt().coerceIn(0, 255)
                                            histoBinsR[r]++
                                            histoBinsG[g]++
                                            histoBinsB[b]++
                                        }
                                    }
                                }
                            }

                            var fpCount = 0
                            if (viewBinding.proOverlayView.showFocusPeaking && width > 8 && height > 8) {
                                val fpStepX = Math.max(4, width / 28)
                                val fpStepY = Math.max(4, height / 22)
                                for (y in 2 until (height - 2) step fpStepY) {
                                    if (fpCount >= 180) break
                                    val rowC = y * rowStride
                                    val rowUp = (y - 2) * rowStride
                                    val rowDn = (y + 2) * rowStride
                                    for (x in 2 until (width - 2) step fpStepX) {
                                        if (fpCount >= 180) break
                                        val iL = rowC + (x - 2) * pixelStride
                                        val iR = rowC + (x + 2) * pixelStride
                                        val iU = rowUp + x * pixelStride
                                        val iD = rowDn + x * pixelStride
                                        if (iR < buffer.limit() && iD < buffer.limit()) {
                                            val gx = Math.abs((buffer.get(iR).toInt() and 0xFF) - (buffer.get(iL).toInt() and 0xFF))
                                            val gy = Math.abs((buffer.get(iD).toInt() and 0xFF) - (buffer.get(iU).toInt() and 0xFF))
                                            if (gx + gy >= 56) {
                                                fpPointsArray[fpCount * 2] = x.toFloat() / width.toFloat()
                                                fpPointsArray[fpCount * 2 + 1] = y.toFloat() / height.toFloat()
                                                fpCount++
                                            }
                                        }
                                    }
                                }
                            }

                            val balanceX = if (totalWeight > 0f) (sumWeightX / totalWeight) / width.toFloat() else 0.5f
                            val balanceY = if (totalWeight > 0f) (sumWeightY / totalWeight) / height.toFloat() else 0.5f
                            val overexposedArray = if (overexposedCount > 0) overexposedBuf.copyOf(overexposedCount * 4) else emptyFloatArray

                            // Pass ONLY finalized arrays back to UI thread at ~15fps max to prevent View invalidate churn
                            runOnUiThread {
                                viewBinding.proOverlayView.updateLuminanceAnalysis(histoBins, overexposedArray, balanceX, balanceY)
                                if (viewBinding.proOverlayView.showHistogram && viewBinding.proOverlayView.histogramRgbMode) {
                                    viewBinding.proOverlayView.updateRgbHistogramAnalysis(histoBinsR, histoBinsG, histoBinsB)
                                }
                                if (viewBinding.proOverlayView.showFocusPeaking) {
                                    viewBinding.proOverlayView.updateFocusPeakingAnalysis(fpPointsArray, fpCount)
                                }
                                if (viewBinding.proOverlayView.showWaveform) {
                                    viewBinding.proOverlayView.updateWaveformAnalysis(wfColsArray, peakIre, overexposedCols)
                                }
                                if (viewBinding.proOverlayView.showVectorScope) {
                                    viewBinding.proOverlayView.updateVectorScopeAnalysis(vsPointsArray, vsPointCount, skinTonePercent)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Luma frame analysis error", e)
                        }
                    }

                    // Motion Blur Simulation: CameraX ImageAnalysis Y-plane temporal motion detection
                    if (needsMotionBlurAnalysis) {
                        try {
                            val motionMetrics = motionBlurProcessor.analyzeYPlaneMotion(
                                imageProxy = imageProxy,
                                isFrontFacing = (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA)
                            )
                            latestMotionMetrics = motionMetrics
                            openGLProcessor?.setMotionBlurVector(
                                motionMetrics.dirX,
                                motionMetrics.dirY,
                                motionMetrics.magnitude
                            )
                            if ((nowMs - lastMotionUiMs) >= 60L) {
                                lastMotionUiMs = nowMs
                                runOnUiThread {
                                    viewBinding.proOverlayView.updateMotionBlurTelemetry(
                                        magnitude = motionMetrics.magnitude,
                                        dirX = motionMetrics.dirX,
                                        dirY = motionMetrics.dirY,
                                        shutterLabel = motionMetrics.simulatedShutterLabel
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Motion blur frame analysis error", e)
                        }
                    }

                    // Real-Time Laplacian Sharpness Monitor: Auto Blur Clearing (runs every 320ms, zero allocations)
                    if (isAutoBlurClearEnabled && !isAfAeLocked && !isPanoramaCapturing && !thermalComplexityReduced && (nowMs - lastBlurCheckMs) >= 320L) {
                        lastBlurCheckMs = nowMs
                        try {
                            val yPlane = imageProxy.planes[0]
                            val yBuf = yPlane.buffer
                            val yWidth = imageProxy.width
                            val yHeight = imageProxy.height
                            val yRowStride = yPlane.rowStride
                            val yPixelStride = yPlane.pixelStride
                            if (yWidth > 24 && yHeight > 24) {
                                val startX = (yWidth * 0.22f).toInt().coerceAtLeast(2)
                                val endX = (yWidth * 0.78f).toInt().coerceAtMost(yWidth - 3)
                                val startY = (yHeight * 0.22f).toInt().coerceAtLeast(2)
                                val endY = (yHeight * 0.78f).toInt().coerceAtMost(yHeight - 3)
                                val step = Math.max(6, Math.min(yWidth, yHeight) / 28)
                                var lapSum = 0.0
                                var lapSqSum = 0.0
                                var sampleCount = 0
                                var sy = startY
                                while (sy < endY) {
                                    val rowC = sy * yRowStride
                                    val rowU = (sy - 1) * yRowStride
                                    val rowD = (sy + 1) * yRowStride
                                    var sx = startX
                                    while (sx < endX) {
                                        val iC = rowC + sx * yPixelStride
                                        val iL = rowC + (sx - 1) * yPixelStride
                                        val iR = rowC + (sx + 1) * yPixelStride
                                        val iU = rowU + sx * yPixelStride
                                        val iD = rowD + sx * yPixelStride
                                        if (iR < yBuf.limit() && iD < yBuf.limit()) {
                                            val c = yBuf.get(iC).toInt() and 0xFF
                                            val l = yBuf.get(iL).toInt() and 0xFF
                                            val r = yBuf.get(iR).toInt() and 0xFF
                                            val u = yBuf.get(iU).toInt() and 0xFF
                                            val d = yBuf.get(iD).toInt() and 0xFF
                                            val lap = (4 * c - l - r - u - d).toDouble()
                                            lapSum += lap
                                            lapSqSum += lap * lap
                                            sampleCount++
                                        }
                                        sx += step
                                    }
                                    sy += step
                                }
                                if (sampleCount > 16) {
                                    val mean = lapSum / sampleCount
                                    val variance = ((lapSqSum / sampleCount) - (mean * mean)).toFloat().coerceAtLeast(0f)
                                    val decayedPeak = rollingPeakSharpness * 0.96f
                                    rollingPeakSharpness = if (variance > decayedPeak) variance else decayedPeak
                                    if (rollingPeakSharpness > 180f && variance < rollingPeakSharpness * 0.50f) {
                                        consecutiveBlurFrames++
                                        if (consecutiveBlurFrames >= 2 && (nowMs - lastAutoBlurClearMs) >= 2200L) {
                                            consecutiveBlurFrames = 0
                                            lastAutoBlurClearMs = nowMs
                                            runOnUiThread { triggerAutoBlurClearSweep(silent = false) }
                                        }
                                    } else {
                                        consecutiveBlurFrames = 0
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Auto blur sharpness analysis skipped", e)
                        }
                    }

                    // Automated Panorama Sequence: 4-Way Sweep, Clone-Me Multi-Pose, Live Mini-Filmstrip, Pace & Haptics
                    if (cameraMode == CameraMode.PANORAMA && isPanoramaCapturing && !isPanoramaStitching) {
                        try {
                            val isFront = (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA)
                            val deltaYaw = normalizeAngleDelta(panoramaStartYawDeg, latestUprightYawDeg)
                            val pitchDrift = latestUprightPitchDeg - panoramaStartPitchDeg
                            val align = panoramaStitcher.analyzeFrame(
                                imageProxy = imageProxy,
                                isFrontFacing = isFront,
                                gyroDeltaYawDeg = deltaYaw,
                                gyroPitchDriftDeg = pitchDrift,
                                rollDeg = viewBinding.proOverlayView.tiltRollDegrees
                            )
                            panoramaDirection = align.panDirection

                            val maxSlices = if (isPanoramaCloneMode) 4 else 14
                            val wantCloneStep = isPanoramaCloneMode && requestCloneSliceCapture
                            if (wantCloneStep) requestCloneSliceCapture = false
                            val shouldCaptureNow = if (isPanoramaCloneMode) {
                                align.shouldTriggerAutoCapture || wantCloneStep
                            } else {
                                align.shouldTriggerAutoCapture
                            }

                            var newCount = align.sliceCount
                            var didAutoCapture = false
                            var previewThumbCopy: Bitmap? = null
                            if (shouldCaptureNow && newCount < maxSlices) {
                                val sliceBmp = panoramaStitcher.imageProxyToBitmap(imageProxy, isFront, 1080)
                                if (sliceBmp != null) {
                                    val filtered = try {
                                        val f = filterManager.applyFilterToBitmap(sliceBmp)
                                        if (f != sliceBmp && !sliceBmp.isRecycled) sliceBmp.recycle()
                                        f
                                    } catch (_: Throwable) {
                                        sliceBmp
                                    }
                                    val stepShiftAbs = if (panoramaStitcher.isVerticalSweep()) Math.abs(align.dyNorm) else Math.abs(align.dxNorm)
                                    panoramaStitcher.lockCurrentFrameAsAnchor(deltaYaw, stepShiftAbs, pitchDrift)
                                    synchronized(panoramaLock) {
                                        if (isPanoramaCapturing) {
                                            val thumbW = 180
                                            val thumbH = ((filtered.height.toFloat() / filtered.width.coerceAtLeast(1)) * thumbW).toInt().coerceIn(80, 260)
                                            previewThumbCopy = try {
                                                Bitmap.createScaledBitmap(filtered, thumbW, thumbH, true)
                                            } catch (_: Throwable) {
                                                null
                                            }
                                            panoramaSlices.add(
                                                PanoramaStitcher.PanoramaSlice(
                                                    bitmap = filtered,
                                                    stepDxNorm = align.dxNorm,
                                                    stepDyNorm = align.dyNorm,
                                                    overlapRatio = align.overlapRatio,
                                                    deltaYawDeg = deltaYaw,
                                                    pitchDriftDeg = pitchDrift
                                                )
                                            )
                                            newCount = panoramaSlices.size
                                            didAutoCapture = true
                                        } else if (!filtered.isRecycled) {
                                            filtered.recycle()
                                        }
                                    }
                                }
                            }

                            val overlapPct = (align.overlapRatio * 100f).toInt().coerceIn(0, 100)
                            val uiGuidance = if (didAutoCapture) "AUTO_CAPTURED" else align.guidance.name
                            val progressOut = if (isPanoramaCloneMode) (newCount.toFloat() / maxSlices.toFloat()).coerceIn(0f, 1f) else align.estimatedProgress
                            val shouldFinishNow = (!isPanoramaCloneMode && align.shouldAutoFinish) || newCount >= maxSlices

                            runOnUiThread {
                                val thumbForUi = previewThumbCopy
                                if (!isPanoramaCapturing) {
                                    if (thumbForUi != null && !thumbForUi.isRecycled) thumbForUi.recycle()
                                    return@runOnUiThread
                                }
                                if (thumbForUi != null) {
                                    viewBinding.proOverlayView.appendPanoramaPreviewSlice(thumbForUi)
                                    if (!thumbForUi.isRecycled) thumbForUi.recycle()
                                }
                                viewBinding.proOverlayView.updatePanoramaAnalysis(
                                    guidance = uiGuidance,
                                    progress = progressOut,
                                    overlapPct = overlapPct,
                                    dxNorm = align.dxNorm,
                                    dyNorm = align.dyNorm,
                                    confidence = align.confidence,
                                    yawDeg = deltaYaw,
                                    pitchDriftDeg = pitchDrift,
                                    sliceCount = newCount,
                                    direction = align.panDirection,
                                    paceNorm = align.paceNorm,
                                    edgeTargetProgress = align.edgeTargetProgress,
                                    isAlignedAndLevel = align.isAlignedAndLevel
                                )
                                if (didAutoCapture) {
                                    window.decorView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    viewBinding.chronometer.text = if (isPanoramaCloneMode) {
                                        "POSE ${newCount}/${maxSlices} · CLONE"
                                    } else {
                                        "${newCount}/${maxSlices} F · ${overlapPct}%"
                                    }
                                    playCameraCue(ToneGenerator.TONE_PROP_BEEP, 30)
                                    if (isPanoramaCloneMode && newCount < maxSlices) {
                                        showHudNotice("Pose #${newCount} locked · Move subject & tap Shutter (hold to stitch)")
                                    }
                                }
                                if (shouldFinishNow) {
                                    finishPanoramaCapture()
                                }
                            }
                        } catch (panoErr: Throwable) {
                            Log.e(TAG, "ImageAnalysis panorama frame error", panoErr)
                        }
                    }

                    // Automated Night Mode: CameraX ImageAnalysis Low-Light Detection & Multi-Frame Stack Auto-Trigger
                    val needsNightDetection = (cameraMode == CameraMode.PHOTO ||
                        cameraMode == CameraMode.NIGHT ||
                        currentShootingEffect == "night_sight") &&
                        recording == null && !isPanoramaCapturing && !isPanoramaStitching
                    if (needsNightDetection && !isNightStacking && (nowMs - lastNightCheckMs) >= 160L) {
                        lastNightCheckMs = nowMs
                        try {
                            val nightInfo = nightExposureStacker.analyzeLowLight(imageProxy)
                            val showHud = cameraMode == CameraMode.NIGHT ||
                                currentShootingEffect == "night_sight" ||
                                nightInfo.isLowLight
                            val resolvedFrames = when (nightFramesMode) {
                                "3" -> 3
                                "5" -> 5
                                "7" -> 7
                                else -> nightInfo.recommendedFrameCount
                            }
                            runOnUiThread {
                                viewBinding.proOverlayView.isNightModeHudActive = showHud
                                viewBinding.proOverlayView.nightAutoTriggerEnabled = isAutoNightStackEnabled
                                viewBinding.proOverlayView.nightSkyToneLabel = if (nightSkyTone == "natural") "NATURAL" else "BRIGHT"
                                viewBinding.proOverlayView.updateNightAnalysis(
                                    avgLuma = nightInfo.avgLuma,
                                    darkRatio = nightInfo.darkPixelRatio,
                                    isLowLight = nightInfo.isLowLight,
                                    dwellProgress = nightInfo.dwellProgress,
                                    recommendedFrames = resolvedFrames,
                                    statusLabel = nightInfo.state.name
                                )
                            }
                            if (nightInfo.shouldAutoTriggerStack &&
                                isAutoNightStackEnabled &&
                                !isCountingDown &&
                                !isBurstCapturing &&
                                !isNightStacking &&
                                cameraMode != CameraMode.PANORAMA
                            ) {
                                nightExposureStacker.markAutoTriggered()
                                val targetFrames = nightInfo.recommendedFrameCount
                                runOnUiThread {
                                    if (!isNightStacking && recording == null && !isPanoramaCapturing) {
                                        startNightExposureStacking(autoTriggered = true, targetFrameCount = targetFrames)
                                    }
                                }
                            }
                        } catch (nightErr: Throwable) {
                            Log.e(TAG, "Low-light ImageAnalysis detection error", nightErr)
                        }
                    }

                    // 4. Dual Face Tracking: MediaPipe 468 3D Landmarks with Automatic Built-in FaceDetector Fallback (Disabled in Eco or Critical Thermal Mode)
                    analysisFrameCounter++
                    val ecoBlocksAr = ::ecoPowerManager.isInitialized && ecoPowerManager.isArDisabled
                    val thermalBlocksAr = ::thermalGuardManager.isInitialized && thermalGuardManager.isArDisabled
                    val needsFaceTracking = !ecoBlocksAr && !thermalBlocksAr && ((viewBinding.arFaceOverlayView.currentLens != ARFaceOverlayView.ArLensMode.NONE &&
                        viewBinding.arFaceOverlayView.currentLens != ARFaceOverlayView.ArLensMode.COMIC_FRAME) ||
                        viewBinding.arFaceOverlayView.faceBeautyEnabled ||
                        viewBinding.arFaceOverlayView.enableComicOnomatopoeia ||
                        viewBinding.arFaceOverlayView.enableComicSpeechBubble)

                    // 4B. Phase 2: MediaPipe 21-Point Hand AR Tracking (alternating frames to preserve 60fps & thermals)
                    val needsHandTracking = !ecoBlocksAr && !thermalBlocksAr &&
                        (viewBinding.arFaceOverlayView.handArEnabled ||
                            viewBinding.arFaceOverlayView.isSnapWipeoutArmed() ||
                            (cameraMode == CameraMode.AR_LENS && isHandGestureTriggerEnabled))
                    if (needsHandTracking && analysisFrameCounter % 2 == 1) {
                        val hm = handLandmarkerHelper
                        if (hm != null && hm.isReady && !hm.isFailed) {
                            try {
                                val rawHandBmp = imageProxy.toBitmap()
                                val rot = imageProxy.imageInfo.rotationDegrees
                                val maxDim = Math.max(rawHandBmp.width, rawHandBmp.height).coerceAtLeast(1)
                                val downScale = if (maxDim > 384) (384f / maxDim.toFloat()) else 1f
                                val m = Matrix().apply {
                                    if (downScale < 1f) postScale(downScale, downScale)
                                    if (rot != 0) postRotate(rot.toFloat())
                                }
                                val handInputBmp = Bitmap.createBitmap(rawHandBmp, 0, 0, rawHandBmp.width, rawHandBmp.height, m, true)
                                if (handInputBmp != rawHandBmp) rawHandBmp.recycle()
                                hm.analyzeRotatedBitmap(handInputBmp, 0)
                            } catch (handErr: Throwable) {
                                Log.w(TAG, "HandLandmarker frame skipped", handErr)
                            }
                        }
                    }

                    // 4C. Phase 3: MediaPipe 33-Point Body Pose & Segmentation Tracking (round-robin frame scheduling for 60fps & Moto Edge 60 Pro thermals)
                    val needsBodyTracking = !ecoBlocksAr && !thermalBlocksAr && viewBinding.arFaceOverlayView.bodyArEnabled
                    val bodyFrameSlot = if (needsHandTracking) (analysisFrameCounter % 3 == 2) else (analysisFrameCounter % 2 == 1)
                    if (needsBodyTracking && bodyFrameSlot) {
                        val pm = poseLandmarkerHelper
                        if (pm != null && pm.isReady && !pm.isFailed) {
                            try {
                                val rawBodyBmp = imageProxy.toBitmap()
                                val rot = imageProxy.imageInfo.rotationDegrees
                                val maxDim = Math.max(rawBodyBmp.width, rawBodyBmp.height).coerceAtLeast(1)
                                val downScale = if (maxDim > 384) (384f / maxDim.toFloat()) else 1f
                                val m = Matrix().apply {
                                    if (downScale < 1f) postScale(downScale, downScale)
                                    if (rot != 0) postRotate(rot.toFloat())
                                }
                                val bodyInputBmp = Bitmap.createBitmap(rawBodyBmp, 0, 0, rawBodyBmp.width, rawBodyBmp.height, m, true)
                                if (bodyInputBmp != rawBodyBmp) rawBodyBmp.recycle()
                                pm.analyzeRotatedBitmap(bodyInputBmp, 0)
                            } catch (bodyErr: Throwable) {
                                Log.w(TAG, "PoseLandmarker frame skipped", bodyErr)
                            }
                        }
                    }

                    if (needsFaceTracking) {
                        val fm = faceMeshHelper
                        var handledByMediaPipe = false
                        if (isMediaPipeSupported && fm != null && fm.isReady && !fm.isFailed && analysisFrameCounter % 2 == 0) {
                            try {
                                val rawMpBmp = imageProxy.toBitmap()
                                val rot = imageProxy.imageInfo.rotationDegrees
                                val maxDim = Math.max(rawMpBmp.width, rawMpBmp.height).coerceAtLeast(1)
                                val downScale = if (maxDim > 480) (480f / maxDim.toFloat()) else 1f
                                val m = Matrix().apply {
                                    if (downScale < 1f) postScale(downScale, downScale)
                                    if (rot != 0) postRotate(rot.toFloat())
                                }
                                val mpInputBmp = Bitmap.createBitmap(rawMpBmp, 0, 0, rawMpBmp.width, rawMpBmp.height, m, true)
                                if (mpInputBmp != rawMpBmp) rawMpBmp.recycle()
                                handledByMediaPipe = fm.analyzeRotatedBitmap(mpInputBmp, 0)
                            } catch (mpErr: Throwable) {
                                Log.w(TAG, "MediaPipe frame conversion fallback to FaceDetector", mpErr)
                                isMediaPipeSupported = false
                                handledByMediaPipe = false
                            }
                        }

                        val mpTimedOut = (System.currentTimeMillis() - lastMediaPipeLandmarkTime) > 1200L
                        if ((!handledByMediaPipe || mpTimedOut) && analysisFrameCounter % 3 == 0) {
                            try {
                                val plane = imageProxy.planes[0]
                                val buffer = plane.buffer
                                val srcW = imageProxy.width
                                val srcH = imageProxy.height
                                val rowStride = plane.rowStride
                                val pixelStride = plane.pixelStride
                                val rot = imageProxy.imageInfo.rotationDegrees

                                val step = Math.max(2, Math.min(srcW, srcH) / 240)
                                val rotSideways = rot == 90 || rot == 270
                                val rawOutW = if (rotSideways) (srcH / step) else (srcW / step)
                                val outW = (rawOutW and 1.inv()).coerceAtLeast(16)
                                val outH = (if (rotSideways) (srcW / step) else (srcH / step)).coerceAtLeast(16)

                                val pixels = IntArray(outW * outH)
                                for (oy in 0 until outH) {
                                    for (ox in 0 until outW) {
                                        val sx: Int
                                        val sy: Int
                                        when (rot) {
                                            90 -> {
                                                sx = (oy * step).coerceIn(0, srcW - 1)
                                                sy = ((srcH - 1) - (ox * step)).coerceIn(0, srcH - 1)
                                            }
                                            180 -> {
                                                sx = ((srcW - 1) - (ox * step)).coerceIn(0, srcW - 1)
                                                sy = ((srcH - 1) - (oy * step)).coerceIn(0, srcH - 1)
                                            }
                                            270 -> {
                                                sx = ((srcW - 1) - (oy * step)).coerceIn(0, srcW - 1)
                                                sy = (ox * step).coerceIn(0, srcH - 1)
                                            }
                                            else -> {
                                                sx = (ox * step).coerceIn(0, srcW - 1)
                                                sy = (oy * step).coerceIn(0, srcH - 1)
                                            }
                                        }
                                        val idx = sy * rowStride + sx * pixelStride
                                        val yVal = if (idx in 0 until buffer.limit()) (buffer.get(idx).toInt() and 0xFF) else 128
                                        pixels[oy * outW + ox] = (0xFF shl 24) or (yVal shl 16) or (yVal shl 8) or yVal
                                    }
                                }

                                val faceBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.RGB_565)
                                faceBmp.setPixels(pixels, 0, outW, 0, 0, outW, outH)
                                val maxFaces = 4
                                val faces = arrayOfNulls<FaceDetector.Face>(maxFaces)
                                val count = FaceDetector(outW, outH, maxFaces).findFaces(faceBmp, faces)
                                if (count > 0) {
                                    val nowFaceMs = System.currentTimeMillis()
                                    val hasPrevFace = (nowFaceMs - lastTrackedFaceTimeMs) < 2000L
                                    var bestFace: FaceDetector.Face? = null
                                    var bestScore = -1f
                                    val tmpMid = PointF()
                                    for (fi in 0 until count) {
                                        val candidate = faces[fi] ?: continue
                                        candidate.getMidPoint(tmpMid)
                                        val nx = (tmpMid.x / outW.toFloat()).coerceIn(0.05f, 0.95f)
                                        val ny = (tmpMid.y / outH.toFloat()).coerceIn(0.05f, 0.95f)
                                        val eyeDist = candidate.eyesDistance()
                                        val conf = candidate.confidence().coerceAtLeast(0.25f)
                                        val centerDist = Math.hypot((nx - 0.5f).toDouble(), (ny - 0.45f).toDouble()).toFloat()
                                        val centralityBonus = (1f - centerDist.coerceIn(0f, 1f)) * 0.45f
                                        val stickyBonus = if (hasPrevFace) {
                                            val prevDist = Math.hypot((nx - lastTrackedFaceX).toDouble(), (ny - lastTrackedFaceY).toDouble()).toFloat()
                                            (1f - (prevDist * 2.5f).coerceIn(0f, 1f)) * 0.65f
                                        } else 0f
                                        val score = eyeDist * conf * (1f + centralityBonus + stickyBonus)
                                        if (score > bestScore) {
                                            bestScore = score
                                            bestFace = candidate
                                        }
                                    }
                                    bestFace?.let { primaryFace ->
                                        val mid = PointF()
                                        primaryFace.getMidPoint(mid)
                                        val normX = (mid.x / outW.toFloat()).coerceIn(0.05f, 0.95f)
                                        val normY = (mid.y / outH.toFloat()).coerceIn(0.05f, 0.95f)
                                        val eyeDist = primaryFace.eyesDistance()
                                        lastTrackedFaceX = normX
                                        lastTrackedFaceY = normY
                                        lastTrackedFaceTimeMs = nowFaceMs
                                        viewBinding.arFaceOverlayView.updateSimpleFace(
                                            normEyeBridgeX = normX,
                                            normEyeBridgeY = normY,
                                            normEyeDist = (eyeDist / outW.toFloat()).coerceIn(0.04f, 0.55f),
                                            isFrontFacing = lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA,
                                            inputWidth = outW,
                                            inputHeight = outH
                                        )
                                    }
                                }
                                faceBmp.recycle()
                            } catch (e: Exception) {
                                Log.e(TAG, "Face tracking fallback error", e)
                            }
                        }
                        if (recording != null && cameraEffect != null && analysisFrameCounter % 3 == 0 && viewBinding.arFaceOverlayView.hasAnyVisibleOverlay()) {
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed && recording != null) {
                                    syncOverlayToVideoProcessor()
                                }
                            }
                        }
                    }

                    imageProxy.close()
                }
            }

        // Real Multi-Cam PiP (ConcurrentCamera) path with automatic single-camera HAL fallback
        if (isMultiCamPipEnabled) {
            val concurrentPair = findConcurrentFrontBackPair(cameraProvider)
            if (concurrentPair != null) {
                try {
                    cameraProvider.unbindAll()
                    cameraEffect = null
                    isNightExtensionActive = false
                    val (primarySelector, secondarySelector) = concurrentPair
                    val pipPreview = Preview.Builder().build().also {
                        it.setSurfaceProvider(viewBinding.pipViewFinder.surfaceProvider)
                    }
                    val isPhotoType = cameraMode == CameraMode.PHOTO || cameraMode == CameraMode.PORTRAIT || cameraMode == CameraMode.NIGHT || cameraMode == CameraMode.PANORAMA
                    // CameraX ConcurrentCamera allows max 2 use cases per camera
                    val primaryUseCaseGroup = UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(if (isPhotoType) imageCapture!! else videoCapture!!)
                        .build()
                    val secondaryUseCaseGroup = UseCaseGroup.Builder()
                        .addUseCase(pipPreview)
                        .build()

                    val primaryConfig = ConcurrentCamera.SingleCameraConfig(primarySelector, primaryUseCaseGroup, this)
                    val secondaryConfig = ConcurrentCamera.SingleCameraConfig(secondarySelector, secondaryUseCaseGroup, this)
                    concurrentCamera = cameraProvider.bindToLifecycle(listOf(primaryConfig, secondaryConfig))
                    camera = concurrentCamera?.cameras?.firstOrNull()
                    cameraControl = camera?.cameraControl
                    initExposureRange()
                    updatePipOverlayVisibility()
                    syncStylizeLoopState()
                    viewBinding.viewFinder.post { applyLivePreviewColorMatrix() }
                    return
                } catch (ccErr: Throwable) {
                    Log.w(TAG, "ConcurrentCamera bind failed on device HAL, reverting safely to single camera", ccErr)
                    handleConcurrentUnsupportedFallback("Dual-Cam PiP rejected by camera HAL — reverted to single camera")
                }
            } else {
                handleConcurrentUnsupportedFallback("Dual-Cam PiP unsupported on this device HAL — single camera active")
            }
        }

        concurrentCamera = null
        updatePipOverlayVisibility()

        var activeNightExt = false
        val effectiveSelector = if (cameraMode == CameraMode.NIGHT) {
            try {
                if (extensionsManager?.isExtensionAvailable(lensFacing, ExtensionMode.NIGHT) == true) {
                    activeNightExt = true
                    extensionsManager!!.getExtensionEnabledCameraSelector(lensFacing, ExtensionMode.NIGHT)
                } else {
                    lensFacing
                }
            } catch (e: Exception) {
                Log.w(TAG, "ExtensionMode.NIGHT check failed, using computational night fallback", e)
                lensFacing
            }
        } else {
            lensFacing
        }
        isNightExtensionActive = activeNightExt

        try {
            cameraProvider.unbindAll()
            cameraEffect = null
            isPreviewCameraEffectActive = false

            val isPhotoType = cameraMode == CameraMode.PHOTO || cameraMode == CameraMode.PORTRAIT || cameraMode == CameraMode.NIGHT || cameraMode == CameraMode.PANORAMA
            if (cameraMode == CameraMode.AR_LENS) {
                // AR LENS Mode binds BOTH ImageCapture (Tap for Photo) AND VideoCapture (Hold >=1s for Video) + ImageAnalysis (FaceMesh)
                val proc = openGLProcessor ?: OpenGLSurfaceProcessor().also { openGLProcessor = it }
                syncAllStateToOpenGLProcessor()
                var boundArCombo = false
                try {
                    val effect = OpenGLSurfaceProcessor.createCameraEffect(proc, proc.getExecutor())
                    val groupBuilder = UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(imageCapture!!)
                        .addUseCase(videoCapture!!)
                        .addUseCase(imageAnalysis!!)
                        .addEffect(effect)
                    camera = cameraProvider.bindToLifecycle(this, lensFacing, groupBuilder.build())
                    cameraEffect = effect
                    isPreviewCameraEffectActive = true
                    boundArCombo = true
                } catch (effectErr: Exception) {
                    Log.w(TAG, "AR LENS CameraEffect 4-use-case bind unsupported on HAL, retrying 3-use-case with CameraEffect", effectErr)
                    cameraEffect = null
                    isPreviewCameraEffectActive = false
                }
                if (!boundArCombo) {
                    try {
                        val effect = OpenGLSurfaceProcessor.createCameraEffect(proc, proc.getExecutor())
                        val groupBuilder = UseCaseGroup.Builder()
                            .addUseCase(preview)
                            .addUseCase(imageCapture!!)
                            .addUseCase(videoCapture!!)
                            .addEffect(effect)
                        camera = cameraProvider.bindToLifecycle(this, lensFacing, groupBuilder.build())
                        cameraEffect = effect
                        isPreviewCameraEffectActive = true
                        boundArCombo = true
                    } catch (threeUcErr: Exception) {
                        Log.w(TAG, "AR LENS Preview+Photo+Video CameraEffect unsupported on HAL, retrying Video-only CameraEffect", threeUcErr)
                        cameraEffect = null
                        isPreviewCameraEffectActive = false
                    }
                }
                if (!boundArCombo) {
                    try {
                        val effect = OpenGLSurfaceProcessor.createVideoOnlyCameraEffect(proc, proc.getExecutor())
                        val groupBuilder = UseCaseGroup.Builder()
                            .addUseCase(preview)
                            .addUseCase(imageCapture!!)
                            .addUseCase(videoCapture!!)
                            .addEffect(effect)
                        camera = cameraProvider.bindToLifecycle(this, lensFacing, groupBuilder.build())
                        cameraEffect = effect
                        isPreviewCameraEffectActive = false
                        boundArCombo = true
                    } catch (vidOnlyErr: Exception) {
                        Log.w(TAG, "AR LENS Video-only CameraEffect unsupported on HAL, retrying without CameraEffect", vidOnlyErr)
                        cameraEffect = null
                        isPreviewCameraEffectActive = false
                    }
                }
                if (!boundArCombo) {
                    try {
                        camera = cameraProvider.bindToLifecycle(this, lensFacing, preview, imageCapture!!, videoCapture!!, imageAnalysis!!)
                        boundArCombo = true
                    } catch (fourUcErr: Exception) {
                        Log.w(TAG, "AR LENS 4-use-case bind unsupported on HAL, falling back to Preview + Photo + Video", fourUcErr)
                    }
                }
                if (!boundArCombo) {
                    camera = cameraProvider.bindToLifecycle(this, lensFacing, preview, imageCapture!!, videoCapture!!)
                }
            } else if (isPhotoType) {
                val usePreviewEisEffect = (::gyroStabilizer.isInitialized && gyroStabilizer.isEnabled && !activeNightExt)
                var boundPreviewWithEffect = false
                if (usePreviewEisEffect) {
                    val proc = openGLProcessor ?: OpenGLSurfaceProcessor().also { openGLProcessor = it }
                    syncAllStateToOpenGLProcessor()
                    try {
                        val effect = OpenGLSurfaceProcessor.createPreviewCameraEffect(proc, proc.getExecutor())
                        val groupBuilder = UseCaseGroup.Builder()
                            .addUseCase(preview)
                            .addUseCase(imageCapture!!)
                            .addUseCase(imageAnalysis!!)
                            .addEffect(effect)
                        camera = cameraProvider.bindToLifecycle(this, effectiveSelector, groupBuilder.build())
                        cameraEffect = effect
                        isPreviewCameraEffectActive = true
                        boundPreviewWithEffect = true
                    } catch (effectErr: Exception) {
                        Log.w(TAG, "Preview CameraEffect EIS unsupported on HAL, using View-level EIS fallback", effectErr)
                        cameraEffect = null
                        isPreviewCameraEffectActive = false
                    }
                }
                if (!boundPreviewWithEffect) {
                    camera = cameraProvider.bindToLifecycle(this, effectiveSelector, preview, imageCapture!!, imageAnalysis!!)
                }
            } else {
                val proc = openGLProcessor ?: OpenGLSurfaceProcessor().also { openGLProcessor = it }
                syncAllStateToOpenGLProcessor()
                var boundWithEffect = false
                // Tier 1: Preview + VideoCapture + ImageAnalysis + Shared GPU CameraEffect
                try {
                    val effect = OpenGLSurfaceProcessor.createCameraEffect(proc, proc.getExecutor())
                    val groupBuilder = UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(videoCapture!!)
                        .addUseCase(imageAnalysis!!)
                        .addEffect(effect)
                    camera = cameraProvider.bindToLifecycle(this, lensFacing, groupBuilder.build())
                    cameraEffect = effect
                    isPreviewCameraEffectActive = true
                    boundWithEffect = true
                } catch (effectErr: Exception) {
                    Log.w(TAG, "CameraEffect 3-use-case VideoCapture binding unsupported on HAL, retrying without ImageAnalysis to preserve video effects", effectErr)
                    cameraEffect = null
                    isPreviewCameraEffectActive = false
                }
                // Tier 2: Preview + VideoCapture + Shared GPU CameraEffect (works on 99% of Android HALs so effects bake into MP4!)
                if (!boundWithEffect) {
                    try {
                        val effect = OpenGLSurfaceProcessor.createCameraEffect(proc, proc.getExecutor())
                        val groupBuilder = UseCaseGroup.Builder()
                            .addUseCase(preview)
                            .addUseCase(videoCapture!!)
                            .addEffect(effect)
                        camera = cameraProvider.bindToLifecycle(this, lensFacing, groupBuilder.build())
                        cameraEffect = effect
                        isPreviewCameraEffectActive = true
                        boundWithEffect = true
                    } catch (tier2Err: Exception) {
                        Log.w(TAG, "Shared Preview+Video CameraEffect unsupported on HAL, retrying Video-only CameraEffect", tier2Err)
                        cameraEffect = null
                        isPreviewCameraEffectActive = false
                    }
                }
                // Tier 3: Preview + VideoCapture + Video-only GPU CameraEffect (preview uses ColorMatrix/CPU while MP4 gets full GPU shaders!)
                if (!boundWithEffect) {
                    try {
                        val effect = OpenGLSurfaceProcessor.createVideoOnlyCameraEffect(proc, proc.getExecutor())
                        val groupBuilder = UseCaseGroup.Builder()
                            .addUseCase(preview)
                            .addUseCase(videoCapture!!)
                            .addEffect(effect)
                        camera = cameraProvider.bindToLifecycle(this, lensFacing, groupBuilder.build())
                        cameraEffect = effect
                        isPreviewCameraEffectActive = false
                        boundWithEffect = true
                    } catch (tier3Err: Exception) {
                        Log.w(TAG, "Video-only CameraEffect unsupported on HAL, falling back to standard VideoCapture", tier3Err)
                        cameraEffect = null
                        isPreviewCameraEffectActive = false
                    }
                }
                if (!boundWithEffect) {
                    camera = cameraProvider.bindToLifecycle(this, lensFacing, preview, videoCapture!!, imageAnalysis!!)
                }
            }

            cameraControl = camera?.cameraControl

            // Initialize Exposure compensation range from CameraInfo (auto-lifts EV in NIGHT mode)
            initExposureRange()

            // Re-apply real-time ColorMatrix / RenderEffect filter onto TextureView preview
            syncStylizeLoopState()
            viewBinding.viewFinder.post { applyLivePreviewColorMatrix() }

            // Reset sharpness baseline & run initial Auto-Focus / Blur-Clear sweep after binding
            isAfAeLocked = false
            rollingPeakSharpness = 0f
            consecutiveBlurFrames = 0
            if (isAutoBlurClearEnabled) {
                viewBinding.viewFinder.postDelayed({ triggerAutoBlurClearSweep(silent = true) }, 280L)
            }

        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed, attempting fallback without analysis", exc)
            try {
                cameraProvider.unbindAll()
                val isPhotoType = cameraMode == CameraMode.PHOTO || cameraMode == CameraMode.PORTRAIT || cameraMode == CameraMode.NIGHT || cameraMode == CameraMode.PANORAMA
                if (cameraMode == CameraMode.AR_LENS) {
                    camera = try {
                        cameraProvider.bindToLifecycle(this, lensFacing, preview, imageCapture!!, videoCapture!!)
                    } catch (_: Exception) {
                        cameraProvider.bindToLifecycle(this, lensFacing, preview, imageCapture!!)
                    }
                } else if (isPhotoType) {
                    camera = cameraProvider.bindToLifecycle(this, effectiveSelector, preview, imageCapture!!)
                } else {
                    camera = cameraProvider.bindToLifecycle(this, lensFacing, preview, videoCapture!!)
                }
                cameraControl = camera?.cameraControl
                initExposureRange()
                viewBinding.viewFinder.post { applyLivePreviewColorMatrix() }
            } catch (e: Exception) {
                // Final safety fallback if OEM Extension selector rejects binding: fall back to standard lensFacing + computational night pipeline
                if (activeNightExt) {
                    isNightExtensionActive = false
                    try {
                        cameraProvider.unbindAll()
                        camera = cameraProvider.bindToLifecycle(this, lensFacing, preview, imageCapture!!, imageAnalysis!!)
                        cameraControl = camera?.cameraControl
                        initExposureRange()
                        viewBinding.viewFinder.post { applyLivePreviewColorMatrix() }
                        return
                    } catch (inner: Exception) {
                        Log.e(TAG, "Fallback night binding failed", inner)
                    }
                }
                Toast.makeText(this, "Camera start error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 2. Photo Pro Mode: Long-press Shutter for Burst Mode
     */
    private fun setupBurstMode() {
        viewBinding.btnCapture.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(75L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
            }
            if (cameraMode == CameraMode.PANORAMA) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        panoLongPressTriggered = false
                        panoLongPressRunnable?.let { panoramaFallbackHandler.removeCallbacks(it) }
                        if (isPanoramaCapturing && isPanoramaCloneMode) {
                            panoLongPressRunnable = Runnable {
                                if (isPanoramaCapturing && isPanoramaCloneMode) {
                                    panoLongPressTriggered = true
                                    window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    finishPanoramaCapture()
                                }
                            }
                            panoramaFallbackHandler.postDelayed(panoLongPressRunnable!!, 520L)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        panoLongPressRunnable?.let { panoramaFallbackHandler.removeCallbacks(it) }
                        panoLongPressRunnable = null
                        if (event.action == MotionEvent.ACTION_UP && !panoLongPressTriggered) {
                            togglePanoramaCapture()
                        }
                        panoLongPressTriggered = false
                    }
                }
                return@setOnTouchListener true
            }
            if (cameraMode == CameraMode.AR_LENS) {
                // AR LENS Mode: Tap (< 500ms) -> Photo; Hold (>= 500ms) -> AR Video; Slide UP while holding -> Lock Hands-Free!
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        if (recording != null && isArRecordingLocked) {
                            arStopOnShutterUp = true
                            return@setOnTouchListener true
                        }
                        arStopOnShutterUp = false
                        isArHoldRecording = false
                        isArRecordingLocked = false
                        arTouchDownRawY = event.rawY
                        arHoldRunnable?.let { arHoldHandler.removeCallbacks(it) }
                        arHoldRunnable = Runnable {
                            if (cameraMode == CameraMode.AR_LENS && recording == null && !isCountingDown) {
                                isArHoldRecording = true
                                window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                viewBinding.arLensModeDock.root.visibility = View.GONE
                                viewBinding.arCalibrationDrawer.visibility = View.GONE
                                viewBinding.tvArRecLockBadge.translationY = 0f
                                viewBinding.tvArRecLockBadge.text = "▲ SLIDE UP TO LOCK"
                                viewBinding.tvArRecLockBadge.setTextColor(Color.parseColor("#FACC15"))
                                viewBinding.tvArRecLockBadge.visibility = View.VISIBLE
                                captureVideo()
                            }
                        }
                        arHoldHandler.postDelayed(arHoldRunnable!!, 500L)
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isArHoldRecording && !isArRecordingLocked) {
                            val slideUpPx = arTouchDownRawY - event.rawY
                            val lockThresholdPx = 56f * resources.displayMetrics.density
                            viewBinding.tvArRecLockBadge.translationY = -slideUpPx.coerceIn(0f, lockThresholdPx) * 0.45f
                            if (slideUpPx >= lockThresholdPx) {
                                isArRecordingLocked = true
                                window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                viewBinding.tvArRecLockBadge.translationY = 0f
                                viewBinding.tvArRecLockBadge.text = "LOCKED · TAP SHUTTER TO STOP"
                                viewBinding.tvArRecLockBadge.setTextColor(Color.parseColor("#34D399"))
                                viewBinding.tvRecordingStatus.text = "AR REC · LOCK"
                                viewBinding.btnPauseResume.visibility = View.VISIBLE
                                showHudNotice("AR Video Locked · Hands-Free")
                            }
                        }
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        arHoldRunnable?.let { arHoldHandler.removeCallbacks(it) }
                        arHoldRunnable = null
                        viewBinding.tvArRecLockBadge.translationY = 0f
                        if (arStopOnShutterUp) {
                            arStopOnShutterUp = false
                            isArRecordingLocked = false
                            isArHoldRecording = false
                            viewBinding.tvArRecLockBadge.visibility = View.GONE
                            if (recording != null) {
                                captureVideo()
                            }
                        } else if (isArRecordingLocked) {
                            // Keep recording hands-free after thumb lifts
                        } else if (isArHoldRecording || recording != null) {
                            isArHoldRecording = false
                            viewBinding.tvArRecLockBadge.visibility = View.GONE
                            if (recording != null) {
                                captureVideo()
                            }
                        } else if (event.action == MotionEvent.ACTION_UP) {
                            onShutterClicked()
                        }
                        return@setOnTouchListener true
                    }
                }
                return@setOnTouchListener true
            }
            val isVideoType = cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.SLOW_MO
            if (isVideoType) {
                // In Video mode, standard click handles start/stop
                if (event.action == MotionEvent.ACTION_UP) {
                    captureVideo()
                }
                return@setOnTouchListener true
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Prepare burst check after 400ms hold (PHOTO mode only; PORTRAIT & NIGHT capture single frames)
                    isBurstCapturing = false
                    burstCount = 0
                    burstRunnable = Runnable {
                        if (!isCountingDown && cameraMode == CameraMode.PHOTO) {
                            isBurstCapturing = true
                            viewBinding.tvBurstBadge.visibility = View.VISIBLE
                            startBurstCapturingLoop()
                        }
                    }
                    burstHandler.postDelayed(burstRunnable!!, 400)
                    return@setOnTouchListener true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    burstRunnable?.let { burstHandler.removeCallbacks(it) }
                    if (isBurstCapturing) {
                        // Conclude burst capture
                        isBurstCapturing = false
                        viewBinding.tvBurstBadge.visibility = View.GONE
                        Toast.makeText(this, "Captured $burstCount burst photos!", Toast.LENGTH_SHORT).show()
                    } else if (event.action == MotionEvent.ACTION_UP) {
                        // Standard single photo tap (with countdown if enabled)
                        onShutterClicked()
                    }
                    return@setOnTouchListener true
                }
            }
            false
        }
    }

    private fun startBurstCapturingLoop() {
        if (!isBurstCapturing || cameraMode != CameraMode.PHOTO) return
        if (remainingBurstBuffer <= 0) {
            viewBinding.tvBurstBadge.text = "BUFFER FULL · FLUSHING"
            return
        }

        burstCount++
        remainingBurstBuffer--
        viewBinding.tvBurstBadge.text = "BURST: $burstCount · REMAINING: ${remainingBurstBuffer}r"
        playCameraCue(ToneGenerator.TONE_PROP_BEEP, 45)
        takePhoto(isBurst = true)

        // Rapid capture every 200ms
        burstHandler.postDelayed({
            startBurstCapturingLoop()
        }, 200)
    }

    private fun cyclePanoramaSweepDirection() {
        val order = listOf(
            PanoramaStitcher.SweepDirection.RIGHT,
            PanoramaStitcher.SweepDirection.LEFT,
            PanoramaStitcher.SweepDirection.UP,
            PanoramaStitcher.SweepDirection.DOWN
        )
        val nextIdx = (order.indexOf(panoramaSweepDir) + 1) % order.size
        setPanoramaSweepDirection(order[nextIdx])
    }

    private fun setPanoramaSweepDirection(dir: PanoramaStitcher.SweepDirection) {
        panoramaSweepDir = dir
        panoramaStitcher.desiredSweepDirection = dir
        viewBinding.proOverlayView.panoramaSweepDir = dir.name
        val label = when (dir) {
            PanoramaStitcher.SweepDirection.RIGHT -> "Sweep Right →"
            PanoramaStitcher.SweepDirection.LEFT -> "Sweep Left ←"
            PanoramaStitcher.SweepDirection.UP -> "Vertical Sweep Up ↑"
            PanoramaStitcher.SweepDirection.DOWN -> "Vertical Sweep Down ↓"
        }
        window.decorView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        showHudNotice("PANORAMA · $label")
        refreshFloatingModeMenu()
    }

    private fun lockPanoramaAeAwb() {
        try {
            val factory = viewBinding.viewFinder.meteringPointFactory
            val centerPt = factory.createPoint(
                viewBinding.viewFinder.width * 0.5f,
                viewBinding.viewFinder.height * 0.5f
            )
            val action = FocusMeteringAction.Builder(
                centerPt,
                FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
            ).disableAutoCancel().build()
            camera?.cameraControl?.startFocusAndMetering(action)
            isPanoramaAeLocked = true
            viewBinding.proOverlayView.panoramaAeLocked = true
        } catch (_: Throwable) {
            isPanoramaAeLocked = false
            viewBinding.proOverlayView.panoramaAeLocked = false
        }
    }

    private fun unlockPanoramaAeAwb() {
        if (isPanoramaAeLocked) {
            try {
                camera?.cameraControl?.cancelFocusAndMetering()
            } catch (_: Throwable) {}
        }
        isPanoramaAeLocked = false
        viewBinding.proOverlayView.panoramaAeLocked = false
    }

    private fun togglePanoramaCapture() {
        if (isPanoramaStitching) return
        if (!isPanoramaCapturing) {
            startPanoramaCapture()
        } else if (isPanoramaCloneMode) {
            val currentCount = synchronized(panoramaLock) { panoramaSlices.size }
            if (currentCount >= 4) {
                finishPanoramaCapture()
            } else {
                requestCloneSliceCapture = true
                window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } else {
            finishPanoramaCapture()
        }
    }

    private fun startPanoramaCapture() {
        clearPanoramaSlices()
        panoramaStitcher.reset()
        panoramaStitcher.desiredSweepDirection = panoramaSweepDir
        panoramaStitcher.cloneModeEnabled = isPanoramaCloneMode
        requestCloneSliceCapture = false
        isPanoramaCapturing = true
        panoramaStartYawDeg = latestUprightYawDeg
        panoramaStartPitchDeg = latestUprightPitchDeg
        panoramaDirection = when (panoramaSweepDir) {
            PanoramaStitcher.SweepDirection.LEFT -> -1
            PanoramaStitcher.SweepDirection.UP -> 2
            PanoramaStitcher.SweepDirection.DOWN -> -2
            else -> 1
        }

        lockPanoramaAeAwb()
        viewBinding.proOverlayView.clearPanoramaPreviewStrip()
        viewBinding.proOverlayView.panoramaSweepDir = panoramaSweepDir.name
        viewBinding.proOverlayView.panoramaCloneMode = isPanoramaCloneMode
        viewBinding.proOverlayView.panoramaTinyPlanetMode = isPanoramaTinyPlanetMode
        viewBinding.proOverlayView.isPanoramaCapturing = true

        val initialGuidance = when {
            isPanoramaCloneMode -> "CLONE_READY"
            panoramaSweepDir == PanoramaStitcher.SweepDirection.LEFT -> "PAN_LEFT"
            panoramaSweepDir == PanoramaStitcher.SweepDirection.UP -> "PAN_UP"
            panoramaSweepDir == PanoramaStitcher.SweepDirection.DOWN -> "PAN_DOWN"
            else -> "PAN_RIGHT"
        }
        viewBinding.proOverlayView.updatePanoramaAnalysis(
            guidance = initialGuidance,
            progress = 0f,
            overlapPct = 100,
            dxNorm = 0f,
            dyNorm = 0f,
            confidence = 1f,
            yawDeg = 0f,
            pitchDriftDeg = 0f,
            sliceCount = 0,
            direction = panoramaDirection,
            paceNorm = 0.22f
        )
        updateCaptureButtonVisual(isRecordingActive = true)
        viewBinding.tvRecordingStatus.text = when {
            isPanoramaCloneMode -> "CLONE PANO"
            isPanoramaTinyPlanetMode -> "360° PLANET"
            else -> "AUTO PANO"
        }
        viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#FACC15"))
        viewBinding.chronometer.text = if (isPanoramaCloneMode) "POSE 0/4 · CLONE" else "0/14 F · AE LOCK"
        playCameraCue(ToneGenerator.TONE_PROP_BEEP2, 90)
        if (isPanoramaCloneMode) {
            showHudNotice("CLONE PANO · AE LOCKED · TAP SHUTTER FOR EACH POSE")
        } else {
            dismissHudNotice()
        }
        refreshFloatingModeMenu()
    }

    private fun cancelPanoramaCapture() {
        isPanoramaCapturing = false
        requestCloneSliceCapture = false
        panoramaFallbackRunnable?.let { panoramaFallbackHandler.removeCallbacks(it) }
        panoramaFallbackRunnable = null
        panoLongPressRunnable?.let { panoramaFallbackHandler.removeCallbacks(it) }
        panoLongPressRunnable = null
        unlockPanoramaAeAwb()
        panoramaStitcher.reset()
        viewBinding.proOverlayView.clearPanoramaPreviewStrip()
        viewBinding.proOverlayView.isPanoramaCapturing = false
        viewBinding.proOverlayView.updatePanoramaAnalysis("READY", 0f, 100, 0f, 0f, 0f, 0f, 0f, 0, 0, 0.22f)
        clearPanoramaSlices()
        if (cameraMode == CameraMode.PANORAMA) {
            updateCaptureButtonVisual(isRecordingActive = false)
            refreshFloatingModeMenu()
        }
    }

    private fun clearPanoramaSlices() {
        synchronized(panoramaLock) {
            for (slice in panoramaSlices) {
                if (!slice.bitmap.isRecycled) slice.bitmap.recycle()
            }
            panoramaSlices.clear()
        }
    }

    private fun finishPanoramaCapture() {
        if (!isPanoramaCapturing) return
        isPanoramaCapturing = false
        requestCloneSliceCapture = false
        panoramaFallbackRunnable?.let { panoramaFallbackHandler.removeCallbacks(it) }
        panoramaFallbackRunnable = null
        panoLongPressRunnable?.let { panoramaFallbackHandler.removeCallbacks(it) }
        panoLongPressRunnable = null
        unlockPanoramaAeAwb()
        viewBinding.proOverlayView.isPanoramaCapturing = false
        updateCaptureButtonVisual(isRecordingActive = false)
        refreshFloatingModeMenu()

        val snapshot: List<PanoramaStitcher.PanoramaSlice>
        synchronized(panoramaLock) {
            snapshot = ArrayList(panoramaSlices)
            panoramaSlices.clear()
        }

        if (snapshot.isEmpty()) {
            viewBinding.proOverlayView.clearPanoramaPreviewStrip()
            return
        }
        if (snapshot.size == 1) {
            Toast.makeText(this, "Pan slowly along arrows before stopping (saved anchor frame)", Toast.LENGTH_SHORT).show()
        }

        val makeTinyPlanet = isPanoramaTinyPlanetMode
        isPanoramaStitching = true
        viewBinding.tvRecordingStatus.text = if (makeTinyPlanet) "PLANET" else "STITCH"
        viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#38BDF8"))
        showHudNotice(
            if (makeTinyPlanet) {
                "RENDERING 360° TINY PLANET FROM ${snapshot.size} FRAMES..."
            } else {
                "ALIGNING, LEVELING & STITCHING ${snapshot.size} PANORAMA FRAMES..."
            }
        )

        cameraExecutor.execute {
            var stitched: Bitmap? = null
            try {
                // Pixel-strip alignment, feathered Canvas stitch, and auto-horizon leveling
                val rawPano = panoramaStitcher.stitchSlices(snapshot) ?: return@execute
                val cleanPano = if (makeTinyPlanet) {
                    val planetBmp = panoramaStitcher.renderTinyPlanet(rawPano, 1200)
                    if (planetBmp != rawPano && !rawPano.isRecycled) rawPano.recycle()
                    planetBmp
                } else {
                    rawPano
                }
                stitched = cleanPano

                // Bake Watermark if active
                if (viewBinding.proOverlayView.showWatermark && viewBinding.proOverlayView.watermarkText.isNotBlank()) {
                    val pCanvas = Canvas(cleanPano)
                    val wmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor(injectedFonts.getOrNull(activeFontIndex)?.colorHex ?: "#38BDF8")
                        textSize = (cleanPano.height * 0.036f).coerceAtLeast(22f)
                        typeface = resolveFontTypeface(injectedFonts.getOrNull(activeFontIndex))
                        letterSpacing = 0.08f
                    }
                    cameraFontHelper?.drawWatermarkOverlay(
                        pCanvas,
                        viewBinding.proOverlayView.watermarkText,
                        cleanPano.width * 0.025f,
                        cleanPano.height - cleanPano.height * 0.055f,
                        wmPaint
                    )
                }

                val prefix = if (makeTinyPlanet) "PLANET_" else if (isPanoramaCloneMode) "CLONE_PANO_" else "PANO_"
                val name = prefix + SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
                    }
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) {
                    contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        cleanPano.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    lastCapturedUri = uri
                    lastCapturedMimeType = "image/jpeg"
                    val outW = cleanPano.width
                    val outH = cleanPano.height
                    runOnUiThread {
                        playShutterClickSound()
                        viewBinding.tvRecordingStatus.text = if (makeTinyPlanet) "PLANET" else "PANO"
                        viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#38BDF8"))
                        viewBinding.chronometer.text = if (makeTinyPlanet) "360° GLOBE" else "AUTO-LEVEL"
                        showHudNotice(
                            if (makeTinyPlanet) "Saved Tiny Planet (${outW}×${outH})"
                            else "Saved Panorama (${outW}×${outH})"
                        )
                        try {
                            viewBinding.ivGalleryThumbnail.setImageURI(null)
                            viewBinding.ivGalleryThumbnail.setImageURI(uri)
                        } catch (_: Exception) {}
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Panorama stitch error", t)
                runOnUiThread {
                    Toast.makeText(this, "Panorama save failed: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                for (s in snapshot) {
                    if (!s.bitmap.isRecycled) s.bitmap.recycle()
                }
                stitched?.takeIf { !it.isRecycled }?.recycle()
                panoramaStitcher.reset()
                isPanoramaStitching = false
                runOnUiThread {
                    viewBinding.proOverlayView.clearPanoramaPreviewStrip()
                    if (cameraMode == CameraMode.PANORAMA && !isPanoramaCapturing) {
                        viewBinding.proOverlayView.updatePanoramaAnalysis("READY", 0f, 100, 0f, 0f, 0f, 0f, 0f, 0, 0, 0.22f)
                        viewBinding.tvRecordingStatus.text = "PANO"
                        viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#38BDF8"))
                        viewBinding.chronometer.text = "AUTO-STITCH"
                    }
                }
            }
        }
    }

    /**
     * Shutter click with Countdown Timer support
     */
    private fun onShutterClicked() {
        if (isCountingDown) return

        if (countdownSeconds > 0) {
            startCountdownTimer(countdownSeconds)
        } else {
            takePhoto(isBurst = false)
        }
    }

    private fun startCountdownTimer(seconds: Int) {
        isCountingDown = true
        viewBinding.tvCountdown.visibility = View.VISIBLE

        object : CountDownTimer((seconds * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secRemaining = (millisUntilFinished / 1000) + 1
                viewBinding.tvCountdown.text = secRemaining.toString()
                playCameraCue(ToneGenerator.TONE_PROP_BEEP, 75)
                viewBinding.tvCountdown.animate().scaleX(1.4f).scaleY(1.4f).setDuration(150).withEndAction {
                    viewBinding.tvCountdown.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }.start()
            }

            override fun onFinish() {
                viewBinding.tvCountdown.visibility = View.GONE
                isCountingDown = false
                playCameraCue(ToneGenerator.TONE_PROP_BEEP2, 120)
                takePhoto(isBurst = false)
            }
        }.start()
    }

    private fun cycleCountdownTimer() {
        countdownSeconds = when (countdownSeconds) {
            0 -> 3
            3 -> 5
            5 -> 10
            else -> 0
        }
        if (countdownSeconds == 0) {
            viewBinding.tvTimerBadge.visibility = View.GONE
            viewBinding.btnTimer.setColorFilter(Color.parseColor("#CCCCCC"))
        } else {
            viewBinding.tvTimerBadge.visibility = View.VISIBLE
            viewBinding.tvTimerBadge.text = "${countdownSeconds}"
            viewBinding.btnTimer.setColorFilter(Color.parseColor("#E2E8F0"))
        }
        // silent (no popup)
    }

    // Auto Trigger Interval Loop Cycle (Auto Record -> Auto Pause -> Auto Resume Loop)
    private fun cycleAutoLoopTimer() {
        when {
            !isAutoLoopEnabled -> {
                isAutoLoopEnabled = true
                autoRecordDuration = 5
                autoPauseDuration = 3
                viewBinding.btnAutoLoop.setColorFilter(Color.parseColor("#E2E8F0"))
                // silent (no popup)
            }
            autoRecordDuration == 5 -> {
                autoRecordDuration = 10
                autoPauseDuration = 5
                viewBinding.btnAutoLoop.setColorFilter(Color.parseColor("#E2E8F0"))
                // silent (no popup)
            }
            autoRecordDuration == 10 -> {
                autoRecordDuration = 15
                autoPauseDuration = 5
                viewBinding.btnAutoLoop.setColorFilter(Color.parseColor("#E2E8F0"))
                // silent (no popup)
            }
            autoRecordDuration == 15 -> {
                autoRecordDuration = 30
                autoPauseDuration = 10
                viewBinding.btnAutoLoop.setColorFilter(Color.parseColor("#E2E8F0"))
                // silent (no popup)
            }
            else -> {
                isAutoLoopEnabled = false
                viewBinding.btnAutoLoop.setColorFilter(Color.parseColor("#CCCCCC"))
                // silent (no popup)
            }
        }
        updateAutoLoopHud()
    }

    private fun startAutoLoopTicker() {
        stopAutoLoopTicker()
        if (!isAutoLoopEnabled) return
        autoLoopIteration = 1
        autoPhaseRemaining = autoRecordDuration
        isAutoLoopPausedPhase = false
        updateAutoLoopHud()

        autoLoopRunnable = object : Runnable {
            override fun run() {
                if (recording == null) return
                autoPhaseRemaining--
                if (autoPhaseRemaining <= 0) {
                    val curRec = recording ?: return
                    if (!isAutoLoopPausedPhase) {
                        // Switch from recording to auto pause
                        isAutoLoopPausedPhase = true
                        autoPhaseRemaining = autoPauseDuration
                        curRec.pause()
                    } else {
                        // Switch from paused to auto resume (next take)
                        isAutoLoopPausedPhase = false
                        autoLoopIteration++
                        autoPhaseRemaining = autoRecordDuration
                        curRec.resume()
                    }
                }
                updateAutoLoopHud()
                autoLoopHandler.postDelayed(this, 1000)
            }
        }
        autoLoopHandler.postDelayed(autoLoopRunnable!!, 1000)
    }

    private fun stopAutoLoopTicker() {
        autoLoopRunnable?.let { autoLoopHandler.removeCallbacks(it) }
        autoLoopRunnable = null
    }

    private fun updateAutoLoopHud() {
        if (!isAutoLoopEnabled) {
            viewBinding.tvAutoLoopHud.visibility = View.GONE
            return
        }
        viewBinding.tvAutoLoopHud.visibility = View.VISIBLE
        if (recording == null) {
            viewBinding.tvAutoLoopHud.text = "AUTO: ${autoRecordDuration}s/${autoPauseDuration}s"
            viewBinding.tvAutoLoopHud.setTextColor(Color.parseColor("#10B981"))
        } else if (!isAutoLoopPausedPhase) {
            viewBinding.tvAutoLoopHud.text = "● AUTO-REC ${autoPhaseRemaining}s (T#${autoLoopIteration})"
            viewBinding.tvAutoLoopHud.setTextColor(Color.parseColor("#EF4444"))
        } else {
            viewBinding.tvAutoLoopHud.text = "⏸ AUTO-PAUSE ${autoPhaseRemaining}s (NEXT #${autoLoopIteration + 1})"
            viewBinding.tvAutoLoopHud.setTextColor(Color.parseColor("#F59E0B"))
        }
    }

    /**
     * 3. Photo Pro Mode: Manual Exposure Compensation Slider
     */
    private fun initExposureRange() {
        val exposureState = camera?.cameraInfo?.exposureState ?: return
        if (exposureState.isExposureCompensationSupported) {
            val range = exposureState.exposureCompensationRange
            val min = range.lower
            val max = range.upper
            var activeIndex = exposureState.exposureCompensationIndex

            if (cameraMode == CameraMode.NIGHT) {
                if (!hasSavedPreNightExposure) {
                    preNightExposureIndex = activeIndex
                    hasSavedPreNightExposure = true
                }
                // Lift hardware AE exposure in low light (+55% of positive range, or +25% when hardware Night Extension handles multi-frame)
                val ratio = if (isNightExtensionActive) 0.25f else 0.55f
                val targetIndex = Math.round(max * ratio).coerceIn(0, max)
                if (targetIndex != activeIndex) {
                    cameraControl?.setExposureCompensationIndex(targetIndex)
                    activeIndex = targetIndex
                }
            } else if (hasSavedPreNightExposure) {
                val restoredIndex = preNightExposureIndex.coerceIn(min, max)
                cameraControl?.setExposureCompensationIndex(restoredIndex)
                activeIndex = restoredIndex
                hasSavedPreNightExposure = false
            }

            viewBinding.proStudioDrawer.exposureSlider.max = max - min
            viewBinding.proStudioDrawer.exposureSlider.progress = (activeIndex - min).coerceIn(0, max - min)

            viewBinding.proStudioDrawer.exposureLayout.visibility = View.VISIBLE
            updateExposureLabel(activeIndex, exposureState.exposureCompensationStep.toFloat())
        } else {
            viewBinding.proStudioDrawer.exposureLayout.visibility = View.GONE
        }
    }

    private fun setupExposureSlider() {
        viewBinding.proStudioDrawer.exposureSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val exposureState = camera?.cameraInfo?.exposureState ?: return
                val min = exposureState.exposureCompensationRange.lower
                val targetIndex = min + progress
                cameraControl?.setExposureCompensationIndex(targetIndex)
                updateExposureLabel(targetIndex, exposureState.exposureCompensationStep.toFloat())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateExposureLabel(index: Int, step: Float) {
        val evValue = index * step
        val prefix = if (evValue > 0) "+" else ""
        viewBinding.proStudioDrawer.tvExposureValue.text = String.format(Locale.US, "EV %s%.1f", prefix, evValue)
    }

    private var lastActiveGrid = ProOverlayView.GridType.RULE_OF_THIRDS

    private fun selectShelfTab(category: ShelfCategory) {
        currentShelfTab = category
        viewBinding.proStudioDrawer.layoutCategoryGrids.visibility = if (category == ShelfCategory.GRIDS) View.VISIBLE else View.GONE
        viewBinding.proStudioDrawer.layoutCategoryExposure.visibility = if (category == ShelfCategory.EXPOSURE) View.VISIBLE else View.GONE
        viewBinding.proStudioDrawer.layoutCategoryOptics.visibility = if (category == ShelfCategory.OPTICS) View.VISIBLE else View.GONE
        viewBinding.proStudioDrawer.layoutCategoryAudio.visibility = if (category == ShelfCategory.AUDIO) View.VISIBLE else View.GONE

        // Update tab styles: Single un-bordered row, sharp solid neon yellow horizontal line (2dp thick) positioned exactly beneath active text
        val tabs = listOf(
            Triple(viewBinding.proStudioDrawer.tabCategoryGrids, viewBinding.proStudioDrawer.indicatorGrids, ShelfCategory.GRIDS),
            Triple(viewBinding.proStudioDrawer.tabCategoryExposure, viewBinding.proStudioDrawer.indicatorExposure, ShelfCategory.EXPOSURE),
            Triple(viewBinding.proStudioDrawer.tabCategoryOptics, viewBinding.proStudioDrawer.indicatorOptics, ShelfCategory.OPTICS),
            Triple(viewBinding.proStudioDrawer.tabCategoryAudio, viewBinding.proStudioDrawer.indicatorAudio, ShelfCategory.AUDIO)
        )
        for ((tabView, indicatorView, cat) in tabs) {
            val isActive = cat == category
            if (isActive) {
                tabView.setTextColor(Color.parseColor("#FFFFFF"))
                indicatorView.setBackgroundColor(Color.parseColor("#E2E8F0"))
                indicatorView.visibility = View.VISIBLE
            } else {
                tabView.setTextColor(Color.parseColor("#64748B"))
                indicatorView.setBackgroundColor(Color.TRANSPARENT)
                indicatorView.visibility = View.INVISIBLE
            }
        }
    }

    private fun updateArtStripModeOrder() {
        val container = viewBinding.effectsStudioDrawer.layoutArtContainer
        val picGroup = viewBinding.effectsStudioDrawer.layoutPictureArtGroup
        val vidGroup = viewBinding.effectsStudioDrawer.layoutVideoArtGroup
        val isVideoMode = (cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.SLOW_MO)
        container.removeView(picGroup)
        container.removeView(vidGroup)
        if (isVideoMode) {
            container.addView(vidGroup)
            container.addView(picGroup)
        } else {
            container.addView(picGroup)
            container.addView(vidGroup)
        }
    }

    private fun updateEffectIntensityVisibility() {
        val showIntensity = (currentShootingEffect != "none") || isComicFilterPreset(currentFilterName)
        viewBinding.effectsStudioDrawer.layoutEffectIntensity.visibility = if (showIntensity) View.VISIBLE else View.GONE
    }

    private fun selectCreativeCategory(target: CreativeTarget, fromUser: Boolean = true) {
        currentCreativeTarget = target
        if (fromUser) {
            isLastActionCreative = true
        }

        val isFilter = target == CreativeTarget.FILTERS
        val isEffect = target == CreativeTarget.EFFECTS
        val isArt = target == CreativeTarget.ART
        val isToon = target == CreativeTarget.TOON

        viewBinding.effectsStudioDrawer.btnCategoryFilters.setBackgroundResource(if (isFilter) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
        viewBinding.effectsStudioDrawer.btnCategoryFilters.setTextColor(Color.parseColor(if (isFilter) "#0F172A" else "#94A3B8"))

        viewBinding.effectsStudioDrawer.btnCategoryEffects.setBackgroundResource(if (isEffect) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
        viewBinding.effectsStudioDrawer.btnCategoryEffects.setTextColor(Color.parseColor(if (isEffect) "#0F172A" else "#94A3B8"))

        viewBinding.effectsStudioDrawer.btnCategoryArt.setBackgroundResource(if (isArt) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
        viewBinding.effectsStudioDrawer.btnCategoryArt.setTextColor(Color.parseColor(if (isArt) "#0F172A" else "#94A3B8"))

        viewBinding.effectsStudioDrawer.btnCategoryToon.setBackgroundResource(if (isToon) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
        viewBinding.effectsStudioDrawer.btnCategoryToon.setTextColor(Color.parseColor(if (isToon) "#0F172A" else "#94A3B8"))

        val activeTabBtn = when (target) {
            CreativeTarget.FILTERS -> viewBinding.effectsStudioDrawer.btnCategoryFilters
            CreativeTarget.EFFECTS -> viewBinding.effectsStudioDrawer.btnCategoryEffects
            CreativeTarget.ART -> viewBinding.effectsStudioDrawer.btnCategoryArt
            CreativeTarget.TOON -> viewBinding.effectsStudioDrawer.btnCategoryToon
        }
        scrollChipIntoView(viewBinding.effectsStudioDrawer.scrollEffectsCategories, activeTabBtn)

        viewBinding.effectsStudioDrawer.scrollFiltersStrip.visibility = if (isFilter) View.VISIBLE else View.GONE
        viewBinding.effectsStudioDrawer.scrollEffectsStrip.visibility = if (isEffect) View.VISIBLE else View.GONE
        viewBinding.effectsStudioDrawer.scrollArtStrip.visibility = if (isArt) View.VISIBLE else View.GONE
        viewBinding.effectsStudioDrawer.scrollToonStrip.visibility = if (isToon) View.VISIBLE else View.GONE

        when (target) {
            CreativeTarget.FILTERS -> updateFilterButtons(currentFilterName)
            CreativeTarget.EFFECTS -> updateShootingEffectButtons()
            CreativeTarget.ART -> {
                updateArtStripModeOrder()
                updateArtEffectButtons()
            }
            CreativeTarget.TOON -> updateToonEffectButtons()
        }

        updateEffectIntensityVisibility()
        updateCreativeStatusUI()
    }

    private fun getShootingEffectLabel(effectId: String): String = when (effectId) {
        "portrait_bokeh" -> "PORTRAIT BOKEH"
        "long_exposure" -> "LONG EXPOSURE"
        "light_painting" -> "LIGHT PAINTING"
        "star_trails" -> "STAR TRAILS"
        "night_sight" -> "NIGHT SIGHT"
        "macro_focus" -> "MACRO FOCUS"
        "tilt_shift" -> "TILT-SHIFT"
        "double_exposure" -> "DOUBLE EXPOSURE"
        "panorama" -> "TINY PLANET"
        "film_grain" -> "35MM FILM GRAIN"
        "soft_halation" -> "SOFT HALATION"
        "vignette" -> "VIGNETTE"
        "video_bokeh" -> "VIDEO BOKEH"
        "slow_motion_120_240" -> "SLOW-MO 240FPS"
        "time_lapse" -> "TIME-LAPSE"
        "hyperlapse" -> "HYPERLAPSE"
        "anamorphic_flare" -> "ANAMORPHIC FLARE"
        "dolly_zoom" -> "DOLLY ZOOM"
        "motion_blur" -> "MOTION BLUR"
        "action_stabilization" -> "HORIZON LOCK"
        "vintage_super8", "super8_film" -> "8MM RETRO FILM"
        "oil_canvas" -> "OIL CANVAS"
        "charcoal_sketch" -> "CHARCOAL SKETCH"
        "watercolor_wash" -> "WATERCOLOR WASH"
        "ink_and_line" -> "INK & LINE"
        "pastel_chalk" -> "PASTEL CHALK"
        "halftone_print" -> "HALFTONE PRINT"
        "cyanotype" -> "CYANOTYPE"
        "woodcut_engraving" -> "WOODCUT ENGRAVING"
        "lithograph" -> "LITHOGRAPH"
        "prism_refraction" -> "PRISM REFRACTION"
        "kaleidoscope" -> "KALEIDOSCOPE"
        "solarize" -> "SOLARIZE (SABATTIER)"
        "film_16mm" -> "16MM DOCUMENTARY"
        "vhs_tape" -> "VHS TAPE (1990S)"
        "crt_monitor" -> "CRT MONITOR"
        "stop_motion" -> "STOP-MOTION FRAME"
        "neon_edge" -> "NEON EDGE"
        "noir_graphic_novel" -> "NOIR GRAPHIC NOVEL"
        "thermal_vision" -> "THERMAL VISION"
        "cel_shaded_studio" -> "CEL-SHADED STUDIO"
        "graphic_novel_noir" -> "GRAPHIC NOVEL (NOIR)"
        "manga_ink" -> "MANGA INK"
        "pop_art_print" -> "POP-ART PRINT"
        "line_art_minimalist" -> "LINE ART (MINIMAL)"
        "ligne_claire" -> "LIGNE CLAIRE"
        "clay_vinyl_3d" -> "3D CLAY / VINYL"
        else -> "OFF"
    }

    private fun getFilterDisplayLabel(presetId: String): String {
        val custom = injectedCustomFilters.find { it.id == presetId }
        if (custom != null && custom.name.isNotBlank()) return custom.name.uppercase(Locale.ROOT)
        return when (presetId.lowercase(Locale.ROOT)) {
            "normal" -> "NORMAL"
            "lut_rec709", "filter_lut_rec709" -> "REC.709"
            "lut_flat_log", "filter_lut_flat_log" -> "FLAT LOG"
            "lut_teal_orange", "filter_lut_blockbuster_teal_orange" -> "LUT TEAL & ORANGE"
            "lut_bleach_bypass", "filter_lut_bleach_bypass" -> "BLEACH BYPASS"
            "lut_anamorphic_cyan", "filter_lut_anamorphic_cyan" -> "ANAMORPHIC CYAN"
            "lut_matte_cinema", "filter_lut_matte_cinema" -> "MATTE CINEMA"
            "lut_midnight_neon", "filter_lut_midnight_neon" -> "MIDNIGHT NEON"
            "lut_warm_documentary", "filter_lut_warm_documentary" -> "WARM DOCU"
            "lut_noir_cinema", "filter_lut_noir_cinema" -> "NOIR CINEMA"
            "cinematic" -> "CINEMA"
            "bw", "black_and_white", "mono" -> "MONO B&W"
            "hdr" -> "VIVID HDR"
            "super8_vintage", "filter_super8_retro" -> "SUPER 8 RETRO"
            else -> presetId.removePrefix("filter_").replace("_", " ").uppercase(Locale.ROOT)
        }
    }

    private fun updateCreativeStatusUI() {
        val statusText = when (currentCreativeTarget) {
            CreativeTarget.FILTERS -> {
                val total = (filterPresetOrder.size - 1).coerceAtLeast(1)
                val idx = filterPresetOrder.indexOf(currentFilterName)
                val label = getFilterDisplayLabel(currentFilterName)
                if (idx > 0) "FILTER: $label ($idx/$total)" else "FILTER: NORMAL (0/$total)"
            }
            CreativeTarget.ART -> {
                val artList = getModeOrderedArtEffects()
                val total = (artList.size - 1).coerceAtLeast(1)
                val idx = artList.indexOf(currentShootingEffect)
                if (idx > 0) {
                    "ART: ${getShootingEffectLabel(currentShootingEffect)} ($idx/$total)"
                } else if (currentShootingEffect != "none") {
                    "ART: OFF · ACTIVE: ${getShootingEffectLabel(currentShootingEffect)}"
                } else {
                    "ART: OFF (0/$total)"
                }
            }
            CreativeTarget.TOON -> {
                val total = (toonStyleOrder.size - 1).coerceAtLeast(1)
                val idx = toonStyleOrder.indexOf(currentShootingEffect)
                if (idx > 0) {
                    "TOON: ${getShootingEffectLabel(currentShootingEffect)} ($idx/$total)"
                } else if (currentShootingEffect != "none") {
                    "TOON: OFF · ACTIVE: ${getShootingEffectLabel(currentShootingEffect)}"
                } else {
                    "TOON: OFF (0/$total)"
                }
            }
            CreativeTarget.EFFECTS -> {
                val modeList = getModeFilteredShootingEffects()
                val total = (modeList.size - 1).coerceAtLeast(1)
                val idx = modeList.indexOf(currentShootingEffect)
                if (idx > 0 && !artEffectIds.contains(currentShootingEffect) && !toonEffectIds.contains(currentShootingEffect)) {
                    "EFFECT: ${getShootingEffectLabel(currentShootingEffect)} ($idx/$total)"
                } else if (artEffectIds.contains(currentShootingEffect) || toonEffectIds.contains(currentShootingEffect)) {
                    "EFFECT: OFF · ACTIVE: ${getShootingEffectLabel(currentShootingEffect)}"
                } else {
                    val g = viewBinding.proOverlayView.enableFilmGrain
                    val d = viewBinding.proOverlayView.enableDateStamp
                    val f = viewBinding.proOverlayView.enableCineFlare
                    when {
                        g && d && f -> "EFFECT: CINEMA COMBO"
                        g -> "EFFECT: 35MM GRAIN"
                        d -> "EFFECT: 90S STAMP"
                        f -> "EFFECT: CINE FLARE"
                        else -> "EFFECT: OFF (0/$total)"
                    }
                }
            }
        }
        viewBinding.effectsStudioDrawer.tvEffectsActiveStatus.text = statusText
        viewBinding.effectsStudioDrawer.tvEffectsActiveStatus.isSelected = true
    }

    private fun scrollChipIntoView(scrollView: android.widget.HorizontalScrollView, child: View) {
        fun performScroll() {
            val contentRoot = scrollView.getChildAt(0) as? ViewGroup ?: return
            if (scrollView.width <= 0 || contentRoot.width <= 0 || child.width <= 0) return
            val rect = Rect()
            child.getDrawingRect(rect)
            try {
                contentRoot.offsetDescendantRectToMyCoords(child, rect)
            } catch (_: IllegalArgumentException) {
                return
            }
            val maxScroll = (contentRoot.width - scrollView.width).coerceAtLeast(0)
            val scrollX = (rect.centerX() - (scrollView.width / 2)).coerceIn(0, maxScroll)
            scrollView.smoothScrollTo(scrollX, 0)
        }
        if (scrollView.width > 0 && child.width > 0 && !scrollView.isLayoutRequested) {
            scrollView.post { performScroll() }
        } else {
            scrollView.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    if (v.width > 0 && child.width > 0) {
                        v.removeOnLayoutChangeListener(this)
                        scrollView.post { performScroll() }
                    }
                }
            })
            scrollView.post { performScroll() }
        }
    }

    private fun findTextureView(view: View): TextureView? {
        if (view is TextureView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findTextureView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun isComicFilterPreset(preset: String): Boolean = when (preset.lowercase(Locale.ROOT)) {
        "comic_book", "comic", "cartoon" -> true
        else -> false
    }

    private fun isStylizedLiveEffect(effectId: String): Boolean = when (effectId) {
        "oil_canvas", "charcoal_sketch", "watercolor_wash", "ink_and_line",
        "pastel_chalk", "halftone_print", "cyanotype", "woodcut_engraving",
        "lithograph", "prism_refraction", "kaleidoscope", "solarize",
        "neon_edge", "noir_graphic_novel", "thermal_vision",
        "cel_shaded_studio", "graphic_novel_noir", "manga_ink", "pop_art_print",
        "line_art_minimalist", "ligne_claire", "clay_vinyl_3d",
        "portrait_bokeh", "video_bokeh", "tilt_shift", "motion_blur" -> true
        else -> false
    }

    private fun syncAllStateToOpenGLProcessor() {
        val proc = openGLProcessor ?: return
        if (::filterManager.isInitialized) {
            proc.setFilterPreset(filterManager.getPreset())
        }
        val custom = injectedCustomFilters.find { it.id == currentFilterName }
        if (custom != null) {
            proc.setCustomFilterParams(true, custom.brightness, custom.contrast, custom.saturation, custom.grayscale > 50)
        } else {
            proc.setCustomFilterParams(false, 0, 0, 0, false)
        }
        proc.setShootingEffect(currentShootingEffect)
        proc.setEffectIntensity(currentShootingEffect, (activeEffectIntensity * 100f).toInt().coerceIn(0, 100))
        proc.setAdjustments(
            viewBinding.proOverlayView.liveBrightnessPercent,
            viewBinding.proOverlayView.liveContrastPercent,
            viewBinding.proOverlayView.liveSaturationPercent
        )
        proc.setNightBoost(cameraMode == CameraMode.NIGHT || currentShootingEffect == "night_sight")
        proc.setComicHalftoneParams(comicHalftoneDensity, comicHalftoneDotSize)
        proc.setOverlayFlags(
            filmGrain = viewBinding.proOverlayView.enableFilmGrain || currentFilterName == "ilford_grain",
            vignette = viewBinding.proOverlayView.enableVignette,
            cineFlare = viewBinding.proOverlayView.enableCineFlare
        )
        if (::gyroStabilizer.isInitialized) {
            val t = gyroStabilizer.currentTelemetry
            proc.setGyroEisTransform(t.isEnabled, t.overscanZoom, t.compXNorm, t.compYNorm, t.compRollDeg)
        }
        syncOverlayToVideoProcessor()
    }

    private fun applyLivePreviewColorMatrix() {
        val targetView = findTextureView(viewBinding.viewFinder)
        if (targetView == null) {
            viewBinding.viewFinder.post { applyLivePreviewColorMatrix() }
            return
        }

        val bri = viewBinding.proOverlayView.liveBrightnessPercent
        val con = viewBinding.proOverlayView.liveContrastPercent
        val sat = viewBinding.proOverlayView.liveSaturationPercent
        val stylizedActive = isStylizedLiveEffect(currentShootingEffect) || isComicFilterPreset(currentFilterName)
        val isNightActive = (cameraMode == CameraMode.NIGHT || currentShootingEffect == "night_sight")
        val isIdentity = isPreviewCameraEffectActive || stylizedActive || (currentFilterName == "normal" && bri == 0 && con == 0 && sat == 0 &&
            !isNightActive &&
            currentShootingEffect != "macro_focus" &&
            currentShootingEffect != "double_exposure" &&
            currentShootingEffect != "vintage_super8" &&
            currentShootingEffect != "super8_film" &&
            currentShootingEffect != "film_16mm" &&
            currentShootingEffect != "vhs_tape" &&
            currentShootingEffect != "crt_monitor" &&
            currentShootingEffect != "stop_motion")

        if (isIdentity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                targetView.setRenderEffect(null)
            } else {
                targetView.setLayerType(View.LAYER_TYPE_NONE, null)
            }
            return
        }

        val combined = buildActiveColorMatrix(includeEffectColorLook = true)
        val colorFilter = ColorMatrixColorFilter(combined)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            targetView.setRenderEffect(RenderEffect.createColorFilterEffect(colorFilter))
        } else {
            val paint = Paint().apply { this.colorFilter = colorFilter }
            targetView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        }
    }

    private fun buildActiveColorMatrix(includeEffectColorLook: Boolean): ColorMatrix {
        val bri = viewBinding.proOverlayView.liveBrightnessPercent
        val con = viewBinding.proOverlayView.liveContrastPercent
        val sat = viewBinding.proOverlayView.liveSaturationPercent
        val combined = ColorMatrix()

        // 1. Base Preset Color Matrix
        when (currentFilterName.lowercase(Locale.ROOT)) {
            "lut_rec709", "filter_lut_rec709" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.08f) })
                val c = 1.12f
                val t = (-0.5f * c + 0.5f) * 255f + 4f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "lut_flat_log", "filter_lut_flat_log" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0.62f) })
                val c = 0.68f
                val t = (-0.5f * c + 0.5f) * 255f + 22f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "lut_teal_orange", "filter_lut_blockbuster_teal_orange" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.36f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    1.20f, 0.04f, -0.06f, 0f, 8f,
                    0.02f, 1.04f, 0.04f, 0f, 0f,
                    -0.08f, 0.08f, 1.24f, 0f, 6f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "lut_bleach_bypass", "filter_lut_bleach_bypass" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0.52f) })
                val c = 1.52f
                val t = (-0.5f * c + 0.5f) * 255f - 8f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "lut_anamorphic_cyan", "filter_lut_anamorphic_cyan" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.18f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    0.96f, 0f, 0.02f, 0f, -12f,
                    0.04f, 1.18f, 0.12f, 0f, -2f,
                    0.06f, 0.14f, 1.32f, 0f, 6f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "lut_matte_cinema", "filter_lut_matte_cinema" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0.86f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    0.86f, 0.04f, 0.0f, 0f, 26f,
                    0.02f, 0.85f, 0.02f, 0f, 24f,
                    0.0f, 0.02f, 0.82f, 0f, 22f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "lut_midnight_neon", "filter_lut_midnight_neon" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.56f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    1.14f, -0.06f, 0.16f, 0f, -4f,
                    -0.04f, 0.94f, 0.14f, 0f, -6f,
                    0.18f, 0.06f, 1.38f, 0f, 16f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "lut_warm_documentary", "filter_lut_warm_documentary" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.14f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    1.08f, 0.04f, 0.0f, 0f, 10f,
                    0.02f, 1.04f, 0.0f, 0f, 6f,
                    0.0f, 0.02f, 0.92f, 0f, -4f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "lut_noir_cinema", "filter_lut_noir_cinema" -> {
                combined.setSaturation(0f)
                val c = 1.72f
                val t = (-0.5f * c + 0.5f) * 255f - 14f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "golden_nostalgia", "filter_golden_nostalgia" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.16f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    1.06f, 0.08f, 0.0f, 0f, 12f,
                    0.04f, 1.03f, 0.02f, 0f, 10f,
                    0.0f, 0.03f, 0.80f, 0f, -6f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "velvia_landscape", "filter_velvia_landscape" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.52f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    1.14f, 0f, 0f, 0f, -14f,
                    0f, 1.26f, 0f, 0f, -12f,
                    0f, 0f, 1.24f, 0f, -12f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "nordic_slate", "filter_nordic_slate" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0.64f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    1.08f, 0f, 0.04f, 0f, -16f,
                    0f, 1.12f, 0.06f, 0f, -12f,
                    0.02f, 0.08f, 1.24f, 0f, -4f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "arctic_mist", "filter_arctic_mist" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0.58f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    0.90f, 0f, 0.03f, 0f, 14f,
                    0f, 0.93f, 0.05f, 0f, 16f,
                    0.02f, 0.06f, 1.02f, 0f, 22f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "classic_mono", "filter_classic_mono" -> {
                combined.setSaturation(0f)
                val contrast = 1.14f
                val t = (-0.5f * contrast + 0.5f) * 255f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    contrast, 0f, 0f, 0f, t,
                    0f, contrast, 0f, 0f, t,
                    0f, 0f, contrast, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "high_contrast_noir", "filter_high_contrast_noir" -> {
                combined.setSaturation(0f)
                val contrast = 1.68f
                val t = (-0.5f * contrast + 0.5f) * 255f - 10f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    contrast, 0f, 0f, 0f, t,
                    0f, contrast, 0f, 0f, t,
                    0f, 0f, contrast, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "ilford_grain", "filter_ilford_grain" -> {
                combined.setSaturation(0f)
                val contrast = 1.32f
                val t = (-0.5f * contrast + 0.5f) * 255f + 4f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    contrast, 0f, 0f, 0f, t,
                    0f, contrast, 0f, 0f, t,
                    0f, 0f, contrast, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "silver_gelatin", "filter_silver_gelatin" -> {
                combined.setSaturation(0f)
                combined.postConcat(ColorMatrix(floatArrayOf(
                    1.22f, 0f, 0f, 0f, -18f,
                    0f, 1.19f, 0f, 0f, -20f,
                    0f, 0f, 1.12f, 0f, -24f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "antique_sepia", "filter_antique_sepia" -> {
                combined.postConcat(ColorMatrix(floatArrayOf(
                    0.44f, 0.84f, 0.21f, 0f, -12f,
                    0.38f, 0.74f, 0.18f, 0f, -14f,
                    0.27f, 0.53f, 0.13f, 0f, -16f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "bw", "black_and_white", "mono" -> {
                combined.setSaturation(0f)
                val contrast = 1.24f
                val t = (-0.5f * contrast + 0.5f) * 255f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    contrast, 0f, 0f, 0f, t,
                    0f, contrast, 0f, 0f, t,
                    0f, 0f, contrast, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "vintage" -> {
                val satMatrix = ColorMatrix().apply { setSaturation(0.65f) }
                combined.postConcat(satMatrix)
                combined.postConcat(ColorMatrix(floatArrayOf(
                    0.95f, 0.22f, 0.08f, 0f, 14f,
                    0.12f, 0.88f, 0.08f, 0f, 8f,
                    0.05f, 0.14f, 0.72f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "cinematic" -> {
                val satMatrix = ColorMatrix().apply { setSaturation(1.12f) }
                combined.postConcat(satMatrix)
                combined.postConcat(ColorMatrix(floatArrayOf(
                    1.14f, -0.03f, 0.0f, 0f, 6f,
                    -0.02f, 1.05f, 0.02f, 0f, 0f,
                    -0.06f, 0.04f, 1.18f, 0f, 8f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "cyberpunk", "cyber" -> {
                val satMatrix = ColorMatrix().apply { setSaturation(1.45f) }
                combined.postConcat(satMatrix)
                combined.postConcat(ColorMatrix(floatArrayOf(
                    1.18f, -0.08f, 0.14f, 0f, 4f,
                    -0.06f, 0.96f, 0.16f, 0f, -2f,
                    0.12f, 0.04f, 1.28f, 0f, 12f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "hdr" -> {
                val satMatrix = ColorMatrix().apply { setSaturation(1.38f) }
                combined.postConcat(satMatrix)
                val contrast = 1.22f
                val t = (-0.5f * contrast + 0.5f) * 255f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    contrast, 0f, 0f, 0f, t,
                    0f, contrast, 0f, 0f, t,
                    0f, 0f, contrast, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "portra_400", "filter_portra_400" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.12f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    1.06f, 0.04f, 0f, 0f, 12f,
                    0.02f, 1.03f, 0f, 0f, 8f,
                    0f, 0.02f, 0.94f, 0f, 6f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "fuji_400h", "filter_fuji_400h" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.06f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    0.96f, 0.02f, 0f, 0f, 8f,
                    0f, 1.08f, 0.03f, 0f, 12f,
                    0.02f, 0.05f, 1.06f, 0f, 12f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "teal_orange", "filter_teal_orange" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.30f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    1.18f, 0.03f, -0.05f, 0f, 6f,
                    0.02f, 1.02f, 0.04f, 0f, 0f,
                    -0.06f, 0.08f, 1.22f, 0f, 4f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "noir_contrast", "filter_noir_contrast" -> {
                combined.setSaturation(0f)
                val c = 1.68f
                val t = (-0.5f * c + 0.5f) * 255f - 8f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "sunset_warmth", "filter_sunset_warmth" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.25f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    1.14f, 0.06f, 0f, 0f, 16f,
                    0.02f, 1.02f, 0f, 0f, 6f,
                    0f, 0f, 0.84f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "matrix_code", "filter_matrix_code" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0.75f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    0.78f, 0f, 0f, 0f, -8f,
                    0.05f, 1.24f, 0.05f, 0f, 10f,
                    0f, 0.02f, 0.82f, 0f, -4f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "pastel_dream", "filter_pastel_dream" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0.82f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    0.96f, 0.04f, 0.02f, 0f, 20f,
                    0.02f, 0.94f, 0.04f, 0f, 18f,
                    0.04f, 0.04f, 1.02f, 0f, 24f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "nordic_blue", "filter_nordic_mood" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0.76f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    0.94f, 0f, 0.02f, 0f, -10f,
                    0f, 1.02f, 0.04f, 0f, -4f,
                    0.02f, 0.06f, 1.22f, 0f, 10f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "super8_vintage", "filter_super8_retro" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0.90f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    1.10f, 0.10f, 0f, 0f, 14f,
                    0.04f, 1.02f, 0f, 0f, 8f,
                    0f, 0.02f, 0.82f, 0f, -8f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            else -> {
                val custom = injectedCustomFilters.find { it.id == currentFilterName }
                if (custom != null) {
                    val satVal = (1.0f + custom.saturation / 100f).coerceIn(0f, 2.5f)
                    combined.postConcat(ColorMatrix().apply { setSaturation(if (custom.grayscale > 50) 0f else satVal) })
                    val conVal = (1.0f + custom.contrast / 100f).coerceIn(0.4f, 2.2f)
                    val t = (-0.5f * conVal + 0.5f) * 255f + (custom.brightness * 1.2f)
                    combined.postConcat(ColorMatrix(floatArrayOf(
                        conVal, 0f, 0f, 0f, t,
                        0f, conVal, 0f, 0f, t,
                        0f, 0f, conVal, 0f, t,
                        0f, 0f, 0f, 1f, 0f
                    )))
                }
            }
        }

        // 1b. Night Mode & Optical Shooting Effects live luminance boosts
        if (includeEffectColorLook) {
        if (cameraMode == CameraMode.NIGHT && currentShootingEffect != "night_sight") {
            val gain = if (isNightExtensionActive) 1.22f else 1.38f
            val lift = if (isNightExtensionActive) 18f else 36f
            combined.postConcat(ColorMatrix().apply { setSaturation(1.12f) })
            combined.postConcat(ColorMatrix(floatArrayOf(
                gain, 0f, 0f, 0f, lift,
                0f, gain + 0.02f, 0f, 0f, lift + 2f,
                0f, 0f, gain + 0.02f, 0f, lift + 2f,
                0f, 0f, 0f, 1f, 0f
            )))
        }

        when (currentShootingEffect) {
            "night_sight" -> {
                val gain = if (cameraMode == CameraMode.NIGHT) 1.42f else 1.28f
                val lift = if (cameraMode == CameraMode.NIGHT) 38f else 26f
                combined.postConcat(ColorMatrix().apply { setSaturation(1.10f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    gain, 0f, 0f, 0f, lift,
                    0f, gain + 0.02f, 0f, 0f, lift + 2f,
                    0f, 0f, gain, 0f, lift,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "tilt_shift" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.32f) })
                val c = 1.12f
                val t = (-0.5f * c + 0.5f) * 255f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "macro_focus" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.08f) })
                val c = 1.12f
                val t = (-0.5f * c + 0.5f) * 255f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "double_exposure" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.18f) })
            }
            "vintage_super8" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0.84f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    1.08f, 0.08f, 0.0f, 0f, 8f,
                    0.03f, 1.02f, 0.0f, 0f, 4f,
                    0.0f, 0.02f, 0.82f, 0f, -6f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "oil_canvas" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.42f) })
                val c = 1.20f
                val t = (-0.5f * c + 0.5f) * 255f + 4f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "charcoal_sketch" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0f) })
                val c = 1.65f
                val t = (-0.5f * c + 0.5f) * 255f + 22f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "watercolor_wash" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.26f) })
                val c = 0.90f
                val t = (-0.5f * c + 0.5f) * 255f + 18f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "ink_and_line" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0f) })
                val c = 2.45f
                val t = (-0.5f * c + 0.5f) * 255f + 16f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "pastel_chalk" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0.78f) })
                val c = 0.82f
                val t = (-0.5f * c + 0.5f) * 255f + 28f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "halftone_print" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0.85f) })
                val c = 1.40f
                val t = (-0.5f * c + 0.5f) * 255f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "cyanotype" -> {
                // Historic Prussian-blue duotone architectural blueprint mapping
                combined.postConcat(ColorMatrix().apply { setSaturation(0f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    0.82f, 0f, 0f, 0f, 8f,
                    0f, 0.92f, 0f, 0f, 34f,
                    0f, 0f, 1.15f, 0f, 74f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "woodcut_engraving" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0f) })
                val c = 2.30f
                val t = (-0.5f * c + 0.5f) * 255f + 6f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "lithograph" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0.36f) })
                val c = 1.32f
                val t = (-0.5f * c + 0.5f) * 255f + 8f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t + 4f,
                    0f, c, 0f, 0f, t + 2f,
                    0f, 0f, c, 0f, t - 4f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "prism_refraction", "kaleidoscope" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.24f) })
            }
            "solarize" -> {
                // Surreal metallic darkroom highlight-inversion tone curve
                combined.postConcat(ColorMatrix(floatArrayOf(
                    -0.85f, 0.15f, 0.25f, 0f, 185f,
                    0.15f, -0.82f, 0.25f, 0f, 192f,
                    0.20f, 0.20f, -0.78f, 0f, 205f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "super8_film" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0.92f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    1.14f, 0.08f, 0f, 0f, 10f,
                    0.04f, 1.04f, 0f, 0f, 4f,
                    0f, 0.02f, 0.80f, 0f, -8f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "film_16mm" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0.88f) })
                val c = 1.14f
                val t = (-0.5f * c + 0.5f) * 255f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0.02f, 0f, 0f, t + 4f,
                    0f, c, 0f, 0f, t + 2f,
                    0f, 0f, c * 0.94f, 0f, t - 2f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "vhs_tape" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.26f) })
                val c = 1.08f
                val t = (-0.5f * c + 0.5f) * 255f + 6f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t + 4f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "crt_monitor", "stop_motion" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.18f) })
                val c = 1.18f
                val t = (-0.5f * c + 0.5f) * 255f + 4f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "neon_edge" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.75f) })
                val c = 1.65f
                val t = (-0.5f * c + 0.5f) * 255f - 48f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c * 0.85f, 0f, 0.2f, 0f, t,
                    0f, c * 1.05f, 0.1f, 0f, t,
                    0.15f, 0.1f, c * 1.15f, 0f, t + 18f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "noir_graphic_novel" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0f) })
                val c = 2.40f
                val t = (-0.5f * c + 0.5f) * 255f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "thermal_vision" -> {
                // False-color infrared FLIR live preview matrix
                combined.postConcat(ColorMatrix(floatArrayOf(
                    1.65f, 0.55f, -0.95f, 0f, 18f,
                    -0.35f, 1.15f, -0.45f, 0f, -12f,
                    -0.85f, -0.25f, 1.85f, 0f, 38f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "cel_shaded_studio" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.38f) })
                val c = 1.40f
                val t = (-0.5f * c + 0.5f) * 255f + 4f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "graphic_novel_noir" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0f) })
                val c = 2.38f
                val t = (-0.5f * c + 0.5f) * 255f + 4f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "manga_ink" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0f) })
                val c = 2.15f
                val t = (-0.5f * c + 0.5f) * 255f + 16f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "pop_art_print" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(2.15f) })
                val c = 1.65f
                val t = (-0.5f * c + 0.5f) * 255f + 6f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "line_art_minimalist" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0f) })
                val c = 2.55f
                val t = (-0.5f * c + 0.5f) * 255f + 26f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "ligne_claire" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(0.88f) })
                val c = 1.18f
                val t = (-0.5f * c + 0.5f) * 255f + 14f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            "clay_vinyl_3d" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.24f) })
                val c = 1.08f
                val t = (-0.5f * c + 0.5f) * 255f + 10f
                combined.postConcat(ColorMatrix(floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
        }
        }

        // 2. Live Adjustment Sliders (Brightness / Contrast / Saturation)
        if (sat != 0) {
            val satVal = (1.0f + (sat / 100f)).coerceIn(0f, 2.5f)
            combined.postConcat(ColorMatrix().apply { setSaturation(satVal) })
        }
        if (con != 0) {
            val conVal = if (con >= 0) 1.0f + (con / 100f) else 1.0f + (con / 200f)
            val translate = (-0.5f * conVal + 0.5f) * 255f
            combined.postConcat(ColorMatrix(floatArrayOf(
                conVal, 0f, 0f, 0f, translate,
                0f, conVal, 0f, 0f, translate,
                0f, 0f, conVal, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        if (bri != 0) {
            val bOff = bri * 1.2f
            combined.postConcat(ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, bOff,
                0f, 1f, 0f, 0f, bOff,
                0f, 0f, 1f, 0f, bOff,
                0f, 0f, 0f, 1f, 0f
            )))
        }

        return combined
    }

    private var hudNoticeRunnable: Runnable? = null
    private val hudNoticeHandler = Handler(Looper.getMainLooper())

    private fun dismissHudNotice() {
        runOnUiThread {
            try {
                hudNoticeRunnable?.let { hudNoticeHandler.removeCallbacks(it) }
                hudNoticeRunnable = null
                viewBinding.layoutHudNotice.animate().cancel()
                viewBinding.layoutHudNotice.visibility = View.GONE
            } catch (_: Exception) {}
        }
    }

    private fun showHudNotice(text: String, isWarning: Boolean = false) {
        runOnUiThread {
            try {
                hudNoticeRunnable?.let { hudNoticeHandler.removeCallbacks(it) }
                viewBinding.tvHudNotice.text = text
                viewBinding.tvHudNotice.setTextColor(if (isWarning) Color.parseColor("#F59E0B") else Color.parseColor("#34D399"))
                viewBinding.layoutHudNotice.visibility = View.VISIBLE
                viewBinding.layoutHudNotice.alpha = 1.0f
                hudNoticeRunnable = Runnable {
                    viewBinding.layoutHudNotice.animate()
                        .alpha(0f)
                        .setDuration(250)
                        .withEndAction { viewBinding.layoutHudNotice.visibility = View.GONE }
                        .start()
                }
                hudNoticeHandler.postDelayed(hudNoticeRunnable!!, 1700)
            } catch (e: Exception) {
                // Ignore view lifecycle edge case
            }
        }
    }

    private fun isCartoonShaderActive(): Boolean {
        return isComicFilterPreset(currentFilterName) || currentShootingEffect in setOf(
            "cel_shaded_studio",
            "graphic_novel_noir",
            "noir_graphic_novel",
            "manga_ink",
            "pop_art_print",
            "line_art_minimalist",
            "ligne_claire"
        )
    }

    private fun syncComicFrameOverlayState() {
        viewBinding.arFaceOverlayView.isCartoonShaderActive = isCartoonShaderActive()
    }

    private fun setFilterPreset(preset: String, showNotice: Boolean = true) {
        currentFilterName = preset
        if (showNotice) {
            currentCreativeTarget = CreativeTarget.FILTERS
            isLastActionCreative = true
        }
        val filterPreset = when (preset.lowercase(Locale.ROOT)) {
            "lut_rec709", "filter_lut_rec709" -> FilterManager.FilterPreset.LUT_REC709
            "lut_flat_log", "filter_lut_flat_log" -> FilterManager.FilterPreset.LUT_FLAT_LOG
            "lut_teal_orange", "filter_lut_blockbuster_teal_orange" -> FilterManager.FilterPreset.LUT_TEAL_ORANGE
            "lut_bleach_bypass", "filter_lut_bleach_bypass" -> FilterManager.FilterPreset.LUT_BLEACH_BYPASS
            "lut_anamorphic_cyan", "filter_lut_anamorphic_cyan" -> FilterManager.FilterPreset.LUT_ANAMORPHIC_CYAN
            "lut_matte_cinema", "filter_lut_matte_cinema" -> FilterManager.FilterPreset.LUT_MATTE_CINEMA
            "lut_midnight_neon", "filter_lut_midnight_neon" -> FilterManager.FilterPreset.LUT_MIDNIGHT_NEON
            "lut_warm_documentary", "filter_lut_warm_documentary" -> FilterManager.FilterPreset.LUT_WARM_DOCUMENTARY
            "lut_noir_cinema", "filter_lut_noir_cinema" -> FilterManager.FilterPreset.LUT_NOIR_CINEMA
            "golden_nostalgia", "filter_golden_nostalgia" -> FilterManager.FilterPreset.GOLDEN_NOSTALGIA
            "velvia_landscape", "filter_velvia_landscape" -> FilterManager.FilterPreset.VELVIA_LANDSCAPE
            "nordic_slate", "filter_nordic_slate" -> FilterManager.FilterPreset.NORDIC_SLATE
            "arctic_mist", "filter_arctic_mist" -> FilterManager.FilterPreset.ARCTIC_MIST
            "classic_mono", "filter_classic_mono" -> FilterManager.FilterPreset.CLASSIC_MONO
            "high_contrast_noir", "filter_high_contrast_noir" -> FilterManager.FilterPreset.HIGH_CONTRAST_NOIR
            "ilford_grain", "filter_ilford_grain" -> FilterManager.FilterPreset.ILFORD_GRAIN
            "silver_gelatin", "filter_silver_gelatin" -> FilterManager.FilterPreset.SILVER_GELATIN
            "antique_sepia", "filter_antique_sepia" -> FilterManager.FilterPreset.ANTIQUE_SEPIA
            "cinematic" -> FilterManager.FilterPreset.CINEMATIC
            "vintage" -> FilterManager.FilterPreset.VINTAGE
            "bw", "black_and_white", "mono" -> FilterManager.FilterPreset.BLACK_AND_WHITE
            "hdr" -> FilterManager.FilterPreset.HDR
            "cyberpunk", "cyber" -> FilterManager.FilterPreset.CYBERPUNK
            "comic_book", "comic", "cartoon" -> FilterManager.FilterPreset.COMIC_BOOK
            "portra_400", "filter_portra_400" -> FilterManager.FilterPreset.PORTRA_400
            "fuji_400h", "filter_fuji_400h" -> FilterManager.FilterPreset.FUJI_400H
            "teal_orange", "filter_teal_orange" -> FilterManager.FilterPreset.TEAL_ORANGE
            "noir_contrast", "filter_noir_contrast" -> FilterManager.FilterPreset.NOIR_CONTRAST
            "sunset_warmth", "filter_sunset_warmth" -> FilterManager.FilterPreset.SUNSET_WARMTH
            "matrix_code", "filter_matrix_code" -> FilterManager.FilterPreset.MATRIX_CODE
            "pastel_dream", "filter_pastel_dream" -> FilterManager.FilterPreset.PASTEL_DREAM
            "nordic_blue", "filter_nordic_mood" -> FilterManager.FilterPreset.NORDIC_BLUE
            "super8_vintage", "filter_super8_retro" -> FilterManager.FilterPreset.SUPER8_VINTAGE
            else -> FilterManager.FilterPreset.NORMAL
        }
        filterManager.setPreset(filterPreset)
        syncAllStateToOpenGLProcessor()
        viewBinding.proOverlayView.activeFilterPreset = preset
        syncComicFrameOverlayState()
        if (!isStylizedLiveEffect(currentShootingEffect) && !isComicFilterPreset(preset)) {
            viewBinding.proOverlayView.updateProcessedEffectBitmap(null)
        }
        applyLivePreviewColorMatrix()
        updateFilterButtons(preset)
        updateEffectIntensityVisibility()
        updateCreativeStatusUI()
        syncStylizeLoopState()
        if (showNotice) {
            showHudNotice("Filter: ${getFilterDisplayLabel(preset)}")
        }
    }

    private fun updateFilterButtons(preset: String) {
        val filterButtons = listOf(
            viewBinding.effectsStudioDrawer.btnFilterNormal to "normal",
            viewBinding.effectsStudioDrawer.btnFilterLutRec709 to "lut_rec709",
            viewBinding.effectsStudioDrawer.btnFilterLutFlatLog to "lut_flat_log",
            viewBinding.effectsStudioDrawer.btnFilterLutTealOrange to "lut_teal_orange",
            viewBinding.effectsStudioDrawer.btnFilterLutBleachBypass to "lut_bleach_bypass",
            viewBinding.effectsStudioDrawer.btnFilterLutAnamorphicCyan to "lut_anamorphic_cyan",
            viewBinding.effectsStudioDrawer.btnFilterLutMatteCinema to "lut_matte_cinema",
            viewBinding.effectsStudioDrawer.btnFilterLutMidnightNeon to "lut_midnight_neon",
            viewBinding.effectsStudioDrawer.btnFilterLutWarmDoc to "lut_warm_documentary",
            viewBinding.effectsStudioDrawer.btnFilterLutNoirCinema to "lut_noir_cinema",
            viewBinding.effectsStudioDrawer.btnFilterGoldenNostalgia to "golden_nostalgia",
            viewBinding.effectsStudioDrawer.btnFilterVelvia to "velvia_landscape",
            viewBinding.effectsStudioDrawer.btnFilterNordicSlate to "nordic_slate",
            viewBinding.effectsStudioDrawer.btnFilterArcticMist to "arctic_mist",
            viewBinding.effectsStudioDrawer.btnFilterClassicMono to "classic_mono",
            viewBinding.effectsStudioDrawer.btnFilterNoir to "high_contrast_noir",
            viewBinding.effectsStudioDrawer.btnFilterIlfordGrain to "ilford_grain",
            viewBinding.effectsStudioDrawer.btnFilterSilverGelatin to "silver_gelatin",
            viewBinding.effectsStudioDrawer.btnFilterAntiqueSepia to "antique_sepia",
            viewBinding.effectsStudioDrawer.btnFilterCinematic to "cinematic",
            viewBinding.effectsStudioDrawer.btnFilterVintage to "vintage",
            viewBinding.effectsStudioDrawer.btnFilterBW to "bw",
            viewBinding.effectsStudioDrawer.btnFilterCyber to "cyberpunk",
            viewBinding.effectsStudioDrawer.btnFilterHDR to "hdr",
            viewBinding.effectsStudioDrawer.btnFilterComicBook to "comic_book",
            viewBinding.effectsStudioDrawer.btnFilterPortra400 to "portra_400",
            viewBinding.effectsStudioDrawer.btnFilterFuji400h to "fuji_400h",
            viewBinding.effectsStudioDrawer.btnFilterTealOrange to "teal_orange",
            viewBinding.effectsStudioDrawer.btnFilterNoirContrast to "noir_contrast",
            viewBinding.effectsStudioDrawer.btnFilterSunsetWarmth to "sunset_warmth",
            viewBinding.effectsStudioDrawer.btnFilterMatrixCode to "matrix_code",
            viewBinding.effectsStudioDrawer.btnFilterPastelDream to "pastel_dream",
            viewBinding.effectsStudioDrawer.btnFilterNordicBlue to "nordic_blue",
            viewBinding.effectsStudioDrawer.btnFilterSuper8Vintage to "super8_vintage"
        ) + dynamicFilterChips
        for ((btn, name) in filterButtons) {
            val isActive = name == preset
            if (isActive) {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_active)
                btn.setTextColor(Color.parseColor("#0F172A"))
                scrollChipIntoView(viewBinding.effectsStudioDrawer.scrollFiltersStrip, btn)
            } else {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_inactive)
                btn.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
    }

    private fun getArLensDisplayName(lens: String): String {
        return when (lens.lowercase(Locale.ROOT)) {
            "none" -> "OFF"
            "mesh_contour", "mesh" -> "3D MESH"
            "cyberpunk_visor", "visor" -> "CYBER VISOR"
            "golden_crown", "crown" -> "GOLDEN CROWN"
            "scifi_hud", "hud" -> "SCI-FI HUD"
            "comic_frame", "comic", "comic_panel" -> "COMIC FRAME"
            else -> {
                val customName = injectedCustomLenses.find { it.id == lens }?.name
                (customName ?: lens.replace("_", " "))
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .uppercase(Locale.ROOT)
            }
        }
    }

    private fun setArLens(lens: String, showNotice: Boolean = true, syncFloatingMenu: Boolean = true) {
        if (lens != "none" && ::ecoPowerManager.isInitialized && ecoPowerManager.isArDisabled) {
            showHudNotice(
                "ECO MODE (${ecoPowerManager.currentPolicy.batteryPercent}%): AR disabled to save battery",
                isWarning = true
            )
            return
        }
        if (lens != "none" && ::thermalGuardManager.isInitialized && thermalGuardManager.isArDisabled) {
            showHudNotice(
                "THERMAL ${thermalGuardManager.currentPolicy.statusLabel}: AR paused to prevent overheating",
                isWarning = true
            )
            return
        }
        val prevLens = currentArLensName
        currentArLensName = lens
        if (lens != "none") {
            lastActiveArLensName = lens
        }
        viewBinding.arFaceOverlayView.activeCustomLensStyle = null
        val mappedMode = when (lens.lowercase(Locale.ROOT)) {
            "mesh_contour", "mesh" -> ARFaceOverlayView.ArLensMode.MESH_CONTOUR
            "cyberpunk_visor", "visor" -> ARFaceOverlayView.ArLensMode.CYBERPUNK_VISOR
            "golden_crown", "crown" -> ARFaceOverlayView.ArLensMode.GOLDEN_CROWN
            "scifi_hud", "hud" -> ARFaceOverlayView.ArLensMode.SCIFI_HUD
            "comic_frame", "comic", "comic_panel" -> ARFaceOverlayView.ArLensMode.COMIC_FRAME
            else -> {
                val customLens = injectedCustomLenses.find { it.id == lens }
                if (customLens != null) {
                    if (customLens.style.equals("glb_3d", ignoreCase = true) &&
                        (customLens.glbFilePath.isNotEmpty() || customLens.glbAssetPath.isNotEmpty())
                    ) {
                        val loaded = if (customLens.glbFilePath.isNotEmpty()) {
                            viewBinding.arFaceOverlayView.loadGlbModelFromFile(
                                filePath = customLens.glbFilePath,
                                anchor = customLens.anchor,
                                modelScale = customLens.modelScale,
                                offsetY = customLens.modelOffsetY,
                                rotYDeg = customLens.modelRotationY,
                                rotXDeg = customLens.modelRotationX,
                                autoSpin = customLens.autoSpin
                            )
                        } else {
                            viewBinding.arFaceOverlayView.loadGlbModelFromAsset(
                                assetPath = customLens.glbAssetPath,
                                anchor = customLens.anchor,
                                modelScale = customLens.modelScale,
                                offsetY = customLens.modelOffsetY,
                                rotYDeg = customLens.modelRotationY,
                                rotXDeg = customLens.modelRotationX,
                                autoSpin = customLens.autoSpin
                            )
                        }
                        if (loaded) {
                            ARFaceOverlayView.ArLensMode.GLB_3D
                        } else {
                            showHudNotice("Failed to load 3D GLB model: ${customLens.name}", isWarning = true)
                            ARFaceOverlayView.ArLensMode.NONE
                        }
                    } else {
                        viewBinding.arFaceOverlayView.activeCustomLensStyle = ARFaceOverlayView.CustomLensStyleConfig(
                            style = customLens.style,
                            anchor = customLens.anchor,
                            primaryColorHex = customLens.primaryColor,
                            accentColorHex = customLens.accentColor,
                            telemetryText = customLens.telemetryText
                        )
                        ARFaceOverlayView.ArLensMode.CUSTOM_STYLE
                    }
                } else ARFaceOverlayView.ArLensMode.NONE
            }
        }
        syncComicFrameOverlayState()
        viewBinding.arFaceOverlayView.currentLens = mappedMode
        if (mappedMode == ARFaceOverlayView.ArLensMode.NONE) {
            viewBinding.arFaceOverlayView.clear()
        }
        syncOverlayToVideoProcessor()
        updateLensButtons(lens)
        updateArDockStatusLabel()
        if (syncFloatingMenu && cameraMode == CameraMode.AR_LENS) {
            if (lens != "none") {
                activeArFamily = "face"
            } else if (activeArFamily == "face") {
                activeArFamily = "off"
                activeFloatingSettingId = null
            }
            refreshFloatingModeMenu()
        }
        if (isAutoAdaptEnabled && lens != "none" && lens != prevLens) {
            val recommendedFront = lens in setOf("mesh_contour", "cyberpunk_visor", "golden_crown", "comic_frame") ||
                mappedMode == ARFaceOverlayView.ArLensMode.GLB_3D ||
                mappedMode == ARFaceOverlayView.ArLensMode.CUSTOM_STYLE
            val isCurrentlyFront = (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA)
            if (recommendedFront != isCurrentlyFront && recording == null) {
                switchCamera()
            }
        }
        if (showNotice) {
            val displayName = getArLensDisplayName(lens)
            if (mappedMode == ARFaceOverlayView.ArLensMode.GLB_3D) {
                showHudNotice("3D GLB AR Lens: $displayName")
            } else {
                showHudNotice("AR Lens: $displayName")
            }
        }
    }

    private fun updateArDockStatusLabel() {
        val total = (arLensOrder.size - 1).coerceAtLeast(1)
        val idx = arLensOrder.indexOf(currentArLensName)
        val label = getArLensDisplayName(currentArLensName)
        viewBinding.arLensModeDock.tvArDockStatus.text = when {
            currentArLensName == "comic_frame" && isCartoonShaderActive() -> "AR LENS: $label · SHADER ON ($idx/$total)"
            idx > 0 -> "AR LENS: $label ($idx/$total)"
            else -> "AR LENS: OFF (0/$total)"
        }
    }

    private fun updateLensButtons(lens: String) {
        val lensButtons = listOf(
            viewBinding.arLensModeDock.btnArNone to "none",
            viewBinding.arLensModeDock.btnArMesh to "mesh_contour",
            viewBinding.arLensModeDock.btnArVisor to "cyberpunk_visor",
            viewBinding.arLensModeDock.btnArCrown to "golden_crown",
            viewBinding.arLensModeDock.btnArHud to "scifi_hud",
            viewBinding.arLensModeDock.btnArComicFrame to "comic_frame"
        ) + dynamicLensChips
        for ((btn, name) in lensButtons) {
            val isActive = name == lens
            if (isActive) {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_active)
                btn.setTextColor(Color.parseColor("#0F172A"))
                scrollChipIntoView(viewBinding.arLensModeDock.scrollArModeLensesStrip, btn)
            } else {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_inactive)
                btn.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
    }

    private fun toggleComicOnomatopoeia() {
        val next = !viewBinding.arFaceOverlayView.enableComicOnomatopoeia
        viewBinding.arFaceOverlayView.enableComicOnomatopoeia = next
        viewBinding.arLensModeDock.btnArComicOnomatopoeia.setBackgroundResource(
            if (next) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive
        )
        viewBinding.arLensModeDock.btnArComicOnomatopoeia.setTextColor(
            Color.parseColor(if (next) "#0F172A" else "#FACC15")
        )
        viewBinding.arLensModeDock.btnArComicOnomatopoeia.text = if (next) "POW! ON" else "POW! COMIC SFX"
        if (next && !isAudioMuted) {
            playCameraCue(ToneGenerator.TONE_PROP_BEEP2, 110)
            comicSfxHelper?.playComicBurst(comicSfxPow, comicSfxBam, comicSfxZap, 0.72f, isAudioMuted)
        }
        showHudNotice(if (next) "Comic Onomatopoeia: POW! BAM! ZAP! ON" else "Comic Onomatopoeia: OFF")
    }

    private fun getModeOrderedArtEffects(): List<String> {
        val isVideoMode = (cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.SLOW_MO)
        return listOf("none") + if (isVideoMode) (videoArtOrder + pictureArtOrder) else (pictureArtOrder + videoArtOrder)
    }

    private fun getModeFilteredShootingEffects(): List<String> {
        val isVideoMode = (cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.SLOW_MO)
        return if (isVideoMode) {
            listOf(
                "none",
                "video_bokeh", "slow_motion_120_240", "time_lapse", "hyperlapse",
                "anamorphic_flare", "dolly_zoom", "action_stabilization",
                "vintage_super8", "film_16mm", "vhs_tape", "crt_monitor", "stop_motion",
                "motion_blur", "tilt_shift", "film_grain", "soft_halation", "vignette"
            )
        } else {
            listOf(
                "none",
                "portrait_bokeh", "long_exposure", "light_painting", "star_trails",
                "night_sight", "macro_focus", "double_exposure", "panorama",
                "motion_blur", "tilt_shift", "film_grain", "soft_halation", "vignette"
            )
        }
    }

    private fun updateShootingEffectButtons() {
        val isVideoMode = (cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.SLOW_MO)
        val effectButtons = listOf(
            viewBinding.effectsStudioDrawer.btnEffectOff to "none",
            viewBinding.effectsStudioDrawer.btnEffectBokeh to "portrait_bokeh",
            viewBinding.effectsStudioDrawer.btnEffectLongExp to "long_exposure",
            viewBinding.effectsStudioDrawer.btnEffectLightPaint to "light_painting",
            viewBinding.effectsStudioDrawer.btnEffectStarTrails to "star_trails",
            viewBinding.effectsStudioDrawer.btnEffectNightSight to "night_sight",
            viewBinding.effectsStudioDrawer.btnEffectMacro to "macro_focus",
            viewBinding.effectsStudioDrawer.btnEffectDoubleExp to "double_exposure",
            viewBinding.effectsStudioDrawer.btnEffectPanorama to "panorama",
            viewBinding.effectsStudioDrawer.btnEffectVideoBokeh to "video_bokeh",
            viewBinding.effectsStudioDrawer.btnEffectSlowMotion to "slow_motion_120_240",
            viewBinding.effectsStudioDrawer.btnEffectTimeLapse to "time_lapse",
            viewBinding.effectsStudioDrawer.btnEffectHyperlapse to "hyperlapse",
            viewBinding.effectsStudioDrawer.btnEffectAnamorphicFlare to "anamorphic_flare",
            viewBinding.effectsStudioDrawer.btnEffectDollyZoom to "dolly_zoom",
            viewBinding.effectsStudioDrawer.btnEffectHorizonLock to "action_stabilization",
            viewBinding.effectsStudioDrawer.btnEffectSuper8 to "vintage_super8",
            viewBinding.effectsStudioDrawer.btnEffectFilm16mm to "film_16mm",
            viewBinding.effectsStudioDrawer.btnEffectVhsTape to "vhs_tape",
            viewBinding.effectsStudioDrawer.btnEffectCrtMonitor to "crt_monitor",
            viewBinding.effectsStudioDrawer.btnEffectStopMotion to "stop_motion",
            viewBinding.effectsStudioDrawer.btnEffectMotionBlur to "motion_blur",
            viewBinding.effectsStudioDrawer.btnEffectTiltShift to "tilt_shift",
            viewBinding.effectsStudioDrawer.btnEffectFilmGrain to "film_grain",
            viewBinding.effectsStudioDrawer.btnEffectHalation to "soft_halation",
            viewBinding.effectsStudioDrawer.btnEffectVignette to "vignette"
        )
        val activeShootingId = if (currentShootingEffect == "none") {
            "none"
        } else if (artEffectIds.contains(currentShootingEffect) || toonEffectIds.contains(currentShootingEffect)) {
            ""
        } else {
            currentShootingEffect
        }
        for ((btn, id) in effectButtons) {
            val isVisibleForMode = when {
                id == "none" -> true
                isVideoMode -> !photoOnlyEffectIds.contains(id)
                else -> !videoOnlyEffectIds.contains(id)
            }
            btn.visibility = if (isVisibleForMode) View.VISIBLE else View.GONE
            val isActive = isVisibleForMode && id == activeShootingId
            if (isActive) {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_active)
                btn.setTextColor(Color.parseColor("#0F172A"))
                scrollChipIntoView(viewBinding.effectsStudioDrawer.scrollEffectsStrip, btn)
            } else {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_inactive)
                btn.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
        updateArtEffectButtons()
        updateToonEffectButtons()
    }

    private fun updateArtEffectButtons() {
        val artButtons = listOf(
            viewBinding.effectsStudioDrawer.btnArtOff to "none",
            viewBinding.effectsStudioDrawer.btnEffectOilCanvas to "oil_canvas",
            viewBinding.effectsStudioDrawer.btnEffectCharcoalSketch to "charcoal_sketch",
            viewBinding.effectsStudioDrawer.btnEffectWatercolorWash to "watercolor_wash",
            viewBinding.effectsStudioDrawer.btnEffectInkLine to "ink_and_line",
            viewBinding.effectsStudioDrawer.btnEffectPastelChalk to "pastel_chalk",
            viewBinding.effectsStudioDrawer.btnEffectHalftonePrint to "halftone_print",
            viewBinding.effectsStudioDrawer.btnEffectCyanotype to "cyanotype",
            viewBinding.effectsStudioDrawer.btnEffectWoodcut to "woodcut_engraving",
            viewBinding.effectsStudioDrawer.btnEffectLithograph to "lithograph",
            viewBinding.effectsStudioDrawer.btnEffectPrism to "prism_refraction",
            viewBinding.effectsStudioDrawer.btnEffectKaleidoscope to "kaleidoscope",
            viewBinding.effectsStudioDrawer.btnEffectSolarize to "solarize",
            viewBinding.effectsStudioDrawer.btnEffectNeonEdge to "neon_edge",
            viewBinding.effectsStudioDrawer.btnEffectNoirNovel to "noir_graphic_novel",
            viewBinding.effectsStudioDrawer.btnEffectThermalVision to "thermal_vision"
        )
        val activeArtId = when {
            currentShootingEffect == "none" -> "none"
            artEffectIds.contains(currentShootingEffect) -> currentShootingEffect
            else -> ""
        }
        for ((btn, id) in artButtons) {
            val isActive = id == activeArtId
            if (isActive) {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_active)
                btn.setTextColor(Color.parseColor("#0F172A"))
                scrollChipIntoView(viewBinding.effectsStudioDrawer.scrollArtStrip, btn)
            } else {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_inactive)
                btn.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
    }

    private fun updateToonEffectButtons() {
        val toonButtons = listOf(
            viewBinding.effectsStudioDrawer.btnToonOff to "none",
            viewBinding.effectsStudioDrawer.btnEffectCelShaded to "cel_shaded_studio",
            viewBinding.effectsStudioDrawer.btnEffectGraphicNoir to "graphic_novel_noir",
            viewBinding.effectsStudioDrawer.btnEffectMangaInk to "manga_ink",
            viewBinding.effectsStudioDrawer.btnEffectPopArt to "pop_art_print",
            viewBinding.effectsStudioDrawer.btnEffectLineArt to "line_art_minimalist",
            viewBinding.effectsStudioDrawer.btnEffectLigneClaire to "ligne_claire",
            viewBinding.effectsStudioDrawer.btnEffectClayVinyl to "clay_vinyl_3d"
        )
        val activeToonId = when {
            currentShootingEffect == "none" -> "none"
            toonEffectIds.contains(currentShootingEffect) -> currentShootingEffect
            else -> ""
        }
        for ((btn, id) in toonButtons) {
            val isActive = id == activeToonId
            if (isActive) {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_active)
                btn.setTextColor(Color.parseColor("#0F172A"))
                scrollChipIntoView(viewBinding.effectsStudioDrawer.scrollToonStrip, btn)
            } else {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_inactive)
                btn.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
    }

    private fun setShootingEffect(effectId: String, showNotice: Boolean = true) {
        currentShootingEffect = effectId
        val isArtEffect = artEffectIds.contains(effectId)
        val isToonEffect = toonEffectIds.contains(effectId)
        currentTextureEffectIndex = getModeFilteredShootingEffects().indexOf(effectId).coerceAtLeast(0)
        if (showNotice) {
            if (isToonEffect) {
                currentCreativeTarget = CreativeTarget.TOON
            } else if (isArtEffect) {
                currentCreativeTarget = CreativeTarget.ART
            } else if (effectId != "none") {
                currentCreativeTarget = CreativeTarget.EFFECTS
            }
            isLastActionCreative = true
        }
        viewBinding.proOverlayView.activeShootingEffect = effectId
        syncComicFrameOverlayState()
        if (effectId != "motion_blur") {
            motionBlurProcessor.reset()
            latestMotionMetrics = MotionBlurProcessor.MotionMetrics.IDLE
            openGLProcessor?.setMotionBlurVector(1f, 0f, 0.18f)
            viewBinding.proOverlayView.updateMotionBlurTelemetry(0f, 1f, 0f, "1/60s")
        }
        if (!isStylizedLiveEffect(effectId) && !isComicFilterPreset(currentFilterName)) {
            viewBinding.proOverlayView.updateProcessedEffectBitmap(null)
        }
        viewBinding.proOverlayView.enableVignette = (effectId == "vignette")

        isUpdatingEffectSwitches = true
        try {
            val isGrain = (effectId == "film_grain")
            viewBinding.effectsStudioDrawer.switchFilmGrain.isChecked = isGrain
            viewBinding.proOverlayView.enableFilmGrain = isGrain

            val isFlare = (effectId == "anamorphic_flare")
            viewBinding.effectsStudioDrawer.switchCineFlare.isChecked = isFlare
            viewBinding.proOverlayView.enableCineFlare = isFlare
        } finally {
            isUpdatingEffectSwitches = false
        }
        syncAllStateToOpenGLProcessor()

        dollyZoomAnimator?.cancel()
        dollyZoomAnimator = null
        if (effectId != "action_stabilization" && effectId != "hyperlapse") {
            viewBinding.viewFinder.rotation = 0f
        }

        if (effectId == "dolly_zoom") {
            dollyZoomAnimator = android.animation.ValueAnimator.ofFloat(1.0f, 1.36f).apply {
                duration = 1600L
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    val s = anim.animatedValue as Float
                    viewBinding.viewFinder.scaleX = s
                    viewBinding.viewFinder.scaleY = s
                }
                start()
            }
        } else {
            val eisZoom = if (::gyroStabilizer.isInitialized && gyroStabilizer.isEnabled && cameraEffect == null) {
                gyroStabilizer.overscanZoom
            } else {
                1.0f
            }
            val cropScale = when (effectId) {
                "macro_focus" -> 1.26f
                "action_stabilization" -> 1.15f
                "hyperlapse" -> 1.10f
                else -> eisZoom
            }
            viewBinding.viewFinder.animate().scaleX(cropScale).scaleY(cropScale).setDuration(180).start()
        }

        applyLivePreviewColorMatrix()
        updateShootingEffectButtons()
        updateEffectIntensityVisibility()
        updateCreativeStatusUI()
        syncStylizeLoopState()
        if (showNotice) {
            showHudNotice("Effect: ${getShootingEffectLabel(effectId)}")
        }
    }

    /**
     * Fast separable 5-tap (radius 2) edge-preserving bilateral filter.
     * Flattens skin/surface noise cleanly into smooth cartoon cel fills or silky painterly washes
     * while keeping eyes, hair, and major contours razor-sharp.
     */
    private fun smoothIntArray(src: IntArray, w: Int, h: Int, passes: Int, colorThreshold: Float = 36f): IntArray {
        var cur = src
        val n = w * h
        var tmp = IntArray(n)
        var dst = IntArray(n)
        val invThresh = 1f / Math.max(8f, colorThreshold)
        val spatialW = floatArrayOf(0.55f, 0.85f, 1.0f, 0.85f, 0.55f)

        for (pass in 0 until passes) {
            // Horizontal pass
            for (y in 0 until h) {
                val yOff = y * w
                for (x in 0 until w) {
                    val idx = yOff + x
                    val c = cur[idx]
                    val cr = (c shr 16) and 0xFF
                    val cg = (c shr 8) and 0xFF
                    val cb = c and 0xFF
                    var sumR = cr.toFloat()
                    var sumG = cg.toFloat()
                    var sumB = cb.toFloat()
                    var wSum = 1.0f

                    for (k in -2..2) {
                        if (k == 0) continue
                        val nx = (x + k).coerceIn(0, w - 1)
                        val nc = cur[yOff + nx]
                        val nr = (nc shr 16) and 0xFF
                        val ng = (nc shr 8) and 0xFF
                        val nb = nc and 0xFF
                        val diff = (Math.abs(nr - cr) + Math.abs(ng - cg) + Math.abs(nb - cb)) * 0.3333f
                        val rNorm = diff * invThresh
                        if (rNorm < 2.2f) {
                            val wt = spatialW[k + 2] / (1f + rNorm * rNorm * rNorm)
                            sumR += nr * wt
                            sumG += ng * wt
                            sumB += nb * wt
                            wSum += wt
                        }
                    }
                    val invW = 1f / wSum
                    val outR = (sumR * invW + 0.5f).toInt().coerceIn(0, 255)
                    val outG = (sumG * invW + 0.5f).toInt().coerceIn(0, 255)
                    val outB = (sumB * invW + 0.5f).toInt().coerceIn(0, 255)
                    tmp[idx] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
                }
            }
            // Vertical pass
            for (y in 0 until h) {
                val yOff = y * w
                for (x in 0 until w) {
                    val idx = yOff + x
                    val c = tmp[idx]
                    val cr = (c shr 16) and 0xFF
                    val cg = (c shr 8) and 0xFF
                    val cb = c and 0xFF
                    var sumR = cr.toFloat()
                    var sumG = cg.toFloat()
                    var sumB = cb.toFloat()
                    var wSum = 1.0f

                    for (k in -2..2) {
                        if (k == 0) continue
                        val ny = (y + k).coerceIn(0, h - 1)
                        val nc = tmp[ny * w + x]
                        val nr = (nc shr 16) and 0xFF
                        val ng = (nc shr 8) and 0xFF
                        val nb = nc and 0xFF
                        val diff = (Math.abs(nr - cr) + Math.abs(ng - cg) + Math.abs(nb - cb)) * 0.3333f
                        val rNorm = diff * invThresh
                        if (rNorm < 2.2f) {
                            val wt = spatialW[k + 2] / (1f + rNorm * rNorm * rNorm)
                            sumR += nr * wt
                            sumG += ng * wt
                            sumB += nb * wt
                            wSum += wt
                        }
                    }
                    val invW = 1f / wSum
                    val outR = (sumR * invW + 0.5f).toInt().coerceIn(0, 255)
                    val outG = (sumG * invW + 0.5f).toInt().coerceIn(0, 255)
                    val outB = (sumB * invW + 0.5f).toInt().coerceIn(0, 255)
                    dst[idx] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
                }
            }
            val swap = cur
            cur = dst
            dst = swap
        }
        return cur
    }

    /**
     * Fast O(w*h) 3-pass separable box blur approximating a creamy optical Gaussian bokeh defocus.
     */
    private fun boxBlurIntArray(src: IntArray, w: Int, h: Int, radius: Int, passes: Int = 2): IntArray {
        val r = radius.coerceIn(1, 24)
        val n = w * h
        var cur = src.copyOf()
        val tmp = IntArray(n)
        var dst = IntArray(n)
        val div = (r * 2 + 1).toFloat()

        for (pass in 0 until passes) {
            // Horizontal sliding window
            for (y in 0 until h) {
                val row = y * w
                var sumR = 0; var sumG = 0; var sumB = 0
                for (k in -r..r) {
                    val c = cur[row + k.coerceIn(0, w - 1)]
                    sumR += (c shr 16) and 0xFF
                    sumG += (c shr 8) and 0xFF
                    sumB += c and 0xFF
                }
                for (x in 0 until w) {
                    tmp[row + x] = (0xFF shl 24) or
                        ((sumR / div).toInt().coerceIn(0, 255) shl 16) or
                        ((sumG / div).toInt().coerceIn(0, 255) shl 8) or
                        (sumB / div).toInt().coerceIn(0, 255)
                    val outPx = cur[row + (x - r).coerceIn(0, w - 1)]
                    val inPx = cur[row + (x + r + 1).coerceIn(0, w - 1)]
                    sumR += ((inPx shr 16) and 0xFF) - ((outPx shr 16) and 0xFF)
                    sumG += ((inPx shr 8) and 0xFF) - ((outPx shr 8) and 0xFF)
                    sumB += (inPx and 0xFF) - (outPx and 0xFF)
                }
            }
            // Vertical sliding window
            for (x in 0 until w) {
                var sumR = 0; var sumG = 0; var sumB = 0
                for (k in -r..r) {
                    val c = tmp[k.coerceIn(0, h - 1) * w + x]
                    sumR += (c shr 16) and 0xFF
                    sumG += (c shr 8) and 0xFF
                    sumB += c and 0xFF
                }
                for (y in 0 until h) {
                    dst[y * w + x] = (0xFF shl 24) or
                        ((sumR / div).toInt().coerceIn(0, 255) shl 16) or
                        ((sumG / div).toInt().coerceIn(0, 255) shl 8) or
                        (sumB / div).toInt().coerceIn(0, 255)
                    val outPx = tmp[(y - r).coerceIn(0, h - 1) * w + x]
                    val inPx = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                    sumR += ((inPx shr 16) and 0xFF) - ((outPx shr 16) and 0xFF)
                    sumG += ((inPx shr 8) and 0xFF) - ((outPx shr 8) and 0xFF)
                    sumB += (inPx and 0xFF) - (outPx and 0xFF)
                }
            }
            val swap = cur
            cur = dst
            dst = swap
        }
        return cur
    }

    /**
     * Fast O(w*h) Soft-Weighted Kuwahara Painterly Filter using 2D integral prefix sums.
     * Polynomial inverse-variance weighting blends flat regions seamlessly (ZERO square grid artifacts)
     * while preserving crisp, expressive painterly brushstroke edges.
     */
    private fun applyKuwaharaIntArray(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val pw = w + 1
        val ph = h + 1
        val total = pw * ph
        val pR = LongArray(total)
        val pG = LongArray(total)
        val pB = LongArray(total)
        val pL = DoubleArray(total)
        val pL2 = DoubleArray(total)

        for (y in 0 until h) {
            var rowR = 0L
            var rowG = 0L
            var rowB = 0L
            var rowL = 0.0
            var rowL2 = 0.0
            val srcRow = y * w
            val curRow = (y + 1) * pw
            val prevRow = y * pw
            for (x in 0 until w) {
                val c = src[srcRow + x]
                val r = ((c shr 16) and 0xFF).toLong()
                val g = ((c shr 8) and 0xFF).toLong()
                val b = (c and 0xFF).toLong()
                val lum = 0.299 * r + 0.587 * g + 0.114 * b
                rowR += r
                rowG += g
                rowB += b
                rowL += lum
                rowL2 += lum * lum
                val idx = curRow + x + 1
                val up = prevRow + x + 1
                pR[idx] = pR[up] + rowR
                pG[idx] = pG[up] + rowG
                pB[idx] = pB[up] + rowB
                pL[idx] = pL[up] + rowL
                pL2[idx] = pL2[up] + rowL2
            }
        }

        val out = IntArray(w * h)
        for (y in 0 until h) {
            val yTop0 = (y - radius).coerceIn(0, h - 1)
            val yTop1 = y.coerceIn(0, h - 1)
            val yBot0 = y.coerceIn(0, h - 1)
            val yBot1 = (y + radius).coerceIn(0, h - 1)
            val rowOut = y * w
            for (x in 0 until w) {
                val xL0 = (x - radius).coerceIn(0, w - 1)
                val xL1 = x.coerceIn(0, w - 1)
                val xR0 = x.coerceIn(0, w - 1)
                val xR1 = (x + radius).coerceIn(0, w - 1)

                var wSum = 0.0
                var accR = 0.0
                var accG = 0.0
                var accB = 0.0

                for (q in 0 until 4) {
                    val x0 = if ((q and 1) == 0) xL0 else xR0
                    val x1 = if ((q and 1) == 0) xL1 else xR1
                    val y0 = if (q < 2) yTop0 else yBot0
                    val y1 = if (q < 2) yTop1 else yBot1
                    val area = (x1 - x0 + 1) * (y1 - y0 + 1)
                    if (area <= 0) continue

                    val br = (y1 + 1) * pw + (x1 + 1)
                    val bl = (y1 + 1) * pw + x0
                    val tr = y0 * pw + (x1 + 1)
                    val tl = y0 * pw + x0

                    val sL = pL[br] - pL[bl] - pL[tr] + pL[tl]
                    val sL2 = pL2[br] - pL2[bl] - pL2[tr] + pL2[tl]
                    val meanL = sL / area
                    val variance = Math.max(0.0, (sL2 / area) - (meanL * meanL))
                    // Smooth polynomial weight eliminates square box grid seams in low-variance regions
                    val vScaled = variance * 0.012
                    val wq = 1.0 / (1.0 + vScaled * vScaled)
                    val mR = (pR[br] - pR[bl] - pR[tr] + pR[tl]) / area.toDouble()
                    val mG = (pG[br] - pG[bl] - pG[tr] + pG[tl]) / area.toDouble()
                    val mB = (pB[br] - pB[bl] - pB[tr] + pB[tl]) / area.toDouble()
                    accR += mR * wq
                    accG += mG * wq
                    accB += mB * wq
                    wSum += wq
                }
                val invW = if (wSum > 0.0001) 1.0 / wSum else 1.0
                val outR = (accR * invW + 0.5).toInt().coerceIn(0, 255)
                val outG = (accG * invW + 0.5).toInt().coerceIn(0, 255)
                val outB = (accB * invW + 0.5).toInt().coerceIn(0, 255)
                out[rowOut + x] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }
        return out
    }

    /**
     * Separable 1D Gaussian luminance blur for crisp XDoG cartoon/ink contours.
     */
    private fun blurLumFloat(lum: FloatArray, w: Int, h: Int, wide: Boolean): FloatArray {
        val n = w * h
        val tmp = FloatArray(n)
        val out = FloatArray(n)
        val k = if (!wide) floatArrayOf(0.25f, 0.5f, 0.25f) else floatArrayOf(0.06f, 0.14f, 0.20f, 0.20f, 0.20f, 0.14f, 0.06f)
        val half = k.size / 2
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0f
                for (i in -half..half) {
                    acc += lum[row + (x + i).coerceIn(0, w - 1)] * k[i + half]
                }
                tmp[row + x] = acc
            }
        }
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0f
                for (i in -half..half) {
                    acc += tmp[(y + i).coerceIn(0, h - 1) * w + x] * k[i + half]
                }
                out[row + x] = acc
            }
        }
        return out
    }

    /**
     * Multi-Pass Dual-Scale 3x3 Sobel Edge Detector (1px micro + 2px macro ink contours across both Luminance & RGB Color Vectors)
     * combined with XDoG valley crease amplification for genuine comic-book inkwork.
     */
    private fun computeXdogEdges(arr: IntArray, lum: FloatArray, w: Int, h: Int): FloatArray {
        val n = w * h
        val blurTight = blurLumFloat(lum, w, h, wide = false)
        val blurWide = blurLumFloat(blurTight, w, h, wide = true)
        val rawEdges = FloatArray(n)

        for (y in 0 until h) {
            val y0 = (y - 1).coerceIn(0, h - 1) * w
            val yC = y * w
            val y1 = (y + 1).coerceIn(0, h - 1) * w
            val y2m = (y - 2).coerceIn(0, h - 1) * w
            val y2p = (y + 2).coerceIn(0, h - 1) * w
            for (x in 0 until w) {
                val x0 = (x - 1).coerceIn(0, w - 1)
                val x1 = (x + 1).coerceIn(0, w - 1)
                val x2m = (x - 2).coerceIn(0, w - 1)
                val x2p = (x + 2).coerceIn(0, w - 1)
                val p = yC + x

                // Pass A: 1px RGB Color-Vector Sobel for chromatic boundaries & fine inked details
                val tl = arr[y0 + x0]; val tc = arr[y0 + x]; val tr = arr[y0 + x1]
                val ml = arr[yC + x0];                     val mr = arr[yC + x1]
                val bl = arr[y1 + x0]; val bc = arr[y1 + x]; val br = arr[y1 + x1]

                val gxR = -((tl shr 16) and 0xFF) + ((tr shr 16) and 0xFF) - 2 * ((ml shr 16) and 0xFF) + 2 * ((mr shr 16) and 0xFF) - ((bl shr 16) and 0xFF) + ((br shr 16) and 0xFF)
                val gyR = -((tl shr 16) and 0xFF) - 2 * ((tc shr 16) and 0xFF) - ((tr shr 16) and 0xFF) + ((bl shr 16) and 0xFF) + 2 * ((bc shr 16) and 0xFF) + ((br shr 16) and 0xFF)
                val gxG = -((tl shr 8) and 0xFF) + ((tr shr 8) and 0xFF) - 2 * ((ml shr 8) and 0xFF) + 2 * ((mr shr 8) and 0xFF) - ((bl shr 8) and 0xFF) + ((br shr 8) and 0xFF)
                val gyG = -((tl shr 8) and 0xFF) - 2 * ((tc shr 8) and 0xFF) - ((tr shr 8) and 0xFF) + ((bl shr 8) and 0xFF) + 2 * ((bc shr 8) and 0xFF) + ((br shr 8) and 0xFF)
                val gxB = -(tl and 0xFF) + (tr and 0xFF) - 2 * (ml and 0xFF) + 2 * (mr and 0xFF) - (bl and 0xFF) + (br and 0xFF)
                val gyB = -(tl and 0xFF) - 2 * (tc and 0xFF) - (tr and 0xFF) + (bl and 0xFF) + 2 * (bc and 0xFF) + (br and 0xFF)

                val fineSobel = Math.sqrt(
                    (0.30f * (gxR * gxR + gyR * gyR) +
                     0.52f * (gxG * gxG + gyG * gyG) +
                     0.18f * (gxB * gxB + gyB * gyB)).toDouble()
                ).toFloat() * 0.44f

                // Pass B: 2px Macro Luminance Sobel for continuous, thick outer comic contour outlines
                val gLx = -lum[y2m + x2m] + lum[y2m + x2p] - 2f * lum[yC + x2m] + 2f * lum[yC + x2p] - lum[y2p + x2m] + lum[y2p + x2p]
                val gLy = -lum[y2m + x2m] - 2f * lum[y2m + x] - lum[y2m + x2p] + lum[y2p + x2m] + 2f * lum[y2p + x] + lum[y2p + x2p]
                val macroSobel = Math.hypot(gLx.toDouble(), gLy.toDouble()).toFloat() * 0.36f

                val dogCrease = Math.max(0f, blurWide[p] * 0.985f - blurTight[p]) * 3.15f
                rawEdges[p] = Math.max(fineSobel, macroSobel) * 0.88f + dogCrease
            }
        }

        // Pass C: 3x3 Directional Contour Reinforcement (bridges 1px gaps so comic outlines flow cleanly)
        val out = FloatArray(n)
        for (y in 0 until h) {
            val ym = (y - 1).coerceIn(0, h - 1) * w
            val yc = y * w
            val yp = (y + 1).coerceIn(0, h - 1) * w
            for (x in 0 until w) {
                val xm = (x - 1).coerceIn(0, w - 1)
                val xp = (x + 1).coerceIn(0, w - 1)
                val c = rawEdges[yc + x]
                val nMax = Math.max(
                    Math.max(rawEdges[ym + x], rawEdges[yp + x]),
                    Math.max(rawEdges[yc + xm], rawEdges[yc + xp])
                )
                out[yc + x] = c * 0.72f + nMax * 0.38f
            }
        }
        return out
    }

    /**
     * Unified pixel-accurate Art, Optical Blur & Toon Renderer used for both Live Viewfinder & Captured JPEG Photos
     */
    private fun renderStylizedEffectBitmap(srcBmp: Bitmap, effectId: String, forLivePreview: Boolean): Bitmap {
        if (effectId == "motion_blur") {
            return motionBlurProcessor.applyDirectionalMotionBlur(
                src = srcBmp,
                metrics = latestMotionMetrics,
                intensity = activeEffectIntensity,
                forLivePreview = forLivePreview
            )
        }
        val w = srcBmp.width.coerceAtLeast(1)
        val h = srcBmp.height.coerceAtLeast(1)
        val maxSample = if (forLivePreview) 480 else 1440
        val sw: Int
        val sh: Int
        if (w >= h) {
            sw = Math.min(w, maxSample)
            sh = Math.max(64, (sw * (h / w.toFloat())).toInt())
        } else {
            sh = Math.min(h, maxSample)
            sw = Math.max(64, (sh * (w / h.toFloat())).toInt())
        }
        val refScale = (Math.min(sw, sh).toFloat() / 400f).coerceIn(1f, 3.8f)

        if (effectId == "kaleidoscope") {
            val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val small = Bitmap.createScaledBitmap(srcBmp, sw, sh, true)
            canvas.drawColor(Color.parseColor("#020617"))
            val cx = sw * 0.5f
            val cy = sh * 0.5f
            val rad = Math.hypot(sw.toDouble(), sh.toDouble()).toFloat() * 0.6f
            val wedge = Path().apply {
                moveTo(0f, 0f)
                lineTo(rad, -rad * Math.tan(Math.PI / 8.0).toFloat() * 1.05f)
                lineTo(rad, rad * Math.tan(Math.PI / 8.0).toFloat() * 1.05f)
                close()
            }
            for (i in 0..7) {
                canvas.save()
                canvas.translate(cx, cy)
                canvas.rotate(i * 45f)
                canvas.clipPath(wedge)
                if (i % 2 == 1) canvas.scale(1f, -1f)
                canvas.drawBitmap(small, -cx, -cy, null)
                canvas.restore()
            }
            if (small != srcBmp) small.recycle()
            return if (forLivePreview) out else Bitmap.createScaledBitmap(out, w, h, true).also { if (it != out) out.recycle() }
        }

        val small = Bitmap.createScaledBitmap(srcBmp, sw, sh, true)
        val rawArr = IntArray(sw * sh)
        small.getPixels(rawArr, 0, sw, 0, 0, sw, sh)
        if (small != srcBmp) small.recycle()

        // IMPORTANT: Painterly Kuwahara is ONLY for Art painting effects — NEVER for Toon/Cartoon shaders!
        val kuwaharaRadius = when (effectId) {
            "oil_canvas", "watercolor_wash" -> if (forLivePreview) 2 else 5
            "pastel_chalk", "lithograph" -> if (forLivePreview) 2 else 4
            else -> 0
        }
        val baseArr = if (kuwaharaRadius > 0) applyKuwaharaIntArray(rawArr, sw, sh, kuwaharaRadius) else rawArr

        val smoothPasses = when (effectId) {
            // Toon & Comic-Book styles get clean edge-preserving bilateral flattening without mushing small details
            "comic_book", "comic", "cartoon", "cel_shaded_studio", "ligne_claire", "pop_art_print" -> if (forLivePreview) 2 else 4
            "clay_vinyl_3d" -> if (forLivePreview) 2 else 3
            "graphic_novel_noir", "noir_graphic_novel", "manga_ink", "line_art_minimalist" -> if (forLivePreview) 2 else 3
            // Art styles get smooth bilateral blending after Kuwahara to erase box corners cleanly
            "oil_canvas", "watercolor_wash", "pastel_chalk", "lithograph" -> if (forLivePreview) 1 else 2
            "charcoal_sketch", "ink_and_line", "woodcut_engraving", "halftone_print" -> if (forLivePreview) 1 else 2
            else -> 0
        }
        val smoothThresh = when (effectId) {
            "comic_book", "comic", "cartoon", "cel_shaded_studio", "ligne_claire", "pop_art_print" -> 40f
            "clay_vinyl_3d" -> 34f
            "oil_canvas", "watercolor_wash", "pastel_chalk" -> 32f
            else -> 28f
        }
        val pxArr = if (smoothPasses > 0) smoothIntArray(baseArr, sw, sh, smoothPasses, smoothThresh) else baseArr
        val n = sw * sh
        val outArr = IntArray(n)

        val lum = FloatArray(n)
        for (i in 0 until n) {
            val c = pxArr[i]
            lum[i] = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        }

        val needsEdges = effectId in setOf(
            "comic_book", "comic", "cartoon",
            "cel_shaded_studio", "ligne_claire", "graphic_novel_noir", "noir_graphic_novel",
            "manga_ink", "line_art_minimalist", "pop_art_print", "clay_vinyl_3d",
            "charcoal_sketch", "ink_and_line", "watercolor_wash", "pastel_chalk",
            "halftone_print", "woodcut_engraving", "lithograph", "neon_edge"
        )
        val edges = if (needsEdges) computeXdogEdges(pxArr, lum, sw, sh) else FloatArray(0)

        fun smoothstep(e0: Float, e1: Float, v: Float): Float {
            val d = e1 - e0
            if (Math.abs(d) < 0.0001f) return if (v >= e1) 1f else 0f
            val t = ((v - e0) / d).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        // Bold, crisp cartoon cel-shading band step (tight AA border at 0.45..0.55 instead of blurry photo gradient)
        fun toonCelBand(l: Float, bands: Int = 4): Float {
            val norm = (l / 255f).coerceIn(0f, 1f)
            val scaled = norm * bands
            val b = Math.floor(scaled.toDouble()).toFloat()
            val f = scaled - b
            val q = (b + smoothstep(0.44f, 0.56f, f)) / bands
            return (q * 232f + 18f).coerceIn(18f, 252f)
        }

        fun softArtBand(l: Float, bands: Int = 6): Float {
            val norm = (l / 255f).coerceIn(0f, 1f)
            val scaled = norm * bands
            val b = Math.floor(scaled.toDouble()).toFloat()
            val f = scaled - b
            val q = (b + smoothstep(0.28f, 0.72f, f)) / bands
            return (q * 238f + 12f).coerceIn(12f, 252f)
        }

        fun rotatedHalftoneDot(x: Int, y: Int, darkness: Float, pitch: Float): Float {
            if (darkness <= 0.03f) return 0f
            val rx = (x * 0.7071f - y * 0.7071f) / pitch
            val ry = (x * 0.7071f + y * 0.7071f) / pitch
            val fx = rx - Math.floor(rx.toDouble()).toFloat() - 0.5f
            val fy = ry - Math.floor(ry.toDouble()).toFloat() - 0.5f
            val dist = Math.sqrt((fx * fx + fy * fy).toDouble()).toFloat()
            val rad = (Math.sqrt(darkness.coerceIn(0f, 1f).toDouble()).toFloat() * 0.50f * comicHalftoneDotSize).coerceIn(0.06f, 0.88f)
            return 1f - smoothstep(rad - 0.08f, rad + 0.08f, dist)
        }

        fun packRgb(r: Int, g: Int, b: Int): Int {
            return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }

        val isAlphaMaskedOpticalBlur = forLivePreview && effectId in setOf("portrait_bokeh", "video_bokeh", "tilt_shift")
        when (effectId) {
            "portrait_bokeh", "video_bokeh", "tilt_shift" -> {
                val blurRadius = if (forLivePreview) Math.max(5, Math.min(sw, sh) / 30) else Math.max(10, Math.min(sw, sh) / 48)
                val blurred = boxBlurIntArray(rawArr, sw, sh, blurRadius, passes = 2)
                val isTilt = effectId == "tilt_shift"
                val effBlendLocal = activeEffectIntensity.coerceIn(0f, 1f)
                for (y in 0 until sh) {
                    val ny = y / sh.toFloat()
                    val row = y * sw
                    for (x in 0 until sw) {
                        val idx = row + x
                        val nx = x / sw.toFloat()
                        val rawMask = if (isTilt) {
                            (Math.abs(ny - 0.5f) - 0.14f) / 0.24f
                        } else {
                            val d = Math.hypot((nx - 0.5).toDouble(), ((ny - 0.45) * 1.12).toDouble()).toFloat()
                            (d - 0.22f) / 0.28f
                        }.coerceIn(0f, 1f)
                        val mask = rawMask * rawMask * (3f - 2f * rawMask)
                        val b = blurred[idx]
                        if (isAlphaMaskedOpticalBlur) {
                            // Keep sharp focal region 100% transparent so full-resolution native PreviewView shines through!
                            val a = (mask * effBlendLocal * 255f).toInt().coerceIn(0, 255)
                            outArr[idx] = (a shl 24) or (b and 0x00FFFFFF)
                        } else {
                            val o = rawArr[idx]
                            outArr[idx] = packRgb(
                                (((o shr 16) and 0xFF) * (1f - mask) + ((b shr 16) and 0xFF) * mask).toInt(),
                                (((o shr 8) and 0xFF) * (1f - mask) + ((b shr 8) and 0xFF) * mask).toInt(),
                                ((o and 0xFF) * (1f - mask) + (b and 0xFF) * mask).toInt()
                            )
                        }
                    }
                }
            }
            "prism_refraction" -> {
                val shift = Math.max(3, sw / 48)
                for (y in 0 until sh) {
                    val row = y * sw
                    for (x in 0 until sw) {
                        val idx = row + x
                        val cL = rawArr[row + (x - shift).coerceIn(0, sw - 1)]
                        val cC = rawArr[idx]
                        val cR = rawArr[row + (x + shift).coerceIn(0, sw - 1)]
                        outArr[idx] = packRgb((cL shr 16) and 0xFF, (cC shr 8) and 0xFF, cR and 0xFF)
                    }
                }
            }
            // ========================
            // 7 GENUINE TOON STYLES + COMIC BOOK SHADER (SOBEL + HSV QUANTIZED BANDING)
            // ========================
            "comic_book", "comic", "cartoon", "cel_shaded_studio" -> {
                // Multi-pass Dual-Scale Sobel Ink Outlines + HSV Quantized Color Banding + Luminance-Channel Newspaper Halftone Screen
                val hsvTmp = FloatArray(3)
                val dotPitch = Math.max(2.6f, (sw / 92f) / comicHalftoneDensity.coerceIn(0.4f, 2.2f))
                for (y in 0 until sh) {
                    val row = y * sw
                    for (x in 0 until sw) {
                        val i = row + x
                        val ink = smoothstep(23f, 44f, edges[i])
                        val c = pxArr[i]
                        Color.RGBToHSV((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, hsvTmp)
                        // Quantize hue into 18 clean comic ink sectors + snap saturation & value bands
                        hsvTmp[0] = (Math.round(hsvTmp[0] / 20f) * 20f) % 360f
                        hsvTmp[1] = if (hsvTmp[1] > 0.10f) Math.min(1f, hsvTmp[1] * 1.48f + 0.08f) else hsvTmp[1] * 0.45f
                        val scaledV = hsvTmp[2].coerceIn(0f, 1f) * 4f
                        val vBand = Math.floor(scaledV.toDouble()).toFloat()
                        val vFrac = smoothstep(0.42f, 0.58f, scaledV - vBand)
                        hsvTmp[2] = (((vBand + vFrac) / 4f) * 0.90f + 0.10f).coerceIn(0.08f, 0.98f)
                        val qCol = Color.HSVToColor(hsvTmp)
                        // Warm newsprint paper stock subtle tint + luminance-driven halftone dot overlay
                        var baseR = ((qCol shr 16) and 0xFF) * 1.00f
                        var baseG = ((qCol shr 8) and 0xFF) * 0.982f
                        var baseB = (qCol and 0xFF) * 0.945f
                        val normLum = (lum[i] / 255f).coerceIn(0f, 1f)
                        val lumDensity = smoothstep(0.90f, 0.12f, normLum)
                        if (lumDensity > 0.02f) {
                            val newsDot = rotatedHalftoneDot(x, y, lumDensity * 0.68f, dotPitch)
                            val dotDrop = newsDot * (0.14f + 0.32f * lumDensity)
                            val lumFactor = 1f - dotDrop
                            baseR *= lumFactor
                            baseG *= lumFactor
                            baseB *= lumFactor
                        }
                        outArr[i] = packRgb(
                            (baseR * (1f - ink) + 8f * ink).toInt(),
                            (baseG * (1f - ink) + 9f * ink).toInt(),
                            (baseB * (1f - ink) + 16f * ink).toInt()
                        )
                    }
                }
            }
            "ligne_claire" -> {
                // European Clear-Line Comic (Tintin / Moebius): 3 HSV quantized pastel/matte cel bands + uniform Sobel ink contours
                val hsvTmp = FloatArray(3)
                for (i in 0 until n) {
                    val ink = smoothstep(22f, 42f, edges[i]) * 0.94f
                    val c = pxArr[i]
                    Color.RGBToHSV((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, hsvTmp)
                    hsvTmp[0] = (Math.round(hsvTmp[0] / 24f) * 24f) % 360f
                    hsvTmp[1] = (hsvTmp[1] * 1.15f).coerceIn(0f, 0.85f)
                    val scaledV = hsvTmp[2].coerceIn(0f, 1f) * 3f
                    val vBand = Math.floor(scaledV.toDouble()).toFloat()
                    val vFrac = smoothstep(0.44f, 0.56f, scaledV - vBand)
                    hsvTmp[2] = (((vBand + vFrac) / 3f) * 0.74f + 0.24f).coerceIn(0.20f, 0.98f)
                    val qCol = Color.HSVToColor(hsvTmp)
                    val baseR = ((qCol shr 16) and 0xFF).toFloat()
                    val baseG = ((qCol shr 8) and 0xFF).toFloat()
                    val baseB = (qCol and 0xFF).toFloat()
                    outArr[i] = packRgb(
                        (baseR * (1f - ink) + 18f * ink).toInt(),
                        (baseG * (1f - ink) + 16f * ink).toInt(),
                        (baseB * (1f - ink) + 14f * ink).toInt()
                    )
                }
            }
            "graphic_novel_noir", "noir_graphic_novel" -> {
                val dotPitch = Math.max(2.6f, (sw / 82f) / comicHalftoneDensity.coerceIn(0.4f, 2.2f))
                for (y in 0 until sh) {
                    val row = y * sw
                    for (x in 0 until sw) {
                        val idx = row + x
                        val l = lum[idx]
                        val ink = smoothstep(22f, 44f, edges[idx])
                        val shadow = smoothstep(82f, 58f, l)
                        val mid = smoothstep(156f, 128f, l)
                        val htDot = rotatedHalftoneDot(x, y, mid * 0.50f, dotPitch)
                        val midShade = 192f - htDot * 140f
                        var v = 253f * (1f - mid) + midShade * mid
                        v = v * (1f - shadow) + 8f * shadow
                        v = v * (1f - ink) + 6f * ink
                        val iv = v.toInt().coerceIn(0, 255)
                        outArr[idx] = packRgb(iv, iv, iv)
                    }
                }
            }
            "manga_ink" -> {
                val dotPitch = Math.max(2.4f, (sw / 86f) / comicHalftoneDensity.coerceIn(0.4f, 2.2f))
                for (y in 0 until sh) {
                    val row = y * sw
                    for (x in 0 until sw) {
                        val idx = row + x
                        val l = lum[idx]
                        val ink = smoothstep(21f, 42f, edges[idx])
                        val darkFill = smoothstep(58f, 34f, l)
                        val toneDensity = if (l < 114f) 0.52f else if (l < 168f) 0.26f else 0f
                        val stDot = rotatedHalftoneDot(x, y, toneDensity, dotPitch) * 0.88f
                        val shade = Math.max(ink, Math.max(darkFill, stDot))
                        outArr[idx] = packRgb(
                            (253f * (1f - shade) + 12f * shade).toInt(),
                            (251f * (1f - shade) + 13f * shade).toInt(),
                            (245f * (1f - shade) + 18f * shade).toInt()
                        )
                    }
                }
            }
            "line_art_minimalist" -> {
                for (i in 0 until n) {
                    val ink = smoothstep(21f, 42f, edges[i])
                    outArr[i] = packRgb(
                        (254f * (1f - ink) + 12f * ink).toInt(),
                        (253f * (1f - ink) + 14f * ink).toInt(),
                        (248f * (1f - ink) + 18f * ink).toInt()
                    )
                }
            }
            "pop_art_print" -> {
                val dotPitch = Math.max(2.6f, (sw / 68f) / comicHalftoneDensity.coerceIn(0.4f, 2.2f))
                for (y in 0 until sh) {
                    val row = y * sw
                    for (x in 0 until sw) {
                        val idx = row + x
                        val ink = smoothstep(24f, 46f, edges[idx])
                        val c = pxArr[idx]
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        val l = lum[idx]
                        val chroma = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b))
                        val dotMix = rotatedHalftoneDot(x, y, if (l < 175f) 0.42f else 0.22f, dotPitch)
                        val pr: Float; val pg: Float; val pb: Float
                        when {
                            l > 192f -> {
                                // Comic canary yellow highlight + coral dots
                                pr = 254f; pg = 242f * (1f - dotMix) + 140f * dotMix; pb = 112f * (1f - dotMix) + 64f * dotMix
                            }
                            l < 60f -> {
                                // Deep comic indigo shadow + cyan dots
                                pr = 16f * (1f - dotMix) + 34f * dotMix; pg = 24f * (1f - dotMix) + 188f * dotMix; pb = 64f * (1f - dotMix) + 238f * dotMix
                            }
                            chroma < 26 -> {
                                // Neutral midtone -> pop sky cyan + magenta dots
                                pr = 56f * (1f - dotMix) + 236f * dotMix; pg = 189f * (1f - dotMix) + 72f * dotMix; pb = 248f * (1f - dotMix) + 153f * dotMix
                            }
                            r >= g && r >= b -> {
                                // Red / warm skin -> vivid comic red or peach-coral + crimson dots
                                val isDeepRed = r > g + 35
                                pr = if (isDeepRed) 239f else 253f
                                pg = (if (isDeepRed) 35f else 186f) * (1f - dotMix) + 45f * dotMix
                                pb = (if (isDeepRed) 60f else 140f) * (1f - dotMix) + 84f * dotMix
                            }
                            b >= r && b >= g -> {
                                // Royal cobalt blue + cyan dots
                                pr = 37f * (1f - dotMix) + 103f * dotMix; pg = 99f * (1f - dotMix) + 232f * dotMix; pb = 235f
                            }
                            else -> {
                                // Pop emerald + yellow dots
                                pr = 16f * (1f - dotMix) + 250f * dotMix; pg = 185f; pb = 129f * (1f - dotMix) + 21f * dotMix
                            }
                        }
                        outArr[idx] = packRgb(
                            (pr * (1f - ink) + 8f * ink).toInt(),
                            (pg * (1f - ink) + 8f * ink).toInt(),
                            (pb * (1f - ink) + 12f * ink).toInt()
                        )
                    }
                }
            }
            "clay_vinyl_3d" -> {
                // Smooth 3D Toy / Pixar Vinyl Toon: 4-pass bilateral surface (no blocky Kuwahara) + scaled smooth normals + vinyl specular
                val gStep = Math.max(2, Math.round(2f * refScale))
                for (y in 0 until sh) {
                    val ym = (y - gStep).coerceIn(0, sh - 1) * sw
                    val yp = (y + gStep).coerceIn(0, sh - 1) * sw
                    val row = y * sw
                    for (x in 0 until sw) {
                        val xm = (x - gStep).coerceIn(0, sw - 1)
                        val xp = (x + gStep).coerceIn(0, sw - 1)
                        val idx = row + x
                        val c = pxArr[idx]
                        val gx = (lum[row + xp] - lum[row + xm]) * 0.012f
                        val gy = (lum[yp + x] - lum[ym + x]) * 0.012f
                        var nx = -gx * 2.8f
                        var ny = -gy * 2.8f
                        var nz = 0.72f
                        val invLen = 1f / Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat()
                        nx *= invLen; ny *= invLen; nz *= invLen
                        val nDotL = Math.max(0f, -nx * 0.45f - ny * 0.55f + nz * 0.70f)
                        val wrapDiff = 0.66f + 0.52f * nDotL
                        val hDotN = Math.max(0f, -nx * 0.25f - ny * 0.31f + nz * 0.92f)
                        val spec = Math.pow(hDotN.toDouble(), 24.0).toFloat() * 54f
                        val rim = (1f - nz) * (1f - nz) * 30f
                        val rimShadow = smoothstep(46f, 110f, edges[idx]) * 0.16f
                        val r0 = (c shr 16) and 0xFF
                        val g0 = (c shr 8) and 0xFF
                        val b0 = c and 0xFF
                        val l = lum[idx]
                        outArr[idx] = packRgb(
                            (((l + (r0 - l) * 1.24f) * wrapDiff + 6f + spec + rim) * (1f - rimShadow)).toInt(),
                            (((l + (g0 - l) * 1.24f) * wrapDiff + 5f + spec + rim) * (1f - rimShadow)).toInt(),
                            (((l + (b0 - l) * 1.24f) * wrapDiff + 10f + spec + rim * 1.1f) * (1f - rimShadow)).toInt()
                        )
                    }
                }
            }
            // ========================
            // AUTHENTIC ART EFFECTS
            // ========================
            "oil_canvas" -> {
                // Organic painterly oil canvas: resolution-normalized gradient impasto on bilateral-smoothed Kuwahara + fine linen weave
                val gStep = Math.max(2, Math.round(2f * refScale))
                for (y in 0 until sh) {
                    val ym = (y - gStep).coerceIn(0, sh - 1) * sw
                    val yp = (y + gStep).coerceIn(0, sh - 1) * sw
                    val row = y * sw
                    val fy = y / refScale
                    for (x in 0 until sw) {
                        val xm = (x - gStep).coerceIn(0, sw - 1)
                        val xp = (x + gStep).coerceIn(0, sw - 1)
                        val idx = row + x
                        val c = pxArr[idx]
                        val r0 = (c shr 16) and 0xFF
                        val g0 = (c shr 8) and 0xFF
                        val b0 = c and 0xFF
                        val l = lum[idx]
                        val fx = x / refScale
                        val dx = lum[row + xp] - lum[row + xm]
                        val dy = lum[yp + x] - lum[ym + x]
                        val gradMag = Math.abs(dx) + Math.abs(dy)
                        val ridge = ((-dx * 0.45f - dy * 0.55f) * 0.22f).coerceIn(-12f, 16f)
                        val bristle = (Math.sin(fx * 0.58 + fy * 0.52 + Math.sin((fx - fy) * 0.14) * 1.8) * 3.8).toFloat()
                        val weave = (Math.sin(fx * 1.9) * Math.sin(fy * 1.9) * 2.2).toFloat()
                        val brushTone = ridge + bristle * (0.4f + Math.min(0.6f, gradMag / 90f)) + weave
                        outArr[idx] = packRgb(
                            (l + (r0 - l) * 1.32f + brushTone + 3f).toInt(),
                            (l + (g0 - l) * 1.32f + brushTone + 1f).toInt(),
                            (l + (b0 - l) * 1.32f + brushTone - 4f).toInt()
                        )
                    }
                }
            }
            "charcoal_sketch" -> {
                for (y in 0 until sh) {
                    val row = y * sw
                    val fy = y / refScale
                    for (x in 0 until sw) {
                        val idx = row + x
                        val fx = x / refScale
                        val edgeInk = smoothstep(22f, 52f, edges[idx])
                        val l = lum[idx]
                        val h1 = 0.5f + 0.5f * Math.sin((fx + fy) * 0.88 + Math.sin(fy * 0.12)).toFloat()
                        val h2 = 0.5f + 0.5f * Math.sin((fx - fy) * 0.88 + Math.cos(fx * 0.12)).toFloat()
                        val midShade = smoothstep(158f, 76f, l) * (0.26f + 0.40f * h1)
                        val deepShade = smoothstep(82f, 24f, l) * (0.42f + 0.46f * h2)
                        val shade = Math.max(edgeInk * 0.94f, Math.max(midShade, deepShade))
                        val tooth = (Math.sin(fx * 1.6 + fy * 0.8) * Math.cos(fy * 1.2 - fx * 0.6) * 5.0).toFloat()
                        val paperL = 245f - (1f - (l / 255f).coerceIn(0f, 1f)) * 18f + tooth
                        val v = paperL * (1f - shade) + 20f * shade
                        outArr[idx] = packRgb((v + 3f).toInt(), v.toInt(), (v - 6f).toInt())
                    }
                }
            }
            "ink_and_line" -> {
                for (y in 0 until sh) {
                    val row = y * sw
                    val fy = y / refScale
                    for (x in 0 until sw) {
                        val idx = row + x
                        val fx = x / refScale
                        val edgeInk = smoothstep(22f, 48f, edges[idx])
                        val l = lum[idx]
                        val solidDark = smoothstep(42f, 20f, l)
                        val w1 = smoothstep(0.62f, 0.92f, 0.5f + 0.5f * Math.sin((fx + fy) * 1.15).toFloat())
                        val w2 = smoothstep(0.62f, 0.92f, 0.5f + 0.5f * Math.sin((fx - fy) * 1.15).toFloat())
                        val hatch = Math.max(
                            smoothstep(108f, 68f, l) * w1 * 0.78f,
                            smoothstep(64f, 34f, l) * w2 * 0.85f
                        )
                        val ink = Math.max(edgeInk, Math.max(solidDark, hatch))
                        outArr[idx] = packRgb(
                            (252f * (1f - ink) + 14f * ink).toInt(),
                            (251f * (1f - ink) + 15f * ink).toInt(),
                            (246f * (1f - ink) + 21f * ink).toInt()
                        )
                    }
                }
            }
            "watercolor_wash" -> {
                for (y in 0 until sh) {
                    val row = y * sw
                    val fy = y / refScale
                    for (x in 0 until sw) {
                        val idx = row + x
                        val fx = x / refScale
                        val c = pxArr[idx]
                        val r0 = (c shr 16) and 0xFF
                        val g0 = (c shr 8) and 0xFF
                        val b0 = c and 0xFF
                        val l = lum[idx]
                        val bleach = smoothstep(190f, 245f, l) * 0.65f
                        var r = (l + (r0 - l) * 1.46f + 16f) * (1f - bleach) + 251f * bleach
                        var g = (l + (g0 - l) * 1.46f + 14f) * (1f - bleach) + 248f * bleach
                        var b = (l + (b0 - l) * 1.46f + 12f) * (1f - bleach) + 240f * bleach
                        val pool = 1f - smoothstep(26f, 70f, edges[idx]) * 0.24f
                        val tooth = ((Math.sin(fx * 0.8 + fy * 0.45) + Math.cos(fy * 0.7 - fx * 0.35)) * 3.0).toFloat()
                        outArr[idx] = packRgb((r * pool + tooth).toInt(), (g * pool + tooth).toInt(), (b * pool + tooth).toInt())
                    }
                }
            }
            "pastel_chalk" -> {
                for (y in 0 until sh) {
                    val row = y * sw
                    val fy = y / refScale
                    for (x in 0 until sw) {
                        val idx = row + x
                        val fx = x / refScale
                        val c = pxArr[idx]
                        val r0 = (c shr 16) and 0xFF
                        val g0 = (c shr 8) and 0xFF
                        val b0 = c and 0xFF
                        val l = Math.max(1f, lum[idx])
                        val softL = softArtBand(l, 7)
                        val sc = softL / l
                        val chalkTooth = (Math.sin(fx * 0.42 + fy * 0.32) * 2.2 + Math.cos(fy * 0.80) * 1.4).toFloat()
                        val outline = smoothstep(32f, 80f, edges[idx]) * 0.16f
                        val r = ((l + (r0 - l) * 0.88f) * sc * 0.78f + 56f + chalkTooth) * (1f - outline)
                        val g = ((l + (g0 - l) * 0.88f) * sc * 0.78f + 54f + chalkTooth) * (1f - outline)
                        val b = ((l + (b0 - l) * 0.88f) * sc * 0.78f + 58f + chalkTooth) * (1f - outline)
                        outArr[idx] = packRgb(r.toInt().coerceIn(30, 255), g.toInt().coerceIn(28, 255), b.toInt().coerceIn(32, 255))
                    }
                }
            }
            "halftone_print" -> {
                val pitch = Math.max(3.0f, (sw / 74f) / comicHalftoneDensity.coerceIn(0.4f, 2.2f))
                for (y in 0 until sh) {
                    val row = y * sw
                    for (x in 0 until sw) {
                        val idx = row + x
                        val l = (lum[idx] / 255f).coerceIn(0f, 1f)
                        val darkness = (1f - Math.pow(l.toDouble(), 0.92).toFloat()).coerceIn(0f, 1f)
                        val dotInk = rotatedHalftoneDot(x, y, darkness * 0.92f, pitch)
                        val crispEdge = smoothstep(26f, 56f, edges[idx]) * 0.72f
                        val ink = Math.max(dotInk, crispEdge)
                        outArr[idx] = packRgb(
                            (248f * (1f - ink) + 18f * ink).toInt(),
                            (243f * (1f - ink) + 22f * ink).toInt(),
                            (231f * (1f - ink) + 32f * ink).toInt()
                        )
                    }
                }
            }
            "cyanotype" -> {
                for (i in 0 until n) {
                    val l = (lum[i] / 255f).coerceIn(0f, 1f)
                    val s = l * l * (3f - 2f * l)
                    val cL = Math.pow(s.toDouble(), 0.88).toFloat()
                    outArr[i] = packRgb((8 + cL * 230).toInt(), (32 + cL * 214).toInt(), (76 + cL * 176).toInt())
                }
            }
            "woodcut_engraving" -> {
                for (y in 0 until sh) {
                    val row = y * sw
                    val fy = y / refScale
                    for (x in 0 until sw) {
                        val idx = row + x
                        val fx = x / refScale
                        val l = (lum[idx] / 255f).coerceIn(0f, 1f)
                        val wave = 0.5f + 0.5f * Math.sin(fy * 2.15 + Math.sin(fx * 0.06) * 2.0).toFloat()
                        val burin = smoothstep(l - 0.16f, l + 0.16f, wave) * (1f - smoothstep(0.62f, 0.88f, l))
                        val borderInk = smoothstep(22f, 48f, edges[idx])
                        val ink = if (l < 0.14f) 1f else if (l > 0.88f) borderInk else Math.max(borderInk, burin)
                        outArr[idx] = packRgb(
                            (246f * (1f - ink) + 16f * ink).toInt(),
                            (239f * (1f - ink) + 14f * ink).toInt(),
                            (224f * (1f - ink) + 12f * ink).toInt()
                        )
                    }
                }
            }
            "lithograph" -> {
                for (y in 0 until sh) {
                    val row = y * sw
                    val fy = y / refScale
                    for (x in 0 until sw) {
                        val idx = row + x
                        val fx = x / refScale
                        val c = pxArr[idx]
                        val r0 = (c shr 16) and 0xFF
                        val g0 = (c shr 8) and 0xFF
                        val b0 = c and 0xFF
                        val l = Math.max(1f, lum[idx])
                        val stoneBand = softArtBand(l, 5)
                        val sc = stoneBand / l
                        val stipple = (Math.sin(fx * 2.1 + fy * 1.7) * Math.cos(fx * 1.3 - fy * 2.3) * 6.0).toFloat()
                        val inkLine = smoothstep(26f, 60f, edges[idx]) * 0.44f
                        val r = ((l + (r0 - l) * 0.42f) * sc * 0.92f + 16f + stipple) * (1f - inkLine)
                        val g = ((l + (g0 - l) * 0.42f) * sc * 0.90f + 14f + stipple) * (1f - inkLine)
                        val b = ((l + (b0 - l) * 0.36f) * sc * 0.85f + 10f + stipple) * (1f - inkLine)
                        outArr[idx] = packRgb(r.toInt().coerceIn(16, 246), g.toInt().coerceIn(16, 242), b.toInt().coerceIn(14, 234))
                    }
                }
            }
            "solarize" -> {
                for (i in 0 until n) {
                    val c = pxArr[i]
                    val nr = ((c shr 16) and 0xFF) / 255.0
                    val ng = ((c shr 8) and 0xFF) / 255.0
                    val nb = (c and 0xFF) / 255.0
                    val sr = (Math.sin(Math.PI * Math.pow(nr, 0.88)) * 246.0 + 6.0).toInt()
                    val sg = (Math.sin(Math.PI * Math.pow(ng, 0.90)) * 244.0 + 8.0).toInt()
                    val sb = (Math.sin(Math.PI * Math.pow(nb, 0.92)) * 248.0 + 10.0).toInt()
                    outArr[i] = packRgb(sr, sg, sb)
                }
            }
            "neon_edge" -> {
                for (y in 0 until sh) {
                    val vy = y / sh.toFloat()
                    val row = y * sw
                    for (x in 0 until sw) {
                        val vx = x / sw.toFloat()
                        val idx = row + x
                        val edge = ((edges[idx] - 18f) / 96f).coerceIn(0f, 1f)
                        val nr = (6f + edge * (24f + vx * 225f)).toInt()
                        val ng = (8f + edge * (240f - vy * 145f)).toInt()
                        val nb = (18f + edge * 237f).toInt()
                        outArr[idx] = packRgb(nr, ng, nb)
                    }
                }
            }
            "thermal_vision" -> {
                for (i in 0 until n) {
                    val t = lum[i] / 255f
                    val r: Int; val g: Int; val b: Int
                    if (t < 0.25f) {
                        val k = t / 0.25f
                        r = (14f + k * 78f).toInt(); g = (8f + k * 14f).toInt(); b = (68f + k * 130f).toInt()
                    } else if (t < 0.5f) {
                        val k = (t - 0.25f) / 0.25f
                        r = (92f + k * 148f).toInt(); g = (22f + k * 18f).toInt(); b = (198f - k * 110f).toInt()
                    } else if (t < 0.78f) {
                        val k = (t - 0.5f) / 0.28f
                        r = (240f + k * 15f).toInt(); g = (40f + k * 165f).toInt(); b = (88f - k * 78f).toInt()
                    } else {
                        val k = (t - 0.78f) / 0.22f
                        r = 255; g = (205f + k * 50f).toInt(); b = (10f + k * 235f).toInt()
                    }
                    outArr[i] = packRgb(r, g, b)
                }
            }
            else -> System.arraycopy(pxArr, 0, outArr, 0, n)
        }

        val effBlend = activeEffectIntensity.coerceIn(0f, 1f)
        if (!isAlphaMaskedOpticalBlur && effBlend < 0.99f) {
            val inv = 1f - effBlend
            for (i in 0 until n) {
                val o = rawArr[i]
                val s = outArr[i]
                val r = (((o shr 16) and 0xFF) * inv + ((s shr 16) and 0xFF) * effBlend).toInt()
                val g = (((o shr 8) and 0xFF) * inv + ((s shr 8) and 0xFF) * effBlend).toInt()
                val b = ((o and 0xFF) * inv + (s and 0xFF) * effBlend).toInt()
                outArr[i] = packRgb(r, g, b)
            }
        }

        val artBmp = Bitmap.createBitmap(outArr, sw, sh, Bitmap.Config.ARGB_8888)
        if (forLivePreview) return artBmp
        return Bitmap.createScaledBitmap(artBmp, w, h, true).also {
            if (it != artBmp) artBmp.recycle()
        }
    }

    private fun toggleProDrawer() {
        val isVisible = viewBinding.proStudioDrawer.root.visibility == View.VISIBLE
        viewBinding.proStudioDrawer.root.visibility = if (isVisible) View.GONE else View.VISIBLE
        if (!isVisible) {
            viewBinding.effectsStudioDrawer.root.visibility = View.GONE
            isLastActionCreative = false
            selectShelfTab(currentShelfTab)
        }
    }

    private fun toggleCleanScreen() {
        val isClean = !viewBinding.proOverlayView.isCleanScreen
        viewBinding.proOverlayView.isCleanScreen = isClean
        updatePipOverlayVisibility()
        if (isClean) {
            viewBinding.btnCleanScreen.setImageResource(R.drawable.ic_eye_off)
            viewBinding.btnCleanScreen.setColorFilter(Color.parseColor("#E2E8F0"))
            viewBinding.arLensModeDock.root.visibility = View.GONE
            viewBinding.floatingModeMenu.root.visibility = View.GONE
            // silent (no popup)
        } else {
            viewBinding.btnCleanScreen.setImageResource(R.drawable.ic_eye)
            viewBinding.btnCleanScreen.setColorFilter(Color.parseColor("#CCCCCC"))
            viewBinding.arLensModeDock.root.visibility = View.GONE
            if (recording == null) {
                viewBinding.floatingModeMenu.root.visibility = View.VISIBLE
            }
            // silent (no popup)
        }
    }

    private fun toggleAudioMute() {
        isAudioMuted = !isAudioMuted
        viewBinding.proStudioDrawer.switchMicInput.isChecked = !isAudioMuted
        updateAudioMuteUI()
    }

    private fun updateAudioMuteUI() {
        if (isAudioMuted) {
            viewBinding.btnAudioMute.setImageResource(R.drawable.ic_volume_off)
            viewBinding.btnAudioMute.setColorFilter(Color.parseColor("#EF4444"))
            viewBinding.proStudioDrawer.tvMicInputStatus.text = "Microphone muted"
            // silent (no popup)
        } else {
            viewBinding.btnAudioMute.setImageResource(R.drawable.ic_volume_up)
            viewBinding.btnAudioMute.setColorFilter(Color.parseColor("#CCCCCC"))
            viewBinding.proStudioDrawer.tvMicInputStatus.text = "Studio stereo input active"
            // silent (no popup)
        }
    }

    private fun cycleAspectRatio() {
        currentAspectRatio = when (currentAspectRatio) {
            "4:3" -> "16:9"
            "16:9" -> "1:1"
            else -> "4:3"
        }
        viewBinding.btnAspectRatio.text = currentAspectRatio
        viewBinding.proOverlayView.sensorAspectRatio = currentAspectRatio
        // silent (no popup)
    }

    private fun setSpiralOrientation(rot: Int) {
        viewBinding.proOverlayView.spiralOrientation = rot
        val spiralButtons = listOf(
            viewBinding.proStudioDrawer.btnSpiral0 to 0,
            viewBinding.proStudioDrawer.btnSpiral90 to 1,
            viewBinding.proStudioDrawer.btnSpiral180 to 2,
            viewBinding.proStudioDrawer.btnSpiral270 to 3
        )
        for ((btn, r) in spiralButtons) {
            val isActive = r == rot
            if (isActive) {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_active)
                btn.setTextColor(Color.parseColor("#000000"))
            } else {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_inactive)
                btn.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
    }

    private fun setDeSqueeze(label: String, index: Int) {
        currentDeSqueeze = label
        val squeezeButtons = listOf(
            viewBinding.proStudioDrawer.btnSqueezeOff to 0,
            viewBinding.proStudioDrawer.btnSqueeze133 to 1,
            viewBinding.proStudioDrawer.btnSqueeze155 to 2,
            viewBinding.proStudioDrawer.btnSqueeze180 to 3,
            viewBinding.proStudioDrawer.btnSqueeze200 to 4
        )
        for ((btn, idx) in squeezeButtons) {
            val isActive = idx == index
            if (isActive) {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_active)
                btn.setTextColor(Color.parseColor("#000000"))
            } else {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_inactive)
                btn.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
        // silent (no popup)
    }

    private fun updateActiveInstrumentsCount() {
        var count = 0
        if (viewBinding.proOverlayView.currentGrid != ProOverlayView.GridType.NONE) count++
        if (viewBinding.proOverlayView.currentMatte != ProOverlayView.AspectMatte.NONE) count++
        if (viewBinding.proOverlayView.showLevelGauge) count++
        if (isGyroEisEnabled) count++
        if (viewBinding.proOverlayView.showVisualBalance) count++
        if (viewBinding.proOverlayView.showFocusPeaking) count++
        if (viewBinding.proOverlayView.showZebraStripes) count++
        if (viewBinding.proOverlayView.showWaveform) count++
        if (viewBinding.proOverlayView.showHistogram) count++
        if (viewBinding.proOverlayView.showVectorScope) count++
        if (viewBinding.proOverlayView.showAudioVuMeter) count++
        if (viewBinding.proOverlayView.showWatermark) count++
        if (isMultiCamPipEnabled) count++

        if (count > 0) {
            viewBinding.viewStudioBadge.visibility = View.VISIBLE
            viewBinding.proStudioDrawer.tvDrawerSubtitle.text = "$count ACTIVE INSTRUMENTS"
            viewBinding.btnProStudio.setColorFilter(Color.parseColor("#E2E8F0"))
        } else {
            viewBinding.viewStudioBadge.visibility = View.GONE
            viewBinding.proStudioDrawer.tvDrawerSubtitle.text = "INSTRUMENTS IN STANDBY"
            viewBinding.btnProStudio.setColorFilter(Color.parseColor("#CCCCCC"))
        }
    }

    private fun cycleToNextGrid() {
        val nextMode = when (viewBinding.proOverlayView.currentGrid) {
            ProOverlayView.GridType.NONE -> ProOverlayView.GridType.RULE_OF_THIRDS
            ProOverlayView.GridType.RULE_OF_THIRDS -> ProOverlayView.GridType.GOLDEN_SPIRAL
            ProOverlayView.GridType.GOLDEN_SPIRAL -> ProOverlayView.GridType.GOLDEN_RATIO_PHI
            ProOverlayView.GridType.GOLDEN_RATIO_PHI -> ProOverlayView.GridType.DIAGONAL_SYMMETRY
            ProOverlayView.GridType.DIAGONAL_SYMMETRY -> ProOverlayView.GridType.CINEMATIC_239
            ProOverlayView.GridType.CINEMATIC_239 -> ProOverlayView.GridType.NONE
        }
        setGridMode(nextMode)
        // silent (no popup)
    }

    private fun setGridMode(mode: ProOverlayView.GridType) {
        viewBinding.proOverlayView.currentGrid = mode
        val isSpiral = mode == ProOverlayView.GridType.GOLDEN_SPIRAL
        viewBinding.proStudioDrawer.layoutSpiralRotation.visibility = if (isSpiral) View.VISIBLE else View.GONE
        viewBinding.layoutQuickSpiralRotate.visibility = if (isSpiral) View.VISIBLE else View.GONE

        // Update Guide button icon color and active badge
        val hasActiveGrid = mode != ProOverlayView.GridType.NONE
        viewBinding.viewGuideBadge.visibility = if (hasActiveGrid) View.VISIBLE else View.GONE
        viewBinding.btnGuide.setColorFilter(if (hasActiveGrid) Color.parseColor("#E2E8F0") else Color.parseColor("#94A3B8"))

        // Update Pro Drawer grid buttons
        val gridButtons = listOf(
            viewBinding.proStudioDrawer.btnGridOff to ProOverlayView.GridType.NONE,
            viewBinding.proStudioDrawer.btnGridThirds to ProOverlayView.GridType.RULE_OF_THIRDS,
            viewBinding.proStudioDrawer.btnGridPhi to ProOverlayView.GridType.GOLDEN_RATIO_PHI,
            viewBinding.proStudioDrawer.btnGridSpiral to ProOverlayView.GridType.GOLDEN_SPIRAL,
            viewBinding.proStudioDrawer.btnGridDiagonal to ProOverlayView.GridType.DIAGONAL_SYMMETRY,
            viewBinding.proStudioDrawer.btnGridCinematic to ProOverlayView.GridType.CINEMATIC_239
        )
        for ((btn, m) in gridButtons) {
            val isActive = m == mode
            if (isActive) {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_active)
                btn.setTextColor(Color.parseColor("#0F172A"))
            } else {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_inactive)
                btn.setTextColor(Color.parseColor("#CBD5E1"))
            }
        }

        // Update Floating Guide Bar Quick Chips
        val quickChips = listOf(
            viewBinding.btnQuickGridOff to ProOverlayView.GridType.NONE,
            viewBinding.btnQuickGridThirds to ProOverlayView.GridType.RULE_OF_THIRDS,
            viewBinding.btnQuickGridSpiral to ProOverlayView.GridType.GOLDEN_SPIRAL,
            viewBinding.btnQuickGridPhi to ProOverlayView.GridType.GOLDEN_RATIO_PHI,
            viewBinding.btnQuickGridDiagonal to ProOverlayView.GridType.DIAGONAL_SYMMETRY,
            viewBinding.btnQuickGridCinematic to ProOverlayView.GridType.CINEMATIC_239
        )
        for ((chip, m) in quickChips) {
            val isActive = m == mode
            if (isActive) {
                chip.setBackgroundResource(R.drawable.bg_cinema_button_active)
                chip.setTextColor(Color.parseColor("#0F172A"))
            } else {
                chip.setBackgroundResource(R.drawable.bg_cinema_button_inactive)
                chip.setTextColor(Color.parseColor("#CBD5E1"))
            }
        }

        if (mode != ProOverlayView.GridType.NONE) {
            lastActiveGrid = mode
        }
        updateActiveInstrumentsCount()
    }

    private fun setAspectMatteMode(matte: ProOverlayView.AspectMatte) {
        viewBinding.proOverlayView.currentMatte = matte

        val matteButtons = listOf(
            viewBinding.proStudioDrawer.btnMatteOff to ProOverlayView.AspectMatte.NONE,
            viewBinding.proStudioDrawer.btnMatte169 to ProOverlayView.AspectMatte.WIDESCREEN_16_9,
            viewBinding.proStudioDrawer.btnMatteCine to ProOverlayView.AspectMatte.CINE_239,
            viewBinding.proStudioDrawer.btnMatteSquare to ProOverlayView.AspectMatte.SQUARE_1_1,
            viewBinding.proStudioDrawer.btnMatte916 to ProOverlayView.AspectMatte.VERTICAL_9_16,
            viewBinding.proStudioDrawer.btnMatte45 to ProOverlayView.AspectMatte.PORTRAIT_4_5
        )
        for ((btn, m) in matteButtons) {
            val isActive = m == matte
            if (isActive) {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_active)
                btn.setTextColor(Color.parseColor("#0F172A"))
            } else {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_inactive)
                btn.setTextColor(Color.parseColor("#CBD5E1"))
            }
        }

        // silent (no popup)
        updateActiveInstrumentsCount()
    }

    private fun setZoomLevel(ratio: Float, index: Int) {
        val zoomState = camera?.cameraInfo?.zoomState?.value
        val minZoom = zoomState?.minZoomRatio ?: 1.0f
        val maxZoom = zoomState?.maxZoomRatio ?: 8.0f
        val clamped = ratio.coerceIn(minZoom, maxZoom)
        cameraControl?.setZoomRatio(clamped)

        // Viewfinder zoom pills
        val pillViews = listOf(viewBinding.btnZoom06, viewBinding.btnZoom1, viewBinding.btnZoom2, viewBinding.btnZoom5)
        for (i in pillViews.indices) {
            if (i == index) {
                pillViews[i].setBackgroundResource(R.drawable.bg_zoom_pill_active)
                pillViews[i].setTextColor(Color.parseColor("#0F172A"))
            } else {
                pillViews[i].setBackgroundResource(android.R.color.transparent)
                pillViews[i].setTextColor(Color.parseColor("#CBD5E1"))
            }
        }

        // Drawer lens buttons
        val lensButtons = listOf(viewBinding.proStudioDrawer.btnLens06, viewBinding.proStudioDrawer.btnLens1, viewBinding.proStudioDrawer.btnLens2, viewBinding.proStudioDrawer.btnLens5)
        for (i in lensButtons.indices) {
            val btn = lensButtons[i]
            val isActive = i == index
            if (isActive) {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_active)
                val title = btn.getChildAt(0) as? TextView
                val sub = btn.getChildAt(1) as? TextView
                title?.setTextColor(Color.parseColor("#0F172A"))
                sub?.setTextColor(Color.parseColor("#334155"))
            } else {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_inactive)
                val title = btn.getChildAt(0) as? TextView
                val sub = btn.getChildAt(1) as? TextView
                title?.setTextColor(Color.parseColor("#E2E8F0"))
                sub?.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
    }

    /**
     * 4. Video Recorder: Start, Pause, Resume, and Stop
     */
    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return

        val curRecording = recording
        if (curRecording != null) {
            // Stop recording
            curRecording.stop()
            recording = null
            return
        }

        // Ensure OpenGL SurfaceProcessor has the latest active filter, effect, intensity, adjustments & AR overlay before recording starts
        syncAllStateToOpenGLProcessor()
        syncOverlayToVideoProcessor(forceUpload = true)

        // Start a new recording session
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                if (PermissionChecker.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PermissionChecker.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        playRecordStartSound()
                        isRecordingPaused = false
                        updateCaptureButtonVisual(isRecordingActive = true)
                        viewBinding.recordingTimer.visibility = View.VISIBLE
                        viewBinding.tvRecordingStatus.text = if (cameraMode == CameraMode.AR_LENS) {
                            if (isArRecordingLocked) "AR REC · LOCK" else "AR REC"
                        } else "REC"
                        viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#EF4444"))
                        viewBinding.recordingDot.visibility = View.VISIBLE
                        viewBinding.btnPauseResume.visibility = if (cameraMode == CameraMode.AR_LENS && !isArRecordingLocked) View.GONE else View.VISIBLE
                        viewBinding.btnGallery.visibility = View.VISIBLE
                        viewBinding.btnPauseResume.setImageResource(R.drawable.ic_pause)
                        if (cameraMode == CameraMode.AR_LENS) {
                            viewBinding.arLensModeDock.root.visibility = View.GONE
                            viewBinding.arCalibrationDrawer.visibility = View.GONE
                            viewBinding.tvArRecLockBadge.visibility = View.VISIBLE
                        }

                        // Reset stopped offset and start chronometer from 00:00
                        timeWhenStopped = 0L
                        viewBinding.chronometer.base = SystemClock.elapsedRealtime()
                        viewBinding.chronometer.setTextColor(Color.parseColor("#FFFFFF"))
                        viewBinding.chronometer.start()

                        startAutoLoopTicker()

                        viewBinding.tabPhoto.isEnabled = false
                        viewBinding.tabVideo.isEnabled = false
                        viewBinding.tabArLens.isEnabled = false
                        viewBinding.btnSwitchCamera.isEnabled = false
                    }

                    is VideoRecordEvent.Pause -> {
                        playCameraCue(ToneGenerator.TONE_PROP_BEEP, 70)
                        isRecordingPaused = true
                        // Save exact elapsed time in milliseconds
                        timeWhenStopped = SystemClock.elapsedRealtime() - viewBinding.chronometer.base
                        viewBinding.chronometer.stop()
                        viewBinding.tvRecordingStatus.text = "PAUSED"
                        viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#F59E0B"))
                        viewBinding.recordingDot.visibility = View.GONE
                        viewBinding.btnPauseResume.setImageResource(R.drawable.ic_play_arrow)
                        // silent (no popup)
                    }

                    is VideoRecordEvent.Resume -> {
                        playCameraCue(ToneGenerator.TONE_PROP_BEEP2, 85)
                        isRecordingPaused = false
                        // Offset base by saved elapsed time so counting resumes seamlessly from where it stopped
                        viewBinding.chronometer.base = SystemClock.elapsedRealtime() - timeWhenStopped
                        viewBinding.chronometer.start()
                        viewBinding.tvRecordingStatus.text = if (cameraMode == CameraMode.AR_LENS) "AR REC" else "REC"
                        viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#EF4444"))
                        viewBinding.recordingDot.visibility = View.VISIBLE
                        viewBinding.btnPauseResume.setImageResource(R.drawable.ic_pause)
                        // silent (no popup)
                    }

                    is VideoRecordEvent.Finalize -> {
                        playRecordStopSound()
                        stopAutoLoopTicker()
                        isArHoldRecording = false
                        isArRecordingLocked = false
                        arStopOnShutterUp = false
                        viewBinding.tvArRecLockBadge.visibility = View.GONE
                        viewBinding.tvArRecLockBadge.translationY = 0f
                        openGLProcessor?.updateOverlayBitmap(null)
                        viewBinding.chronometer.stop()
                        timeWhenStopped = 0L
                        viewBinding.btnPauseResume.visibility = View.GONE
                        viewBinding.btnGallery.visibility = View.VISIBLE
                        if (cameraMode == CameraMode.AR_LENS) {
                            viewBinding.tvRecordingStatus.text = "AR LENS"
                            viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#E879F9"))
                            viewBinding.recordingDot.setBackgroundResource(R.drawable.bg_green_dot)
                            viewBinding.recordingDot.visibility = View.VISIBLE
                            viewBinding.chronometer.text = "TAP PHOTO · HOLD 0.5S VID"
                            viewBinding.chronometer.setTextColor(Color.parseColor("#F0ABFC"))
                            updateCaptureButtonVisual(isRecordingActive = false)
                            viewBinding.arLensModeDock.root.visibility = View.VISIBLE
                        } else {
                            viewBinding.tvRecordingStatus.text = "STANDBY"
                            viewBinding.tvRecordingStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
                            viewBinding.recordingDot.visibility = View.GONE
                            viewBinding.chronometer.text = "00:00:00"
                            updateCaptureButtonVisual(isRecordingActive = false)
                        }

                        viewBinding.tabPhoto.isEnabled = true
                        viewBinding.tabVideo.isEnabled = true
                        viewBinding.tabArLens.isEnabled = true
                        viewBinding.btnSwitchCamera.isEnabled = true

                        if (!recordEvent.hasError()) {
                            val outputUri = recordEvent.outputResults.outputUri
                            lastCapturedUri = outputUri
                            lastCapturedMimeType = "video/mp4"
                            val msg = "Video saved: ${outputUri}"
                            Log.d(TAG, msg)
                            runOnUiThread {
                                try {
                                    viewBinding.ivGalleryThumbnail.setImageURI(outputUri)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed updating video thumbnail", e)
                                }
                            }
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture error: ${recordEvent.error}")
                        }
                    }
                }
            }
    }

    /**
     * Pause / Resume Video Recording using recording.pause() and recording.resume()
     */
    private fun toggleVideoPauseResume() {
        val curRecording = recording ?: return
        if (isRecordingPaused) {
            curRecording.resume()
        } else {
            curRecording.pause()
        }
    }

    /**
     * 5. Capture Photo and Save into MediaStore
     */
    private fun takePhoto(isBurst: Boolean = false) {
        if (!isBurst && (cameraMode == CameraMode.NIGHT || currentShootingEffect == "night_sight")) {
            if (!isNightStacking) {
                val targetFrames = when (nightFramesMode) {
                    "3" -> 3
                    "5" -> 5
                    "7" -> 7
                    else -> nightExposureStacker.lastRecommendedFrameCount
                }
                startNightExposureStacking(
                    autoTriggered = false,
                    targetFrameCount = targetFrames
                )
            }
            return
        }
        val imageCapture = imageCapture ?: return
        if (!isBurst) {
            playShutterClickSound()
        }

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val needsPostProcess = cameraMode == CameraMode.NIGHT ||
            isMultiCamPipEnabled && viewBinding.pipCardContainer.visibility == View.VISIBLE ||
            currentFilterName != "normal" ||
            currentShootingEffect != "none" ||
            viewBinding.proOverlayView.liveBrightnessPercent != 0 ||
            viewBinding.proOverlayView.liveContrastPercent != 0 ||
            viewBinding.proOverlayView.liveSaturationPercent != 0 ||
            viewBinding.arFaceOverlayView.currentLens != ARFaceOverlayView.ArLensMode.NONE ||
            viewBinding.arFaceOverlayView.enableComicOnomatopoeia ||
            viewBinding.arFaceOverlayView.enableComicSpeechBubble ||
            viewBinding.arFaceOverlayView.hasArTextItems() ||
            viewBinding.proOverlayView.enableFilmGrain ||
            viewBinding.proOverlayView.enableDateStamp ||
            viewBinding.proOverlayView.enableCineFlare ||
            viewBinding.proOverlayView.enableVignette ||
            viewBinding.proOverlayView.showWatermark

        if (!isBurst) {
            if (cameraMode == CameraMode.NIGHT) {
                viewBinding.tvRecordingStatus.text = "HOLD STEADY"
                viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#818CF8"))
                showHudNotice("NIGHT EXPOSURE · HOLD STEADY")
            }
            viewBinding.viewFinder.animate().alpha(0.3f).setDuration(50).withEndAction {
                viewBinding.viewFinder.animate().alpha(1.0f).setDuration(150).start()
            }.start()
        }

        val pipBitmapSnapshot: Bitmap? = try {
            if (isMultiCamPipEnabled && viewBinding.pipCardContainer.visibility == View.VISIBLE) {
                findTextureView(viewBinding.pipViewFinder)?.bitmap
            } else null
        } catch (_: Throwable) {
            null
        }

        if (needsPostProcess) {
            // Capture to private cache file first, apply all filters/effects/Art/Toon/AR cleanly,
            // then publish ONCE into MediaStore (IS_PENDING=1 -> write -> IS_PENDING=0) so OEM Scoped Storage never blocks overwrite!
            val tempFile = File(cacheDir, "raw_capture_${System.currentTimeMillis()}.jpg")
            val tempOutputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()
            imageCapture.takePicture(
                tempOutputOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        if (pipBitmapSnapshot != null && !pipBitmapSnapshot.isRecycled) pipBitmapSnapshot.recycle()
                        tempFile.delete()
                        Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                        if (cameraMode == CameraMode.NIGHT) {
                            runOnUiThread {
                                viewBinding.tvRecordingStatus.text = "NIGHT"
                                viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#818CF8"))
                            }
                        }
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        cameraExecutor.execute {
                            val finalUri = processAndSaveCreativePhoto(tempFile, name, pipBitmapSnapshot)
                            runOnUiThread {
                                if (cameraMode == CameraMode.NIGHT) {
                                    viewBinding.tvRecordingStatus.text = "NIGHT"
                                    viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#818CF8"))
                                }
                                if (finalUri != null) {
                                    lastCapturedUri = finalUri
                                    lastCapturedMimeType = "image/jpeg"
                                    try {
                                        viewBinding.ivGalleryThumbnail.setImageURI(null)
                                        viewBinding.ivGalleryThumbnail.setImageURI(finalUri)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed updating gallery thumbnail", e)
                                    }
                                }
                            }
                        }
                    }
                }
            )
            return
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    if (pipBitmapSnapshot != null && !pipBitmapSnapshot.isRecycled) pipBitmapSnapshot.recycle()
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri
                    Log.d(TAG, "Photo saved: ${savedUri}")
                    if (savedUri != null) {
                        lastCapturedUri = savedUri
                        lastCapturedMimeType = "image/jpeg"
                        runOnUiThread {
                            try {
                                viewBinding.ivGalleryThumbnail.setImageURI(null)
                                viewBinding.ivGalleryThumbnail.setImageURI(savedUri)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed updating gallery thumbnail", e)
                            }
                        }
                    }
                }
            }
        )
    }

    private fun applyComputationalNightEnhancement(bmp: Bitmap, isHardwareExt: Boolean) {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return

        // 1. Sample strided average luminance across image to detect dark night scenes
        val row = IntArray(w)
        val stepY = Math.max(1, h / 40)
        val stepX = Math.max(1, w / 40)
        var lumaSum = 0.0
        var count = 0
        var y = 0
        while (y < h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val px = row[x]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                lumaSum += 0.299 * r + 0.587 * g + 0.114 * b
                count++
                x += stepX
            }
            y += stepY
        }
        val avgLuma = if (count > 0) (lumaSum / count) else 80.0
        // Stronger shadow/gamma recovery in dark environments; milder HDR lift if already well-lit
        val baseDarkness = ((140.0 - avgLuma) / 115.0).coerceIn(0.0, 1.0)
        val darkness = if (isHardwareExt) baseDarkness * 0.55 else baseDarkness
        val gamma = 0.84 - darkness * 0.36 // down to 0.48 in pitch-dark scenes
        val floorLift = darkness * 14.0

        val lut = IntArray(256)
        for (i in 0..255) {
            val norm = i / 255.0
            val lifted = Math.pow(norm, gamma) * 255.0 + floorLift * Math.pow(1.0 - norm, 2.0)
            lut[i] = Math.round(lifted).toInt().coerceIn(0, 255)
        }

        // 2. Apply LUT row-by-row to avoid large memory allocations on 12MP-48MP captures
        for (py in 0 until h) {
            bmp.getPixels(row, 0, w, 0, py, w, 1)
            for (px in 0 until w) {
                val c = row[px]
                val a = c ushr 24
                val r = lut[(c shr 16) and 0xFF]
                val g = lut[(c shr 8) and 0xFF]
                val b = lut[c and 0xFF]
                row[px] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            bmp.setPixels(row, 0, w, 0, py, w, 1)
        }
    }

    /**
     * Captures a balanced bracketed multi-frame exposure sequence (Night Mode), tracks live hand steadiness
     * with a shutter progress ring, and fuses with 2-stage alignment, highlight recovery & deep night sky dehaze.
     */
    private fun startNightExposureStacking(autoTriggered: Boolean, targetFrameCount: Int) {
        val cap = imageCapture ?: return
        if (isNightStacking || isPanoramaCapturing || isPanoramaStitching || recording != null) return

        isNightStacking = true
        nightShutterProgress = 0.08f
        nightLastYawDeg = latestUprightYawDeg
        nightLastPitchDeg = latestUprightPitchDeg
        val deepSky = (nightSkyTone == "natural")

        val exposureState = camera?.cameraInfo?.exposureState
        val savedEvIndex = exposureState?.exposureCompensationIndex ?: 0
        val evRange = exposureState?.exposureCompensationRange
        val minEv = evRange?.lower ?: 0
        val maxEv = evRange?.upper ?: 0
        val bracketIndices = nightExposureStacker.buildExposureBracketIndices(minEv, maxEv, targetFrameCount)
        val totalFrames = bracketIndices.size
        val avgLumaHint = nightExposureStacker.lastAvgLuma

        val pipBitmapSnapshot: Bitmap? = try {
            if (isMultiCamPipEnabled && viewBinding.pipCardContainer.visibility == View.VISIBLE) {
                findTextureView(viewBinding.pipViewFinder)?.bitmap
            } else null
        } catch (_: Throwable) {
            null
        }

        viewBinding.proOverlayView.updateNightStackProgress(
            stacking = true,
            currentFrame = 1,
            totalFrames = totalFrames,
            fusing = false,
            steadyScore = 1f
        )
        updateCaptureButtonVisual(isRecordingActive = false)
        viewBinding.tvRecordingStatus.text = "HOLD"
        viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#34D399"))
        viewBinding.chronometer.text = "1/${totalFrames} F"
        playShutterClickSound()

        val capturedFrames = java.util.Collections.synchronizedList(ArrayList<Bitmap>())

        fun finishStackSequence() {
            if (exposureState?.isExposureCompensationSupported == true) {
                cameraControl?.setExposureCompensationIndex(savedEvIndex)
            }
            val snapshot = ArrayList(capturedFrames)
            capturedFrames.clear()
            if (snapshot.isEmpty()) {
                if (pipBitmapSnapshot != null && !pipBitmapSnapshot.isRecycled) pipBitmapSnapshot.recycle()
                isNightStacking = false
                nightShutterProgress = 0f
                runOnUiThread {
                    viewBinding.proOverlayView.updateNightStackProgress(false, 0, totalFrames, fusing = false)
                    updateCaptureButtonVisual(isRecordingActive = false)
                    viewBinding.tvRecordingStatus.text = if (cameraMode == CameraMode.NIGHT) "NIGHT" else "READY"
                }
                return
            }

            // Immediately transition to FUSING phase so user knows shooting is complete & HUD never jumps back
            nightShutterProgress = 1f
            runOnUiThread {
                viewBinding.proOverlayView.updateNightStackProgress(
                    stacking = false,
                    currentFrame = snapshot.size,
                    totalFrames = snapshot.size,
                    fusing = true,
                    steadyScore = 1f
                )
                updateCaptureButtonVisual(isRecordingActive = false)
                viewBinding.tvRecordingStatus.text = "FUSE"
                viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#22D3EE"))
                viewBinding.chronometer.text = "ALIGN ${snapshot.size}F"
            }

            cameraExecutor.execute {
                var stackedBmp: Bitmap? = null
                try {
                    stackedBmp = nightExposureStacker.stackExposureFrames(
                        frames = snapshot,
                        avgLumaHint = avgLumaHint,
                        bracketOffsets = bracketIndices,
                        deepNightSky = deepSky
                    )
                    val outBmp = stackedBmp ?: return@execute

                    val name = "NIGHT_" + SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
                        }
                    }
                    val stackedUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    val uri = stackedUri
                    if (uri != null) {
                        contentResolver.openOutputStream(uri, "wt")?.use { out ->
                            outBmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        }
                        if (!outBmp.isRecycled) outBmp.recycle()
                        stackedBmp = null

                        // Apply active LUT/filter/watermark/PiP while skipping duplicate night gamma LUT
                        applyCreativeLookToSavedPhoto(uri, pipBitmapSnapshot, skipNightLut = true)
                        lastCapturedUri = uri
                        lastCapturedMimeType = "image/jpeg"

                        runOnUiThread {
                            playShutterClickSound()
                            showHudNotice("Saved Night Photo (${snapshot.size}F · ${if (deepSky) "Natural" else "Bright"})")
                            try {
                                viewBinding.ivGalleryThumbnail.setImageURI(null)
                                viewBinding.ivGalleryThumbnail.setImageURI(uri)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed updating gallery thumbnail after Night stack", e)
                            }
                        }
                    } else if (pipBitmapSnapshot != null && !pipBitmapSnapshot.isRecycled) {
                        pipBitmapSnapshot.recycle()
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Night stacking failed", t)
                    if (pipBitmapSnapshot != null && !pipBitmapSnapshot.isRecycled) pipBitmapSnapshot.recycle()
                } finally {
                    for (bmp in snapshot) {
                        if (!bmp.isRecycled) bmp.recycle()
                    }
                    stackedBmp?.takeIf { !it.isRecycled }?.recycle()
                    isNightStacking = false
                    nightShutterProgress = 0f
                    runOnUiThread {
                        viewBinding.proOverlayView.updateNightStackProgress(false, 0, totalFrames, fusing = false)
                        updateCaptureButtonVisual(isRecordingActive = false)
                        if (cameraMode == CameraMode.NIGHT) {
                            viewBinding.tvRecordingStatus.text = "NIGHT"
                            viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#818CF8"))
                            viewBinding.chronometer.text = "MULTI-EXP · LOW LIGHT"
                        } else if (cameraMode == CameraMode.PHOTO) {
                            viewBinding.tvRecordingStatus.text = "READY"
                            viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#10B981"))
                            viewBinding.chronometer.text = "12MP 4:3"
                        }
                    }
                }
            }
        }

        fun captureBracketStep(stepIndex: Int) {
            if (stepIndex >= totalFrames) {
                finishStackSequence()
                return
            }
            val evIdx = bracketIndices[stepIndex]
            if (exposureState?.isExposureCompensationSupported == true) {
                cameraControl?.setExposureCompensationIndex(evIdx)
            }
            val yawDelta = Math.abs(normalizeAngleDelta(nightLastYawDeg, latestUprightYawDeg))
            val pitchDelta = Math.abs(latestUprightPitchDeg - nightLastPitchDeg)
            val driftDeg = yawDelta + pitchDelta
            val steadyScore = (1f - (driftDeg / 6.0f)).coerceIn(0.15f, 1.0f)
            nightLastYawDeg = latestUprightYawDeg
            nightLastPitchDeg = latestUprightPitchDeg
            nightShutterProgress = ((stepIndex + 1).toFloat() / totalFrames.toFloat()).coerceIn(0.12f, 0.96f)

            runOnUiThread {
                viewBinding.proOverlayView.updateNightStackProgress(
                    stacking = true,
                    currentFrame = stepIndex + 1,
                    totalFrames = totalFrames,
                    fusing = false,
                    steadyScore = steadyScore
                )
                updateCaptureButtonVisual(isRecordingActive = false)
                viewBinding.chronometer.text = "${stepIndex + 1}/${totalFrames} F"
                viewBinding.viewFinder.animate().alpha(0.68f).setDuration(40).withEndAction {
                    viewBinding.viewFinder.animate().alpha(1.0f).setDuration(80).start()
                }.start()
            }

            // Brief AE settle window so camera HAL applies the updated exposure bracket
            nightHandler.postDelayed({
                cap.takePicture(
                    cameraExecutor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                val bmp = nightExposureStacker.decodeCapturedProxyToBitmap(image, 1920)
                                if (bmp != null) {
                                    capturedFrames.add(bmp)
                                }
                            } catch (e: Throwable) {
                                Log.e(TAG, "Night bracket frame decode failed", e)
                            } finally {
                                image.close()
                            }
                            runOnUiThread {
                                window.decorView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                playCameraCue(ToneGenerator.TONE_PROP_BEEP, 24)
                            }
                            nightHandler.post { captureBracketStep(stepIndex + 1) }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "Night bracket capture error on frame $stepIndex", exception)
                            nightHandler.post { captureBracketStep(stepIndex + 1) }
                        }
                    }
                )
            }, if (stepIndex == 0) 40L else 110L)
        }

        captureBracketStep(0)
    }

    private fun computeSafeInSampleSize(srcW: Int, srcH: Int, maxEdge: Int = 2560): Int {
        var sample = 1
        var w = srcW
        var h = srcH
        while (Math.max(w, h) > maxEdge * 1.45f) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun decodeOrientedBitmapSafely(rawFile: File): Bitmap? {
        val orientation = try {
            ExifInterface(rawFile.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(rawFile.absolutePath, bounds)
        val sampleSize = computeSafeInSampleSize(bounds.outWidth.coerceAtLeast(1), bounds.outHeight.coerceAtLeast(1), 2560)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawBmp = BitmapFactory.decodeFile(rawFile.absolutePath, decodeOpts) ?: return null
        val rotDeg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        return if (rotDeg != 0f) {
            val m = Matrix().apply { postRotate(rotDeg) }
            Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, m, true).also {
                if (it != rawBmp) rawBmp.recycle()
            }
        } else {
            rawBmp
        }
    }

    private fun processAndSaveCreativePhoto(rawFile: File, displayName: String, pipBitmapSnapshot: Bitmap? = null): Uri? {
        try {
            val orientedBmp = decodeOrientedBitmapSafely(rawFile)
            if (orientedBmp == null) {
                if (pipBitmapSnapshot != null && !pipBitmapSnapshot.isRecycled) pipBitmapSnapshot.recycle()
                return null
            }
            val finalBmp = renderCreativeLookOntoBitmap(orientedBmp, pipBitmapSnapshot, skipNightLut = false)
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    finalBmp.compress(Bitmap.CompressFormat.JPEG, 94, out)
                    out.flush()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val doneCv = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                    contentResolver.update(uri, doneCv, null, null)
                }
            }
            if (!finalBmp.isRecycled) finalBmp.recycle()
            return uri
        } catch (t: Throwable) {
            if (pipBitmapSnapshot != null && !pipBitmapSnapshot.isRecycled) pipBitmapSnapshot.recycle()
            Log.e(TAG, "Failed processing creative photo, falling back to raw file save", t)
            return try {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
                    }
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) {
                    rawFile.inputStream().use { input ->
                        contentResolver.openOutputStream(uri)?.use { output -> input.copyTo(output) }
                    }
                }
                uri
            } catch (fallbackErr: Throwable) {
                Log.e(TAG, "Fallback raw photo save failed", fallbackErr)
                null
            }
        } finally {
            try { rawFile.delete() } catch (_: Throwable) {}
        }
    }

    private fun applyCreativeLookToSavedPhoto(savedUri: Uri, pipBitmapSnapshot: Bitmap? = null, skipNightLut: Boolean = false) {
        try {
            var orientation = ExifInterface.ORIENTATION_NORMAL
            contentResolver.openInputStream(savedUri)?.use { exifStream ->
                val exif = ExifInterface(exifStream)
                orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(savedUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            val sampleSize = computeSafeInSampleSize(bounds.outWidth.coerceAtLeast(1), bounds.outHeight.coerceAtLeast(1), 2560)
            val rawBmp = contentResolver.openInputStream(savedUri)?.use { input ->
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(input, null, opts)
            }
            if (rawBmp == null) {
                if (pipBitmapSnapshot != null && !pipBitmapSnapshot.isRecycled) pipBitmapSnapshot.recycle()
                return
            }

            val rotDeg = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            val orientedBmp = if (rotDeg != 0f) {
                val m = Matrix().apply { postRotate(rotDeg) }
                Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, m, true).also {
                    if (it != rawBmp) rawBmp.recycle()
                }
            } else {
                rawBmp
            }

            val finalBmp = renderCreativeLookOntoBitmap(orientedBmp, pipBitmapSnapshot, skipNightLut)
            val outStream = try {
                contentResolver.openOutputStream(savedUri, "wt")
            } catch (_: Throwable) {
                contentResolver.openOutputStream(savedUri, "w")
            }
            outStream?.use { out ->
                finalBmp.compress(Bitmap.CompressFormat.JPEG, 94, out)
                out.flush()
            }
            if (!finalBmp.isRecycled) finalBmp.recycle()
        } catch (e: Throwable) {
            if (pipBitmapSnapshot != null && !pipBitmapSnapshot.isRecycled) pipBitmapSnapshot.recycle()
            Log.e(TAG, "Failed post-processing photo creative look", e)
        }
    }

    private fun renderCreativeLookOntoBitmap(orientedBmp: Bitmap, pipBitmapSnapshot: Bitmap? = null, skipNightLut: Boolean = false): Bitmap {
            var finalBmp = Bitmap.createBitmap(orientedBmp.width, orientedBmp.height, Bitmap.Config.ARGB_8888)
            run {
                val baseCanvas = Canvas(finalBmp)
                val basePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                val hasColorMatrix = !isComicFilterPreset(currentFilterName) && (
                    currentFilterName != "normal" ||
                    viewBinding.proOverlayView.liveBrightnessPercent != 0 ||
                    viewBinding.proOverlayView.liveContrastPercent != 0 ||
                    viewBinding.proOverlayView.liveSaturationPercent != 0
                )
                if (hasColorMatrix) {
                    basePaint.colorFilter = ColorMatrixColorFilter(buildActiveColorMatrix(includeEffectColorLook = false))
                }
                baseCanvas.drawBitmap(orientedBmp, 0f, 0f, basePaint)
                if (!orientedBmp.isRecycled) orientedBmp.recycle()

                // If Comic Book base filter preset is selected, apply multi-pass GPU Comic shader with reliable CPU fallback
                if (isComicFilterPreset(currentFilterName)) {
                    val gpuComic = try {
                        filterManager.applyToonEffectToBitmap(finalBmp, "comic_book", 0.92f)
                    } catch (_: Throwable) {
                        null
                    }
                    val comicStyled = gpuComic ?: renderStylizedEffectBitmap(finalBmp, "comic_book", forLivePreview = false)
                    val writableComic = comicStyled.copy(Bitmap.Config.ARGB_8888, true)
                    if (comicStyled != finalBmp && !comicStyled.isRecycled) comicStyled.recycle()
                    if (!finalBmp.isRecycled) finalBmp.recycle()
                    finalBmp = writableComic
                }
            }

            // Bake Macro Focus 1.28x or Action Stabilization / Dolly Zoom crop before drawing overlays
            if (currentShootingEffect == "macro_focus" || currentShootingEffect == "action_stabilization" || currentShootingEffect == "dolly_zoom") {
                val factor = if (currentShootingEffect == "macro_focus") 0.76f else 0.86f
                val cropW = (finalBmp.width * factor).toInt().coerceAtLeast(16)
                val cropH = (finalBmp.height * factor).toInt().coerceAtLeast(16)
                val sx = ((finalBmp.width - cropW) / 2).coerceAtLeast(0)
                val sy = ((finalBmp.height - cropH) / 2).coerceAtLeast(0)
                val cropped = Bitmap.createBitmap(finalBmp, sx, sy, cropW, cropH)
                val scaled = Bitmap.createScaledBitmap(cropped, finalBmp.width, finalBmp.height, true)
                if (cropped != scaled) cropped.recycle()
                val writable = scaled.copy(Bitmap.Config.ARGB_8888, true)
                if (scaled != writable) scaled.recycle()
                finalBmp.recycle()
                finalBmp = writable
            }

            // Computational Night Mode / Night Sight adaptive shadow-recovery LUT (skipped when already stacked)
            if (!skipNightLut && (cameraMode == CameraMode.NIGHT || currentShootingEffect == "night_sight")) {
                applyComputationalNightEnhancement(finalBmp, isHardwareExt = isNightExtensionActive && cameraMode == CameraMode.NIGHT)
            }

            val canvas = Canvas(finalBmp)
            val fw = finalBmp.width.toFloat()
            val fh = finalBmp.height.toFloat()
            val overlayW = viewBinding.proOverlayView.width.toFloat().coerceAtLeast(1f)
            val overlayH = viewBinding.proOverlayView.height.toFloat().coerceAtLeast(1f)
            val scaleX = fw / overlayW
            val scaleY = fh / overlayH
            val scale = Math.min(scaleX, scaleY)

            // Bake Optical, Shutter & Motion Shooting Effects into saved JPEG
            when (currentShootingEffect) {
                "night_sight" -> {
                    val nightCm = ColorMatrix().apply { setSaturation(1.08f) }
                    val copy = finalBmp.copy(Bitmap.Config.ARGB_8888, false)
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(nightCm) }
                    canvas.drawBitmap(copy, 0f, 0f, p)
                    copy.recycle()
                }
                "double_exposure" -> {
                    val ghost = finalBmp.copy(Bitmap.Config.ARGB_8888, false)
                    val m = Matrix().apply {
                        postTranslate(-fw * 0.5f, -fh * 0.5f)
                        postScale(1.12f, 1.12f)
                        postRotate(3.2f)
                        postTranslate(fw * 0.54f, fh * 0.48f)
                    }
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 92 }
                    canvas.drawBitmap(ghost, m, p)
                    ghost.recycle()
                }
                "motion_blur" -> {
                    val blurredBmp = motionBlurProcessor.applyDirectionalMotionBlur(
                        src = finalBmp,
                        metrics = latestMotionMetrics,
                        intensity = activeEffectIntensity,
                        forLivePreview = false
                    )
                    canvas.drawBitmap(blurredBmp, 0f, 0f, null)
                    if (blurredBmp != finalBmp && !blurredBmp.isRecycled) blurredBmp.recycle()
                }
                "long_exposure" -> {
                    val streakSrc = finalBmp.copy(Bitmap.Config.ARGB_8888, false)
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 48 }
                    val shiftAmt = 0.012f
                    canvas.drawBitmap(streakSrc, -fw * shiftAmt, 0f, p)
                    canvas.drawBitmap(streakSrc, fw * shiftAmt, 0f, p)
                    streakSrc.recycle()
                    val trailP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeWidth = (fw * 0.008f).coerceAtLeast(6f)
                        shader = LinearGradient(
                            0f, fh * 0.65f, fw, fh * 0.65f,
                            intArrayOf(Color.TRANSPARENT, Color.parseColor("#99F59E0B"), Color.TRANSPARENT),
                            null, Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawLine(0f, fh * 0.65f, fw, fh * 0.64f, trailP)
                }
                "light_painting" -> {
                    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                    }
                    val path = Path()
                    for (step in 0..64) {
                        val t = 1.2 + step * 0.08
                        val px = fw * (0.5f + 0.30f * Math.sin(t * 1.3).toFloat())
                        val py = fh * (0.48f + 0.22f * Math.cos(t * 2.1).toFloat())
                        if (step == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    glowPaint.strokeWidth = (fw * 0.022f).coerceAtLeast(16f)
                    glowPaint.color = Color.parseColor("#44EC4899")
                    canvas.drawPath(path, glowPaint)
                    glowPaint.strokeWidth = (fw * 0.007f).coerceAtLeast(6f)
                    glowPaint.color = Color.parseColor("#E638BDF8")
                    canvas.drawPath(path, glowPaint)
                }
                "star_trails" -> {
                    val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeWidth = (fw * 0.0035f).coerceAtLeast(3f)
                    }
                    val cx = fw * 0.5f
                    val cy = fh * 0.22f
                    for (i in 1..9) {
                        val r = Math.min(fw, fh) * (0.10f + i * 0.065f)
                        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
                        starPaint.color = if (i % 2 == 0) Color.parseColor("#AAFFFFFF") else Color.parseColor("#AAFEF08A")
                        canvas.drawArc(oval, i * 33f, 54f + (i % 3) * 18f, false, starPaint)
                    }
                }
                "portrait_bokeh", "video_bokeh", "tilt_shift" -> {
                    val smallW = (finalBmp.width / 14).coerceAtLeast(32)
                    val smallH = (finalBmp.height / 14).coerceAtLeast(32)
                    val down = Bitmap.createScaledBitmap(finalBmp, smallW, smallH, true)
                    val blurred = Bitmap.createScaledBitmap(down, finalBmp.width, finalBmp.height, true)
                    down.recycle()

                    val maskLayer = Bitmap.createBitmap(finalBmp.width, finalBmp.height, Bitmap.Config.ARGB_8888)
                    val mCanvas = Canvas(maskLayer)
                    mCanvas.drawBitmap(blurred, 0f, 0f, null)
                    blurred.recycle()

                    val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                        shader = if (currentShootingEffect == "portrait_bokeh" || currentShootingEffect == "video_bokeh") {
                            val r = Math.max(fw, fh) * 0.58f
                            RadialGradient(
                                fw * 0.5f, fh * 0.45f, r,
                                intArrayOf(Color.TRANSPARENT, Color.argb(120, 0, 0, 0), Color.BLACK),
                                floatArrayOf(0.28f, 0.50f, 1.0f),
                                Shader.TileMode.CLAMP
                            )
                        } else {
                            LinearGradient(
                                0f, 0f, 0f, fh,
                                intArrayOf(Color.BLACK, Color.argb(140, 0, 0, 0), Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(140, 0, 0, 0), Color.BLACK),
                                floatArrayOf(0f, 0.24f, 0.36f, 0.64f, 0.76f, 1.0f),
                                Shader.TileMode.CLAMP
                            )
                        }
                    }
                    mCanvas.drawRect(0f, 0f, fw, fh, maskPaint)
                    canvas.drawBitmap(maskLayer, 0f, 0f, null)
                    maskLayer.recycle()
                }
                "panorama" -> {
                    // Creative Tiny Planet 360° Polar Globe Effect baked cleanly into Photo mode
                    val planetSize = Math.min(fw, fh).toInt().coerceIn(480, 1180)
                    val planetBmp = panoramaStitcher.renderTinyPlanet(finalBmp, planetSize)
                    val bgPaint = Paint().apply { color = Color.parseColor("#020617") }
                    canvas.drawRect(0f, 0f, fw, fh, bgPaint)
                    val dstSize = Math.min(fw, fh) * 0.92f
                    val dstLeft = (fw - dstSize) * 0.5f
                    val dstTop = (fh - dstSize) * 0.5f
                    val dstRect = RectF(dstLeft, dstTop, dstLeft + dstSize, dstTop + dstSize)
                    canvas.drawBitmap(planetBmp, null, dstRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    if (!planetBmp.isRecycled) planetBmp.recycle()
                }
                "soft_halation" -> {
                    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                    haloPaint.shader = RadialGradient(
                        fw * 0.28f, fh * 0.24f, fw * 0.44f,
                        intArrayOf(Color.parseColor("#55FF4D1A"), Color.parseColor("#20FF7043"), Color.TRANSPARENT),
                        floatArrayOf(0f, 0.45f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(0f, 0f, fw, fh, haloPaint)
                }
                "vintage_super8" -> {
                    val s8Cm = ColorMatrix().apply {
                        setSaturation(0.84f)
                        postConcat(ColorMatrix(floatArrayOf(
                            1.08f, 0.08f, 0.0f, 0f, 8f,
                            0.03f, 1.02f, 0.0f, 0f, 4f,
                            0.0f, 0.02f, 0.82f, 0f, -6f,
                            0f, 0f, 0f, 1f, 0f
                        )))
                    }
                    val copy = finalBmp.copy(Bitmap.Config.ARGB_8888, false)
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(s8Cm) }
                    canvas.drawBitmap(copy, 0f, 0f, p)
                    copy.recycle()

                    val leakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = RadialGradient(
                            fw * 0.92f, fh * 0.16f, fw * 0.48f,
                            intArrayOf(Color.parseColor("#66FB923C"), Color.parseColor("#28EF4444"), Color.TRANSPARENT),
                            floatArrayOf(0f, 0.5f, 1f),
                            Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawRect(0f, 0f, fw, fh, leakPaint)

                    val railW = fw * 0.045f
                    val gatePaint = Paint().apply { color = Color.parseColor("#E6060608") }
                    canvas.drawRect(0f, 0f, railW, fh, gatePaint)
                    canvas.drawRect(fw - fw * 0.015f, 0f, fw, fh, gatePaint)
                }
                "oil_canvas", "charcoal_sketch", "watercolor_wash", "ink_and_line",
                "pastel_chalk", "halftone_print", "cyanotype", "woodcut_engraving",
                "lithograph", "prism_refraction", "kaleidoscope", "solarize",
                "neon_edge", "noir_graphic_novel", "thermal_vision", "clay_vinyl_3d" -> {
                    val styledBmp = renderStylizedEffectBitmap(finalBmp, currentShootingEffect, forLivePreview = false)
                    canvas.drawBitmap(styledBmp, 0f, 0f, null)
                    if (styledBmp != finalBmp && !styledBmp.isRecycled) styledBmp.recycle()
                }
                "cel_shaded_studio", "graphic_novel_noir", "manga_ink", "pop_art_print",
                "line_art_minimalist", "ligne_claire" -> {
                    val gpuToon = try {
                        filterManager.applyToonEffectToBitmap(finalBmp, currentShootingEffect, activeEffectIntensity)
                    } catch (_: Throwable) {
                        null
                    }
                    val styledBmp = gpuToon ?: renderStylizedEffectBitmap(finalBmp, currentShootingEffect, forLivePreview = false)
                    canvas.drawBitmap(styledBmp, 0f, 0f, null)
                    if (styledBmp != finalBmp && !styledBmp.isRecycled) styledBmp.recycle()
                }
                "super8_film" -> {
                    val s8Cm = ColorMatrix().apply {
                        setSaturation(0.92f)
                        postConcat(ColorMatrix(floatArrayOf(
                            1.14f, 0.08f, 0f, 0f, 10f,
                            0.04f, 1.04f, 0f, 0f, 4f,
                            0f, 0.02f, 0.80f, 0f, -8f,
                            0f, 0f, 0f, 1f, 0f
                        )))
                    }
                    val copy = finalBmp.copy(Bitmap.Config.ARGB_8888, false)
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(s8Cm) }
                    canvas.drawBitmap(copy, 0f, 0f, p)
                    copy.recycle()
                    val scrP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = (fw * 0.002f).coerceAtLeast(2f)
                        color = Color.parseColor("#66FFFBEB")
                    }
                    canvas.drawLine(fw * 0.24f, 0f, fw * 0.245f, fh, scrP)
                    canvas.drawLine(fw * 0.72f, 0f, fw * 0.715f, fh, scrP)
                    val gatePath = Path().apply {
                        fillType = Path.FillType.EVEN_ODD
                        addRect(0f, 0f, fw, fh, Path.Direction.CW)
                        val ix = fw * 0.045f
                        val iy = fh * 0.04f
                        val cr = Math.min(fw, fh) * 0.085f
                        addRoundRect(RectF(ix, iy, fw - ix, fh - iy), cr, cr, Path.Direction.CCW)
                    }
                    canvas.drawPath(gatePath, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F0070709") })
                }
                "film_16mm" -> {
                    val docCm = ColorMatrix().apply {
                        setSaturation(0.88f)
                        val c = 1.14f
                        val t = (-0.5f * c + 0.5f) * 255f
                        postConcat(ColorMatrix(floatArrayOf(
                            c, 0.02f, 0f, 0f, t + 4f,
                            0f, c, 0f, 0f, t + 2f,
                            0f, 0f, c * 0.94f, 0f, t - 2f,
                            0f, 0f, 0f, 1f, 0f
                        )))
                    }
                    val copy = finalBmp.copy(Bitmap.Config.ARGB_8888, false)
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(docCm) }
                    canvas.drawBitmap(copy, 0f, 0f, p)
                    copy.recycle()
                }
                "vhs_tape" -> {
                    val smallW = (finalBmp.width / 4).coerceAtLeast(120)
                    val smallH = (finalBmp.height / 4).coerceAtLeast(160)
                    val down = Bitmap.createScaledBitmap(finalBmp, smallW, smallH, true)
                    val soft = Bitmap.createScaledBitmap(down, finalBmp.width, finalBmp.height, true)
                    down.recycle()
                    canvas.drawBitmap(soft, 0f, 0f, null)
                    val bleedP = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 58 }
                    canvas.drawBitmap(soft, -fw * 0.008f, 0f, bleedP)
                    canvas.drawBitmap(soft, fw * 0.008f, 0f, bleedP)
                    soft.recycle()
                    val trackY = fh * 0.68f
                    val trackP = Paint().apply { color = Color.parseColor("#55FFFFFF") }
                    canvas.drawRect(0f, trackY, fw, trackY + fh * 0.015f, trackP)
                    canvas.drawRect(0f, fh - fh * 0.012f, fw, fh, trackP)
                }
                "crt_monitor" -> {
                    val scanP = Paint().apply { color = Color.parseColor("#52020612") }
                    val step = (fh / 160f).toInt().coerceAtLeast(4)
                    for (y in 0 until finalBmp.height step step) {
                        canvas.drawRect(0f, y.toFloat(), fw, y + step * 0.45f, scanP)
                    }
                    val vigP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = RadialGradient(
                            fw * 0.5f, fh * 0.5f, Math.max(fw, fh) * 0.72f,
                            intArrayOf(Color.parseColor("#1EB8E6FD"), Color.TRANSPARENT, Color.parseColor("#99020617")),
                            floatArrayOf(0f, 0.62f, 1f),
                            Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawRect(0f, 0f, fw, fh, vigP)
                }
                "stop_motion" -> {
                    val smCm = ColorMatrix().apply { setSaturation(1.18f) }
                    val copy = finalBmp.copy(Bitmap.Config.ARGB_8888, false)
                    canvas.drawBitmap(copy, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(smCm) })
                    copy.recycle()
                }
            }

            // Bake Anamorphic Flare streaks if Cine Flare toggle OR anamorphic_flare effect is active
            if (viewBinding.proOverlayView.enableCineFlare || currentShootingEffect == "anamorphic_flare") {
                val flarePaint = Paint(Paint.ANTI_ALIAS_FLAG)
                val cy = fh * 0.44f
                val streakH = fh * 0.018f
                flarePaint.shader = LinearGradient(
                    0f, cy, fw, cy,
                    intArrayOf(Color.TRANSPARENT, Color.parseColor("#B300D4FF"), Color.parseColor("#E6FFFFFF"), Color.parseColor("#B300D4FF"), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, cy - streakH, fw, cy + streakH, flarePaint)
                val cy2 = fh * 0.56f
                val streakH2 = fh * 0.010f
                flarePaint.shader = LinearGradient(
                    0f, cy2, fw, cy2,
                    intArrayOf(Color.TRANSPARENT, Color.parseColor("#88F59E0B"), Color.parseColor("#CCFFFFFF"), Color.parseColor("#88F59E0B"), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.38f, 0.58f, 0.72f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, cy2 - streakH2, fw, cy2 + streakH2, flarePaint)
            }

            // Bake Film Grain if Film Grain toggle, Shooting Effect, Vintage Super 8, 16mm Docu, OR Ilford Grain filter is active
            if (viewBinding.proOverlayView.enableFilmGrain || currentShootingEffect == "film_grain" || currentShootingEffect == "vintage_super8" || currentShootingEffect == "film_16mm" || currentFilterName == "ilford_grain") {
                val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                val rng = java.util.Random(System.currentTimeMillis())
                val step = (finalBmp.width / 80).coerceIn(12, 32)
                for (gx in 0 until finalBmp.width step step) {
                    for (gy in 0 until finalBmp.height step step) {
                        val px = gx + rng.nextInt(step)
                        val py = gy + rng.nextInt(step)
                        val alpha = rng.nextInt(48) + 18
                        grainPaint.color = if (rng.nextBoolean()) Color.argb(alpha, 250, 248, 240) else Color.argb(alpha, 15, 15, 15)
                        canvas.drawCircle(px.toFloat(), py.toFloat(), (finalBmp.width * 0.0012f).coerceAtLeast(1.2f), grainPaint)
                    }
                }
            }

            // Bake Vignette if enabled
            if (viewBinding.proOverlayView.enableVignette || currentShootingEffect == "vignette") {
                val vigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        fw * 0.5f, fh * 0.5f, Math.max(fw, fh) * 0.72f,
                        intArrayOf(Color.TRANSPARENT, Color.parseColor("#A6000000")),
                        floatArrayOf(0.42f, 1.0f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(0f, 0f, fw, fh, vigPaint)
            }

            // Bake Retro Date Stamp if enabled
            if (viewBinding.proOverlayView.enableDateStamp) {
                val sdf = SimpleDateFormat("''yy MM dd  HH:mm", Locale.US)
                val dateText = viewBinding.proOverlayView.customDateStampText ?: sdf.format(java.util.Date())
                val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#FFA500")
                    textSize = (finalBmp.width * 0.032f).coerceAtLeast(28f)
                    typeface = Typeface.MONOSPACE
                    isFakeBoldText = true
                }
                val tw = stampPaint.measureText(dateText)
                canvas.drawText(dateText, finalBmp.width - tw - (finalBmp.width * 0.05f), finalBmp.height - (finalBmp.height * 0.06f), stampPaint)
            }

            // Retro Date Stamp handled above; Cine Flare already baked at line above without double-drawing

            // Bake AR Face Lens & Freehand AR Text overlays cleanly onto captured image (without selection guides)
            syncComicFrameOverlayState()
            if (viewBinding.arFaceOverlayView.hasAnyVisibleOverlay()) {
                canvas.save()
                val offsetX = (finalBmp.width - overlayW * scale) / 2f
                val offsetY = (finalBmp.height - overlayH * scale) / 2f
                canvas.translate(offsetX, offsetY)
                canvas.scale(scale, scale)
                viewBinding.arFaceOverlayView.drawCompositeOverlay(canvas, overlayW, overlayH, includeSelectionOutline = false)
                canvas.restore()
            }

            // Bake Typography / Studio Watermark Banner if enabled
            if (viewBinding.proOverlayView.showWatermark && viewBinding.proOverlayView.watermarkText.isNotBlank()) {
                val wmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor(injectedFonts.getOrNull(activeFontIndex)?.colorHex ?: "#38BDF8")
                    textSize = (fw * 0.026f).coerceAtLeast(24f)
                    typeface = resolveFontTypeface(injectedFonts.getOrNull(activeFontIndex))
                    letterSpacing = 0.08f
                }
                cameraFontHelper?.drawWatermarkOverlay(
                    canvas,
                    viewBinding.proOverlayView.watermarkText,
                    fw * 0.055f,
                    fh - fh * 0.055f,
                    wmPaint
                )
            }

            // Bake Real Multi-Cam PiP overlay onto saved JPEG photo when dual-camera mode is active
            if (pipBitmapSnapshot != null && !pipBitmapSnapshot.isRecycled) {
                try {
                    val pipW = fw * 0.25f
                    val pipH = pipW * (pipBitmapSnapshot.height.toFloat() / pipBitmapSnapshot.width.toFloat().coerceAtLeast(1f))
                    val marginX = fw * 0.045f
                    val marginY = fh * 0.14f
                    val pipRect = RectF(fw - marginX - pipW, marginY, fw - marginX, marginY + pipH)
                    val corner = pipW * 0.10f
                    val saveId = canvas.save()
                    val clipPath = Path().apply { addRoundRect(pipRect, corner, corner, Path.Direction.CW) }
                    canvas.clipPath(clipPath)
                    canvas.drawBitmap(pipBitmapSnapshot, null, pipRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    canvas.restoreToCount(saveId)
                    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = (fw * 0.004f).coerceAtLeast(4f)
                        color = Color.parseColor("#FACC15")
                    }
                    canvas.drawRoundRect(pipRect, corner, corner, borderPaint)
                } finally {
                    if (!pipBitmapSnapshot.isRecycled) pipBitmapSnapshot.recycle()
                }
            }

            return finalBmp
    }

    private val allCameraModes = listOf(
        CameraMode.SLOW_MO,
        CameraMode.VIDEO,
        CameraMode.PHOTO,
        CameraMode.PORTRAIT,
        CameraMode.NIGHT,
        CameraMode.PANORAMA,
        CameraMode.AR_LENS
    )

    private fun getModeLabel(mode: CameraMode): String = when (mode) {
        CameraMode.SLOW_MO -> "SLOW-MO"
        CameraMode.VIDEO -> "VIDEO"
        CameraMode.PHOTO -> "PHOTO"
        CameraMode.PORTRAIT -> "PORTRAIT"
        CameraMode.NIGHT -> "NIGHT"
        CameraMode.PANORAMA -> "PANORAMA"
        CameraMode.AR_LENS -> "AR LENS"
    }

    private fun updateModeCarouselSlots(animated: Boolean = true, slideDirection: Int = 0) {
        val curIdx = allCameraModes.indexOf(cameraMode)
        val total = allCameraModes.size
        val farLeftMode = allCameraModes[(curIdx - 2 + total * 10) % total]
        val leftMode = allCameraModes[(curIdx - 1 + total * 10) % total]
        val centerMode = allCameraModes[curIdx]
        val rightMode = allCameraModes[(curIdx + 1) % total]
        val farRightMode = allCameraModes[(curIdx + 2) % total]

        viewBinding.tabSlotFarLeft.text = getModeLabel(farLeftMode)
        viewBinding.tabSlotLeft.text = getModeLabel(leftMode)
        viewBinding.tabSlotCenter.text = getModeLabel(centerMode)
        viewBinding.tabSlotRight.text = getModeLabel(rightMode)
        viewBinding.tabSlotFarRight.text = getModeLabel(farRightMode)

        // Legacy compatibility
        val modeTabs = listOf(
            viewBinding.tabSlowMo to CameraMode.SLOW_MO,
            viewBinding.tabVideo to CameraMode.VIDEO,
            viewBinding.tabPhoto to CameraMode.PHOTO,
            viewBinding.tabPortrait to CameraMode.PORTRAIT,
            viewBinding.tabNight to CameraMode.NIGHT,
            viewBinding.tabPanorama to CameraMode.PANORAMA,
            viewBinding.tabArLens to CameraMode.AR_LENS
        )
        for ((tab, m) in modeTabs) {
            val isActive = m == cameraMode
            tab.setTextColor(if (isActive) Color.parseColor("#FFFFFF") else Color.parseColor("#94A3B8"))
            tab.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
        }

        if (animated && slideDirection != 0) {
            val startX = if (slideDirection > 0) 50f else -50f
            viewBinding.modeSelector.translationX = startX
            viewBinding.modeSelector.animate()
                .translationX(0f)
                .setDuration(160)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            viewBinding.modeSelector.translationX = 0f
        }
    }

    private fun updateCaptureButtonVisual(isRecordingActive: Boolean = false) {
        val isSnapMagic = (cameraMode == CameraMode.AR_LENS && activeArFamily == "snap_magic")
        viewBinding.btnCapture.setImageDrawable(
            createModeCaptureDrawable(cameraMode, isRecordingActive, isSnapMagic)
        )
    }

    private fun createModeCaptureDrawable(
        mode: CameraMode,
        isRecordingActive: Boolean,
        isSnapMagic: Boolean
    ): android.graphics.drawable.Drawable {
        return object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val path = android.graphics.Path()

            override fun draw(canvas: android.graphics.Canvas) {
                val b = bounds
                val w = b.width().toFloat()
                val h = b.height().toFloat()
                if (w <= 0f || h <= 0f) return
                val cx = b.exactCenterX()
                val cy = b.exactCenterY()
                val outerR = Math.min(w, h) * 0.46f
                val ringW = outerR * 0.11f
                val innerR = outerR - ringW * 1.45f

                // Outer ring color tuned per camera mode
                val ringColor = when {
                    isRecordingActive && mode == CameraMode.PANORAMA -> Color.parseColor("#38BDF8")
                    isRecordingActive -> Color.parseColor("#EF4444")
                    mode == CameraMode.VIDEO -> Color.parseColor("#EF4444")
                    mode == CameraMode.SLOW_MO -> Color.parseColor("#FACC15")
                    mode == CameraMode.PORTRAIT -> Color.parseColor("#FBBF24")
                    mode == CameraMode.NIGHT -> Color.parseColor("#818CF8")
                    mode == CameraMode.PANORAMA -> Color.parseColor("#38BDF8")
                    mode == CameraMode.AR_LENS && isSnapMagic -> Color.parseColor("#FACC15")
                    mode == CameraMode.AR_LENS -> Color.parseColor("#22D3EE")
                    else -> Color.parseColor("#F8FAFC")
                }

                // Subtle dark backing for legibility over bright backgrounds
                paint.style = android.graphics.Paint.Style.FILL
                paint.color = Color.argb(120, 8, 12, 20)
                canvas.drawCircle(cx, cy, outerR, paint)

                // Outer stroke ring
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = ringW
                paint.color = if (mode == CameraMode.NIGHT && isNightStacking) Color.argb(80, 129, 140, 248) else ringColor
                canvas.drawCircle(cx, cy, outerR - ringW * 0.5f, paint)

                // Live circular progress arc around Moon shutter during Night Mode multi-frame capture/fusion
                if (mode == CameraMode.NIGHT && isNightStacking && nightShutterProgress > 0.01f) {
                    val arcR = outerR - ringW * 0.5f
                    val arcRect = android.graphics.RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
                    paint.style = android.graphics.Paint.Style.STROKE
                    paint.strokeCap = android.graphics.Paint.Cap.ROUND
                    paint.strokeWidth = ringW * 1.18f
                    paint.color = if (nightShutterProgress >= 0.99f) Color.parseColor("#22D3EE") else Color.parseColor("#34D399")
                    canvas.drawArc(arcRect, -90f, 360f * nightShutterProgress.coerceIn(0.06f, 1f), false, paint)
                }

                // Active recording / panorama sweep stop square
                if (isRecordingActive) {
                    val sqHalf = innerR * 0.46f
                    val sqCorner = innerR * 0.18f
                    paint.style = android.graphics.Paint.Style.FILL
                    paint.color = if (mode == CameraMode.PANORAMA) Color.parseColor("#38BDF8") else Color.parseColor("#EF4444")
                    val rect = android.graphics.RectF(cx - sqHalf, cy - sqHalf, cx + sqHalf, cy + sqHalf)
                    canvas.drawRoundRect(rect, sqCorner, sqCorner, paint)
                    return
                }

                // Inner disc fill
                val fillColor = when {
                    mode == CameraMode.VIDEO -> Color.parseColor("#EF4444")
                    mode == CameraMode.SLOW_MO -> Color.parseColor("#FACC15")
                    mode == CameraMode.NIGHT || mode == CameraMode.PANORAMA || mode == CameraMode.AR_LENS -> Color.parseColor("#0F172A")
                    else -> Color.parseColor("#F8FAFC")
                }
                paint.style = android.graphics.Paint.Style.FILL
                paint.color = fillColor
                canvas.drawCircle(cx, cy, innerR, paint)

                if (mode == CameraMode.NIGHT || mode == CameraMode.PANORAMA || mode == CameraMode.AR_LENS) {
                    paint.style = android.graphics.Paint.Style.STROKE
                    paint.strokeWidth = ringW * 0.55f
                    paint.color = ringColor
                    canvas.drawCircle(cx, cy, innerR - ringW * 0.35f, paint)
                }

                // Mode-specific crisp vector icon centered inside shutter
                val ic = innerR * 0.48f
                paint.strokeCap = android.graphics.Paint.Cap.ROUND
                paint.strokeJoin = android.graphics.Paint.Join.ROUND

                when (mode) {
                    CameraMode.PHOTO -> {
                        // Crisp camera icon (body + top bump + center lens)
                        paint.style = android.graphics.Paint.Style.STROKE
                        paint.strokeWidth = innerR * 0.095f
                        paint.color = Color.parseColor("#0F172A")
                        val body = android.graphics.RectF(cx - ic, cy - ic * 0.62f, cx + ic, cy + ic * 0.72f)
                        canvas.drawRoundRect(body, ic * 0.24f, ic * 0.24f, paint)
                        path.reset()
                        path.moveTo(cx - ic * 0.42f, cy - ic * 0.62f)
                        path.lineTo(cx - ic * 0.24f, cy - ic * 0.92f)
                        path.lineTo(cx + ic * 0.24f, cy - ic * 0.92f)
                        path.lineTo(cx + ic * 0.42f, cy - ic * 0.62f)
                        canvas.drawPath(path, paint)
                        canvas.drawCircle(cx, cy + ic * 0.06f, ic * 0.34f, paint)
                    }
                    CameraMode.PORTRAIT -> {
                        // Aperture / bokeh iris ring with 6 blades
                        paint.style = android.graphics.Paint.Style.STROKE
                        paint.strokeWidth = innerR * 0.09f
                        paint.color = Color.parseColor("#B45309")
                        canvas.drawCircle(cx, cy, ic * 0.88f, paint)
                        canvas.drawCircle(cx, cy, ic * 0.30f, paint)
                        for (i in 0 until 6) {
                            val a = Math.toRadians((i * 60.0) - 15.0)
                            val a2 = Math.toRadians((i * 60.0) + 45.0)
                            val x1 = cx + (Math.cos(a) * ic * 0.30f).toFloat()
                            val y1 = cy + (Math.sin(a) * ic * 0.30f).toFloat()
                            val x2 = cx + (Math.cos(a2) * ic * 0.86f).toFloat()
                            val y2 = cy + (Math.sin(a2) * ic * 0.86f).toFloat()
                            canvas.drawLine(x1, y1, x2, y2, paint)
                        }
                    }
                    CameraMode.NIGHT -> {
                        // Crescent moon + 2 crisp stars
                        paint.style = android.graphics.Paint.Style.FILL
                        paint.color = Color.parseColor("#C7D2FE")
                        val moonR = ic * 0.78f
                        canvas.drawCircle(cx - ic * 0.08f, cy + ic * 0.04f, moonR, paint)
                        paint.color = Color.parseColor("#0F172A")
                        canvas.drawCircle(cx + ic * 0.26f, cy - ic * 0.20f, moonR * 0.82f, paint)
                        paint.color = Color.parseColor("#E0E7FF")
                        canvas.drawCircle(cx + ic * 0.46f, cy - ic * 0.42f, ic * 0.13f, paint)
                        canvas.drawCircle(cx + ic * 0.62f, cy - ic * 0.05f, ic * 0.09f, paint)
                    }
                    CameraMode.PANORAMA -> {
                        // Wide curved panorama frame + left/right sweep arrows
                        paint.style = android.graphics.Paint.Style.STROKE
                        paint.strokeWidth = innerR * 0.09f
                        paint.color = Color.parseColor("#7DD3FC")
                        path.reset()
                        path.moveTo(cx - ic * 0.92f, cy - ic * 0.56f)
                        path.quadTo(cx, cy - ic * 0.24f, cx + ic * 0.92f, cy - ic * 0.56f)
                        path.lineTo(cx + ic * 0.92f, cy + ic * 0.56f)
                        path.quadTo(cx, cy + ic * 0.24f, cx - ic * 0.92f, cy + ic * 0.56f)
                        path.close()
                        canvas.drawPath(path, paint)
                        // Center horizontal sweep arrow
                        canvas.drawLine(cx - ic * 0.42f, cy, cx + ic * 0.42f, cy, paint)
                        canvas.drawLine(cx + ic * 0.20f, cy - ic * 0.20f, cx + ic * 0.44f, cy, paint)
                        canvas.drawLine(cx + ic * 0.20f, cy + ic * 0.20f, cx + ic * 0.44f, cy, paint)
                    }
                    CameraMode.VIDEO -> {
                        // White camcorder silhouette
                        paint.style = android.graphics.Paint.Style.FILL
                        paint.color = Color.WHITE
                        val camBody = android.graphics.RectF(cx - ic * 0.86f, cy - ic * 0.54f, cx + ic * 0.28f, cy + ic * 0.54f)
                        canvas.drawRoundRect(camBody, ic * 0.22f, ic * 0.22f, paint)
                        path.reset()
                        path.moveTo(cx + ic * 0.34f, cy - ic * 0.20f)
                        path.lineTo(cx + ic * 0.88f, cy - ic * 0.52f)
                        path.lineTo(cx + ic * 0.88f, cy + ic * 0.52f)
                        path.lineTo(cx + ic * 0.34f, cy + ic * 0.20f)
                        path.close()
                        canvas.drawPath(path, paint)
                    }
                    CameraMode.SLOW_MO -> {
                        // Speedometer gauge arc + needle in dark slate
                        paint.style = android.graphics.Paint.Style.STROKE
                        paint.strokeWidth = innerR * 0.10f
                        paint.color = Color.parseColor("#0F172A")
                        val arcRect = android.graphics.RectF(cx - ic * 0.84f, cy - ic * 0.72f, cx + ic * 0.84f, cy + ic * 0.96f)
                        canvas.drawArc(arcRect, 180f, 180f, false, paint)
                        canvas.drawLine(cx, cy + ic * 0.20f, cx + ic * 0.46f, cy - ic * 0.32f, paint)
                        paint.style = android.graphics.Paint.Style.FILL
                        canvas.drawCircle(cx, cy + ic * 0.20f, ic * 0.16f, paint)
                    }
                    CameraMode.AR_LENS -> {
                        // 4-point sparkle star (amber-gold for Snap Magic, cyan for Face/Hand/Body/World AR)
                        val starColor = if (isSnapMagic) Color.parseColor("#FDE047") else Color.parseColor("#67E8F9")
                        paint.style = android.graphics.Paint.Style.FILL
                        paint.color = starColor
                        path.reset()
                        path.moveTo(cx, cy - ic * 0.92f)
                        path.quadTo(cx + ic * 0.14f, cy - ic * 0.14f, cx + ic * 0.92f, cy)
                        path.quadTo(cx + ic * 0.14f, cy + ic * 0.14f, cx, cy + ic * 0.92f)
                        path.quadTo(cx - ic * 0.14f, cy + ic * 0.14f, cx - ic * 0.92f, cy)
                        path.quadTo(cx - ic * 0.14f, cy - ic * 0.14f, cx, cy - ic * 0.92f)
                        path.close()
                        canvas.drawPath(path, paint)
                        // Secondary accent sparkle dot
                        canvas.drawCircle(cx + ic * 0.58f, cy - ic * 0.56f, ic * 0.14f, paint)
                    }
                }
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun setMode(mode: CameraMode, slideDirection: Int = 0) {
        if (recording != null || isNightStacking) return
        val prevMode = cameraMode
        if (isPanoramaCapturing && mode != CameraMode.PANORAMA) {
            cancelPanoramaCapture()
        }
        if (prevMode == CameraMode.AR_LENS && mode != CameraMode.AR_LENS) {
            arHoldRunnable?.let { arHoldHandler.removeCallbacks(it) }
            arHoldRunnable = null
            isArHoldRecording = false
            isArRecordingLocked = false
            arStopOnShutterUp = false
            viewBinding.tvArRecLockBadge.visibility = View.GONE
            viewBinding.tvArRecLockBadge.translationY = 0f
            if (currentArLensName != "none") {
                lastActiveArLensName = currentArLensName
            }
            stopBatchLoop()
            isBatchModeActive = false
            viewBinding.arLensModeDock.btnArBatch.text = "BATCH: OFF"
            viewBinding.arLensModeDock.btnArBatch.setBackgroundResource(R.drawable.bg_cinema_button_inactive)
            viewBinding.arLensModeDock.btnArBatch.setTextColor(Color.parseColor("#94A3B8"))
            viewBinding.arLensModeDock.root.visibility = View.GONE
            viewBinding.arCalibrationDrawer.visibility = View.GONE
            // Preserve activeArFamily when switching out of AR_LENS so returning restores the user's AR mode!
            setArLens("none", showNotice = false, syncFloatingMenu = false)
        }
        cameraMode = mode
        if (prevMode != mode) {
            dismissHudNotice()
        }
        nightExposureStacker.clearDarkEpisodeLock()
        viewBinding.proOverlayView.isNightModeHudActive = (mode == CameraMode.NIGHT || currentShootingEffect == "night_sight")
        isLastActionCreative = false
        // Mode switch is already reflected cleanly by the center carousel pill, shutter icon, and top badge (no overlapping Mode toast)
        viewBinding.proOverlayView.isPanoramaModeActive = (mode == CameraMode.PANORAMA)

        updateModeCarouselSlots(animated = true, slideDirection = slideDirection)

        val isVideoType = mode == CameraMode.VIDEO || mode == CameraMode.SLOW_MO
        viewBinding.recordingTimer.visibility = View.VISIBLE
        viewBinding.zoomControls.visibility = if (mode == CameraMode.AR_LENS) View.GONE else View.VISIBLE
        // Top bar simplicity: Flash always visible; Effects ONLY in Photo/Video modes (hidden in AR); other settings live in right floating bar
        viewBinding.btnFilter.visibility = if (mode == CameraMode.AR_LENS) View.GONE else View.VISIBLE
        viewBinding.layoutBtnGuide.visibility = View.GONE
        viewBinding.layoutBtnTimer.visibility = View.GONE
        viewBinding.btnAutoLoop.visibility = View.GONE
        viewBinding.btnAspectRatio.visibility = View.GONE
        viewBinding.layoutBtnProStudio.visibility = View.GONE
        viewBinding.btnAudioMute.visibility = View.GONE
        viewBinding.arLensModeDock.root.visibility = View.GONE

        if (mode == CameraMode.AR_LENS) {
            viewBinding.effectsStudioDrawer.root.visibility = View.GONE
            viewBinding.proStudioDrawer.root.visibility = View.GONE
            viewBinding.layoutGuideFloatingBar.visibility = View.GONE
            updateCaptureButtonVisual(isRecordingActive = false)
            viewBinding.btnPauseResume.visibility = View.GONE
            viewBinding.recordingDot.setBackgroundResource(R.drawable.bg_green_dot)
            viewBinding.recordingDot.visibility = View.VISIBLE
            viewBinding.tvRecordingStatus.text = "AR LENS"
            viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#E879F9"))
            viewBinding.chronometer.text = "TAP PHOTO · HOLD 0.5S VID"
            viewBinding.chronometer.setTextColor(Color.parseColor("#F0ABFC"))
            // Entering AR LENS from another mode wakes up Face AR if left on Off so swipe & lenses work immediately!
            if (prevMode != CameraMode.AR_LENS && activeArFamily == "off") {
                activeArFamily = "face"
            }
            // Auto-open right floating settings menu ONLY in AR mode when an effect is active
            activeFloatingSettingId = null
            isFloatingMenuOpen = (activeArFamily != "off")
            applyActiveArFamilyState(fromUser = false)
            syncArDockFromCalibration()
            refreshFloatingModeMenu()
        } else if (!isVideoType) {
            updateCaptureButtonVisual(isRecordingActive = false)
            viewBinding.btnPauseResume.visibility = View.GONE
            viewBinding.recordingDot.setBackgroundResource(R.drawable.bg_green_dot)
            viewBinding.recordingDot.visibility = View.VISIBLE
            if (mode == CameraMode.PANORAMA) {
                viewBinding.tvRecordingStatus.text = "PANO"
                viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#38BDF8"))
                viewBinding.chronometer.text = "110° SWEEP"
                viewBinding.chronometer.setTextColor(Color.parseColor("#7DD3FC"))
            } else if (mode == CameraMode.NIGHT) {
                viewBinding.tvRecordingStatus.text = "NIGHT"
                viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#818CF8"))
                viewBinding.chronometer.text = "MULTI-EXP · LOW LIGHT"
                viewBinding.chronometer.setTextColor(Color.parseColor("#A5B4FC"))
            } else if (mode == CameraMode.PORTRAIT) {
                viewBinding.tvRecordingStatus.text = "PORTRAIT"
                viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#FBBF24"))
                viewBinding.chronometer.text = "DEPTH · 4:3"
                viewBinding.chronometer.setTextColor(Color.parseColor("#94A3B8"))
            } else {
                viewBinding.tvRecordingStatus.text = "READY"
                viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#10B981"))
                viewBinding.chronometer.text = "12MP 4:3"
                viewBinding.chronometer.setTextColor(Color.parseColor("#94A3B8"))
            }
            // Non-AR modes keep right floating bar collapsed by default
            activeFloatingSettingId = null
            isFloatingMenuOpen = false
            refreshFloatingModeMenu()
        } else {
            updateCaptureButtonVisual(isRecordingActive = false)
            viewBinding.btnPauseResume.visibility = View.GONE
            viewBinding.recordingDot.setBackgroundResource(R.drawable.bg_green_dot)
            viewBinding.recordingDot.visibility = View.VISIBLE
            viewBinding.tvRecordingStatus.text = "STANDBY"
            viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#10B981"))
            viewBinding.chronometer.text = "00:00:00"
            viewBinding.chronometer.setTextColor(Color.parseColor("#FFFFFF"))
            updateAutoLoopHud()
            // Non-AR modes keep right floating bar collapsed by default
            activeFloatingSettingId = null
            isFloatingMenuOpen = false
            refreshFloatingModeMenu()
        }

        if (!isVideoType && videoOnlyEffectIds.contains(currentShootingEffect)) {
            setShootingEffect("none")
        } else if (isVideoType && photoOnlyEffectIds.contains(currentShootingEffect)) {
            setShootingEffect("none")
        } else {
            updateShootingEffectButtons()
        }

        openGLProcessor?.setNightBoost(mode == CameraMode.NIGHT || currentShootingEffect == "night_sight")
        updateArtStripModeOrder()
        applyLivePreviewColorMatrix()
        bindCameraUseCases()
    }

    private fun centerModeTab(@Suppress("UNUSED_PARAMETER") mode: CameraMode, smooth: Boolean = true) {
        updateModeCarouselSlots(animated = smooth, slideDirection = 0)
    }

    /**
     * Query MediaStore specifically for photos or videos captured by this CameraX Pro app.
     * Restricts queries to the app's folder and files starting with CameraX or timestamp formats.
     */
    private fun getLatestAppMediaUri(): Pair<Uri, String>? {
        val cachedUri = lastCapturedUri
        val cachedMime = lastCapturedMimeType
        if (cachedUri != null && cachedMime != null) {
            return Pair(cachedUri, cachedMime)
        }

        var latestUri: Uri? = null
        var latestMime = "image/jpeg"
        var latestTimestamp = 0L

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE
        )
        // Match files saved in CameraX folders or with CameraX file naming
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%CameraX%", "%CameraX%")
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    val date = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED))
                    val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)) ?: "image/jpeg"
                    latestTimestamp = date
                    latestUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    latestMime = mime
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed querying app images", e)
        }

        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    val date = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED))
                    val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)) ?: "video/mp4"
                    if (date > latestTimestamp || latestUri == null) {
                        latestUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        latestMime = mime
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed querying app videos", e)
        }

        return if (latestUri != null) {
            lastCapturedUri = latestUri
            lastCapturedMimeType = latestMime
            Pair(latestUri!!, latestMime)
        } else null
    }

    private fun updateGalleryThumbnail() {
        val media = getLatestAppMediaUri()
        if (media != null) {
            runOnUiThread {
                try {
                    viewBinding.ivGalleryThumbnail.setImageURI(media.first)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not set thumbnail", e)
                }
            }
        }
    }

    /**
     * Opens ONLY the photos and videos captured by this CameraX Pro application.
     * Prevents dumping random device files, downloads, or external messaging media.
     */
    private fun openAppMediaGallery() {
        val media = getLatestAppMediaUri()
        if (media != null) {
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(media.first, media.second)
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                startActivity(intent)
                return
            } catch (e: Exception) {
                Log.e(TAG, "Could not launch app media viewer", e)
            }
        }

        Toast.makeText(
            this,
            "No CameraX photos or videos captured yet. Capture a photo or video to view in app gallery!",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun navigateNextCameraMode() {
        if (recording != null) return
        val currentIndex = allCameraModes.indexOf(cameraMode)
        val nextIndex = (currentIndex + 1) % allCameraModes.size
        setMode(allCameraModes[nextIndex], slideDirection = 1)
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun navigatePreviousCameraMode() {
        if (recording != null) return
        val currentIndex = allCameraModes.indexOf(cameraMode)
        val prevIndex = if (currentIndex <= 0) allCameraModes.size - 1 else currentIndex - 1
        setMode(allCameraModes[prevIndex], slideDirection = -1)
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun navigateNextGrid() {
        val nextMode = when (viewBinding.proOverlayView.currentGrid) {
            ProOverlayView.GridType.NONE -> ProOverlayView.GridType.RULE_OF_THIRDS
            ProOverlayView.GridType.RULE_OF_THIRDS -> ProOverlayView.GridType.GOLDEN_SPIRAL
            ProOverlayView.GridType.GOLDEN_SPIRAL -> ProOverlayView.GridType.GOLDEN_RATIO_PHI
            ProOverlayView.GridType.GOLDEN_RATIO_PHI -> ProOverlayView.GridType.DIAGONAL_SYMMETRY
            ProOverlayView.GridType.DIAGONAL_SYMMETRY -> ProOverlayView.GridType.CINEMATIC_239
            ProOverlayView.GridType.CINEMATIC_239 -> ProOverlayView.GridType.NONE
        }
        setGridMode(nextMode)
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun navigatePreviousGrid() {
        val prevMode = when (viewBinding.proOverlayView.currentGrid) {
            ProOverlayView.GridType.NONE -> ProOverlayView.GridType.CINEMATIC_239
            ProOverlayView.GridType.RULE_OF_THIRDS -> ProOverlayView.GridType.NONE
            ProOverlayView.GridType.GOLDEN_SPIRAL -> ProOverlayView.GridType.RULE_OF_THIRDS
            ProOverlayView.GridType.GOLDEN_RATIO_PHI -> ProOverlayView.GridType.GOLDEN_SPIRAL
            ProOverlayView.GridType.DIAGONAL_SYMMETRY -> ProOverlayView.GridType.GOLDEN_RATIO_PHI
            ProOverlayView.GridType.CINEMATIC_239 -> ProOverlayView.GridType.DIAGONAL_SYMMETRY
        }
        setGridMode(prevMode)
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun navigateNextArLens() {
        val currentIdx = arLensOrder.indexOf(currentArLensName)
        val safeIdx = if (currentIdx >= 0) currentIdx else 0
        // At 'none' (index 0): swiping left exits AR Lens mode to the next camera mode
        if (safeIdx == 0) {
            navigateNextCameraMode()
            return
        }
        val nextIdx = if (safeIdx + 1 >= arLensOrder.size) 0 else safeIdx + 1
        val targetLens = arLensOrder[nextIdx]
        setArLens(targetLens, showNotice = targetLens != "none")
        if (targetLens == "none") {
            showHudNotice("AR LENS: NONE · Swipe left to exit AR mode")
        }
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun navigatePreviousArLens() {
        val currentIdx = arLensOrder.indexOf(currentArLensName)
        val safeIdx = if (currentIdx >= 0) currentIdx else 0
        // Right swipe steps backward toward 'none'; once already at 'none', exits AR mode to previous camera mode
        if (safeIdx == 0) {
            navigatePreviousCameraMode()
            return
        }
        val prevIdx = (safeIdx - 1).coerceAtLeast(0)
        val targetLens = arLensOrder[prevIdx]
        setArLens(targetLens, showNotice = targetLens != "none")
        if (targetLens == "none") {
            showHudNotice("AR LENS: NONE · Swipe left to exit AR mode")
        }
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun navigateNextEffectOrFilter() {
        when (currentCreativeTarget) {
            CreativeTarget.EFFECTS -> {
                val modeEffects = getModeFilteredShootingEffects()
                val currentIdx = modeEffects.indexOf(currentShootingEffect)
                val safeIdx = if (currentIdx >= 0) currentIdx else 0
                val nextIdx = (safeIdx + 1) % modeEffects.size
                setShootingEffect(modeEffects[nextIdx])
            }
            CreativeTarget.ART -> {
                val artList = getModeOrderedArtEffects()
                val currentIdx = artList.indexOf(currentShootingEffect)
                val safeIdx = if (currentIdx >= 0) currentIdx else 0
                val nextIdx = (safeIdx + 1) % artList.size
                setShootingEffect(artList[nextIdx])
            }
            CreativeTarget.TOON -> {
                val currentIdx = toonStyleOrder.indexOf(currentShootingEffect)
                val safeIdx = if (currentIdx >= 0) currentIdx else 0
                val nextIdx = (safeIdx + 1) % toonStyleOrder.size
                setShootingEffect(toonStyleOrder[nextIdx])
            }
            else -> {
                val currentIdx = filterPresetOrder.indexOf(currentFilterName)
                val safeIdx = if (currentIdx >= 0) currentIdx else 0
                val nextIdx = (safeIdx + 1) % filterPresetOrder.size
                setFilterPreset(filterPresetOrder[nextIdx])
            }
        }
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun navigatePreviousEffectOrFilter() {
        when (currentCreativeTarget) {
            CreativeTarget.EFFECTS -> {
                val modeEffects = getModeFilteredShootingEffects()
                val currentIdx = modeEffects.indexOf(currentShootingEffect)
                val safeIdx = if (currentIdx >= 0) currentIdx else 0
                val prevIdx = if (safeIdx <= 0) modeEffects.size - 1 else safeIdx - 1
                setShootingEffect(modeEffects[prevIdx])
            }
            CreativeTarget.ART -> {
                val artList = getModeOrderedArtEffects()
                val currentIdx = artList.indexOf(currentShootingEffect)
                val safeIdx = if (currentIdx >= 0) currentIdx else 0
                val prevIdx = if (safeIdx <= 0) artList.size - 1 else safeIdx - 1
                setShootingEffect(artList[prevIdx])
            }
            CreativeTarget.TOON -> {
                val currentIdx = toonStyleOrder.indexOf(currentShootingEffect)
                val safeIdx = if (currentIdx >= 0) currentIdx else 0
                val prevIdx = if (safeIdx <= 0) toonStyleOrder.size - 1 else safeIdx - 1
                setShootingEffect(toonStyleOrder[prevIdx])
            }
            else -> {
                val currentIdx = filterPresetOrder.indexOf(currentFilterName)
                val safeIdx = if (currentIdx >= 0) currentIdx else 0
                val prevIdx = if (safeIdx <= 0) filterPresetOrder.size - 1 else safeIdx - 1
                setFilterPreset(filterPresetOrder[prevIdx])
            }
        }
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun applyTextureEffectPreset(index: Int) {
        val modeEffects = getModeFilteredShootingEffects()
        val safeIdx = ((index % modeEffects.size) + modeEffects.size) % modeEffects.size
        setShootingEffect(modeEffects[safeIdx])
    }

    private fun switchCamera() {
        if (recording != null || isPanoramaCapturing || isPanoramaStitching || isNightStacking) return
        motionBlurProcessor.reset()
        panoramaStitcher.reset()
        nightExposureStacker.reset()
        latestMotionMetrics = MotionBlurProcessor.MotionMetrics.IDLE
        lensFacing = if (lensFacing == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        viewBinding.arFaceOverlayView.clearHandLandmarks()
        viewBinding.arFaceOverlayView.clearBodyPose()
        lastStableBodyStance = ""
        stableBodyStanceStartMs = 0L
        stableBodyStanceFired = false
        lastStableHandGesture = ""
        stableHandGestureStartMs = 0L
        stableHandGestureFired = false
        bindCameraUseCases()
    }

    private fun toggleFlashMode() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
    }

    private fun handleTwoFingerArGesture(event: MotionEvent, view: View): Boolean {
        val anyTwoFingerEnabled = twoFingerPinchEnabled || twoFingerRotateEnabled || twoFingerDragEnabled
        if (cameraMode != CameraMode.AR_LENS || currentArLensName == "none" || !anyTwoFingerEnabled) {
            if (isTwoFingerArAdjusting) {
                isTwoFingerArAdjusting = false
                arGesturePointerId1 = MotionEvent.INVALID_POINTER_ID
                arGesturePointerId2 = MotionEvent.INVALID_POINTER_ID
            }
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    arGesturePointerId1 = event.getPointerId(0)
                    arGesturePointerId2 = event.getPointerId(1)
                    val x1 = event.getX(0)
                    val y1 = event.getY(0)
                    val x2 = event.getX(1)
                    val y2 = event.getY(1)
                    arGestureStartMidX = (x1 + x2) * 0.5f
                    arGestureStartMidY = (y1 + y2) * 0.5f
                    arGestureStartDist = Math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat().coerceAtLeast(24f)
                    arGestureStartAngle = Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
                    arGestureOrigOffsetX = calibOffsetX
                    arGestureOrigOffsetY = calibOffsetY
                    arGestureOrigScale = calibScale
                    arGestureOrigRotDeg = calibRotDeg
                    isTwoFingerArAdjusting = true
                    isMultiTouchGesture = true
                    suppressNextTapFocus = true
                    showHudNotice(
                        String.format(
                            Locale.US,
                            "2-FINGER AR · %.2fx · %+d,%+dpx · %+d°",
                            calibScale,
                            (calibOffsetX * 600f).roundToInt(),
                            (calibOffsetY * 600f).roundToInt(),
                            calibRotDeg.roundToInt()
                        )
                    )
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isTwoFingerArAdjusting && event.pointerCount >= 2) {
                    val idx1 = event.findPointerIndex(arGesturePointerId1)
                    val idx2 = event.findPointerIndex(arGesturePointerId2)
                    if (idx1 >= 0 && idx2 >= 0) {
                        val x1 = event.getX(idx1)
                        val y1 = event.getY(idx1)
                        val x2 = event.getX(idx2)
                        val y2 = event.getY(idx2)
                        val midX = (x1 + x2) * 0.5f
                        val midY = (y1 + y2) * 0.5f
                        val dist = Math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat().coerceAtLeast(16f)
                        val angle = Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())

                        val vw = view.width.coerceAtLeast(1).toFloat()
                        val vh = view.height.coerceAtLeast(1).toFloat()

                        // 1. Place adjust (two-finger drag)
                        if (twoFingerDragEnabled) {
                            val deltaNormX = ((midX - arGestureStartMidX) / vw) * twoFingerDragSensitivity
                            val deltaNormY = ((midY - arGestureStartMidY) / vh) * twoFingerDragSensitivity
                            calibOffsetX = (arGestureOrigOffsetX + deltaNormX).coerceIn(-0.25f, 0.25f)
                            calibOffsetY = (arGestureOrigOffsetY + deltaNormY).coerceIn(-0.25f, 0.25f)
                        }

                        // 2. Size adjust (two-finger pinch)
                        if (twoFingerPinchEnabled) {
                            val rawRatio = dist / arGestureStartDist
                            val weightedRatio = (1.0f + (rawRatio - 1.0f) * twoFingerPinchSensitivity).coerceIn(0.25f, 4.0f)
                            calibScale = (arGestureOrigScale * weightedRatio).coerceIn(0.5f, 2.0f)
                        }

                        // 3. 3D Orientation / Tilt adjust (two-finger rotate)
                        if (twoFingerRotateEnabled) {
                            var dRad = angle - arGestureStartAngle
                            while (dRad > Math.PI) dRad -= (2.0 * Math.PI)
                            while (dRad < -Math.PI) dRad += (2.0 * Math.PI)
                            val dirSign = if (invertTwoFingerRotate) -1f else 1f
                            val dDeg = Math.toDegrees(dRad).toFloat() * twoFingerRotateSensitivity * dirSign
                            calibRotDeg = (arGestureOrigRotDeg + dDeg).coerceIn(-45f, 45f)
                        }

                        pushArCalibrationToOverlay()
                        syncArDockFromCalibration()
                        showHudNotice(
                            String.format(
                                Locale.US,
                                "2-FINGER AR · %.2fx · %+d,%+dpx · %+d°",
                                calibScale,
                                (calibOffsetX * 600f).roundToInt(),
                                (calibOffsetY * 600f).roundToInt(),
                                calibRotDeg.roundToInt()
                            )
                        )
                        return true
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isTwoFingerArAdjusting) {
                    val remaining = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.pointerCount - 1 else 0
                    if (remaining < 2) {
                        isTwoFingerArAdjusting = false
                        arGesturePointerId1 = MotionEvent.INVALID_POINTER_ID
                        arGesturePointerId2 = MotionEvent.INVALID_POINTER_ID
                        viewBinding.sbCalibOffsetX.progress = ((calibOffsetX * 600f).roundToInt() + 150).coerceIn(0, 300)
                        viewBinding.sbCalibOffsetY.progress = ((calibOffsetY * 600f).roundToInt() + 150).coerceIn(0, 300)
                        viewBinding.sbCalibScale.progress = (((calibScale - 0.5f) / 1.5f) * 100f).roundToInt().coerceIn(0, 100)
                        viewBinding.sbCalibRotation.progress = (calibRotDeg.roundToInt() + 45).coerceIn(0, 90)
                        syncArDockFromCalibration()
                    }
                    return true
                }
            }
        }
        return isTwoFingerArAdjusting
    }

    private fun animateFocusRing(x: Float, y: Float, colorHex: String, keepVisible: Boolean = false, pulseScale: Float = 1.28f) {
        val ring = viewBinding.focusRing
        val fallbackPx = 64f * resources.displayMetrics.density
        val ringW = if (ring.width > 0) ring.width.toFloat() else fallbackPx
        val ringH = if (ring.height > 0) ring.height.toFloat() else fallbackPx
        ring.animate().cancel()
        ring.x = (x - ringW / 2f).coerceIn(0f, (viewBinding.viewFinder.width - ringW).coerceAtLeast(0f))
        ring.y = (y - ringH / 2f).coerceIn(0f, (viewBinding.viewFinder.height - ringH).coerceAtLeast(0f))
        ring.backgroundTintList = ColorStateList.valueOf(Color.parseColor(colorHex))
        ring.visibility = View.VISIBLE
        ring.alpha = 1.0f
        ring.scaleX = pulseScale
        ring.scaleY = pulseScale
        ring.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .alpha(if (keepVisible) 0.96f else 0.88f)
            .setDuration(200L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (!keepVisible) {
                    ring.animate()
                        .alpha(0f)
                        .setStartDelay(950L)
                        .setDuration(240L)
                        .withEndAction { ring.visibility = View.GONE }
                        .start()
                }
            }
            .start()
    }

    private fun triggerTapToFocusAndClearBlur(
        x: Float,
        y: Float,
        lockAfAe: Boolean = false,
        isAutoClear: Boolean = false,
        silent: Boolean = false
    ) {
        val control = cameraControl ?: return
        val vfW = viewBinding.viewFinder.width.toFloat().coerceAtLeast(1f)
        val vfH = viewBinding.viewFinder.height.toFloat().coerceAtLeast(1f)
        val clampedX = x.coerceIn(0f, vfW)
        val clampedY = y.coerceIn(0f, vfH)
        lastFocusNormX = (clampedX / vfW).coerceIn(0.12f, 0.88f)
        lastFocusNormY = (clampedY / vfH).coerceIn(0.12f, 0.88f)
        isAfAeLocked = lockAfAe
        consecutiveBlurFrames = 0
        lastAutoBlurClearMs = SystemClock.uptimeMillis()

        // Cancel any stale/blurry focus lock immediately before issuing a fresh optical sweep
        try {
            control.cancelFocusAndMetering()
        } catch (_: Exception) {
        }

        val factory = viewBinding.viewFinder.meteringPointFactory
        // Tight AF spot (0.14) for crisp subject sharpness + wider AE/AWB spot (0.28) to prevent blown highlights
        val afPoint = factory.createPoint(clampedX, clampedY, 0.14f)
        val aePoint = factory.createPoint(clampedX, clampedY, 0.28f)
        val actionBuilder = FocusMeteringAction.Builder(afPoint, FocusMeteringAction.FLAG_AF)
            .addPoint(aePoint, FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB)

        if (lockAfAe) {
            actionBuilder.disableAutoCancel()
        } else {
            // Auto-return to Continuous Auto-Focus (CAF) after 3s so camera stays sharp when moving
            actionBuilder.setAutoCancelDuration(3L, TimeUnit.SECONDS)
        }

        if (!silent) {
            val initialColor = when {
                lockAfAe -> "#F59E0B"
                isAutoClear -> "#38BDF8"
                else -> "#FACC15"
            }
            animateFocusRing(clampedX, clampedY, initialColor, keepVisible = lockAfAe)
            if (!isAutoClear) {
                playCameraCue(ToneGenerator.TONE_PROP_ACK, 40)
            }
        }

        val future = control.startFocusAndMetering(actionBuilder.build())
        future.addListener({
            try {
                val result = future.get()
                if (result.isFocusSuccessful) {
                    if (!silent) {
                        val confirmColor = if (lockAfAe) "#F59E0B" else "#34D399"
                        animateFocusRing(clampedX, clampedY, confirmColor, keepVisible = lockAfAe, pulseScale = 1.06f)
                        if (lockAfAe) {
                            window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            showHudNotice("AF/AE Locked • Tap anywhere to unlock")
                        } else if (isAutoClear && cameraMode != CameraMode.PANORAMA) {
                            showHudNotice("Auto Blur Cleared • Sharp")
                        }
                    }
                } else if (!isAutoClear && !lockAfAe) {
                    // Fallback wide blur-clearing sweep so lens never stays blurry on low-contrast taps
                    try {
                        control.cancelFocusAndMetering()
                        val widePoint = factory.createPoint(clampedX, clampedY, 0.36f)
                        val fallbackAction = FocusMeteringAction.Builder(
                            widePoint,
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                        ).setAutoCancelDuration(3L, TimeUnit.SECONDS).build()
                        control.startFocusAndMetering(fallbackAction)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
                // Cancelled by a newer tap focus request
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun triggerAutoBlurClearSweep(silent: Boolean = false) {
        if (!isAutoBlurClearEnabled || isAfAeLocked) return
        val vfW = viewBinding.viewFinder.width.toFloat()
        val vfH = viewBinding.viewFinder.height.toFloat()
        if (vfW <= 1f || vfH <= 1f) return
        val targetX = vfW * lastFocusNormX.coerceIn(0.2f, 0.8f)
        val targetY = vfH * lastFocusNormY.coerceIn(0.2f, 0.8f)
        // Run silently during AR Lens / Snap Magic so floating focus boxes & toast banners don't clutter the AR scene
        val effectiveSilent = silent || cameraMode == CameraMode.AR_LENS
        triggerTapToFocusAndClearBlur(targetX, targetY, lockAfAe = false, isAutoClear = true, silent = effectiveSilent)
    }

    private fun setupViewfinderGestures() {
        val scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (cameraMode == CameraMode.AR_LENS && currentArLensName != "none") {
                    return true
                }
                val currentZoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                cameraControl?.setZoomRatio(currentZoomRatio * detector.scaleFactor)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (cameraMode != CameraMode.AR_LENS || currentArLensName == "none") {
                    rollingPeakSharpness = 0f
                    consecutiveBlurFrames = 0
                    if (isAutoBlurClearEnabled && !isAfAeLocked) {
                        viewBinding.viewFinder.postDelayed({ triggerAutoBlurClearSweep(silent = true) }, 180L)
                    }
                }
            }
        })

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (suppressNextTapFocus) {
                    suppressNextTapFocus = false
                    return true
                }
                if (cameraMode == CameraMode.PANORAMA && !isPanoramaCapturing && viewBinding.proOverlayView.isPointInPanoramaRail(e.x, e.y)) {
                    cyclePanoramaSweepDirection()
                    return true
                }
                if (viewBinding.proOverlayView.currentGrid == ProOverlayView.GridType.GOLDEN_SPIRAL) {
                    val nextRot = (viewBinding.proOverlayView.spiralOrientation + 1) % 4
                    setSpiralOrientation(nextRot)
                    window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }

                // Phase 4 World AR: Tap-to-Place 3D world anchor onto detected floor/wall surface
                if (viewBinding.arFaceOverlayView.worldArEnabled) {
                    val placed = viewBinding.arFaceOverlayView.placeWorldAnchorAtScreenPoint(e.x, e.y)
                    if (placed != null) {
                        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        playCameraCue(ToneGenerator.TONE_PROP_ACK, 40)
                        showHudNotice("World Anchor: ${placed.objectType.name.replace("_", " ")} (${String.format(Locale.US, "%.2fm", placed.distanceM)})")
                        return true
                    }
                }

                
                triggerTapToFocusAndClearBlur(e.x, e.y, lockAfAe = false, isAutoClear = false, silent = false)
                
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (suppressNextTapFocus) return
                
                triggerTapToFocusAndClearBlur(e.x, e.y, lockAfAe = true, isAutoClear = false, silent = false)
                
            }
        })

        viewBinding.proOverlayView.setOnTouchListener { view, event ->
            if (handleTwoFingerArGesture(event, view)) {
                return@setOnTouchListener true
            }
            if (viewBinding.arFaceOverlayView.handleArTextTouch(event)) {
                return@setOnTouchListener true
            }
            scaleGestureDetector.onTouchEvent(event)
            if (!scaleGestureDetector.isInProgress) {
                gestureDetector.onTouchEvent(event)
            }
            if (event.action == MotionEvent.ACTION_UP) {
                view.performClick()
            }
            true
        }
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 6. Real-Time Filter & Image Adjustment Controls
     */
    private fun setupFilterAndArControls() {
        setFilterPreset("normal", showNotice = false)
        setArLens("none", showNotice = false)
        setShootingEffect("none", showNotice = false)

        // Live Adjustments: Brightness, Contrast, Saturation
        viewBinding.proStudioDrawer.sbBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val pct = progress - 100
                    filterManager.setBrightnessPercent(pct)
                    viewBinding.proOverlayView.liveBrightnessPercent = pct
                    openGLProcessor?.setAdjustments(pct, viewBinding.proOverlayView.liveContrastPercent, viewBinding.proOverlayView.liveSaturationPercent)
                    applyLivePreviewColorMatrix()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        viewBinding.proStudioDrawer.sbContrast.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val pct = progress - 100
                    filterManager.setContrastPercent(pct)
                    viewBinding.proOverlayView.liveContrastPercent = pct
                    openGLProcessor?.setAdjustments(viewBinding.proOverlayView.liveBrightnessPercent, pct, viewBinding.proOverlayView.liveSaturationPercent)
                    applyLivePreviewColorMatrix()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        viewBinding.proStudioDrawer.sbSaturation.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val pct = progress - 100
                    filterManager.setSaturationPercent(pct)
                    viewBinding.proOverlayView.liveSaturationPercent = pct
                    openGLProcessor?.setAdjustments(viewBinding.proOverlayView.liveBrightnessPercent, viewBinding.proOverlayView.liveContrastPercent, pct)
                    applyLivePreviewColorMatrix()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L
    private var isSwipeConsumed = false
    private var isMultiTouchGesture = false
    private var downInModeCarousel = false
    private var downInIgnoredControl = false
    private var suppressNextTapFocus = false

    private fun isPointInView(view: View, rawX: Float, rawY: Float): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val rect = Rect()
        view.getGlobalVisibleRect(rect)
        return rect.contains(rawX.toInt(), rawY.toInt())
    }

    private fun routeHorizontalSwipe(isNext: Boolean) {
        if (downInModeCarousel) {
            if (isNext) navigateNextCameraMode() else navigatePreviousCameraMode()
            return
        }
        if (viewBinding.layoutGuideFloatingBar.visibility == View.VISIBLE) {
            if (isNext) navigateNextGrid() else navigatePreviousGrid()
            return
        }
        if (viewBinding.effectsStudioDrawer.root.visibility == View.VISIBLE) {
            if (isNext) navigateNextEffectOrFilter() else navigatePreviousEffectOrFilter()
            return
        }
        if (cameraMode == CameraMode.AR_LENS) {
            if (isNext) navigateNextArLens() else navigatePreviousArLens()
            return
        }
        if (isNext) navigateNextCameraMode() else navigatePreviousCameraMode()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.rawX
                touchDownY = ev.rawY
                touchDownTime = SystemClock.uptimeMillis()
                isSwipeConsumed = false
                suppressNextTapFocus = false
                isMultiTouchGesture = ev.pointerCount > 1

                downInModeCarousel = isPointInView(viewBinding.modeScrollView, ev.rawX, ev.rawY)
                downInIgnoredControl =
                    isPointInView(viewBinding.pipCardContainer, ev.rawX, ev.rawY) ||
                    isPointInView(viewBinding.proModeBar, ev.rawX, ev.rawY) ||
                    isPointInView(viewBinding.bottomControls, ev.rawX, ev.rawY) ||
                    isPointInView(viewBinding.zoomControls, ev.rawX, ev.rawY) ||
                    isPointInView(viewBinding.proStudioDrawer.root, ev.rawX, ev.rawY) ||
                    isPointInView(viewBinding.arLensModeDock.root, ev.rawX, ev.rawY) ||
                    isPointInView(viewBinding.arCalibrationDrawer, ev.rawX, ev.rawY) ||
                    isPointInView(viewBinding.floatingModeMenu.floatingRightMenuWrapper, ev.rawX, ev.rawY) ||
                    (viewBinding.floatingModeMenu.layoutFloatingParamBar.visibility == View.VISIBLE &&
                        isPointInView(viewBinding.floatingModeMenu.layoutFloatingParamBar, ev.rawX, ev.rawY)) ||
                    (viewBinding.floatingModeMenu.layoutArSwipeStrip.visibility == View.VISIBLE &&
                        isPointInView(viewBinding.floatingModeMenu.layoutArSwipeStrip, ev.rawX, ev.rawY)) ||
                    (viewBinding.effectsStudioDrawer.root.visibility == View.VISIBLE && (
                        isPointInView(viewBinding.effectsStudioDrawer.layoutEffectsHeader, ev.rawX, ev.rawY) ||
                        isPointInView(viewBinding.effectsStudioDrawer.layoutEffectsSubItems, ev.rawX, ev.rawY)
                    )) ||
                    (viewBinding.layoutGuideFloatingBar.visibility == View.VISIBLE && (
                        isPointInView(viewBinding.scrollGuideChips, ev.rawX, ev.rawY) ||
                        isPointInView(viewBinding.btnCloseGuideBar, ev.rawX, ev.rawY)
                    ))
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                isMultiTouchGesture = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount > 1) {
                    isMultiTouchGesture = true
                }
                if (!isSwipeConsumed && !isMultiTouchGesture && !downInIgnoredControl && recording == null) {
                    val dx = ev.rawX - touchDownX
                    val dy = ev.rawY - touchDownY

                    if (Math.abs(dx) > 42 && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                        val topToolbarRect = Rect()
                        viewBinding.proModeBar.getGlobalVisibleRect(topToolbarRect)
                        val bottomControlsRect = Rect()
                        viewBinding.bottomControls.getGlobalVisibleRect(bottomControlsRect)

                        val minSwipeY = if (topToolbarRect.bottom > 0) topToolbarRect.bottom.toFloat() else 0f
                        val maxSwipeY = if (bottomControlsRect.top > 0) bottomControlsRect.top.toFloat() else Float.MAX_VALUE
                        val inSwipeZone = downInModeCarousel || (ev.rawY >= minSwipeY && ev.rawY <= maxSwipeY)

                        if (inSwipeZone) {
                            isSwipeConsumed = true
                            val cancelEv = MotionEvent.obtain(ev)
                            cancelEv.action = MotionEvent.ACTION_CANCEL
                            super.dispatchTouchEvent(cancelEv)
                            cancelEv.recycle()

                            routeHorizontalSwipe(isNext = dx < 0)
                            return true
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isSwipeConsumed) {
                    return true
                }
                val diffX = ev.rawX - touchDownX
                val diffY = ev.rawY - touchDownY
                val duration = SystemClock.uptimeMillis() - touchDownTime

                if (!isMultiTouchGesture && !downInIgnoredControl && recording == null &&
                    Math.abs(diffX) > 30 && Math.abs(diffX) > Math.abs(diffY) * 1.2f && duration < 450
                ) {
                    val topToolbarRect = Rect()
                    viewBinding.proModeBar.getGlobalVisibleRect(topToolbarRect)
                    val bottomControlsRect = Rect()
                    viewBinding.bottomControls.getGlobalVisibleRect(bottomControlsRect)

                    val minSwipeY = if (topToolbarRect.bottom > 0) topToolbarRect.bottom.toFloat() else 0f
                    val maxSwipeY = if (bottomControlsRect.top > 0) bottomControlsRect.top.toFloat() else Float.MAX_VALUE
                    val inSwipeZone = downInModeCarousel || (ev.rawY >= minSwipeY && ev.rawY <= maxSwipeY)

                    if (inSwipeZone) {
                        isSwipeConsumed = true
                        routeHorizontalSwipe(isNext = diffX < 0)
                        return true
                    }
                }

                // Clean tap outside open drawers -> dismiss drawer without blocking shutter/record bar
                if (!isMultiTouchGesture && Math.abs(diffX) < 22 && Math.abs(diffY) < 22 &&
                    !isPointInView(viewBinding.bottomControls, ev.rawX, ev.rawY)
                ) {
                    var closedDrawer = false
                    if (viewBinding.proStudioDrawer.root.visibility == View.VISIBLE &&
                        !isPointInView(viewBinding.proStudioDrawer.root, ev.rawX, ev.rawY) &&
                        !isPointInView(viewBinding.layoutBtnProStudio, ev.rawX, ev.rawY)
                    ) {
                        viewBinding.proStudioDrawer.root.visibility = View.GONE
                        closedDrawer = true
                    }
                    if (viewBinding.layoutGuideFloatingBar.visibility == View.VISIBLE &&
                        !isPointInView(viewBinding.layoutGuideFloatingBar, ev.rawX, ev.rawY) &&
                        !isPointInView(viewBinding.layoutBtnGuide, ev.rawX, ev.rawY)
                    ) {
                        viewBinding.layoutGuideFloatingBar.visibility = View.GONE
                        closedDrawer = true
                    }
                    if (viewBinding.effectsStudioDrawer.root.visibility == View.VISIBLE &&
                        !isPointInView(viewBinding.effectsStudioDrawer.layoutEffectsHeader, ev.rawX, ev.rawY) &&
                        !isPointInView(viewBinding.effectsStudioDrawer.layoutEffectsSubItems, ev.rawX, ev.rawY) &&
                        !isPointInView(viewBinding.btnFilter, ev.rawX, ev.rawY) &&
                        !isPointInView(viewBinding.modeScrollView, ev.rawX, ev.rawY)
                    ) {
                        viewBinding.effectsStudioDrawer.root.visibility = View.GONE
                        isLastActionCreative = false
                        closedDrawer = true
                    }
                    if (viewBinding.arCalibrationDrawer.visibility == View.VISIBLE &&
                        !isPointInView(viewBinding.arCalibrationDrawer, ev.rawX, ev.rawY) &&
                        !isPointInView(viewBinding.arLensModeDock.btnArCalibrate, ev.rawX, ev.rawY)
                    ) {
                        viewBinding.arCalibrationDrawer.visibility = View.GONE
                        viewBinding.arLensModeDock.btnArCalibrate.setBackgroundResource(R.drawable.bg_cinema_button_inactive)
                        viewBinding.arLensModeDock.btnArCalibrate.setTextColor(Color.parseColor("#38BDF8"))
                        closedDrawer = true
                    }
                    if (closedDrawer) {
                        suppressNextTapFocus = true
                        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun ensureMediaActionSound(): MediaActionSound? {
        if (mediaActionSound == null) {
            try {
                mediaActionSound = MediaActionSound().apply {
                    load(MediaActionSound.SHUTTER_CLICK)
                    load(MediaActionSound.START_VIDEO_RECORDING)
                    load(MediaActionSound.STOP_VIDEO_RECORDING)
                }
            } catch (_: Throwable) {
                mediaActionSound = null
            }
        }
        return mediaActionSound
    }

    private fun playShutterClickSound() {
        if (isAudioMuted) return
        try {
            val mas = ensureMediaActionSound()
            if (mas != null) {
                mas.play(MediaActionSound.SHUTTER_CLICK)
            } else {
                playCameraCue(ToneGenerator.TONE_PROP_BEEP, 45)
            }
        } catch (_: Throwable) {
            playCameraCue(ToneGenerator.TONE_PROP_BEEP, 45)
        }
    }

    private fun playRecordStartSound() {
        if (isAudioMuted) return
        try {
            val mas = ensureMediaActionSound()
            if (mas != null) {
                mas.play(MediaActionSound.START_VIDEO_RECORDING)
            } else {
                playCameraCue(ToneGenerator.TONE_PROP_BEEP2, 110)
            }
        } catch (_: Throwable) {
            playCameraCue(ToneGenerator.TONE_PROP_BEEP2, 110)
        }
    }

    private fun playRecordStopSound() {
        if (isAudioMuted) return
        try {
            val mas = ensureMediaActionSound()
            if (mas != null) {
                mas.play(MediaActionSound.STOP_VIDEO_RECORDING)
            } else {
                playCameraCue(ToneGenerator.TONE_PROP_BEEP, 130)
            }
        } catch (_: Throwable) {
            playCameraCue(ToneGenerator.TONE_PROP_BEEP, 130)
        }
    }

    private fun playCameraCue(toneType: Int, durationMs: Int = 80) {
        if (isAudioMuted) return
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 68)
            }
            toneGenerator?.startTone(toneType, durationMs)
        } catch (_: Throwable) {
            // Ignore audio hardware init failures on silent/headless devices
        }
    }

    private fun resolveVideoQuality(): Quality = when {
        selectedVideoQualityLabel.startsWith("4K") -> Quality.UHD
        selectedVideoQualityLabel.startsWith("FHD") -> Quality.FHD
        selectedVideoQualityLabel.startsWith("SD") -> Quality.SD
        else -> Quality.HD
    }

    private fun resolveFontTypeface(def: CustomFontDef?): Typeface {
        val styleInt = if (def?.italic == true) Typeface.BOLD_ITALIC else Typeface.BOLD
        return when (def?.category?.lowercase(Locale.ROOT)) {
            "cinematic", "editorial" -> Typeface.create(Typeface.SERIF, styleInt)
            "scifi", "tactical" -> Typeface.create(Typeface.SANS_SERIF, styleInt)
            else -> Typeface.create(Typeface.MONOSPACE, styleInt)
        }
    }

    private fun applyActiveFontPreset() {
        val def = injectedFonts.getOrNull(activeFontIndex)
        val baseTf = resolveFontTypeface(def)
        val label = def?.name?.uppercase(Locale.ROOT) ?: "CYBER ORBITRON"
        val txtHex = def?.colorHex ?: "#38BDF8"
        val glwHex = def?.glowHex ?: "#0284C7"
        viewBinding.proStudioDrawer.tvWatermarkFontStatus.text = "Font: $label"
        viewBinding.proOverlayView.setWatermarkStyle(baseTf, txtHex, glwHex)
        if (!def?.family.isNullOrBlank()) {
            cameraFontHelper?.loadDownloadableFont(
                CameraFontHelper.FontConfig(
                    name = def!!.name,
                    familyQuery = "name=${def.family}&weight=700",
                    textColor = Color.parseColor(txtHex),
                    glowColor = Color.parseColor(glwHex)
                )
            ) { loadedTf ->
                runOnUiThread {
                    viewBinding.proOverlayView.setWatermarkStyle(loadedTf, txtHex, glwHex)
                }
            }
        }
    }

    private fun createStudioChipView(label: String): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (36 * density).toInt()
            )
            lp.marginStart = (6 * density).toInt()
            layoutParams = lp
            minWidth = (86 * density).toInt()
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            gravity = android.view.Gravity.CENTER
            text = label.uppercase(Locale.ROOT)
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setBackgroundResource(R.drawable.bg_cinema_button_inactive)
        }
    }

    private fun addCustomLensChipIfMissing(cl: CustomLensDef) {
        if (!arLensOrder.contains(cl.id)) {
            arLensOrder.add(cl.id)
            val label = if (cl.style.equals("glb_3d", ignoreCase = true)) "3D · ${cl.name}" else cl.name
            val chip = createStudioChipView(label)
            chip.setOnClickListener { setArLens(cl.id) }
            val container = viewBinding.arLensModeDock.layoutArModeLensesContainer
            val importIdx = container.indexOfChild(viewBinding.arLensModeDock.btnArImportGlb)
            if (importIdx >= 0) {
                container.addView(chip, importIdx)
            } else {
                container.addView(chip)
            }
            dynamicLensChips.add(chip to cl.id)
        }
    }

    private fun setupDynamicConfigChips() {
        loadPersistedDeviceGlbLenses()
        val builtInFilterAliases = setOf(
            "filter_lut_rec709", "filter_lut_flat_log", "filter_lut_blockbuster_teal_orange",
            "filter_lut_bleach_bypass", "filter_lut_anamorphic_cyan", "filter_lut_matte_cinema",
            "filter_lut_midnight_neon", "filter_lut_warm_documentary", "filter_lut_noir_cinema",
            "filter_golden_nostalgia", "filter_velvia_landscape", "filter_nordic_slate",
            "filter_arctic_mist", "filter_classic_mono", "filter_high_contrast_noir",
            "filter_ilford_grain", "filter_silver_gelatin", "filter_antique_sepia",
            "filter_portra_400", "filter_fuji_400h", "filter_teal_orange", "filter_noir_contrast",
            "filter_sunset_warmth", "filter_matrix_code", "filter_pastel_dream",
            "filter_nordic_mood", "filter_super8_retro"
        )
        for (cf in injectedCustomFilters) {
            val idLower = cf.id.lowercase(Locale.ROOT)
            if (!filterPresetOrder.contains(cf.id) && !builtInFilterAliases.contains(idLower)) {
                filterPresetOrder.add(cf.id)
                val chip = createStudioChipView(cf.name)
                chip.setOnClickListener { setFilterPreset(cf.id) }
                viewBinding.effectsStudioDrawer.layoutFiltersContainer.addView(chip)
                dynamicFilterChips.add(chip to cf.id)
            }
        }
        for (cl in injectedCustomLenses) {
            addCustomLensChipIfMissing(cl)
        }
    }

    private fun launchGlbLensPicker() {
        try {
            importGlbLensLauncher.launch(arrayOf("model/gltf-binary", "application/octet-stream", "*/*"))
        } catch (e: Exception) {
            Log.e(TAG, "Unable to launch .glb file picker", e)
            showHudNotice("Unable to open file picker", isWarning = true)
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        var name = "Custom_3D_Lens.glb"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val value = cursor.getString(idx)
                        if (!value.isNullOrBlank()) name = value
                    }
                }
            }
        } catch (_: Exception) {}
        return name
    }

    private fun importGlbLensFromUri(uri: Uri) {
        try {
            val rawName = resolveDisplayName(uri)
            val cleanBase = rawName.replace(Regex("\\.glb$", RegexOption.IGNORE_CASE), "")
                .replace('_', ' ')
                .replace(Regex("[^a-zA-Z0-9 -]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifEmpty { "3D Model" }
                .take(24)

            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to read selected file")

            if (bytes.size < 20 || bytes[0] != 0x67.toByte() || bytes[1] != 0x6C.toByte() ||
                bytes[2] != 0x54.toByte() || bytes[3] != 0x46.toByte()
            ) {
                showHudNotice("Selected file is not a valid glTF 2.0 binary (.glb)", isWarning = true)
                return
            }

            val modelsDir = File(filesDir, "ar_models").apply { if (!exists()) mkdirs() }
            val lensId = "glb_${System.currentTimeMillis()}"
            val outFile = File(modelsDir, "$lensId.glb")
            FileOutputStream(outFile).use { it.write(bytes) }

            val anchorChoices = arrayOf("Forehead / Hat / Crown", "Eyes / Glasses / Visor", "Nose & Cheeks / Mask", "Full Face / Helmet")
            val anchorKeys = arrayOf("forehead", "eyes", "nose_cheeks", "full_face")

            AlertDialog.Builder(this)
                .setTitle("Anchor for '$cleanBase' (.glb)")
                .setItems(anchorChoices) { _, which ->
                    val anchor = anchorKeys[which.coerceIn(anchorKeys.indices)]
                    val newDef = CustomLensDef(
                        id = lensId,
                        name = cleanBase,
                        anchor = anchor,
                        style = "glb_3d",
                        primaryColor = "#22D3EE",
                        accentColor = "#F59E0B",
                        glbAssetPath = "",
                        glbFilePath = outFile.absolutePath,
                        modelScale = 0.85f,
                        modelOffsetY = 0f,
                        modelRotationY = 0f,
                        modelRotationX = 0f,
                        autoSpin = false
                    )
                    injectedCustomLenses.add(newDef)
                    savePersistedDeviceGlbLenses()
                    addCustomLensChipIfMissing(newDef)
                    setArLens(lensId)
                }
                .setNegativeButton("Cancel") { _, _ ->
                    try { outFile.delete() } catch (_: Exception) {}
                }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import .glb AR lens", e)
            showHudNotice("GLB import failed: ${e.localizedMessage ?: "I/O error"}", isWarning = true)
        }
    }

    private fun savePersistedDeviceGlbLenses() {
        try {
            val prefs = getSharedPreferences("camerax_ar_glb_lenses", Context.MODE_PRIVATE)
            val arr = JSONArray()
            for (cl in injectedCustomLenses) {
                if (cl.glbFilePath.isNotEmpty() && File(cl.glbFilePath).exists()) {
                    val obj = JSONObject().apply {
                        put("id", cl.id)
                        put("name", cl.name)
                        put("anchor", cl.anchor)
                        put("glbFilePath", cl.glbFilePath)
                        put("modelScale", cl.modelScale.toDouble())
                        put("modelOffsetY", cl.modelOffsetY.toDouble())
                        put("modelRotationY", cl.modelRotationY.toDouble())
                        put("modelRotationX", cl.modelRotationX.toDouble())
                        put("autoSpin", cl.autoSpin)
                    }
                    arr.put(obj)
                }
            }
            prefs.edit().putString("saved_glb_lenses_json", arr.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to persist device GLB lenses: ${e.message}")
        }
    }

    private fun loadPersistedDeviceGlbLenses() {
        try {
            val prefs = getSharedPreferences("camerax_ar_glb_lenses", Context.MODE_PRIVATE)
            val raw = prefs.getString("saved_glb_lenses_json", null) ?: return
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "")
                val filePath = obj.optString("glbFilePath", "")
                if (id.isEmpty() || filePath.isEmpty() || !File(filePath).exists()) continue
                if (injectedCustomLenses.any { it.id == id }) continue
                injectedCustomLenses.add(
                    CustomLensDef(
                        id = id,
                        name = obj.optString("name", "3D GLB"),
                        anchor = obj.optString("anchor", "forehead"),
                        style = "glb_3d",
                        primaryColor = "#22D3EE",
                        accentColor = "#F59E0B",
                        glbAssetPath = "",
                        glbFilePath = filePath,
                        modelScale = obj.optDouble("modelScale", 0.85).toFloat(),
                        modelOffsetY = obj.optDouble("modelOffsetY", 0.0).toFloat(),
                        modelRotationY = obj.optDouble("modelRotationY", 0.0).toFloat(),
                        modelRotationX = obj.optDouble("modelRotationX", 0.0).toFloat(),
                        autoSpin = obj.optBoolean("autoSpin", false)
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to restore persisted device GLB lenses: ${e.message}")
        }
    }

    private fun setupProEnhancementControls() {
        // Video Quality Pill Cycle (4K 60FPS -> FHD 60FPS -> FHD 30FPS -> HD 30FPS)
        viewBinding.tvVideoQualityPill.setOnClickListener {
            if (recording != null) {
                showHudNotice("Stop recording before switching video quality", isWarning = true)
                return@setOnClickListener
            }
            selectedVideoQualityLabel = when (selectedVideoQualityLabel) {
                "4K 60FPS" -> "FHD 60FPS"
                "FHD 60FPS" -> "FHD 30FPS"
                "FHD 30FPS" -> "HD 30FPS"
                else -> "4K 60FPS"
            }
            viewBinding.tvVideoQualityPill.text = selectedVideoQualityLabel
            bindCameraUseCases()
            showHudNotice("Video Quality: $selectedVideoQualityLabel")
        }

        // Histogram Mode Toggle (LUMA <-> RGB)
        viewBinding.proStudioDrawer.btnHistogramMode.setOnClickListener {
            val nextRgb = !viewBinding.proOverlayView.histogramRgbMode
            viewBinding.proOverlayView.histogramRgbMode = nextRgb
            viewBinding.proStudioDrawer.btnHistogramMode.text = if (nextRgb) "RGB" else "LUMA"
            viewBinding.proOverlayView.invalidate()
            showHudNotice(if (nextRgb) "Histogram: RGB Channels" else "Histogram: Luminance")
        }

        // Focus Peaking Toggle
        viewBinding.proStudioDrawer.switchFocusPeaking.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.showFocusPeaking = isChecked
            viewBinding.proStudioDrawer.tvFocusPeakingStatus.text = if (isChecked) "Active · Cyan edge contour highlight" else "Focus Peaking: Inactive"
            updateActiveInstrumentsCount()
            showHudNotice("Focus Peaking ${if (isChecked) "ON" else "OFF"}")
        }

        // Burst Buffer Limit Cycle (15 -> 30 -> 50 -> 100)
        viewBinding.proStudioDrawer.btnBurstLimit.setOnClickListener {
            maxBurstBuffer = when (maxBurstBuffer) {
                15 -> 30
                30 -> 50
                50 -> 100
                else -> 15
            }
            remainingBurstBuffer = maxBurstBuffer
            viewBinding.proStudioDrawer.btnBurstLimit.text = "${maxBurstBuffer} SHOTS"
            viewBinding.proStudioDrawer.tvBurstLimitStatus.text = "Buffer limit: ${maxBurstBuffer} RAW/JPEG frames"
            showHudNotice("Burst Limit: ${maxBurstBuffer} Shots")
        }

        // Typography & Watermark Banner
        viewBinding.proStudioDrawer.switchWatermark.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.showWatermark = isChecked
            updateActiveInstrumentsCount()
            viewBinding.proOverlayView.invalidate()
            showHudNotice("Watermark ${if (isChecked) "ON" else "OFF"}")
        }
        viewBinding.proStudioDrawer.btnCycleFont.setOnClickListener {
            if (injectedFonts.isNotEmpty()) {
                activeFontIndex = (activeFontIndex + 1) % injectedFonts.size
                applyActiveFontPreset()
                showHudNotice("Font: ${injectedFonts[activeFontIndex].name}")
            }
        }

        // Creative Effect Intensity Slider (0% - 100%)
        viewBinding.effectsStudioDrawer.sbEffectIntensity.progress = (activeEffectIntensity * 100f).toInt().coerceIn(0, 100)
        viewBinding.effectsStudioDrawer.tvEffectIntensityVal.text = "${viewBinding.effectsStudioDrawer.sbEffectIntensity.progress}%"
        viewBinding.effectsStudioDrawer.sbEffectIntensity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    activeEffectIntensity = (progress / 100f).coerceIn(0f, 1f)
                    viewBinding.effectsStudioDrawer.tvEffectIntensityVal.text = "$progress%"
                    openGLProcessor?.setEffectIntensity(currentShootingEffect, progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun pushArCalibrationToOverlay() {
        viewBinding.arFaceOverlayView.setCalibration(
            offsetX = calibOffsetX,
            offsetY = calibOffsetY,
            scale = calibScale,
            rotationDeg = calibRotDeg,
            eyeSpacing = calibEyeSpace,
            forehead = calibForehead,
            chin = calibChin,
            debug = calibDebug
        )
    }

    private fun resetArCalibrationValues() {
        calibOffsetX = 0f; calibOffsetY = 0f; calibScale = 1.0f; calibRotDeg = 0f
        calibEyeSpace = 1.0f; calibForehead = 0f; calibChin = 0f; calibDebug = false
        comicHalftoneDensity = 1.0f; comicHalftoneDotSize = 1.0f; comicLabelDepth = 1.0f
        viewBinding.sbCalibOffsetX.progress = 150
        viewBinding.sbCalibOffsetY.progress = 150
        viewBinding.sbCalibScale.progress = 50
        viewBinding.sbCalibRotation.progress = 45
        viewBinding.sbCalibEyeSpace.progress = 40
        viewBinding.sbCalibForehead.progress = 80
        viewBinding.sbCalibChin.progress = 80
        viewBinding.switchArMeshDebug.isChecked = false
        viewBinding.sbHalftoneDensity.progress = 33
        viewBinding.sbHalftoneDotSize.progress = 47
        viewBinding.sbComicLabelDepth.progress = 40
        viewBinding.arFaceOverlayView.comicLabelDepth = 1.0f
        filterManager.setComicHalftoneParams(1.0f, 1.0f)
        openGLProcessor?.setComicHalftoneParams(1.0f, 1.0f)
        pushArCalibrationToOverlay()
        syncArDockFromCalibration()
        showHudNotice("AR Calibration Reset")
    }

    private fun toggleArCalibrationDrawer() {
        val show = viewBinding.arCalibrationDrawer.visibility != View.VISIBLE
        viewBinding.arCalibrationDrawer.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            viewBinding.proStudioDrawer.root.visibility = View.GONE
            viewBinding.layoutGuideFloatingBar.visibility = View.GONE
        }
        viewBinding.arLensModeDock.btnArCalibrate.setBackgroundResource(
            if (show) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive
        )
        viewBinding.arLensModeDock.btnArCalibrate.setTextColor(Color.parseColor(if (show) "#0F172A" else "#38BDF8"))
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private val faceLipSwatches = listOf("#DC264B", "#C84B66", "#B91C1C", "#7C3AED", "#B45309", "#EC4899")
    private val faceIrisSwatches = listOf("#00F0FF", "#10B981", "#F59E0B", "#8B5CF6", "#38BDF8", "#F43F5E")
    private var faceLipSwatchIdx = 0
    private var faceIrisSwatchIdx = 0

    private fun loadCustomFaceMaskFromUri(uri: Uri) {
        try {
            val rawBmp = contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: run {
                showHudNotice("Could not decode mask image", isWarning = true)
                return
            }
            val maxDim = 768
            val scaled = if (rawBmp.width > maxDim || rawBmp.height > maxDim) {
                val ratio = minOf(maxDim.toFloat() / rawBmp.width, maxDim.toFloat() / rawBmp.height)
                val out = Bitmap.createScaledBitmap(
                    rawBmp,
                    (rawBmp.width * ratio).toInt().coerceAtLeast(64),
                    (rawBmp.height * ratio).toInt().coerceAtLeast(64),
                    true
                )
                if (out != rawBmp) rawBmp.recycle()
                out
            } else {
                rawBmp
            }
            viewBinding.arFaceOverlayView.setCustomFaceMaskBitmap(scaled)
            updateFaceBeautyPresetUi()
            showHudNotice("Loaded Custom PNG Face Mask")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load custom PNG face mask", e)
            showHudNotice("Mask load error: ${e.localizedMessage ?: "I/O"}", isWarning = true)
        }
    }

    private fun showSaveArLookDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Look name (e.g. Cyber Glam)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
            setPadding(36, 24, 36, 24)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Save Custom AR Look")
            .setMessage("Saves current AR lens, Face AR makeup, mask & calibration to this device.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty().ifEmpty {
                    "AR Look ${System.currentTimeMillis() % 1000}"
                }
                val prefs = getSharedPreferences("camerax_saved_ar_effects_v1", Context.MODE_PRIVATE)
                val existing = try { org.json.JSONArray(prefs.getString("looks", "[]") ?: "[]") } catch (_: Exception) { org.json.JSONArray() }
                val entry = JSONObject().apply {
                    put("name", name)
                    put("savedAt", System.currentTimeMillis())
                    put("payload", buildArCalibrationJsonString())
                }
                val updated = org.json.JSONArray()
                updated.put(entry)
                for (i in 0 until minOf(existing.length(), 24)) {
                    val prevObj = existing.optJSONObject(i)
                    if (prevObj != null && prevObj.optString("name") != name) {
                        updated.put(prevObj)
                    }
                }
                prefs.edit().putString("looks", updated.toString()).apply()
                showHudNotice("Saved AR Look: $name")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLoadArLookDialog() {
        val prefs = getSharedPreferences("camerax_saved_ar_effects_v1", Context.MODE_PRIVATE)
        val arr = try { org.json.JSONArray(prefs.getString("looks", "[]") ?: "[]") } catch (_: Exception) { org.json.JSONArray() }
        if (arr.length() == 0) {
            showHudNotice("No saved looks yet — tap SAVE LOOK first")
            return
        }
        val names = Array(arr.length()) { idx -> arr.optJSONObject(idx)?.optString("name", "Look #${idx + 1}") ?: "Look" }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Saved On-Device AR Looks (${arr.length()})")
            .setItems(names) { _, which ->
                val item = arr.optJSONObject(which) ?: return@setItems
                val payload = item.optString("payload", "")
                if (payload.isNotEmpty() && applyArCalibrationJsonString(payload)) {
                    showHudNotice("Applied: ${names[which]}")
                }
            }
            .setNeutralButton("Clear All") { _, _ ->
                prefs.edit().remove("looks").apply()
                showHudNotice("Cleared Saved AR Looks")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun updateFaceBeautyPresetUi() {
        val ov = viewBinding.arFaceOverlayView
        val activeId = if (ov.faceBeautyEnabled) ov.faceBeautyPresetId else "off"
        val pairs = listOf(
            viewBinding.btnFaceArOff to "off",
            viewBinding.btnFaceArGlow to "natural_glow",
            viewBinding.btnFaceArGlam to "red_carpet",
            viewBinding.btnFaceArCyber to "cyber_neon",
            viewBinding.btnFaceArKitsune to "kitsune_spirit",
            viewBinding.btnFaceArGold to "golden_circuit"
        )
        for ((btn, id) in pairs) {
            val active = activeId == id
            btn.setBackgroundResource(if (active) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
            btn.setTextColor(Color.parseColor(if (active) "#0F172A" else "#E2E8F0"))
        }
        viewBinding.sbFaceBeautySmooth.progress = (ov.skinSmoothing * 100f).roundToInt().coerceIn(0, 100)
        viewBinding.sbFaceLipOpacity.progress = (ov.lipOpacity * 100f).roundToInt().coerceIn(0, 100)
        viewBinding.sbFaceEyeOpacity.progress = (maxOf(ov.eyeShadowOpacity, ov.eyelinerOpacity) * 100f).roundToInt().coerceIn(0, 100)
        viewBinding.sbFaceIrisOpacity.progress = (ov.irisOpacity * 100f).roundToInt().coerceIn(0, 100)
        viewBinding.sbFaceMaskOpacity.progress = (ov.faceMaskOpacity * 100f).roundToInt().coerceIn(0, 100)
        viewBinding.switchFaceOcclusion.isChecked = ov.faceOcclusionEnabled
    }

    private fun updateBubbleStyleButtons(activeStyle: ARFaceOverlayView.ComicSpeechBubbleStyle) {
        val btns = listOf(
            viewBinding.btnBubbleSpeech to ARFaceOverlayView.ComicSpeechBubbleStyle.SPEECH,
            viewBinding.btnBubbleThought to ARFaceOverlayView.ComicSpeechBubbleStyle.THOUGHT,
            viewBinding.btnBubbleShout to ARFaceOverlayView.ComicSpeechBubbleStyle.SHOUT
        )
        for ((btn, st) in btns) {
            val active = st == activeStyle
            btn.setBackgroundResource(if (active) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
            btn.setTextColor(Color.parseColor(if (active) "#0F172A" else "#94A3B8"))
        }
    }

    private fun applyComicPaletteIndex(idx: Int) {
        val p = comicPalettePresets[idx % comicPalettePresets.size]
        viewBinding.arFaceOverlayView.setComicOnomatopoeiaPalette(
            p.powTop, p.powBottom, p.powExtrude, p.powBurst,
            p.bamTop, p.bamBottom, p.bamExtrude, p.bamBurst,
            p.zapTop, p.zapBottom, p.zapExtrude, p.zapBurst
        )
        viewBinding.btnComicPalette.text = "PALETTE: ${p.name}"
    }

    private fun colorIntToHex(colorInt: Int): String {
        return String.format(Locale.US, "#%06X", 0xFFFFFF and colorInt)
    }

    private fun parseValidHexOrFallback(candidate: String?, fallback: String): String {
        if (candidate.isNullOrBlank()) return fallback
        val trimmed = candidate.trim()
        return try {
            Color.parseColor(trimmed)
            trimmed
        } catch (_: Exception) {
            fallback
        }
    }

    private fun buildArCalibrationJsonString(): String {
        val pxX = (calibOffsetX * 600f).roundToInt().coerceIn(-150, 150)
        val pxY = (calibOffsetY * 600f).roundToInt().coerceIn(-150, 150)
        val pxForehead = (calibForehead * 800f).roundToInt().coerceIn(-80, 80)
        val pxChin = (calibChin * 800f).roundToInt().coerceIn(-80, 80)

        val overlay = viewBinding.arFaceOverlayView
        val bubbleStyleStr = when (overlay.speechBubbleStyle) {
            ARFaceOverlayView.ComicSpeechBubbleStyle.THOUGHT -> "thought"
            ARFaceOverlayView.ComicSpeechBubbleStyle.SHOUT -> "shout"
            else -> "speech"
        }

        val root = JSONObject().apply {
            put("schemaVersion", 1)
            put("targetLens", currentArLensName)
            put("pixelOffsets", JSONObject().apply {
                put("x", pxX)
                put("y", pxY)
                put("forehead", pxForehead)
                put("chin", pxChin)
            })
            put("normalizedOffsets", JSONObject().apply {
                put("x", (pxX / 720.0))
                put("y", (pxY / 960.0))
                put("forehead", (pxForehead / 960.0))
                put("chin", (pxChin / 960.0))
            })
            put("transform", JSONObject().apply {
                put("scale", calibScale.toDouble())
                put("rotationDeg", calibRotDeg.toDouble())
                put("eyeSpacingFactor", calibEyeSpace.toDouble())
                put("depthZ", 1.0)
            })
            put("interactionSettings", JSONObject().apply {
                put("twoFingerPinchEnabled", twoFingerPinchEnabled)
                put("twoFingerPinchSensitivity", twoFingerPinchSensitivity.toDouble())
                put("twoFingerRotateEnabled", twoFingerRotateEnabled)
                put("twoFingerRotateSensitivity", twoFingerRotateSensitivity.toDouble())
                put("twoFingerDragEnabled", twoFingerDragEnabled)
                put("twoFingerDragSensitivity", twoFingerDragSensitivity.toDouble())
                put("invertRotateDirection", invertTwoFingerRotate)
            })
            put("faceBeauty", JSONObject().apply {
                put("enabled", overlay.faceBeautyEnabled)
                put("presetId", overlay.faceBeautyPresetId)
                put("skinSmoothing", overlay.skinSmoothing.toDouble())
                put("faceOcclusionEnabled", overlay.faceOcclusionEnabled)
                put("lipColor", overlay.lipColorHex)
                put("lipOpacity", overlay.lipOpacity.toDouble())
                put("lipGloss", overlay.lipGloss.toDouble())
                put("eyeShadowColor", overlay.eyeShadowColorHex)
                put("eyeShadowOpacity", overlay.eyeShadowOpacity.toDouble())
                put("eyelinerOpacity", overlay.eyelinerOpacity.toDouble())
                put("eyebrowColor", overlay.eyebrowColorHex)
                put("eyebrowOpacity", overlay.eyebrowOpacity.toDouble())
                put("blushColor", overlay.blushColorHex)
                put("blushOpacity", overlay.blushOpacity.toDouble())
                put("irisColor", overlay.irisColorHex)
                put("irisOpacity", overlay.irisOpacity.toDouble())
                put("maskPattern", overlay.faceMaskPattern)
                put("maskOpacity", overlay.faceMaskOpacity.toDouble())
                put("maskPrimaryColor", overlay.faceMaskPrimaryHex)
                put("maskAccentColor", overlay.faceMaskAccentHex)
            })
            put("debugMeshActive", calibDebug)
            put("comicOnomatopoeia", overlay.enableComicOnomatopoeia)
            put("comicSpeechBubble", JSONObject().apply {
                put("enabled", overlay.enableComicSpeechBubble)
                put("style", bubbleStyleStr)
                put("text", overlay.speechBubbleText)
            })
            put("comicLabelDepth", comicLabelDepth.toDouble())
            put("cartoonShaderHalftone", JSONObject().apply {
                put("halftoneDensity", comicHalftoneDensity.toDouble())
                put("halftoneDotSize", comicHalftoneDotSize.toDouble())
            })
            put("comicOnomatopoeiaPalette", JSONObject().apply {
                put("presetId", comicPalettePresets[comicPaletteIndex % comicPalettePresets.size].id)
                put("pow", JSONObject().apply {
                    put("topColor", colorIntToHex(overlay.powPalette.topColor))
                    put("bottomColor", colorIntToHex(overlay.powPalette.bottomColor))
                    put("extrudeColor", colorIntToHex(overlay.powPalette.extrudeColor))
                    put("burstColor", colorIntToHex(overlay.powPalette.burstColor))
                })
                put("bam", JSONObject().apply {
                    put("topColor", colorIntToHex(overlay.bamPalette.topColor))
                    put("bottomColor", colorIntToHex(overlay.bamPalette.bottomColor))
                    put("extrudeColor", colorIntToHex(overlay.bamPalette.extrudeColor))
                    put("burstColor", colorIntToHex(overlay.bamPalette.burstColor))
                })
                put("zap", JSONObject().apply {
                    put("topColor", colorIntToHex(overlay.zapPalette.topColor))
                    put("bottomColor", colorIntToHex(overlay.zapPalette.bottomColor))
                    put("extrudeColor", colorIntToHex(overlay.zapPalette.extrudeColor))
                    put("burstColor", colorIntToHex(overlay.zapPalette.burstColor))
                })
            })
            put("comicOnomatopoeiaSounds", JSONObject().apply {
                put("enabled", true)
                put("pow", JSONObject().apply { put("presetId", comicSfxPow); put("volume", 0.85) })
                put("bam", JSONObject().apply { put("presetId", comicSfxBam); put("volume", 0.85) })
                put("zap", JSONObject().apply { put("presetId", comicSfxZap); put("volume", 0.85) })
            })
        }
        return root.toString(2)
    }

    private fun applyArCalibrationJsonString(rawJson: String): Boolean {
        val data = JSONObject(rawJson)
        val pixelOffsets = data.optJSONObject("pixelOffsets")
        val normalizedOffsets = data.optJSONObject("normalizedOffsets")
        val transform = data.optJSONObject("transform")
        val halftoneObj = data.optJSONObject("cartoonShaderHalftone")
        val speechObj = data.optJSONObject("comicSpeechBubble")
        val paletteObj = data.optJSONObject("comicOnomatopoeiaPalette")
        val soundsObj = data.optJSONObject("comicOnomatopoeiaSounds")

        val interactionObj = data.optJSONObject("interactionSettings")
        val faceBeautyObj = data.optJSONObject("faceBeauty")

        val hasKnownFields = pixelOffsets != null ||
            normalizedOffsets != null ||
            transform != null ||
            interactionObj != null ||
            faceBeautyObj != null ||
            halftoneObj != null ||
            paletteObj != null ||
            data.has("offsetX") ||
            data.has("offsetY") ||
            data.has("scale") ||
            data.has("rotationDeg") ||
            data.has("eyeSpacingFactor") ||
            data.has("foreheadOffset") ||
            data.has("chinOffset") ||
            data.has("debugMeshActive") ||
            data.has("showMeshDebug")
        if (!hasKnownFields) return false

        if (faceBeautyObj != null) {
            val ov = viewBinding.arFaceOverlayView
            ov.faceBeautyEnabled = faceBeautyObj.optBoolean("enabled", ov.faceBeautyEnabled)
            ov.faceBeautyPresetId = faceBeautyObj.optString("presetId", ov.faceBeautyPresetId)
            ov.skinSmoothing = faceBeautyObj.optDouble("skinSmoothing", ov.skinSmoothing.toDouble()).toFloat().coerceIn(0f, 1f)
            ov.faceOcclusionEnabled = faceBeautyObj.optBoolean("faceOcclusionEnabled", ov.faceOcclusionEnabled)
            ov.lipColorHex = parseValidHexOrFallback(faceBeautyObj.optString("lipColor"), ov.lipColorHex)
            ov.lipOpacity = faceBeautyObj.optDouble("lipOpacity", ov.lipOpacity.toDouble()).toFloat().coerceIn(0f, 1f)
            ov.lipGloss = faceBeautyObj.optDouble("lipGloss", ov.lipGloss.toDouble()).toFloat().coerceIn(0f, 1f)
            ov.eyeShadowColorHex = parseValidHexOrFallback(faceBeautyObj.optString("eyeShadowColor"), ov.eyeShadowColorHex)
            ov.eyeShadowOpacity = faceBeautyObj.optDouble("eyeShadowOpacity", ov.eyeShadowOpacity.toDouble()).toFloat().coerceIn(0f, 1f)
            ov.eyelinerOpacity = faceBeautyObj.optDouble("eyelinerOpacity", ov.eyelinerOpacity.toDouble()).toFloat().coerceIn(0f, 1f)
            ov.eyebrowColorHex = parseValidHexOrFallback(faceBeautyObj.optString("eyebrowColor"), ov.eyebrowColorHex)
            ov.eyebrowOpacity = faceBeautyObj.optDouble("eyebrowOpacity", ov.eyebrowOpacity.toDouble()).toFloat().coerceIn(0f, 1f)
            ov.blushColorHex = parseValidHexOrFallback(faceBeautyObj.optString("blushColor"), ov.blushColorHex)
            ov.blushOpacity = faceBeautyObj.optDouble("blushOpacity", ov.blushOpacity.toDouble()).toFloat().coerceIn(0f, 1f)
            ov.irisColorHex = parseValidHexOrFallback(faceBeautyObj.optString("irisColor"), ov.irisColorHex)
            ov.irisOpacity = faceBeautyObj.optDouble("irisOpacity", ov.irisOpacity.toDouble()).toFloat().coerceIn(0f, 1f)
            ov.faceMaskPattern = faceBeautyObj.optString("maskPattern", ov.faceMaskPattern)
            ov.faceMaskOpacity = faceBeautyObj.optDouble("maskOpacity", ov.faceMaskOpacity.toDouble()).toFloat().coerceIn(0f, 1f)
            ov.faceMaskPrimaryHex = parseValidHexOrFallback(faceBeautyObj.optString("maskPrimaryColor"), ov.faceMaskPrimaryHex)
            ov.faceMaskAccentHex = parseValidHexOrFallback(faceBeautyObj.optString("maskAccentColor"), ov.faceMaskAccentHex)
            updateFaceBeautyPresetUi()
        }

        if (interactionObj != null) {
            twoFingerPinchEnabled = interactionObj.optBoolean("twoFingerPinchEnabled", twoFingerPinchEnabled)
            twoFingerPinchSensitivity = interactionObj
                .optDouble("twoFingerPinchSensitivity", twoFingerPinchSensitivity.toDouble())
                .toFloat()
                .coerceIn(0.25f, 2.5f)
            twoFingerRotateEnabled = interactionObj.optBoolean("twoFingerRotateEnabled", twoFingerRotateEnabled)
            twoFingerRotateSensitivity = interactionObj
                .optDouble("twoFingerRotateSensitivity", twoFingerRotateSensitivity.toDouble())
                .toFloat()
                .coerceIn(0.25f, 2.5f)
            twoFingerDragEnabled = interactionObj.optBoolean("twoFingerDragEnabled", twoFingerDragEnabled)
            twoFingerDragSensitivity = interactionObj
                .optDouble("twoFingerDragSensitivity", twoFingerDragSensitivity.toDouble())
                .toFloat()
                .coerceIn(0.25f, 2.5f)
            invertTwoFingerRotate = interactionObj.optBoolean("invertRotateDirection", invertTwoFingerRotate)
        }

        fun readOffsetPx(
            pxKey: String,
            normKey: String,
            flatKey: String,
            refPx: Float,
            minPx: Int,
            maxPx: Int,
            fallbackPx: Int
        ): Int {
            return when {
                pixelOffsets != null && pixelOffsets.has(pxKey) ->
                    pixelOffsets.optDouble(pxKey, fallbackPx.toDouble()).roundToInt().coerceIn(minPx, maxPx)
                normalizedOffsets != null && normalizedOffsets.has(normKey) ->
                    (normalizedOffsets.optDouble(normKey, 0.0) * refPx).roundToInt().coerceIn(minPx, maxPx)
                data.has(flatKey) ->
                    data.optDouble(flatKey, fallbackPx.toDouble()).roundToInt().coerceIn(minPx, maxPx)
                else -> fallbackPx
            }
        }

        val pxX = readOffsetPx("x", "x", "offsetX", 720f, -150, 150, (calibOffsetX * 600f).roundToInt())
        val pxY = readOffsetPx("y", "y", "offsetY", 960f, -150, 150, (calibOffsetY * 600f).roundToInt())
        val pxForehead = readOffsetPx("forehead", "forehead", "foreheadOffset", 960f, -80, 80, (calibForehead * 800f).roundToInt())
        val pxChin = readOffsetPx("chin", "chin", "chinOffset", 960f, -80, 80, (calibChin * 800f).roundToInt())

        calibOffsetX = pxX / 600f
        calibOffsetY = pxY / 600f
        calibForehead = pxForehead / 800f
        calibChin = pxChin / 800f

        val rawScale = when {
            transform != null && transform.has("scale") -> transform.optDouble("scale", calibScale.toDouble()).toFloat()
            data.has("scale") -> data.optDouble("scale", calibScale.toDouble()).toFloat()
            else -> calibScale
        }
        calibScale = rawScale.coerceIn(0.5f, 2.0f)

        val rawRot = when {
            transform != null && transform.has("rotationDeg") -> transform.optDouble("rotationDeg", calibRotDeg.toDouble()).toFloat()
            data.has("rotationDeg") -> data.optDouble("rotationDeg", calibRotDeg.toDouble()).toFloat()
            else -> calibRotDeg
        }
        calibRotDeg = rawRot.coerceIn(-45f, 45f)

        val rawEye = when {
            transform != null && transform.has("eyeSpacingFactor") -> transform.optDouble("eyeSpacingFactor", calibEyeSpace.toDouble()).toFloat()
            data.has("eyeSpacingFactor") -> data.optDouble("eyeSpacingFactor", calibEyeSpace.toDouble()).toFloat()
            else -> calibEyeSpace
        }
        calibEyeSpace = rawEye.coerceIn(0.6f, 1.6f)

        calibDebug = when {
            data.has("debugMeshActive") -> data.optBoolean("debugMeshActive", calibDebug)
            data.has("showMeshDebug") -> data.optBoolean("showMeshDebug", calibDebug)
            else -> calibDebug
        }

        if (data.has("comicOnomatopoeia")) {
            viewBinding.arFaceOverlayView.enableComicOnomatopoeia = data.optBoolean("comicOnomatopoeia", false)
        }

        if (speechObj != null) {
            val enabled = speechObj.optBoolean("enabled", viewBinding.arFaceOverlayView.enableComicSpeechBubble)
            val style = when (speechObj.optString("style", "speech").lowercase(Locale.ROOT)) {
                "thought" -> ARFaceOverlayView.ComicSpeechBubbleStyle.THOUGHT
                "shout" -> ARFaceOverlayView.ComicSpeechBubbleStyle.SHOUT
                else -> ARFaceOverlayView.ComicSpeechBubbleStyle.SPEECH
            }
            val text = speechObj.optString("text", viewBinding.arFaceOverlayView.speechBubbleText).take(48)
            viewBinding.arFaceOverlayView.speechBubbleStyle = style
            viewBinding.arFaceOverlayView.speechBubbleText = if (text.isNotBlank()) text else "HERO MODE!"
            viewBinding.switchComicSpeechBubble.isChecked = enabled
            updateBubbleStyleButtons(style)
            viewBinding.btnCycleBubblePhrase.text = "TEXT: ${viewBinding.arFaceOverlayView.speechBubbleText}"
        }

        if (data.has("comicLabelDepth")) {
            comicLabelDepth = data.optDouble("comicLabelDepth", comicLabelDepth.toDouble()).toFloat().coerceIn(0.0f, 2.5f)
            viewBinding.arFaceOverlayView.comicLabelDepth = comicLabelDepth
        }

        val rawHalftoneDensity = when {
            halftoneObj != null && halftoneObj.has("halftoneDensity") -> halftoneObj.optDouble("halftoneDensity", comicHalftoneDensity.toDouble()).toFloat()
            data.has("halftoneDensity") -> data.optDouble("halftoneDensity", comicHalftoneDensity.toDouble()).toFloat()
            else -> comicHalftoneDensity
        }
        val rawHalftoneDotSize = when {
            halftoneObj != null && halftoneObj.has("halftoneDotSize") -> halftoneObj.optDouble("halftoneDotSize", comicHalftoneDotSize.toDouble()).toFloat()
            data.has("halftoneDotSize") -> data.optDouble("halftoneDotSize", comicHalftoneDotSize.toDouble()).toFloat()
            else -> comicHalftoneDotSize
        }
        comicHalftoneDensity = rawHalftoneDensity.coerceIn(0.4f, 2.2f)
        comicHalftoneDotSize = rawHalftoneDotSize.coerceIn(0.3f, 1.8f)

        if (paletteObj != null) {
            val overlay = viewBinding.arFaceOverlayView
            val powObj = paletteObj.optJSONObject("pow")
            val bamObj = paletteObj.optJSONObject("bam")
            val zapObj = paletteObj.optJSONObject("zap")
            overlay.setComicOnomatopoeiaPalette(
                parseValidHexOrFallback(powObj?.optString("topColor"), colorIntToHex(overlay.powPalette.topColor)),
                parseValidHexOrFallback(powObj?.optString("bottomColor"), colorIntToHex(overlay.powPalette.bottomColor)),
                parseValidHexOrFallback(powObj?.optString("extrudeColor"), colorIntToHex(overlay.powPalette.extrudeColor)),
                parseValidHexOrFallback(powObj?.optString("burstColor"), colorIntToHex(overlay.powPalette.burstColor)),
                parseValidHexOrFallback(bamObj?.optString("topColor"), colorIntToHex(overlay.bamPalette.topColor)),
                parseValidHexOrFallback(bamObj?.optString("bottomColor"), colorIntToHex(overlay.bamPalette.bottomColor)),
                parseValidHexOrFallback(bamObj?.optString("extrudeColor"), colorIntToHex(overlay.bamPalette.extrudeColor)),
                parseValidHexOrFallback(bamObj?.optString("burstColor"), colorIntToHex(overlay.bamPalette.burstColor)),
                parseValidHexOrFallback(zapObj?.optString("topColor"), colorIntToHex(overlay.zapPalette.topColor)),
                parseValidHexOrFallback(zapObj?.optString("bottomColor"), colorIntToHex(overlay.zapPalette.bottomColor)),
                parseValidHexOrFallback(zapObj?.optString("extrudeColor"), colorIntToHex(overlay.zapPalette.extrudeColor)),
                parseValidHexOrFallback(zapObj?.optString("burstColor"), colorIntToHex(overlay.zapPalette.burstColor))
            )
            val presetId = paletteObj.optString("presetId", "")
            val matchedIdx = comicPalettePresets.indexOfFirst { it.id == presetId }
            if (matchedIdx >= 0) {
                comicPaletteIndex = matchedIdx
                viewBinding.btnComicPalette.text = "PALETTE: ${comicPalettePresets[matchedIdx].name}"
            } else {
                viewBinding.btnComicPalette.text = "PALETTE: IMPORTED"
            }
        }

        if (soundsObj != null) {
            val powSfx = soundsObj.optJSONObject("pow")?.optString("presetId", comicSfxPow)
            val bamSfx = soundsObj.optJSONObject("bam")?.optString("presetId", comicSfxBam)
            val zapSfx = soundsObj.optJSONObject("zap")?.optString("presetId", comicSfxZap)
            if (powSfx in comicSfxChoices) comicSfxPow = powSfx!!
            if (bamSfx in comicSfxChoices) comicSfxBam = bamSfx!!
            if (zapSfx in comicSfxChoices) comicSfxZap = zapSfx!!
        }

        // Sync UI SeekBars & Switches to imported values
        viewBinding.sbCalibOffsetX.progress = (pxX + 150).coerceIn(0, 300)
        viewBinding.sbCalibOffsetY.progress = (pxY + 150).coerceIn(0, 300)
        viewBinding.sbCalibScale.progress = (((calibScale - 0.5f) / 1.5f) * 100f).roundToInt().coerceIn(0, 100)
        viewBinding.sbCalibRotation.progress = (calibRotDeg.roundToInt() + 45).coerceIn(0, 90)
        viewBinding.sbCalibEyeSpace.progress = ((calibEyeSpace - 0.6f) * 100f).roundToInt().coerceIn(0, 100)
        viewBinding.sbCalibForehead.progress = (pxForehead + 80).coerceIn(0, 160)
        viewBinding.sbCalibChin.progress = (pxChin + 80).coerceIn(0, 160)
        viewBinding.switchArMeshDebug.isChecked = calibDebug
        viewBinding.sbHalftoneDensity.progress = (((comicHalftoneDensity - 0.4f) / 1.8f) * 100f).roundToInt().coerceIn(0, 100)
        viewBinding.sbHalftoneDotSize.progress = (((comicHalftoneDotSize - 0.3f) / 1.5f) * 100f).roundToInt().coerceIn(0, 100)
        viewBinding.sbComicLabelDepth.progress = ((comicLabelDepth / 2.5f) * 100f).roundToInt().coerceIn(0, 100)

        filterManager.setComicHalftoneParams(comicHalftoneDensity, comicHalftoneDotSize)
        openGLProcessor?.setComicHalftoneParams(comicHalftoneDensity, comicHalftoneDotSize)
        pushArCalibrationToOverlay()
        syncArDockFromCalibration()

        val targetLens = data.optString("targetLens", "").trim()
        val knownBuiltIn = setOf("none", "mesh_contour", "cyberpunk_visor", "golden_crown", "scifi_hud", "comic_frame")
        if (targetLens.isNotEmpty() && (targetLens in knownBuiltIn || injectedCustomLenses.any { it.id == targetLens })) {
            setArLens(targetLens, showNotice = false)
        }
        return true
    }

    private fun setupArCalibrationControls() {
        viewBinding.btnCloseArCalibDrawer.setOnClickListener {
            viewBinding.arCalibrationDrawer.visibility = View.GONE
            viewBinding.arLensModeDock.btnArCalibrate.setBackgroundResource(R.drawable.bg_cinema_button_inactive)
            viewBinding.arLensModeDock.btnArCalibrate.setTextColor(Color.parseColor("#38BDF8"))
        }
        viewBinding.btnArCalibReset.setOnClickListener { resetArCalibrationValues() }
        viewBinding.btnArCalibExportJson.setOnClickListener {
            val safeLens = currentArLensName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            exportArCalibrationJsonLauncher.launch("ar-calibration-$safeLens.json")
        }
        viewBinding.btnArCalibImportJson.setOnClickListener {
            importArCalibrationJsonLauncher.launch(arrayOf("application/json", "text/plain"))
        }

        // Phase 1: Advanced Face AR Quick Preset Bar, Skin Smoothing & 3D Occlusion
        val faceBeautyButtons = listOf(
            viewBinding.btnFaceArOff to "off",
            viewBinding.btnFaceArGlow to "natural_glow",
            viewBinding.btnFaceArGlam to "red_carpet",
            viewBinding.btnFaceArCyber to "cyber_neon",
            viewBinding.btnFaceArKitsune to "kitsune_spirit",
            viewBinding.btnFaceArGold to "golden_circuit"
        )
        for ((btn, presetId) in faceBeautyButtons) {
            btn.setOnClickListener {
                viewBinding.arFaceOverlayView.applyFaceBeautyPreset(presetId)
                updateFaceBeautyPresetUi()
                if (viewBinding.arFaceOverlayView.faceBeautyEnabled) {
                    showHudNotice("Face AR: ${presetId.replace('_', ' ').uppercase(Locale.US)}")
                } else {
                    showHudNotice("Face AR: OFF")
                }
            }
        }
        viewBinding.sbFaceBeautySmooth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = progress.coerceIn(0, 100)
                if (fromUser) {
                    viewBinding.arFaceOverlayView.skinSmoothing = pct / 100f
                    if (pct > 0 && !viewBinding.arFaceOverlayView.faceBeautyEnabled) {
                        viewBinding.arFaceOverlayView.faceBeautyEnabled = true
                        updateFaceBeautyPresetUi()
                    }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        viewBinding.sbFaceLipOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = progress.coerceIn(0, 100) / 100f
                val ov = viewBinding.arFaceOverlayView
                ov.lipOpacity = v
                if (v > 0.02f) { ov.faceBeautyEnabled = true; ov.faceBeautyPresetId = "custom" }
                ov.invalidate()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        viewBinding.sbFaceEyeOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = progress.coerceIn(0, 100) / 100f
                val ov = viewBinding.arFaceOverlayView
                ov.eyeShadowOpacity = v * 0.85f
                ov.eyelinerOpacity = v
                if (v > 0.02f) { ov.faceBeautyEnabled = true; ov.faceBeautyPresetId = "custom" }
                ov.invalidate()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        viewBinding.sbFaceIrisOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = progress.coerceIn(0, 100) / 100f
                val ov = viewBinding.arFaceOverlayView
                ov.irisOpacity = v
                if (v > 0.02f) { ov.faceBeautyEnabled = true; ov.faceBeautyPresetId = "custom" }
                ov.invalidate()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        viewBinding.sbFaceMaskOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = progress.coerceIn(0, 100) / 100f
                val ov = viewBinding.arFaceOverlayView
                ov.faceMaskOpacity = v
                if (v > 0.02f && ov.faceMaskPattern == "none") {
                    ov.faceMaskPattern = "cyber_circuit"
                    ov.faceBeautyEnabled = true
                }
                ov.invalidate()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        viewBinding.btnFaceLipColor.setOnClickListener {
            faceLipSwatchIdx = (faceLipSwatchIdx + 1) % faceLipSwatches.size
            val hex = faceLipSwatches[faceLipSwatchIdx]
            val ov = viewBinding.arFaceOverlayView
            ov.lipColorHex = hex
            if (ov.lipOpacity < 0.35f) ov.lipOpacity = 0.62f
            ov.faceBeautyEnabled = true
            ov.faceBeautyPresetId = "custom"
            ov.invalidate()
            updateFaceBeautyPresetUi()
            showHudNotice("Lip Shade: $hex")
        }
        viewBinding.btnFaceIrisColor.setOnClickListener {
            faceIrisSwatchIdx = (faceIrisSwatchIdx + 1) % faceIrisSwatches.size
            val hex = faceIrisSwatches[faceIrisSwatchIdx]
            val ov = viewBinding.arFaceOverlayView
            ov.irisColorHex = hex
            if (ov.irisOpacity < 0.45f) ov.irisOpacity = 0.72f
            ov.faceBeautyEnabled = true
            ov.faceBeautyPresetId = "custom"
            ov.invalidate()
            updateFaceBeautyPresetUi()
            showHudNotice("Iris Lens: $hex")
        }
        viewBinding.btnFaceImportMask.setOnClickListener {
            importFaceMaskLauncher.launch(arrayOf("image/png", "image/webp", "image/*"))
        }
        viewBinding.btnFaceCompare.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    viewBinding.arFaceOverlayView.isComparingBeforeAfter = true
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    viewBinding.arFaceOverlayView.isComparingBeforeAfter = false
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
        viewBinding.btnArSaveEffect.setOnClickListener { showSaveArLookDialog() }
        viewBinding.btnArLoadEffect.setOnClickListener { showLoadArLookDialog() }
        viewBinding.switchFaceOcclusion.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.arFaceOverlayView.faceOcclusionEnabled = isChecked
        }
        updateFaceBeautyPresetUi()

        // Phase 2: Hand AR Controls (21-Point Landmarks, Rings, Bracelets/Holo Watch, 3D Props & Gestures)
        val updateHandArControlsUi = {
            val ov = viewBinding.arFaceOverlayView
            viewBinding.switchHandAr.isChecked = ov.handArEnabled
            viewBinding.switchHandSkeleton.isChecked = ov.handShowSkeleton
            viewBinding.switchHandGestureTrigger.isChecked = isHandGestureTriggerEnabled
            val handBtns = listOf(
                viewBinding.btnHandArOff to "off",
                viewBinding.btnHandArJewelry to "luxury_jewelry",
                viewBinding.btnHandArGauntlet to "cyber_gauntlet",
                viewBinding.btnHandArOrb to "arcane_orb",
                viewBinding.btnHandArFlame to "pyro_wand"
            )
            for ((btn, pid) in handBtns) {
                val active = (pid == "off" && !ov.handArEnabled) || (ov.handArEnabled && ov.handPresetId == pid)
                btn.setBackgroundResource(if (active) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
                btn.setTextColor(Color.parseColor(if (active) "#0F172A" else "#CBD5E1"))
            }
            viewBinding.btnHandRingCycle.text = "RING: ${ov.handRingStyle.name.take(5)}"
            viewBinding.btnHandBraceletCycle.text = "CUFF: ${ov.handBraceletStyle.name.take(5)}"
            viewBinding.btnHandPropCycle.text = "3D: ${ov.handPropType.name.take(6)}"
            viewBinding.btnHandAnchorCycle.text = "PT: ${ov.handPropAnchor.name.take(5)}"
        }

        viewBinding.switchHandAr.setOnCheckedChangeListener { _, isChecked ->
            val ov = viewBinding.arFaceOverlayView
            if (isChecked && ov.handRingStyle == ARFaceOverlayView.HandRingStyle.NONE &&
                ov.handBraceletStyle == ARFaceOverlayView.HandBraceletStyle.NONE &&
                ov.handPropType == ARFaceOverlayView.HandPropType.NONE
            ) {
                ov.applyHandArPreset("luxury_jewelry")
            } else {
                ov.handArEnabled = isChecked
                if (!isChecked) {
                    ov.clearHandLandmarks()
                }
            }
            updateHandArControlsUi()
        }
        viewBinding.switchHandSkeleton.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.arFaceOverlayView.handShowSkeleton = isChecked
            if (isChecked) viewBinding.arFaceOverlayView.handArEnabled = true
            updateHandArControlsUi()
        }
        viewBinding.switchHandGestureTrigger.setOnCheckedChangeListener { _, isChecked ->
            isHandGestureTriggerEnabled = isChecked
            showHudNotice(if (isChecked) "Hand Gesture Triggers: ON" else "Hand Gesture Triggers: OFF")
        }
        val handPresetMap = listOf(
            viewBinding.btnHandArOff to "off",
            viewBinding.btnHandArJewelry to "luxury_jewelry",
            viewBinding.btnHandArGauntlet to "cyber_gauntlet",
            viewBinding.btnHandArOrb to "arcane_orb",
            viewBinding.btnHandArFlame to "pyro_wand"
        )
        for ((btn, pid) in handPresetMap) {
            btn.setOnClickListener {
                viewBinding.arFaceOverlayView.applyHandArPreset(pid)
                updateHandArControlsUi()
                showHudNotice("Hand AR: ${pid.replace("_", " ").uppercase()}")
            }
        }
        viewBinding.btnHandRingCycle.setOnClickListener {
            val vals = ARFaceOverlayView.HandRingStyle.values()
            val ov = viewBinding.arFaceOverlayView
            ov.handRingStyle = vals[(ov.handRingStyle.ordinal + 1) % vals.size]
            ov.handArEnabled = true
            ov.handPresetId = "custom"
            updateHandArControlsUi()
        }
        viewBinding.btnHandBraceletCycle.setOnClickListener {
            val vals = ARFaceOverlayView.HandBraceletStyle.values()
            val ov = viewBinding.arFaceOverlayView
            ov.handBraceletStyle = vals[(ov.handBraceletStyle.ordinal + 1) % vals.size]
            ov.handArEnabled = true
            ov.handPresetId = "custom"
            updateHandArControlsUi()
        }
        viewBinding.btnHandPropCycle.setOnClickListener {
            val vals = ARFaceOverlayView.HandPropType.values()
            val ov = viewBinding.arFaceOverlayView
            ov.handPropType = vals[(ov.handPropType.ordinal + 1) % vals.size]
            ov.handArEnabled = true
            ov.handPresetId = "custom"
            updateHandArControlsUi()
        }
        viewBinding.btnHandAnchorCycle.setOnClickListener {
            val vals = ARFaceOverlayView.HandAnchorPoint.values()
            val ov = viewBinding.arFaceOverlayView
            ov.handPropAnchor = vals[(ov.handPropAnchor.ordinal + 1) % vals.size]
            ov.handArEnabled = true
            updateHandArControlsUi()
        }
        updateHandArControlsUi()

        // Phase 3: Body AR Controls (33-Point Full-Body Pose, Virtual Wings/Cape/Armor, Kinetic Aura & Segmentation Backdrops)
        val updateBodyArControlsUi = {
            val ov = viewBinding.arFaceOverlayView
            viewBinding.switchBodyAr.isChecked = ov.bodyArEnabled
            viewBinding.switchBodySkeleton.isChecked = ov.bodyShowSkeleton
            viewBinding.switchBodyPoseTrigger.isChecked = isBodyPoseTriggerEnabled
            val bodyBtns = listOf(
                viewBinding.btnBodyArOff to "off",
                viewBinding.btnBodyArWings to "cyber_angel",
                viewBinding.btnBodyArCape to "hero_cape",
                viewBinding.btnBodyArMecha to "mecha_armor",
                viewBinding.btnBodyArChroma to "chroma_studio"
            )
            for ((btn, pid) in bodyBtns) {
                val active = (pid == "off" && !ov.bodyArEnabled) || (ov.bodyArEnabled && ov.bodyPresetId == pid)
                btn.setBackgroundResource(if (active) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
                btn.setTextColor(Color.parseColor(if (active) "#0F172A" else "#CBD5E1"))
            }
            viewBinding.btnBodyGearCycle.text = "GEAR: ${ov.bodyAccessoryStyle.name.take(6)}"
            viewBinding.btnBodyFxCycle.text = "FX: ${ov.bodyEffectType.name.take(5)}"
            viewBinding.btnBodySegCycle.text = "BG: ${ov.bodySegmentationMode.name.take(6)}"
        }

        viewBinding.switchBodyAr.setOnCheckedChangeListener { _, isChecked ->
            val ov = viewBinding.arFaceOverlayView
            if (isChecked && ov.bodyAccessoryStyle == ARFaceOverlayView.BodyAccessoryStyle.NONE &&
                ov.bodyEffectType == ARFaceOverlayView.BodyEffectType.NONE &&
                ov.bodySegmentationMode == ARFaceOverlayView.BodySegmentationMode.NONE
            ) {
                ov.applyBodyArPreset("cyber_angel")
            } else {
                ov.bodyArEnabled = isChecked
                if (!isChecked) {
                    ov.clearBodyPose()
                }
            }
            updateBodyArControlsUi()
        }
        viewBinding.switchBodySkeleton.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.arFaceOverlayView.bodyShowSkeleton = isChecked
            if (isChecked) viewBinding.arFaceOverlayView.bodyArEnabled = true
            updateBodyArControlsUi()
        }
        viewBinding.switchBodyPoseTrigger.setOnCheckedChangeListener { _, isChecked ->
            isBodyPoseTriggerEnabled = isChecked
            if (!isChecked) viewBinding.arFaceOverlayView.updateBodyPoseDwell(0f, "")
            showHudNotice(if (isChecked) "Body Pose Triggers: ON (Arms Up=Snap)" else "Body Pose Triggers: OFF")
        }
        val bodyPresetMap = listOf(
            viewBinding.btnBodyArOff to "off",
            viewBinding.btnBodyArWings to "cyber_angel",
            viewBinding.btnBodyArCape to "hero_cape",
            viewBinding.btnBodyArMecha to "mecha_armor",
            viewBinding.btnBodyArChroma to "chroma_studio"
        )
        for ((btn, pid) in bodyPresetMap) {
            btn.setOnClickListener {
                viewBinding.arFaceOverlayView.applyBodyArPreset(pid)
                updateBodyArControlsUi()
                showHudNotice("Body AR: ${pid.replace("_", " ").uppercase()}")
            }
        }
        viewBinding.btnBodyGearCycle.setOnClickListener {
            val vals = ARFaceOverlayView.BodyAccessoryStyle.values()
            val ov = viewBinding.arFaceOverlayView
            ov.bodyAccessoryStyle = vals[(ov.bodyAccessoryStyle.ordinal + 1) % vals.size]
            ov.bodyArEnabled = true
            ov.bodyPresetId = "custom"
            updateBodyArControlsUi()
        }
        viewBinding.btnBodyFxCycle.setOnClickListener {
            val vals = ARFaceOverlayView.BodyEffectType.values()
            val ov = viewBinding.arFaceOverlayView
            ov.bodyEffectType = vals[(ov.bodyEffectType.ordinal + 1) % vals.size]
            ov.bodyArEnabled = true
            ov.bodyPresetId = "custom"
            updateBodyArControlsUi()
        }
        viewBinding.btnBodySegCycle.setOnClickListener {
            val vals = ARFaceOverlayView.BodySegmentationMode.values()
            val ov = viewBinding.arFaceOverlayView
            ov.bodySegmentationMode = vals[(ov.bodySegmentationMode.ordinal + 1) % vals.size]
            ov.bodyArEnabled = true
            ov.bodyPresetId = "custom"
            updateBodyArControlsUi()
        }
        updateBodyArControlsUi()

        // Phase 4: World AR Controls (Zero-Conflict 6-DoF IMU Planes, Tap-to-Place Objects & Surface Ruler)
        val syncWorldArTrackerState = {
            syncWorldArLifecycleAndPower()
        }
        val updateWorldArControlsUi = {
            val ov = viewBinding.arFaceOverlayView
            viewBinding.switchWorldAr.isChecked = ov.worldArEnabled
            viewBinding.switchWorldGrid.isChecked = ov.worldShowPlaneGrid
            viewBinding.switchWorldRuler.isChecked = ov.worldShowRuler
            val worldBtns = listOf(
                viewBinding.btnWorldArOff to "off",
                viewBinding.btnWorldArCube to "holo_showcase",
                viewBinding.btnWorldArPortal to "cyber_portal",
                viewBinding.btnWorldArMeasure to "ar_measure"
            )
            for ((btn, pid) in worldBtns) {
                val active = (pid == "off" && !ov.worldArEnabled) || (ov.worldArEnabled && ov.worldPresetId == pid)
                btn.setBackgroundResource(if (active) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
                btn.setTextColor(Color.parseColor(if (active) "#0F172A" else "#CBD5E1"))
            }
            viewBinding.btnWorldObjCycle.text = "OBJ: ${ov.worldActiveObject.name.take(6)}"
            viewBinding.btnWorldClearAnchors.text = "CLR (${ov.getWorldAnchorCount()})"
            syncWorldArTrackerState()
        }

        viewBinding.switchWorldAr.setOnCheckedChangeListener { _, isChecked ->
            val ov = viewBinding.arFaceOverlayView
            if (isChecked && ov.getWorldAnchorCount() == 0) {
                worldArTracker?.resetReferenceYaw()
                ov.applyWorldArPreset("holo_showcase")
            } else {
                ov.worldArEnabled = isChecked
            }
            updateWorldArControlsUi()
        }
        viewBinding.switchWorldGrid.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.arFaceOverlayView.worldShowPlaneGrid = isChecked
            if (isChecked) viewBinding.arFaceOverlayView.worldArEnabled = true
            updateWorldArControlsUi()
        }
        viewBinding.switchWorldRuler.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.arFaceOverlayView.worldShowRuler = isChecked
            if (isChecked) viewBinding.arFaceOverlayView.worldArEnabled = true
            updateWorldArControlsUi()
        }
        val worldPresetMap = listOf(
            viewBinding.btnWorldArOff to "off",
            viewBinding.btnWorldArCube to "holo_showcase",
            viewBinding.btnWorldArPortal to "cyber_portal",
            viewBinding.btnWorldArMeasure to "ar_measure"
        )
        for ((btn, pid) in worldPresetMap) {
            btn.setOnClickListener {
                if (pid != "off") worldArTracker?.resetReferenceYaw()
                viewBinding.arFaceOverlayView.applyWorldArPreset(pid)
                updateWorldArControlsUi()
                showHudNotice("World AR: ${pid.replace("_", " ").uppercase()}")
            }
        }
        viewBinding.btnWorldObjCycle.setOnClickListener {
            val nextObj = viewBinding.arFaceOverlayView.cycleWorldActiveObject()
            viewBinding.arFaceOverlayView.worldArEnabled = true
            updateWorldArControlsUi()
            showHudNotice("World Object: ${nextObj.name.replace("_", " ")}")
        }
        viewBinding.btnWorldDropAnchor.setOnClickListener {
            viewBinding.arFaceOverlayView.worldArEnabled = true
            val placed = viewBinding.arFaceOverlayView.dropDefaultWorldAnchor()
            updateWorldArControlsUi()
            showHudNotice("Dropped ${placed.objectType.name.replace("_", " ")} (${String.format(Locale.US, "%.2fm", placed.distanceM)})")
        }
        viewBinding.btnWorldClearAnchors.setOnClickListener {
            viewBinding.arFaceOverlayView.clearWorldAnchors()
            updateWorldArControlsUi()
            showHudNotice("World AR Anchors Cleared")
        }
        updateWorldArControlsUi()

        viewBinding.sbCalibOffsetX.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val px = progress - 150
                viewBinding.tvCalibOffsetX.text = "${px}px"
                if (fromUser) { calibOffsetX = px / 600f; pushArCalibrationToOverlay() }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        viewBinding.sbCalibOffsetY.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val px = progress - 150
                viewBinding.tvCalibOffsetY.text = "${px}px"
                if (fromUser) { calibOffsetY = px / 600f; pushArCalibrationToOverlay() }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        viewBinding.sbCalibScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val s = 0.5f + (progress / 100f) * 1.5f
                viewBinding.tvCalibScale.text = String.format(Locale.US, "%.2fx", s)
                if (fromUser) { calibScale = s; pushArCalibrationToOverlay() }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        viewBinding.sbCalibRotation.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val deg = (progress - 45).toFloat()
                viewBinding.tvCalibRotation.text = "${deg.toInt()}°"
                if (fromUser) { calibRotDeg = deg; pushArCalibrationToOverlay() }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        viewBinding.sbCalibEyeSpace.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val es = 0.6f + (progress / 100f)
                viewBinding.tvCalibEyeSpace.text = String.format(Locale.US, "%.2fx", es)
                if (fromUser) { calibEyeSpace = es; pushArCalibrationToOverlay() }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        viewBinding.sbCalibForehead.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress - 80
                viewBinding.tvCalibForehead.text = "${v}px"
                if (fromUser) { calibForehead = v / 800f; pushArCalibrationToOverlay() }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        viewBinding.sbCalibChin.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress - 80
                viewBinding.tvCalibChin.text = "${v}px"
                if (fromUser) { calibChin = v / 800f; pushArCalibrationToOverlay() }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        viewBinding.switchArMeshDebug.setOnCheckedChangeListener { _, isChecked ->
            calibDebug = isChecked
            pushArCalibrationToOverlay()
        }

        // Halftone Density (0.4x .. 2.2x)
        viewBinding.sbHalftoneDensity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val d = 0.4f + (progress / 100f) * 1.8f
                viewBinding.tvHalftoneDensity.text = String.format(Locale.US, "%.2fx", d)
                if (fromUser) {
                    comicHalftoneDensity = d
                    filterManager.setComicHalftoneParams(d, comicHalftoneDotSize)
                    openGLProcessor?.setComicHalftoneParams(d, comicHalftoneDotSize)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        // Halftone Dot Size (0.3x .. 1.8x)
        viewBinding.sbHalftoneDotSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val ds = 0.3f + (progress / 100f) * 1.5f
                viewBinding.tvHalftoneDotSize.text = String.format(Locale.US, "%.2fx", ds)
                if (fromUser) {
                    comicHalftoneDotSize = ds
                    filterManager.setComicHalftoneParams(comicHalftoneDensity, ds)
                    openGLProcessor?.setComicHalftoneParams(comicHalftoneDensity, ds)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        // Comic Label 3D Depth (0.0x .. 2.5x)
        viewBinding.sbComicLabelDepth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val dp = (progress / 100f) * 2.5f
                viewBinding.tvComicLabelDepth.text = String.format(Locale.US, "%.2fx", dp)
                if (fromUser) {
                    comicLabelDepth = dp
                    viewBinding.arFaceOverlayView.comicLabelDepth = dp
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        viewBinding.btnComicPalette.setOnClickListener {
            comicPaletteIndex = (comicPaletteIndex + 1) % comicPalettePresets.size
            applyComicPaletteIndex(comicPaletteIndex)
            showHudNotice("Comic Palette: ${comicPalettePresets[comicPaletteIndex].name}")
        }
        viewBinding.btnComicRandomize.setOnClickListener {
            val rng = java.util.Random()
            val randomHex = {
                val hues = listOf("#FACC15", "#EF4444", "#22D3EE", "#EC4899", "#A855F7", "#84CC16", "#F97316", "#3B82F6", "#FFFFFF", "#FFFBEB")
                hues[rng.nextInt(hues.size)]
            }
            viewBinding.arFaceOverlayView.setComicOnomatopoeiaPalette(
                "#FFFFFF", randomHex(), randomHex(), randomHex(),
                "#FFFBEB", randomHex(), randomHex(), randomHex(),
                "#ECFEFF", randomHex(), randomHex(), randomHex()
            )
            comicSfxPow = comicSfxChoices[rng.nextInt(comicSfxChoices.size)]
            comicSfxBam = comicSfxChoices[rng.nextInt(comicSfxChoices.size)]
            comicSfxZap = comicSfxChoices[rng.nextInt(comicSfxChoices.size)]
            if (!viewBinding.arFaceOverlayView.enableComicOnomatopoeia && !viewBinding.arFaceOverlayView.enableComicSpeechBubble) {
                toggleComicOnomatopoeia()
            } else {
                comicSfxHelper?.playComicBurst(comicSfxPow, comicSfxBam, comicSfxZap, 0.72f, isAudioMuted)
            }
            viewBinding.btnComicPalette.text = "PALETTE: RANDOM"
            showHudNotice("Randomized Comic Palette & SFX!")
        }

        // Speech Bubble Studio Controls
        viewBinding.switchComicSpeechBubble.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.arFaceOverlayView.enableComicSpeechBubble = isChecked
            showHudNotice("Comic Speech Bubble ${if (isChecked) "ON" else "OFF"}")
        }
        viewBinding.btnBubbleSpeech.setOnClickListener {
            viewBinding.arFaceOverlayView.speechBubbleStyle = ARFaceOverlayView.ComicSpeechBubbleStyle.SPEECH
            updateBubbleStyleButtons(ARFaceOverlayView.ComicSpeechBubbleStyle.SPEECH)
        }
        viewBinding.btnBubbleThought.setOnClickListener {
            viewBinding.arFaceOverlayView.speechBubbleStyle = ARFaceOverlayView.ComicSpeechBubbleStyle.THOUGHT
            updateBubbleStyleButtons(ARFaceOverlayView.ComicSpeechBubbleStyle.THOUGHT)
        }
        viewBinding.btnBubbleShout.setOnClickListener {
            viewBinding.arFaceOverlayView.speechBubbleStyle = ARFaceOverlayView.ComicSpeechBubbleStyle.SHOUT
            updateBubbleStyleButtons(ARFaceOverlayView.ComicSpeechBubbleStyle.SHOUT)
        }
        viewBinding.btnCycleBubblePhrase.setOnClickListener {
            comicBubblePhraseIndex = (comicBubblePhraseIndex + 1) % comicBubblePhrases.size
            val phrase = comicBubblePhrases[comicBubblePhraseIndex]
            viewBinding.arFaceOverlayView.speechBubbleText = phrase
            viewBinding.btnCycleBubblePhrase.text = "TEXT: $phrase"
            if (!viewBinding.switchComicSpeechBubble.isChecked) {
                viewBinding.switchComicSpeechBubble.isChecked = true
            }
        }

        // AR Batch Queue & Auto-Adapt Studio
        viewBinding.switchArAutoAdapt.setOnCheckedChangeListener { _, isChecked ->
            isAutoAdaptEnabled = isChecked
            showHudNotice("AR Auto-Adapt ${if (isChecked) "ON" else "OFF"}")
        }
        viewBinding.btnBatchSelectAll.setOnClickListener {
            batchLensQueue.clear()
            batchLensQueue.addAll(arLensOrder.filter { it != "none" })
            batchStepIndex = 0
            updateBatchQueueHud()
            if (batchLensQueue.isNotEmpty()) setArLens(batchLensQueue[0])
        }
        viewBinding.btnBatchClear.setOnClickListener {
            stopBatchLoop()
            batchLensQueue.clear()
            batchStepIndex = 0
            updateBatchQueueHud()
            setArLens("none")
        }
        viewBinding.btnBatchPrev.setOnClickListener { stepBatchQueue(-1) }
        viewBinding.btnBatchNext.setOnClickListener { stepBatchQueue(1) }
        viewBinding.btnBatchPlayPause.setOnClickListener {
            if (batchLensQueue.isEmpty()) {
                batchLensQueue.addAll(arLensOrder.filter { it != "none" })
            }
            if (isBatchPlaying) {
                stopBatchLoop()
            } else {
                startBatchLoop()
            }
        }
        updateBatchQueueHud()
    }

    private fun selectArDockTab(tab: ArDockTab, fromUser: Boolean = true) {
        currentArDockTab = tab
        val dock = viewBinding.arLensModeDock
        val ov = viewBinding.arFaceOverlayView

        val tabButtons = listOf(
            dock.tabArDockLenses to ArDockTab.LENSES,
            dock.tabArDockHand to ArDockTab.HAND,
            dock.tabArDockBody to ArDockTab.BODY,
            dock.tabArDockWorld to ArDockTab.WORLD,
            dock.tabArDockMix to ArDockTab.MIX,
            dock.tabArDockComicText to ArDockTab.COMIC_TEXT
        )
        for ((btn, t) in tabButtons) {
            val active = (tab == t)
            btn.setBackgroundResource(if (active) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
            btn.setTextColor(Color.parseColor(if (active) "#0F172A" else if (t == ArDockTab.MIX) "#F472B6" else "#94A3B8"))
        }

        dock.scrollArModeLensesStrip.visibility = if (tab == ArDockTab.LENSES) View.VISIBLE else View.GONE
        dock.scrollArModeHandStrip.visibility = if (tab == ArDockTab.HAND) View.VISIBLE else View.GONE
        dock.scrollArModeBodyStrip.visibility = if (tab == ArDockTab.BODY) View.VISIBLE else View.GONE
        dock.scrollArModeWorldStrip.visibility = if (tab == ArDockTab.WORLD) View.VISIBLE else View.GONE
        dock.layoutArModeMixPanel.visibility = if (tab == ArDockTab.MIX) View.VISIBLE else View.GONE
        dock.layoutArModeComicTextPanel.visibility = if (tab == ArDockTab.COMIC_TEXT) View.VISIBLE else View.GONE

        // Exclusive Single-Tracker Performance Isolation on Motorola Edge 60 Pro (keeps 60 FPS & low heat)
        if (fromUser) {
            when (tab) {
                ArDockTab.LENSES -> {
                    ov.handArEnabled = false
                    ov.bodyArEnabled = false
                    ov.worldArEnabled = false
                    syncWorldArLifecycleAndPower()
                    if (currentArLensName == "none") setArLens("cyberpunk_visor", showNotice = false)
                }
                ArDockTab.HAND -> {
                    ov.bodyArEnabled = false
                    ov.worldArEnabled = false
                    syncWorldArLifecycleAndPower()
                    if (!ov.handArEnabled) ov.applyHandArPreset("cyber_gauntlet")
                }
                ArDockTab.BODY -> {
                    ov.handArEnabled = false
                    ov.worldArEnabled = false
                    syncWorldArLifecycleAndPower()
                    if (!ov.bodyArEnabled) ov.applyBodyArPreset("cyber_angel")
                }
                ArDockTab.WORLD -> {
                    ov.handArEnabled = false
                    ov.bodyArEnabled = false
                    if (!ov.worldArEnabled) {
                        worldArTracker?.resetReferenceYaw()
                        ov.applyWorldArPreset("holo_showcase")
                    }
                    syncWorldArLifecycleAndPower()
                    // Smart camera flip: World AR naturally uses Back Camera for floors & walls
                    if (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA && recording == null) {
                        lensFacing = CameraSelector.DEFAULT_BACK_CAMERA
                        faceMeshHelper?.setFrontFacing(false)
                        bindCameraUseCases()
                    }
                }
                ArDockTab.MIX -> {
                    // Mix mode allows stacking Face + Hand + Body + World simultaneously
                    syncWorldArLifecycleAndPower()
                }
                ArDockTab.COMIC_TEXT -> Unit
            }
        }

        // Interactive mode prompt & status header
        when (tab) {
            ArDockTab.LENSES -> {
                dock.tvArShutterHint.text = "2-FINGER: PLACE · SIZE · TILT"
                dock.tvArDockStatus.setTextColor(Color.parseColor("#E879F9"))
            }
            ArDockTab.HAND -> {
                dock.tvArDockStatus.text = "HAND AR · ${ov.handPresetId.replace("_", " ").uppercase()}"
                dock.tvArDockStatus.setTextColor(Color.parseColor("#34D399"))
                dock.tvArShutterHint.text = "TRY: PEACE / PINCH / PALM FX"
            }
            ArDockTab.BODY -> {
                dock.tvArDockStatus.text = "BODY AR · ${ov.bodyPresetId.replace("_", " ").uppercase()}"
                dock.tvArDockStatus.setTextColor(Color.parseColor("#A78BFA"))
                dock.tvArShutterHint.text = "ARMS UP = SNAP · T-POSE = GEAR"
            }
            ArDockTab.WORLD -> {
                dock.tvArDockStatus.text = "WORLD AR · ${ov.getWorldAnchorCount()} ANCHORS"
                dock.tvArDockStatus.setTextColor(Color.parseColor("#38BDF8"))
                dock.tvArShutterHint.text = "TAP FLOOR/WALL TO DROP 3D"
            }
            ArDockTab.MIX -> {
                dock.tvArDockStatus.text = "MIX STUDIO · STACK ALL AR LAYERS"
                dock.tvArDockStatus.setTextColor(Color.parseColor("#F472B6"))
                dock.tvArShutterHint.text = "TAP ROLL FOR WILD COMBOS"
            }
            ArDockTab.COMIC_TEXT -> {
                dock.tvArShutterHint.text = "HOLD & DRAG AR TEXT ON SCREEN"
            }
        }

        if (isArDockCollapsed) {
            isArDockCollapsed = false
            dock.layoutArDockBody.visibility = View.VISIBLE
            dock.btnArDockToggle.text = "v"
        }
        syncArDockFromCalibration()
    }

    private fun rollSurpriseArLook() {
        val ov = viewBinding.arFaceOverlayView
        arComboStreak += 1
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        playCameraCue(ToneGenerator.TONE_PROP_ACK, 45)
        viewBinding.arLensModeDock.btnArSurprise.text = "#$arComboStreak"

        val faceChoices = listOf("cyberpunk_visor", "golden_crown", "scifi_hud", "mesh_contour", "comic_frame")
        val handChoices = listOf("cyber_gauntlet", "luxury_jewelry", "arcane_orb", "pyro_wand")
        val bodyChoices = listOf("cyber_angel", "hero_cape", "mecha_armor", "celestial_stardust", "chroma_studio")
        val worldChoices = listOf("holo_showcase", "cyber_portal", "cosmic_desk", "ar_measure")

        when (currentArDockTab) {
            ArDockTab.HAND -> {
                val pick = handChoices.filter { it != ov.handPresetId }.randomOrNull() ?: handChoices.random()
                ov.applyHandArPreset(pick)
                showHudNotice("ROLL #$arComboStreak · HAND: ${pick.replace("_", " ").uppercase()}!")
            }
            ArDockTab.BODY -> {
                val pick = bodyChoices.filter { it != ov.bodyPresetId }.randomOrNull() ?: bodyChoices.random()
                ov.applyBodyArPreset(pick)
                showHudNotice("ROLL #$arComboStreak · BODY: ${pick.replace("_", " ").uppercase()}!")
            }
            ArDockTab.WORLD -> {
                val pick = worldChoices.filter { it != ov.worldPresetId }.randomOrNull() ?: worldChoices.random()
                worldArTracker?.resetReferenceYaw()
                ov.applyWorldArPreset(pick)
                syncWorldArLifecycleAndPower()
                showHudNotice("ROLL #$arComboStreak · WORLD: ${pick.replace("_", " ").uppercase()}!")
            }
            ArDockTab.MIX -> {
                val f = faceChoices.random()
                val h = handChoices.random()
                val b = bodyChoices.random()
                val w = worldChoices.random()
                setArLens(f, showNotice = false)
                ov.applyHandArPreset(h)
                ov.applyBodyArPreset(b)
                ov.applyWorldArPreset(w)
                syncWorldArLifecycleAndPower()
                showHudNotice("WILD COMBO #$arComboStreak: ${f.take(5).uppercase()} + ${h.take(5).uppercase()} + ${b.take(5).uppercase()}!")
            }
            else -> {
                val pick = faceChoices.filter { it != currentArLensName }.randomOrNull() ?: faceChoices.random()
                setArLens(pick, showNotice = false)
                showHudNotice("ROLL #$arComboStreak · LENS: ${pick.replace("_", " ").uppercase()}!")
            }
        }
        syncArDockFromCalibration()
    }

    private fun syncArDockFromCalibration() {
        isSyncingArDockSliders = true
        val dock = viewBinding.arLensModeDock
        val ov = viewBinding.arFaceOverlayView
        val isModified = Math.abs(calibOffsetX) > 0.002f ||
            Math.abs(calibOffsetY) > 0.002f ||
            Math.abs(calibScale - 1.0f) > 0.01f ||
            Math.abs(calibRotDeg) > 0.5f
        dock.btnArGestureReset.visibility = if (isModified) View.VISIBLE else View.GONE
        dock.switchArDockBubble.isChecked = ov.enableComicSpeechBubble
        dock.switchArDockAutoAdapt.isChecked = isAutoAdaptEnabled

        // Highlight MIX layer status pills
        val faceOn = currentArLensName != "none" || ov.faceBeautyEnabled
        val mixStates = listOf(
            dock.btnDockMixFace to faceOn,
            dock.btnDockMixHand to ov.handArEnabled,
            dock.btnDockMixBody to ov.bodyArEnabled,
            dock.btnDockMixWorld to ov.worldArEnabled
        )
        for ((btn, active) in mixStates) {
            btn.setBackgroundResource(if (active) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
            btn.setTextColor(Color.parseColor(if (active) "#0F172A" else "#94A3B8"))
        }
        if (currentArDockTab == ArDockTab.WORLD) {
            dock.tvArDockStatus.text = "WORLD AR · ${ov.getWorldAnchorCount()} ANCHORS"
        }
        // Sync Snap Dust quick action chips inside Body AR strip
        val isSnapArmed = ov.isSnapWipeoutArmed()
        dock.btnDockBodyLockBg.visibility = if (isSnapArmed) View.VISIBLE else View.GONE
        dock.btnDockBodySnapNow.visibility = if (isSnapArmed) View.VISIBLE else View.GONE
        dock.btnDockBodyGauntlet.visibility = if (isSnapArmed) View.VISIBLE else View.GONE
        if (isSnapArmed) {
            dock.btnDockBodyLockBg.text = if (ov.hasSnapCleanPlate()) "BG LOCKED" else "LOCK BG"
            dock.btnDockBodyLockBg.setBackgroundResource(if (ov.hasSnapCleanPlate()) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
            dock.btnDockBodyLockBg.setTextColor(Color.parseColor(if (ov.hasSnapCleanPlate()) "#0F172A" else "#34D399"))
            val vanished = ov.isSnapVanishedOrDissolving()
            dock.btnDockBodySnapNow.text = if (vanished) "RESET" else "SNAP!"
            val gauntletOn = ov.snapGauntletEnabled
            dock.btnDockBodyGauntlet.text = if (gauntletOn) "GLOVE ON" else "GLOVE OFF"
            dock.btnDockBodyGauntlet.setBackgroundResource(if (gauntletOn) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
            dock.btnDockBodyGauntlet.setTextColor(Color.parseColor(if (gauntletOn) "#0F172A" else "#FBBF24"))
            dock.tvArShutterHint.text = if (ov.hasSnapCleanPlate()) "SNAP FINGERS OR TAP SNAP!" else "LOCK EMPTY BG FIRST, THEN SNAP!"
        }
        isSyncingArDockSliders = false
    }

    private fun triggerSnapWipeoutAction(originX: Float = 0.70f, originY: Float = 0.42f, fromManualButton: Boolean = false) {
        val ov = viewBinding.arFaceOverlayView
        if (!ov.isSnapWipeoutArmed()) {
            ov.applyBodyArPreset("snap_wipeout")
        }
        val hapticConst = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        if (ov.isSnapVanishedOrDissolving()) {
            // Strictly one-time wipeout: finger snaps never reverse! Manual button performs clean instant reset.
            if (fromManualButton) {
                window.decorView.performHapticFeedback(hapticConst)
                ov.resetSnapWipeout()
                showHudNotice("SNAP WIPEOUT RESET · Ready for next take")
                syncArDockFromCalibration()
            }
            return
        }
        window.decorView.performHapticFeedback(hapticConst)
        val liveBmp = try { viewBinding.viewFinder.bitmap } catch (_: Throwable) { null }
        if (ov.triggerSnapWipeout(originX, originY, liveBmp)) {
            playCameraCue(ToneGenerator.TONE_PROP_ACK, 55)
            showHudNotice("SNAP! Feathered wind dust wipeout...")
        }
        syncArDockFromCalibration()
    }

    private fun setupArLensModeDock() {
        val dock = viewBinding.arLensModeDock
        dock.tabArDockLenses.setOnClickListener { selectArDockTab(ArDockTab.LENSES, fromUser = true) }
        dock.tabArDockHand.setOnClickListener { selectArDockTab(ArDockTab.HAND, fromUser = true) }
        dock.tabArDockBody.setOnClickListener { selectArDockTab(ArDockTab.BODY, fromUser = true) }
        dock.tabArDockWorld.setOnClickListener { selectArDockTab(ArDockTab.WORLD, fromUser = true) }
        dock.tabArDockMix.setOnClickListener { selectArDockTab(ArDockTab.MIX, fromUser = true) }
        dock.tabArDockComicText.setOnClickListener { selectArDockTab(ArDockTab.COMIC_TEXT, fromUser = true) }
        dock.btnArSurprise.setOnClickListener { rollSurpriseArLook() }
        dock.btnArDockToggle.setOnClickListener {
            isArDockCollapsed = !isArDockCollapsed
            dock.layoutArDockBody.visibility = if (isArDockCollapsed) View.GONE else View.VISIBLE
            dock.btnArDockToggle.text = if (isArDockCollapsed) "^" else "v"
        }
        dock.btnArGestureReset.setOnClickListener { resetArCalibrationValues() }
        dock.switchArDockBubble.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingArDockSliders) return@setOnCheckedChangeListener
            viewBinding.arFaceOverlayView.enableComicSpeechBubble = isChecked
            viewBinding.switchComicSpeechBubble.isChecked = isChecked
            showHudNotice("Comic Speech Bubble ${if (isChecked) "ON" else "OFF"}")
        }
        dock.switchArDockAutoAdapt.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingArDockSliders) return@setOnCheckedChangeListener
            isAutoAdaptEnabled = isChecked
            viewBinding.switchArAutoAdapt.isChecked = isChecked
            showHudNotice("AR Auto-Adapt ${if (isChecked) "ON" else "OFF"}")
        }

        // Quick Hand AR Carousel listeners
        val handQuickMap = listOf(
            dock.btnDockHandOff to "off",
            dock.btnDockHandCyber to "cyber_gauntlet",
            dock.btnDockHandGold to "luxury_jewelry",
            dock.btnDockHandOrb to "arcane_orb",
            dock.btnDockHandFlame to "pyro_wand"
        )
        for ((btn, pid) in handQuickMap) {
            btn.setOnClickListener {
                viewBinding.arFaceOverlayView.applyHandArPreset(pid)
                dock.tvArDockStatus.text = "HAND AR · ${pid.replace("_", " ").uppercase()}"
                showHudNotice("Hand AR: ${pid.replace("_", " ").uppercase()}")
                syncArDockFromCalibration()
            }
        }
        dock.btnDockHandSkel.setOnClickListener {
            val ov = viewBinding.arFaceOverlayView
            ov.handShowSkeleton = !ov.handShowSkeleton
            if (ov.handShowSkeleton) ov.handArEnabled = true
            showHudNotice("21-Pt Hand Skeleton ${if (ov.handShowSkeleton) "ON" else "OFF"}")
        }

        // Quick Body AR Carousel listeners
        val bodyQuickMap = listOf(
            dock.btnDockBodyOff to "off",
            dock.btnDockBodySnapDust to "snap_wipeout",
            dock.btnDockBodyWings to "cyber_angel",
            dock.btnDockBodyCape to "hero_cape",
            dock.btnDockBodyMecha to "mecha_armor",
            dock.btnDockBodyStardust to "celestial_stardust",
            dock.btnDockBodyChroma to "chroma_studio"
        )
        for ((btn, pid) in bodyQuickMap) {
            btn.setOnClickListener {
                viewBinding.arFaceOverlayView.applyBodyArPreset(pid)
                dock.tvArDockStatus.text = if (pid == "snap_wipeout") "SNAP DUST · ENDGAME WIPEOUT" else "BODY AR · ${pid.replace("_", " ").uppercase()}"
                showHudNotice(if (pid == "snap_wipeout") "SNAP DUST ARMED · Lock empty BG or snap fingers!" else "Body AR: ${pid.replace("_", " ").uppercase()}")
                syncArDockFromCalibration()
            }
        }
        dock.btnDockBodyLockBg.setOnClickListener {
            val bmp = try { viewBinding.viewFinder.bitmap } catch (_: Throwable) { null }
            if (bmp != null) {
                viewBinding.arFaceOverlayView.setSnapCleanPlate(bmp)
                playCameraCue(ToneGenerator.TONE_PROP_BEEP, 40)
                showHudNotice("CLEAN BG LOCKED! Step into frame & snap fingers!")
            } else {
                showHudNotice("Camera warming up — try LOCK BG again", isWarning = true)
            }
            syncArDockFromCalibration()
        }
        dock.btnDockBodySnapNow.setOnClickListener {
            triggerSnapWipeoutAction(0.70f, 0.42f, fromManualButton = true)
        }
        dock.btnDockBodyGauntlet.setOnClickListener {
            val ov = viewBinding.arFaceOverlayView
            ov.snapGauntletEnabled = !ov.snapGauntletEnabled
            showHudNotice(if (ov.snapGauntletEnabled) "6-Stone Infinity Gauntlet EQUIPPED!" else "Infinity Gauntlet hidden (bare hand)")
            syncArDockFromCalibration()
        }
        dock.btnDockBodySkel.setOnClickListener {
            val ov = viewBinding.arFaceOverlayView
            ov.bodyShowSkeleton = !ov.bodyShowSkeleton
            if (ov.bodyShowSkeleton) ov.bodyArEnabled = true
            showHudNotice("33-Pt Body Skeleton ${if (ov.bodyShowSkeleton) "ON" else "OFF"}")
        }

        // Quick World AR Carousel listeners
        val worldQuickMap = listOf(
            dock.btnDockWorldOff to "off",
            dock.btnDockWorldCube to "holo_showcase",
            dock.btnDockWorldPortal to "cyber_portal",
            dock.btnDockWorldPlanet to "cosmic_desk",
            dock.btnDockWorldRuler to "ar_measure",
            dock.btnDockWorldGlb to "glb_world_stage"
        )
        for ((btn, pid) in worldQuickMap) {
            btn.setOnClickListener {
                if (pid != "off") worldArTracker?.resetReferenceYaw()
                viewBinding.arFaceOverlayView.applyWorldArPreset(pid)
                syncWorldArLifecycleAndPower()
                showHudNotice("World AR: ${pid.replace("_", " ").uppercase()}")
                syncArDockFromCalibration()
            }
        }
        dock.btnDockWorldDrop.setOnClickListener {
            viewBinding.arFaceOverlayView.worldArEnabled = true
            val placed = viewBinding.arFaceOverlayView.dropDefaultWorldAnchor()
            syncWorldArLifecycleAndPower()
            syncArDockFromCalibration()
            showHudNotice("Dropped ${placed.objectType.name.replace("_", " ")} (${String.format(Locale.US, "%.2fm", placed.distanceM)})")
        }
        dock.btnDockWorldClear.setOnClickListener {
            viewBinding.arFaceOverlayView.clearWorldAnchors()
            syncArDockFromCalibration()
            showHudNotice("World Anchors Cleared")
        }

        // Quick MIX Studio layer toggles + Wild Combo button
        dock.btnDockMixFace.setOnClickListener {
            if (currentArLensName == "none") setArLens("cyberpunk_visor", showNotice = false) else setArLens("none", showNotice = false)
            syncArDockFromCalibration()
        }
        dock.btnDockMixHand.setOnClickListener {
            val ov = viewBinding.arFaceOverlayView
            if (ov.handArEnabled) ov.applyHandArPreset("off") else ov.applyHandArPreset("arcane_orb")
            syncArDockFromCalibration()
        }
        dock.btnDockMixBody.setOnClickListener {
            val ov = viewBinding.arFaceOverlayView
            if (ov.bodyArEnabled) ov.applyBodyArPreset("off") else ov.applyBodyArPreset("cyber_angel")
            syncArDockFromCalibration()
        }
        dock.btnDockMixWorld.setOnClickListener {
            val ov = viewBinding.arFaceOverlayView
            if (ov.worldArEnabled) ov.applyWorldArPreset("off") else ov.applyWorldArPreset("cyber_portal")
            syncWorldArLifecycleAndPower()
            syncArDockFromCalibration()
        }
        dock.btnDockMixRoll.setOnClickListener {
            currentArDockTab = ArDockTab.MIX
            rollSurpriseArLook()
        }

        selectArDockTab(ArDockTab.LENSES, fromUser = false)
        syncArDockFromCalibration()
    }

    // =========================================================================
    // SIMPLIFIED RIGHT-SIDE FLOATING MODE MENU + BOTTOM PARAMETER BAR + AR STRIP
    // =========================================================================
    private var isFloatingMenuOpen: Boolean = false
    private var activeFloatingSettingId: String? = null
    private var activeArFamily: String = "face"

    private data class FloatingMenuSpec(
        val id: String,
        val label: String,
        val glyph: String = "",
        val badge: String = "",
        val isModified: Boolean = false,
        val isSlider: Boolean = false,
        val isAction: Boolean = false,
        val min: Int = 0,
        val max: Int = 100,
        val defaultVal: Int = 0,
        val currentVal: Int = 0,
        val formatVal: ((Int) -> String)? = null,
        val onSliderChange: ((Int) -> Unit)? = null,
        val options: List<Pair<String, String>> = emptyList(),
        val activeOptionId: String = "",
        val onOptionSelect: ((String) -> Unit)? = null,
        val onAction: (() -> Unit)? = null
    )

    private fun setupFloatingModeMenu() {
        val fm = viewBinding.floatingModeMenu
        fm.btnFloatingMenuArrow.setOnClickListener {
            isFloatingMenuOpen = !isFloatingMenuOpen
            if (!isFloatingMenuOpen) activeFloatingSettingId = null
            refreshFloatingModeMenu()
        }
        fm.btnFloatingParamClose.setOnClickListener {
            activeFloatingSettingId = null
            refreshFloatingModeMenu()
        }
        fm.btnArFamilyOff.setOnClickListener { selectArFamily("off") }
        fm.btnArFamilySnapMagic.setOnClickListener { selectArFamily("snap_magic") }
        fm.btnArFamilyFace.setOnClickListener { selectArFamily("face") }
        fm.btnArFamilyHand.setOnClickListener { selectArFamily("hand") }
        fm.btnArFamilyBody.setOnClickListener { selectArFamily("body") }
        fm.btnArFamilyWorld.setOnClickListener { selectArFamily("world") }
        fm.btnArFamilyBeauty.setOnClickListener { selectArFamily("beauty") }

        // Keep top bar clean on initial launch
        viewBinding.layoutBtnGuide.visibility = View.GONE
        viewBinding.layoutBtnTimer.visibility = View.GONE
        viewBinding.btnAutoLoop.visibility = View.GONE
        viewBinding.btnAspectRatio.visibility = View.GONE
        viewBinding.layoutBtnProStudio.visibility = View.GONE
        viewBinding.btnAudioMute.visibility = View.GONE
        updateCaptureButtonVisual(isRecordingActive = false)
        refreshFloatingModeMenu()
    }

    private fun selectArFamily(family: String) {
        activeArFamily = family
        activeFloatingSettingId = null
        // Auto-open right floating settings menu ONLY in AR mode when an effect is selected
        isFloatingMenuOpen = (cameraMode == CameraMode.AR_LENS && family != "off")
        applyActiveArFamilyState(fromUser = true)
        updateCaptureButtonVisual(isRecordingActive = (recording != null))
        refreshFloatingModeMenu()
    }

    private fun applyActiveArFamilyState(fromUser: Boolean) {
        val ov = viewBinding.arFaceOverlayView
        when (activeArFamily) {
            "off" -> {
                setArLens("none", showNotice = false, syncFloatingMenu = false)
                ov.applyHandArPreset("off")
                ov.applyBodyArPreset("off")
                ov.applyWorldArPreset("off")
                ov.applyFaceBeautyPreset("off")
                syncWorldArLifecycleAndPower()
                if (fromUser) showHudNotice("AR Effects Off")
            }
            "snap_magic" -> {
                // All-in-one Snap Magic: 6-Stone Infinity Gauntlet + Finger-Snap Detection + Feathered Dust Wipeout
                setArLens("none", showNotice = false, syncFloatingMenu = false)
                ov.applyHandArPreset("off")
                ov.applyWorldArPreset("off")
                ov.applyFaceBeautyPreset("off")
                ov.snapGauntletEnabled = true
                ov.applyBodyArPreset("snap_wipeout")
                syncWorldArLifecycleAndPower()
                if (fromUser) showHudNotice("Snap Magic Ready — Wear Gauntlet & Snap Fingers")
            }
            "face" -> {
                ov.applyHandArPreset("off")
                ov.applyBodyArPreset("off")
                ov.applyWorldArPreset("off")
                ov.applyFaceBeautyPreset("off")
                syncWorldArLifecycleAndPower()
                val restore = if (lastActiveArLensName == "none") "cyberpunk_visor" else lastActiveArLensName
                if (currentArLensName == "none") setArLens(restore, showNotice = false, syncFloatingMenu = false)
                if (fromUser) showHudNotice("Face AR Active")
            }
            "hand" -> {
                setArLens("none", showNotice = false, syncFloatingMenu = false)
                ov.applyBodyArPreset("off")
                ov.applyWorldArPreset("off")
                ov.applyFaceBeautyPreset("off")
                if (!ov.handArEnabled) ov.applyHandArPreset("cyber_gauntlet")
                syncWorldArLifecycleAndPower()
                if (fromUser) showHudNotice("Hand AR Active")
            }
            "body" -> {
                setArLens("none", showNotice = false, syncFloatingMenu = false)
                ov.applyHandArPreset("off")
                ov.applyWorldArPreset("off")
                ov.applyFaceBeautyPreset("off")
                if (!ov.bodyArEnabled || ov.isSnapWipeoutArmed()) ov.applyBodyArPreset("cyber_angel")
                syncWorldArLifecycleAndPower()
                if (fromUser) showHudNotice("Body AR Active")
            }
            "world" -> {
                setArLens("none", showNotice = false, syncFloatingMenu = false)
                ov.applyHandArPreset("off")
                ov.applyBodyArPreset("off")
                ov.applyFaceBeautyPreset("off")
                if (!ov.worldArEnabled) ov.applyWorldArPreset("holo_showcase")
                syncWorldArLifecycleAndPower()
                if (fromUser) showHudNotice("World AR Active")
            }
            "beauty" -> {
                setArLens("none", showNotice = false, syncFloatingMenu = false)
                ov.applyHandArPreset("off")
                ov.applyBodyArPreset("off")
                ov.applyWorldArPreset("off")
                if (!ov.faceBeautyEnabled) ov.applyFaceBeautyPreset("natural_glow")
                syncWorldArLifecycleAndPower()
                if (fromUser) showHudNotice("Beauty AR Active")
            }
        }
    }

    private fun buildFloatingMenuSpecs(): List<FloatingMenuSpec> {
        val ov = viewBinding.arFaceOverlayView
        if (cameraMode == CameraMode.AR_LENS) {
            return when (activeArFamily) {
                "off" -> emptyList()
                "snap_magic" -> listOf(
                    FloatingMenuSpec(
                        id = "snap_action",
                        label = if (ov.isSnapVanishedOrDissolving()) "Reset" else "Snap!",
                        glyph = if (ov.isSnapVanishedOrDissolving()) "↺" else "✦",
                        badge = if (ov.isSnapVanishedOrDissolving()) "Dust" else "Armed",
                        isModified = true,
                        isAction = true,
                        onAction = {
                            triggerSnapWipeoutAction(0.70f, 0.42f, fromManualButton = true)
                            refreshFloatingModeMenu()
                        }
                    ),
                    FloatingMenuSpec(
                        id = "snap_sens",
                        label = "Sens.",
                        glyph = "◎",
                        badge = "${((handLandmarkerHelper?.snapPinchThreshold ?: 0.36f) * 100f).toInt().coerceIn(20, 60)}%",
                        isModified = ((handLandmarkerHelper?.snapPinchThreshold ?: 0.36f) * 100f).toInt() != 36,
                        isSlider = true,
                        min = 20,
                        max = 60,
                        defaultVal = 36,
                        currentVal = ((handLandmarkerHelper?.snapPinchThreshold ?: 0.36f) * 100f).toInt().coerceIn(20, 60),
                        formatVal = { v -> "$v%" },
                        onSliderChange = { v ->
                            handLandmarkerHelper?.snapPinchThreshold = v / 100f
                        }
                    ),
                    FloatingMenuSpec(
                        id = "snap_distance",
                        label = "Range",
                        glyph = "↔",
                        badge = "≤${(13.2f / (handLandmarkerHelper?.minNearHandScale ?: 0.15f).coerceIn(0.08f, 0.28f)).toInt().coerceIn(48, 165)}cm",
                        isModified = true,
                        isSlider = true,
                        min = 48,
                        max = 165,
                        defaultVal = 88,
                        currentVal = (13.2f / (handLandmarkerHelper?.minNearHandScale ?: 0.15f).coerceIn(0.08f, 0.28f)).toInt().coerceIn(48, 165),
                        formatVal = { v -> "≤${v}cm" },
                        onSliderChange = { v ->
                            val minScale = (13.2f / v.toFloat().coerceAtLeast(48f)).coerceIn(0.08f, 0.28f)
                            handLandmarkerHelper?.nearCameraDistanceEnabled = true
                            handLandmarkerHelper?.minNearHandScale = minScale
                            ov.snapMinNearHandScale = minScale
                        }
                    ),
                    FloatingMenuSpec(
                        id = "snap_density",
                        label = "Density",
                        glyph = "❖",
                        badge = String.format("%.1fx", ov.snapDustDensity),
                        isModified = (ov.snapDustDensity * 100f).toInt() != 135,
                        isSlider = true,
                        min = 40,
                        max = 250,
                        defaultVal = 135,
                        currentVal = (ov.snapDustDensity * 100f).toInt().coerceIn(40, 250),
                        formatVal = { v -> String.format("%.2fx", v / 100f) },
                        onSliderChange = { v ->
                            ov.snapDustDensity = v / 100f
                        }
                    ),
                    FloatingMenuSpec(
                        id = "snap_wind",
                        label = "Wind",
                        glyph = "≈",
                        badge = String.format("%.1fx", ov.snapWindSpeed),
                        isModified = (ov.snapWindSpeed * 100f).toInt() != 130,
                        isSlider = true,
                        min = 30,
                        max = 300,
                        defaultVal = 130,
                        currentVal = (ov.snapWindSpeed * 100f).toInt().coerceIn(30, 300),
                        formatVal = { v -> String.format("%.1fx", v / 100f) },
                        onSliderChange = { v ->
                            ov.snapWindSpeed = v / 100f
                        }
                    ),
                    FloatingMenuSpec(
                        id = "snap_lock_bg",
                        label = if (ov.hasSnapCleanPlate()) "BG OK" else "Lock BG",
                        glyph = "▣",
                        badge = if (ov.hasSnapCleanPlate()) "Locked" else "Auto",
                        isModified = ov.hasSnapCleanPlate(),
                        isAction = true,
                        onAction = {
                            val bmp = try { viewBinding.viewFinder.bitmap } catch (_: Throwable) { null }
                            if (bmp != null) {
                                ov.setSnapCleanPlate(bmp)
                                showHudNotice("Clean background locked for Snap Magic")
                                refreshFloatingModeMenu()
                            }
                        }
                    )
                )
                "face" -> listOf(
                    FloatingMenuSpec(
                        id = "face_lens",
                        label = "Lens",
                        glyph = "◈",
                        badge = when (currentArLensName) {
                            "none" -> "Off"
                            "cyberpunk_visor" -> "Visor"
                            "golden_crown" -> "Crown"
                            "scifi_hud" -> "HUD"
                            "mesh_contour" -> "Mesh"
                            "comic_frame" -> "Comic"
                            else -> {
                                val customName = injectedCustomLenses.find { it.id == currentArLensName }?.name
                                if (!customName.isNullOrBlank()) {
                                    val words = customName.trim().split(' ').filter { it.isNotEmpty() && it.any { ch -> ch.isLetter() } }
                                    (words.lastOrNull() ?: customName).take(7)
                                } else {
                                    "Custom"
                                }
                            }
                        },
                        isModified = currentArLensName != "none",
                        options = listOf(
                            "mesh_contour" to "Mesh",
                            "cyberpunk_visor" to "Visor",
                            "golden_crown" to "Crown",
                            "scifi_hud" to "HUD",
                            "comic_frame" to "Comic"
                        ) + injectedCustomLenses.map { it.id to it.name.take(12) },
                        activeOptionId = currentArLensName,
                        onOptionSelect = { id ->
                            setArLens(id, showNotice = false, syncFloatingMenu = false)
                            lastActiveArLensName = id
                            refreshFloatingModeMenu()
                        }
                    ),
                    FloatingMenuSpec(
                        id = "face_scale",
                        label = "Size",
                        glyph = "⤢",
                        badge = String.format("%.1fx", calibScale),
                        isModified = Math.abs(calibScale - 1f) > 0.03f,
                        isSlider = true,
                        min = 50,
                        max = 200,
                        defaultVal = 100,
                        currentVal = (calibScale * 100f).toInt().coerceIn(50, 200),
                        formatVal = { v -> String.format("%.2fx", v / 100f) },
                        onSliderChange = { v ->
                            calibScale = v / 100f
                            pushArCalibrationToOverlay()
                        }
                    ),
                    FloatingMenuSpec(
                        id = "face_offset_y",
                        label = "Height",
                        glyph = "↕",
                        badge = "${if ((calibOffsetY * 100f).toInt() > 0) "+" else ""}${(calibOffsetY * 100f).toInt()}",
                        isModified = (calibOffsetY * 100f).toInt() != 0,
                        isSlider = true,
                        min = -40,
                        max = 40,
                        defaultVal = 0,
                        currentVal = (calibOffsetY * 100f).toInt().coerceIn(-40, 40),
                        formatVal = { v -> "${if (v > 0) "+" else ""}$v" },
                        onSliderChange = { v ->
                            calibOffsetY = v / 100f
                            pushArCalibrationToOverlay()
                        }
                    ),
                    FloatingMenuSpec(
                        id = "face_comic",
                        label = "Comic",
                        glyph = "★",
                        badge = if (ov.enableComicOnomatopoeia) "POW!" else "Off",
                        isModified = ov.enableComicOnomatopoeia,
                        options = listOf("off" to "Off", "on" to "POW! SFX"),
                        activeOptionId = if (ov.enableComicOnomatopoeia) "on" else "off",
                        onOptionSelect = { id ->
                            ov.enableComicOnomatopoeia = (id == "on")
                            refreshFloatingModeMenu()
                        }
                    ),
                    FloatingMenuSpec(
                        id = "face_glb",
                        label = "3D GLB",
                        glyph = "⬡",
                        badge = "Load",
                        isAction = true,
                        onAction = { launchGlbLensPicker() }
                    )
                )
                "hand" -> listOf(
                    FloatingMenuSpec(
                        id = "hand_style",
                        label = "Style",
                        glyph = "✦",
                        badge = when (ov.handPresetId) {
                            "luxury_jewelry" -> "Luxe"
                            "cyber_gauntlet" -> "Cyber"
                            "arcane_orb" -> "Orb"
                            "pyro_wand" -> "Flame"
                            else -> "On"
                        },
                        isModified = true,
                        options = listOf(
                            "luxury_jewelry" to "Luxe",
                            "cyber_gauntlet" to "Cyber",
                            "arcane_orb" to "Orb",
                            "pyro_wand" to "Flame"
                        ),
                        activeOptionId = ov.handPresetId,
                        onOptionSelect = { id ->
                            ov.applyHandArPreset(id)
                            refreshFloatingModeMenu()
                        }
                    ),
                    FloatingMenuSpec(
                        id = "hand_bones",
                        label = "Bones",
                        glyph = "⊞",
                        badge = if (ov.handShowSkeleton) "On" else "Off",
                        isModified = ov.handShowSkeleton,
                        options = listOf("off" to "Hidden", "on" to "Skeleton"),
                        activeOptionId = if (ov.handShowSkeleton) "on" else "off",
                        onOptionSelect = { id ->
                            ov.handShowSkeleton = (id == "on")
                            refreshFloatingModeMenu()
                        }
                    )
                )
                "body" -> listOf(
                    FloatingMenuSpec(
                        id = "body_style",
                        label = "Style",
                        glyph = "✦",
                        badge = when (ov.bodyPresetId) {
                            "cyber_angel" -> "Wings"
                            "hero_cape" -> "Cape"
                            "mecha_armor" -> "Mecha"
                            "chroma_studio" -> "Chroma"
                            else -> "On"
                        },
                        isModified = true,
                        options = listOf(
                            "cyber_angel" to "Wings",
                            "hero_cape" to "Cape",
                            "mecha_armor" to "Mecha",
                            "chroma_studio" to "Chroma"
                        ),
                        activeOptionId = ov.bodyPresetId,
                        onOptionSelect = { id ->
                            ov.applyBodyArPreset(id)
                            refreshFloatingModeMenu()
                        }
                    ),
                    FloatingMenuSpec(
                        id = "body_bones",
                        label = "Bones",
                        glyph = "⊞",
                        badge = if (ov.bodyShowSkeleton) "On" else "Off",
                        isModified = ov.bodyShowSkeleton,
                        options = listOf("off" to "Hidden", "on" to "Skeleton"),
                        activeOptionId = if (ov.bodyShowSkeleton) "on" else "off",
                        onOptionSelect = { id ->
                            ov.bodyShowSkeleton = (id == "on")
                            refreshFloatingModeMenu()
                        }
                    )
                )
                "world" -> listOf(
                    FloatingMenuSpec(
                        id = "world_scene",
                        label = "Object",
                        glyph = "⬡",
                        badge = when (ov.worldPresetId) {
                            "holo_showcase" -> "Cube"
                            "cyber_portal" -> "Portal"
                            "ar_measure" -> "Ruler"
                            "glb_world_stage" -> "3D"
                            else -> "On"
                        },
                        isModified = true,
                        options = listOf(
                            "holo_showcase" to "Cube",
                            "cyber_portal" to "Portal",
                            "ar_measure" to "Ruler",
                            "glb_world_stage" to "3D Stage"
                        ),
                        activeOptionId = ov.worldPresetId,
                        onOptionSelect = { id ->
                            ov.applyWorldArPreset(id)
                            syncWorldArLifecycleAndPower()
                            refreshFloatingModeMenu()
                        }
                    ),
                    FloatingMenuSpec(
                        id = "world_scale",
                        label = "Scale",
                        glyph = "⤢",
                        badge = String.format("%.1fx", ov.worldObjectScale),
                        isModified = Math.abs(ov.worldObjectScale - 1f) > 0.03f,
                        isSlider = true,
                        min = 50,
                        max = 200,
                        defaultVal = 100,
                        currentVal = (ov.worldObjectScale * 100f).toInt().coerceIn(50, 200),
                        formatVal = { v -> String.format("%.2fx", v / 100f) },
                        onSliderChange = { v ->
                            ov.worldObjectScale = v / 100f
                        }
                    ),
                    FloatingMenuSpec(
                        id = "world_ruler",
                        label = "Ruler",
                        glyph = "↔",
                        badge = if (ov.worldShowRuler) "On" else "Off",
                        isModified = ov.worldShowRuler,
                        options = listOf("off" to "Off", "on" to "On"),
                        activeOptionId = if (ov.worldShowRuler) "on" else "off",
                        onOptionSelect = { id ->
                            ov.worldShowRuler = (id == "on")
                            refreshFloatingModeMenu()
                        }
                    )
                )
                else -> listOf(
                    FloatingMenuSpec(
                        id = "beauty_look",
                        label = "Look",
                        glyph = "♥",
                        badge = when (ov.faceBeautyPresetId) {
                            "natural_glow" -> "Natural"
                            "red_carpet" -> "Glam"
                            "cyber_neon" -> "Cyber"
                            "kitsune_spirit" -> "Kitsune"
                            "golden_circuit" -> "Gold"
                            else -> "On"
                        },
                        isModified = true,
                        options = listOf(
                            "natural_glow" to "Natural",
                            "red_carpet" to "Glam",
                            "cyber_neon" to "Cyber",
                            "kitsune_spirit" to "Kitsune",
                            "golden_circuit" to "Gold"
                        ),
                        activeOptionId = ov.faceBeautyPresetId,
                        onOptionSelect = { id ->
                            ov.applyFaceBeautyPreset(id)
                            refreshFloatingModeMenu()
                        }
                    ),
                    FloatingMenuSpec(
                        id = "beauty_smooth",
                        label = "Smooth",
                        glyph = "✦",
                        badge = "${(ov.skinSmoothing * 100f).toInt()}%",
                        isModified = (ov.skinSmoothing * 100f).toInt() != 55,
                        isSlider = true,
                        min = 0,
                        max = 100,
                        defaultVal = 55,
                        currentVal = (ov.skinSmoothing * 100f).toInt().coerceIn(0, 100),
                        formatVal = { v -> "$v%" },
                        onSliderChange = { v ->
                            ov.faceBeautyEnabled = true
                            ov.skinSmoothing = v / 100f
                        }
                    ),
                    FloatingMenuSpec(
                        id = "beauty_lips",
                        label = "Lips",
                        glyph = "◉",
                        badge = "${(ov.lipOpacity * 100f).toInt()}%",
                        isModified = (ov.lipOpacity * 100f).toInt() != 35,
                        isSlider = true,
                        min = 0,
                        max = 100,
                        defaultVal = 35,
                        currentVal = (ov.lipOpacity * 100f).toInt().coerceIn(0, 100),
                        formatVal = { v -> "$v%" },
                        onSliderChange = { v ->
                            ov.faceBeautyEnabled = true
                            ov.lipOpacity = v / 100f
                        }
                    ),
                    FloatingMenuSpec(
                        id = "beauty_eyes",
                        label = "Eyes",
                        glyph = "◎",
                        badge = "${(ov.eyelinerOpacity * 100f).toInt()}%",
                        isModified = (ov.eyelinerOpacity * 100f).toInt() != 30,
                        isSlider = true,
                        min = 0,
                        max = 100,
                        defaultVal = 30,
                        currentVal = (ov.eyelinerOpacity * 100f).toInt().coerceIn(0, 100),
                        formatVal = { v -> "$v%" },
                        onSliderChange = { v ->
                            ov.faceBeautyEnabled = true
                            ov.eyelinerOpacity = v / 100f
                        }
                    )
                )
            }
        }

        // Standard Camera Modes: show ONLY that mode's relevant settings with compact glyphs & live active values
        val timerSpec = FloatingMenuSpec(
            id = "timer",
            label = "Timer",
            glyph = "◷",
            badge = if (countdownSeconds > 0) "${countdownSeconds}s" else "Off",
            isModified = countdownSeconds > 0,
            options = listOf("0" to "Off", "3" to "3s", "5" to "5s", "10" to "10s"),
            activeOptionId = countdownSeconds.toString(),
            onOptionSelect = { id ->
                countdownSeconds = id.toIntOrNull() ?: 0
                refreshFloatingModeMenu()
            }
        )
        val ratioSpec = FloatingMenuSpec(
            id = "ratio",
            label = "Ratio",
            glyph = "▭",
            badge = currentAspectRatio,
            isModified = currentAspectRatio != "4:3",
            options = listOf("4:3" to "4:3", "16:9" to "16:9", "1:1" to "1:1"),
            activeOptionId = currentAspectRatio,
            onOptionSelect = { id ->
                currentAspectRatio = id
                viewBinding.btnAspectRatio.text = id
                viewBinding.proOverlayView.sensorAspectRatio = id
                refreshFloatingModeMenu()
            }
        )
        val evState = camera?.cameraInfo?.exposureState
        val evMin = evState?.exposureCompensationRange?.lower ?: -6
        val evMax = evState?.exposureCompensationRange?.upper ?: 6
        val evCur = evState?.exposureCompensationIndex ?: 0
        val evStep = evState?.exposureCompensationStep?.toFloat() ?: 0.33f
        val evSpec = FloatingMenuSpec(
            id = "ev",
            label = "EV",
            glyph = "☀",
            badge = if (evCur == 0) "0.0" else String.format("%+.1f", evCur * evStep),
            isModified = evCur != 0,
            isSlider = true,
            min = evMin,
            max = evMax,
            defaultVal = 0,
            currentVal = evCur.coerceIn(evMin, evMax),
            formatVal = { v ->
                val evVal = v * evStep
                String.format("%+.1f EV", evVal)
            },
            onSliderChange = { v ->
                camera?.cameraControl?.setExposureCompensationIndex(v)
            }
        )
        val filterSpec = FloatingMenuSpec(
            id = "filter",
            label = "Filter",
            glyph = "◐",
            badge = when (currentFilterName) {
                "normal" -> "Norm"
                "cinematic" -> "Cine"
                "vintage" -> "Vint"
                "bw" -> "B&W"
                "hdr" -> "HDR"
                "cyberpunk" -> "Cyber"
                "comic_book" -> "Comic"
                else -> "On"
            },
            isModified = currentFilterName != "normal",
            options = listOf(
                "normal" to "Normal",
                "cinematic" to "Cinema",
                "vintage" to "Vintage",
                "bw" to "B&W",
                "hdr" to "HDR",
                "cyberpunk" to "Cyber",
                "comic_book" to "Comic"
            ),
            activeOptionId = currentFilterName,
            onOptionSelect = { id ->
                setFilterPreset(id, showNotice = false)
                refreshFloatingModeMenu()
            }
        )
        val gridSpec = FloatingMenuSpec(
            id = "grid",
            label = "Grid",
            glyph = "⊞",
            badge = when (viewBinding.proOverlayView.currentGrid) {
                ProOverlayView.GridType.NONE -> "Off"
                ProOverlayView.GridType.RULE_OF_THIRDS -> "3×3"
                ProOverlayView.GridType.GOLDEN_SPIRAL -> "Spiral"
                ProOverlayView.GridType.GOLDEN_RATIO_PHI -> "Phi"
                ProOverlayView.GridType.DIAGONAL_SYMMETRY -> "Cross"
                ProOverlayView.GridType.CINEMATIC_239 -> "2.39"
                else -> "On"
            },
            isModified = viewBinding.proOverlayView.currentGrid != ProOverlayView.GridType.NONE,
            options = listOf(
                "NONE" to "Off",
                "RULE_OF_THIRDS" to "3x3",
                "GOLDEN_SPIRAL" to "Spiral",
                "GOLDEN_RATIO_PHI" to "Phi",
                "DIAGONAL_SYMMETRY" to "Cross",
                "CINEMATIC_239" to "Cinema"
            ),
            activeOptionId = viewBinding.proOverlayView.currentGrid.name,
            onOptionSelect = { id ->
                val target = try { ProOverlayView.GridType.valueOf(id) } catch (_: Throwable) { ProOverlayView.GridType.NONE }
                setGridMode(target)
                refreshFloatingModeMenu()
            }
        )
        val activeToolsCount = listOf(
            isAutoBlurClearEnabled,
            isAutoClearBlurLogsEnabled,
            viewBinding.proOverlayView.showFocusPeaking,
            viewBinding.proOverlayView.showHistogram,
            viewBinding.proOverlayView.showZebraStripes,
            viewBinding.proOverlayView.showLevelGauge
        ).count { it }
        val proToolsSpec = FloatingMenuSpec(
            id = "pro_tools",
            label = "Tools",
            glyph = "⚙",
            badge = if (activeToolsCount > 0) "${activeToolsCount} On" else "Off",
            isModified = activeToolsCount > 0,
            options = listOf(
                "auto_blur_clear" to if (isAutoBlurClearEnabled) "Auto Clear Blur ✓" else "Auto Clear Blur",
                "auto_clear_blur_logs" to if (isAutoClearBlurLogsEnabled) "Auto-Clear Blur Logs ✓" else "Auto-Clear Blur Logs",
                "peaking" to if (viewBinding.proOverlayView.showFocusPeaking) "Peaking ✓" else "Peaking",
                "hist" to if (viewBinding.proOverlayView.showHistogram) "Histogram ✓" else "Histogram",
                "zebra" to if (viewBinding.proOverlayView.showZebraStripes) "Zebra ✓" else "Zebra",
                "level" to if (viewBinding.proOverlayView.showLevelGauge) "Level ✓" else "Level",
                "drawer" to "All Pro…"
            ),
            activeOptionId = "",
            onOptionSelect = { id ->
                when (id) {
                    "auto_blur_clear" -> {
                        isAutoBlurClearEnabled = !isAutoBlurClearEnabled
                        showHudNotice(if (isAutoBlurClearEnabled) "Auto Blur Clear: ON (Live AF)" else "Auto Blur Clear: OFF")
                        if (isAutoBlurClearEnabled) {
                            rollingPeakSharpness = 0f
                            consecutiveBlurFrames = 0
                            triggerAutoBlurClearSweep(silent = false)
                        }
                    }
                    "auto_clear_blur_logs" -> {
                        isAutoClearBlurLogsEnabled = !isAutoClearBlurLogsEnabled
                        showHudNotice(if (isAutoClearBlurLogsEnabled) "Auto-Clear Blur Logs: ON" else "Auto-Clear Blur Logs: OFF")
                    }
                    "peaking" -> viewBinding.proOverlayView.showFocusPeaking = !viewBinding.proOverlayView.showFocusPeaking
                    "hist" -> viewBinding.proOverlayView.showHistogram = !viewBinding.proOverlayView.showHistogram
                    "zebra" -> viewBinding.proOverlayView.showZebraStripes = !viewBinding.proOverlayView.showZebraStripes
                    "level" -> viewBinding.proOverlayView.showLevelGauge = !viewBinding.proOverlayView.showLevelGauge
                    "drawer" -> {
                        activeFloatingSettingId = null
                        toggleProDrawer()
                    }
                }
                refreshFloatingModeMenu()
            }
        )
        val micSpec = FloatingMenuSpec(
            id = "mic",
            label = "Mic",
            glyph = if (isAudioMuted) "✕" else "◉",
            badge = if (isAudioMuted) "Off" else "On",
            isModified = isAudioMuted,
            options = listOf("on" to "Mic On", "off" to "Muted"),
            activeOptionId = if (isAudioMuted) "off" else "on",
            onOptionSelect = { id ->
                val wantMuted = (id == "off")
                if (wantMuted != isAudioMuted) toggleAudioMute()
                refreshFloatingModeMenu()
            }
        )

        val panoDirSpec = FloatingMenuSpec(
            id = "pano_dir",
            label = "Dir",
            glyph = when (panoramaSweepDir) {
                PanoramaStitcher.SweepDirection.LEFT -> "←"
                PanoramaStitcher.SweepDirection.UP -> "↑"
                PanoramaStitcher.SweepDirection.DOWN -> "↓"
                else -> "→"
            },
            badge = when (panoramaSweepDir) {
                PanoramaStitcher.SweepDirection.LEFT -> "Left"
                PanoramaStitcher.SweepDirection.UP -> "Up"
                PanoramaStitcher.SweepDirection.DOWN -> "Down"
                else -> "Right"
            },
            isModified = panoramaSweepDir != PanoramaStitcher.SweepDirection.RIGHT,
            options = listOf(
                "RIGHT" to "Right →",
                "LEFT" to "Left ←",
                "UP" to "Up ↑",
                "DOWN" to "Down ↓"
            ),
            activeOptionId = panoramaSweepDir.name,
            onOptionSelect = { id ->
                val target = PanoramaStitcher.SweepDirection.values().firstOrNull { it.name == id }
                    ?: PanoramaStitcher.SweepDirection.RIGHT
                setPanoramaSweepDirection(target)
            }
        )
        val panoCloneSpec = FloatingMenuSpec(
            id = "pano_clone",
            label = "Clone",
            glyph = "✦",
            badge = if (isPanoramaCloneMode) "On" else "Off",
            isModified = isPanoramaCloneMode,
            options = listOf("off" to "Standard", "on" to "Clone Pano"),
            activeOptionId = if (isPanoramaCloneMode) "on" else "off",
            onOptionSelect = { id ->
                isPanoramaCloneMode = (id == "on")
                panoramaStitcher.cloneModeEnabled = isPanoramaCloneMode
                viewBinding.proOverlayView.panoramaCloneMode = isPanoramaCloneMode
                showHudNotice(if (isPanoramaCloneMode) "CLONE PANO ON · Multi-Pose Stitch" else "CLONE PANO OFF")
                refreshFloatingModeMenu()
            }
        )
        val panoPlanetSpec = FloatingMenuSpec(
            id = "pano_planet",
            label = "Planet",
            glyph = "◎",
            badge = if (isPanoramaTinyPlanetMode) "360°" else "Wide",
            isModified = isPanoramaTinyPlanetMode,
            options = listOf("off" to "Wide", "on" to "Tiny Planet"),
            activeOptionId = if (isPanoramaTinyPlanetMode) "on" else "off",
            onOptionSelect = { id ->
                isPanoramaTinyPlanetMode = (id == "on")
                viewBinding.proOverlayView.panoramaTinyPlanetMode = isPanoramaTinyPlanetMode
                showHudNotice(if (isPanoramaTinyPlanetMode) "TINY PLANET 360° OUTPUT ON" else "CLASSIC WIDE PANORAMA OUTPUT")
                refreshFloatingModeMenu()
            }
        )
        val panoFinishSpec = FloatingMenuSpec(
            id = "pano_finish",
            label = "Stitch",
            glyph = "✓",
            badge = "Done",
            isModified = true,
            isAction = true,
            onAction = { finishPanoramaCapture() }
        )

        val nightFramesSpec = FloatingMenuSpec(
            id = "night_frames",
            label = "Frames",
            glyph = "☾",
            badge = when (nightFramesMode) {
                "3" -> "3F"
                "5" -> "5F"
                "7" -> "7F"
                else -> "Auto"
            },
            isModified = nightFramesMode != "auto",
            options = listOf(
                "auto" to "Auto",
                "3" to "Fast 3F",
                "5" to "Deep 5F",
                "7" to "Astro 7F"
            ),
            activeOptionId = nightFramesMode,
            onOptionSelect = { id ->
                nightFramesMode = id
                showHudNotice("NIGHT FRAMES · ${id.uppercase(Locale.US)}")
                refreshFloatingModeMenu()
            }
        )
        val nightSkySpec = FloatingMenuSpec(
            id = "night_sky",
            label = "Sky",
            glyph = "✦",
            badge = if (nightSkyTone == "natural") "Nat" else "Bright",
            isModified = nightSkyTone != "natural",
            options = listOf(
                "natural" to "Natural Sky",
                "bright" to "Bright Boost"
            ),
            activeOptionId = nightSkyTone,
            onOptionSelect = { id ->
                nightSkyTone = id
                viewBinding.proOverlayView.nightSkyToneLabel = if (id == "natural") "NATURAL" else "BRIGHT"
                showHudNotice(if (id == "natural") "NIGHT SKY · Deep Natural Blacks" else "NIGHT SKY · Maximum Shadow Boost")
                refreshFloatingModeMenu()
            }
        )
        val nightAutoSpec = FloatingMenuSpec(
            id = "night_auto",
            label = "Auto",
            glyph = "◎",
            badge = if (isAutoNightStackEnabled) "On" else "Man",
            isModified = isAutoNightStackEnabled,
            options = listOf(
                "off" to "Manual",
                "on" to "Auto-Shoot"
            ),
            activeOptionId = if (isAutoNightStackEnabled) "on" else "off",
            onOptionSelect = { id ->
                isAutoNightStackEnabled = (id == "on")
                viewBinding.proOverlayView.nightAutoTriggerEnabled = isAutoNightStackEnabled
                showHudNotice(if (isAutoNightStackEnabled) "AUTO NIGHT SHOOT ON" else "MANUAL SHUTTER NIGHT MODE")
                refreshFloatingModeMenu()
            }
        )

        return when (cameraMode) {
            CameraMode.PHOTO -> listOf(timerSpec, ratioSpec, evSpec, filterSpec, gridSpec, proToolsSpec)
            CameraMode.PORTRAIT -> listOf(timerSpec, ratioSpec, evSpec, filterSpec, gridSpec)
            CameraMode.NIGHT -> listOf(nightFramesSpec, nightSkySpec, nightAutoSpec, evSpec, timerSpec, filterSpec, gridSpec)
            CameraMode.VIDEO -> listOf(ratioSpec, evSpec, micSpec, filterSpec, gridSpec, proToolsSpec)
            CameraMode.SLOW_MO -> listOf(evSpec, micSpec, filterSpec, gridSpec)
            CameraMode.PANORAMA -> if (isPanoramaCapturing && isPanoramaCloneMode) {
                listOf(panoFinishSpec, panoDirSpec, panoCloneSpec, panoPlanetSpec, evSpec, gridSpec)
            } else {
                listOf(panoDirSpec, panoCloneSpec, panoPlanetSpec, evSpec, gridSpec, filterSpec)
            }
            CameraMode.AR_LENS -> emptyList()
        }
    }

    private fun refreshFloatingModeMenu() {
        val fm = viewBinding.floatingModeMenu
        val isAr = (cameraMode == CameraMode.AR_LENS)
        val dp = resources.displayMetrics.density

        // 1. AR Swipeable Strip (visible ONLY in AR Mode)
        fm.layoutArSwipeStrip.visibility = if (isAr) View.VISIBLE else View.GONE
        if (isAr) {
            val arButtons = listOf(
                fm.btnArFamilyOff to "off",
                fm.btnArFamilySnapMagic to "snap_magic",
                fm.btnArFamilyFace to "face",
                fm.btnArFamilyHand to "hand",
                fm.btnArFamilyBody to "body",
                fm.btnArFamilyWorld to "world",
                fm.btnArFamilyBeauty to "beauty"
            )
            var activeArBtn: TextView? = null
            for ((btn, famId) in arButtons) {
                val active = (activeArFamily == famId)
                if (active) activeArBtn = btn
                btn.setBackgroundResource(
                    if (active) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive
                )
                btn.setTextColor(
                    Color.parseColor(
                        if (active) "#0F172A" else if (famId == "snap_magic") "#FDE047" else "#CBD5E1"
                    )
                )
            }
            activeArBtn?.let { targetBtn ->
                fm.scrollArFamilyStrip.post {
                    val scrollTarget = (targetBtn.left + targetBtn.width / 2) - (fm.scrollArFamilyStrip.width / 2)
                    fm.scrollArFamilyStrip.smoothScrollTo(scrollTarget.coerceAtLeast(0), 0)
                }
            }
        }

        // 2. Right Floating Arrow & Mode-Specific Vertical Bar
        val isLandscape = (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        val specs = buildFloatingMenuSpecs()
        val showRightHandle = (!isAr || activeArFamily != "off") && specs.isNotEmpty()
        fm.floatingRightMenuWrapper.visibility = if (showRightHandle) View.VISIBLE else View.GONE
        (fm.floatingRightMenuWrapper.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
            lp.gravity = if (isLandscape) (android.view.Gravity.END or android.view.Gravity.TOP) else (android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL)
            lp.topMargin = ((if (isLandscape) 42 else 0) * dp).toInt()
            lp.bottomMargin = ((if (isLandscape) (if (isAr) 54 else 8) else 0) * dp).toInt()
            fm.floatingRightMenuWrapper.layoutParams = lp
        }
        (fm.scrollFloatingMenuItems.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
            lp.height = if (isLandscape) {
                ((resources.displayMetrics.heightPixels * 0.44f).toInt()).coerceIn((130 * dp).toInt(), (215 * dp).toInt())
            } else {
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            }
            fm.scrollFloatingMenuItems.layoutParams = lp
        }
        fm.btnFloatingMenuArrow.text = if (isFloatingMenuOpen) "▶" else "◀"
        fm.scrollFloatingMenuItems.visibility = if (isFloatingMenuOpen && showRightHandle) View.VISIBLE else View.GONE

        fm.containerFloatingMenuItems.removeAllViews()
        if (isFloatingMenuOpen && showRightHandle) {
            val vPad = ((if (isLandscape) 3 else 7) * dp).toInt()
            val bMargin = ((if (isLandscape) 3 else 5) * dp).toInt()
            for (spec in specs) {
                val isSelected = (activeFloatingSettingId == spec.id)
                val itemBox = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    setPadding((4 * dp).toInt(), vPad, (4 * dp).toInt(), vPad)
                    setBackgroundResource(
                        if (isSelected) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive
                    )
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = bMargin
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (spec.isAction) {
                            spec.onAction?.invoke()
                        } else {
                            activeFloatingSettingId = if (activeFloatingSettingId == spec.id) null else spec.id
                            refreshFloatingModeMenu()
                        }
                    }
                }
                val titleText = if (spec.glyph.isNotEmpty()) "${spec.glyph} ${spec.label}" else spec.label
                val tvTitle = TextView(this).apply {
                    text = titleText
                    textSize = if (isLandscape) 8.5f else 9.5f
                    setTypeface(null, Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER
                    setSingleLine(true)
                    setTextColor(Color.parseColor(if (isSelected) "#0F172A" else "#F1F5F9"))
                }
                itemBox.addView(tvTitle)

                if (spec.badge.isNotEmpty()) {
                    val tvBadge = TextView(this).apply {
                        text = spec.badge
                        textSize = if (isLandscape) 7.5f else 8.5f
                        typeface = Typeface.MONOSPACE
                        gravity = android.view.Gravity.CENTER
                        setSingleLine(true)
                        setPadding(0, (1 * dp).toInt(), 0, 0)
                        setTextColor(
                            Color.parseColor(
                                when {
                                    isSelected -> "#0F172A"
                                    spec.isModified -> if (isAr) "#22D3EE" else "#FACC15"
                                    else -> "#94A3B8"
                                }
                            )
                        )
                    }
                    itemBox.addView(tvBadge)
                }
                fm.containerFloatingMenuItems.addView(itemBox)
            }
        }

        // 3. Bottom Parameter Bar (Slider for numbers / Horizontal Option Chips for choices)
        val activeSpec = specs.find { it.id == activeFloatingSettingId && !it.isAction }
        val paramBarOpen = isFloatingMenuOpen && activeSpec != null
        // Auto-hide the floating zoom pill whenever a bottom parameter tray is open so they never collide
        viewBinding.zoomControls.visibility = if (isAr || paramBarOpen) View.GONE else View.VISIBLE

        val paramLp = fm.layoutFloatingParamBar.layoutParams as? android.widget.FrameLayout.LayoutParams
        if (paramLp != null) {
            paramLp.bottomMargin = ((if (isAr) (if (isLandscape) 52 else 64) else (if (isLandscape) 6 else 12)) * dp).toInt()
            fm.layoutFloatingParamBar.layoutParams = paramLp
        }

        if (paramBarOpen && activeSpec != null) {
            fm.layoutFloatingParamBar.visibility = View.VISIBLE
            fm.tvFloatingParamTitle.text = activeSpec.label
            if (activeSpec.isSlider) {
                fm.seekFloatingParam.visibility = View.VISIBLE
                fm.scrollFloatingParamOptions.visibility = View.GONE
                val span = (activeSpec.max - activeSpec.min).coerceAtLeast(1)
                fm.seekFloatingParam.setOnSeekBarChangeListener(null)
                fm.seekFloatingParam.max = span
                fm.seekFloatingParam.progress = (activeSpec.currentVal - activeSpec.min).coerceIn(0, span)
                fm.tvFloatingParamValue.text = activeSpec.formatVal?.invoke(activeSpec.currentVal) ?: activeSpec.currentVal.toString()
                fm.btnFloatingParamReset.visibility = if (activeSpec.currentVal != activeSpec.defaultVal) View.VISIBLE else View.GONE
                fm.btnFloatingParamReset.setOnClickListener {
                    activeSpec.onSliderChange?.invoke(activeSpec.defaultVal)
                    refreshFloatingModeMenu()
                }
                fm.seekFloatingParam.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val actual = activeSpec.min + progress
                        fm.tvFloatingParamValue.text = activeSpec.formatVal?.invoke(actual) ?: actual.toString()
                        fm.btnFloatingParamReset.visibility = if (actual != activeSpec.defaultVal) View.VISIBLE else View.GONE
                        activeSpec.onSliderChange?.invoke(actual)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        refreshFloatingModeMenu()
                    }
                })
            } else {
                fm.seekFloatingParam.visibility = View.GONE
                fm.btnFloatingParamReset.visibility = View.GONE
                fm.tvFloatingParamValue.text = ""
                fm.scrollFloatingParamOptions.visibility = View.VISIBLE
                fm.containerFloatingParamOptions.removeAllViews()
                for ((optId, optLabel) in activeSpec.options) {
                    val isOptActive = (activeSpec.activeOptionId == optId)
                    val chip = TextView(this).apply {
                        text = optLabel
                        textSize = 11f
                        gravity = android.view.Gravity.CENTER
                        setSingleLine(true)
                        setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
                        setBackgroundResource(
                            if (isOptActive) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive
                        )
                        setTextColor(Color.parseColor(if (isOptActive) "#0F172A" else "#E2E8F0"))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            marginEnd = (6 * dp).toInt()
                        }
                        setOnClickListener {
                            activeSpec.onOptionSelect?.invoke(optId)
                        }
                    }
                    fm.containerFloatingParamOptions.addView(chip)
                }
            }
        } else {
            fm.layoutFloatingParamBar.visibility = View.GONE
        }
    }

    private fun toggleArBatchMode() {
        isBatchModeActive = !isBatchModeActive
        if (isBatchModeActive && batchLensQueue.isEmpty()) {
            batchLensQueue.addAll(arLensOrder.filter { it != "none" })
            batchStepIndex = 0
        }
        if (isBatchModeActive) {
            viewBinding.arCalibrationDrawer.visibility = View.VISIBLE
            startBatchLoop()
        } else {
            stopBatchLoop()
        }
        viewBinding.arLensModeDock.btnArBatch.text = if (isBatchModeActive) "BATCH: ON" else "BATCH: OFF"
        viewBinding.arLensModeDock.btnArBatch.setBackgroundResource(
            if (isBatchModeActive) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive
        )
        viewBinding.arLensModeDock.btnArBatch.setTextColor(Color.parseColor(if (isBatchModeActive) "#0F172A" else "#94A3B8"))
    }

    private fun stepBatchQueue(delta: Int) {
        if (batchLensQueue.isEmpty()) return
        batchStepIndex = ((batchStepIndex + delta) % batchLensQueue.size + batchLensQueue.size) % batchLensQueue.size
        setArLens(batchLensQueue[batchStepIndex])
        updateBatchQueueHud()
    }

    private fun startBatchLoop() {
        stopBatchLoop()
        if (batchLensQueue.isEmpty()) return
        isBatchPlaying = true
        setArLens(batchLensQueue[batchStepIndex % batchLensQueue.size])
        updateBatchQueueHud()
        batchRunnable = object : Runnable {
            override fun run() {
                if (!isBatchPlaying || batchLensQueue.isEmpty()) return
                stepBatchQueue(1)
                batchHandler.postDelayed(this, 2400L)
            }
        }
        batchHandler.postDelayed(batchRunnable!!, 2400L)
    }

    private fun stopBatchLoop() {
        isBatchPlaying = false
        batchRunnable?.let { batchHandler.removeCallbacks(it) }
        batchRunnable = null
        updateBatchQueueHud()
    }

    private fun updateBatchQueueHud() {
        val total = batchLensQueue.size
        val step = if (total > 0) (batchStepIndex % total) + 1 else 0
        viewBinding.tvBatchQueueStatus.text = "QUEUE: $step/$total ${if (isBatchPlaying) "· PLAYING" else "· PAUSED"}"
        viewBinding.btnBatchPlayPause.text = if (isBatchPlaying) "PAUSE" else "PLAY"
    }

    private fun syncOverlayToVideoProcessor(forceUpload: Boolean = false) {
        val proc = openGLProcessor ?: return
        proc.setOverlayCameraFacing(lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA)
        val overlayView = viewBinding.arFaceOverlayView
        if ((recording == null && !forceUpload) || !overlayView.hasAnyVisibleOverlay()) {
            proc.updateOverlayBitmap(null)
            return
        }
        val w = overlayView.width.takeIf { it > 0 } ?: 720
        val h = overlayView.height.takeIf { it > 0 } ?: 1280
        val bmp = overlayView.renderExportOverlayBitmap(w, h)
        proc.updateOverlayBitmap(bmp)
    }

    private fun showArTextStudioDialog() {
        val overlay = viewBinding.arFaceOverlayView
        val selected = overlay.arTextItems.firstOrNull { it.id == overlay.selectedTextId } ?: overlay.arTextItems.lastOrNull()
        val colors = listOf("#FFFFFF", "#FACC15", "#22D3EE", "#FB7185", "#34D399", "#A78BFA")
        val styles = ARFaceOverlayView.ArTextStyle.values()

        var chosenColor = selected?.colorHex ?: "#FACC15"
        var chosenStyle = selected?.style ?: ARFaceOverlayView.ArTextStyle.NEON
        var chosenScale = selected?.scale ?: 1.0f

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 28, 48, 12)
        }
        val input = EditText(this).apply {
            hint = "Write AR text (hold & drag on screen)"
            setText(selected?.text ?: "HELLO AR!")
            setSingleLine(true)
        }
        val tvSize = TextView(this).apply {
            text = "Size: ${(chosenScale * 100).toInt()}%"
            setPadding(0, 18, 0, 4)
        }
        val sbSize = SeekBar(this).apply {
            max = 185
            progress = ((chosenScale - 0.55f) * 100f).toInt().coerceIn(0, 185)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    chosenScale = 0.55f + (p / 100f)
                    tvSize.text = "Size: ${(chosenScale * 100).toInt()}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val btnStyle = TextView(this).apply {
            text = "Style: ${chosenStyle.name} (Tap to cycle)"
            setPadding(0, 18, 0, 12)
            setTextColor(Color.parseColor("#38BDF8"))
            setOnClickListener {
                chosenStyle = styles[(chosenStyle.ordinal + 1) % styles.size]
                text = "Style: ${chosenStyle.name} (Tap to cycle)"
            }
        }
        val btnColor = TextView(this).apply {
            text = "Color: ${chosenColor} (Tap to cycle)"
            setPadding(0, 6, 0, 12)
            setTextColor(Color.parseColor(chosenColor))
            setOnClickListener {
                val idx = colors.indexOf(chosenColor).let { if (it < 0) 0 else (it + 1) % colors.size }
                chosenColor = colors[idx]
                text = "Color: ${chosenColor} (Tap to cycle)"
                setTextColor(Color.parseColor(chosenColor))
            }
        }
        container.addView(input)
        container.addView(tvSize)
        container.addView(sbSize)
        container.addView(btnStyle)
        container.addView(btnColor)

        val builder = AlertDialog.Builder(this)
            .setTitle("AR Text Studio (Photo & Video)")
            .setView(container)
            .setPositiveButton(if (selected != null) "Update" else "Place Text") { _, _ ->
                val txt = input.text.toString().trim().ifEmpty { "HELLO AR!" }
                if (selected != null) {
                    overlay.updateSelectedArText(txt, chosenScale, chosenColor, chosenStyle)
                } else {
                    overlay.addOrSelectArText(txt, chosenScale, chosenColor, chosenStyle)
                }
                viewBinding.arLensModeDock.btnArTextStudio.text = if (overlay.hasArTextItems()) "TEXT (${overlay.arTextItems.size})" else "Aa AR TEXT STUDIO"
                showHudNotice("Hold & drag AR text anywhere on screen")
            }
            .setNeutralButton("Add New") { _, _ ->
                val txt = input.text.toString().trim().ifEmpty { "HELLO AR!" }
                overlay.addOrSelectArText(txt, chosenScale, chosenColor, chosenStyle)
                viewBinding.arLensModeDock.btnArTextStudio.text = "TEXT (${overlay.arTextItems.size})"
                showHudNotice("Added AR text — hold & drag to move")
            }
        if (selected != null) {
            builder.setNegativeButton("Delete") { _, _ ->
                overlay.removeSelectedArText()
                viewBinding.arLensModeDock.btnArTextStudio.text = if (overlay.hasArTextItems()) "TEXT (${overlay.arTextItems.size})" else "Aa AR TEXT STUDIO"
            }
        } else {
            builder.setNegativeButton("Cancel", null)
        }
        builder.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        dollyZoomAnimator?.cancel()
        panoramaFallbackHandler.removeCallbacksAndMessages(null)
        nightHandler.removeCallbacksAndMessages(null)
        if (::ecoPowerManager.isInitialized) {
            ecoPowerManager.unregister()
        }
        if (::thermalGuardManager.isInitialized) {
            thermalGuardManager.unregister()
        }
        if (::gyroStabilizer.isInitialized) {
            gyroStabilizer.unregister()
        }
        panoramaStitcher.reset()
        nightExposureStacker.reset()
        clearPanoramaSlices()
        stylizeHandler.removeCallbacksAndMessages(null)
        batchHandler.removeCallbacksAndMessages(null)
        autoLoopHandler.removeCallbacksAndMessages(null)
        motionBlurProcessor.reset()
        stylizeExecutor?.shutdownNow()
        openGLProcessor?.release()
        comicSfxHelper?.release()
        try { toneGenerator?.release() } catch (_: Throwable) {}
        try { mediaActionSound?.release() } catch (_: Throwable) {}
        mediaActionSound = null
        sensorManager.unregisterListener(sensorEventListener)
        faceMeshHelper?.clear()
        handLandmarkerHelper?.clear()
        poseLandmarkerHelper?.clear()
        worldArTracker?.stop()
        viewBinding.arFaceOverlayView.releaseGlbEngine()
        cameraExecutor.shutdown()
        burstHandler.removeCallbacksAndMessages(null)
        arHoldHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val TAG = "CameraXPro"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}

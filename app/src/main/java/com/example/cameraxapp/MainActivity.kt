package com.example.cameraxapp

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
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
import com.example.cameraxapp.ar.ARFaceOverlayView
import com.example.cameraxapp.audio.ComicSoundEffectsHelper
import com.example.cameraxapp.font.CameraFontHelper
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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

    // Default lens is BACK camera
    private var lensFacing: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    // Real-Time Filter, MediaPipe 468-Mesh & Concurrent Multi-Cam PiP Engine
    private lateinit var filterManager: FilterManager
    private var faceMeshHelper: FaceMeshHelper? = null
    @Volatile
    private var isMediaPipeSupported: Boolean = true
    @Volatile
    private var lastMediaPipeLandmarkTime: Long = 0L
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

    // AR Lens Mode Dedicated Dock & Tap-Photo / Hold (>=1s) Video Recording State
    private enum class ArDockTab { LENSES, ADJUST, COMIC_TEXT }
    private var currentArDockTab = ArDockTab.LENSES
    private var isArDockCollapsed: Boolean = false
    private var isSyncingArDockSliders: Boolean = false
    private var lastActiveArLensName: String = "cyberpunk_visor"
    private var isArHoldRecording: Boolean = false
    private val arHoldHandler = Handler(Looper.getMainLooper())
    private var arHoldRunnable: Runnable? = null

    // Audio Cue, Comic SFX & Typography Engines
    private var toneGenerator: ToneGenerator? = null
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
        val autoSpin: Boolean = true
    )
    private val injectedCustomLenses = mutableListOf<CustomLensDef>(
        CustomLensDef("lens_neon_cat_ears", "Neon Feline Ears", "forehead", "cat_ears", "#ec4899", "#38bdf8", "", "", 1f, 0f, 0f, 0f, true),
        CustomLensDef("lens_celestial_halo", "Celestial Halo", "forehead", "halo_tiara", "#fbbf24", "#ffffff", "", "", 1f, 0f, 0f, 0f, true),
        CustomLensDef("lens_cyber_respirator", "Cyber Mask 2077", "mouth", "cyber_mask", "#06b6d4", "#f43f5e", "", "", 1f, 0f, 0f, 0f, true)
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

    // Automated Night Mode Low-Light ImageAnalysis & Multi-Frame Exposure Stacking State
    private val nightExposureStacker = NightExposureStacker()
    @Volatile
    private var isNightStacking = false
    @Volatile
    private var isAutoNightStackEnabled = true
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

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                // roll: orientationAngles[2] in radians (-PI to PI)
                val rollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
                val pitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
                smoothRollDeg = smoothRollDeg * 0.78f + rollDeg * 0.22f
                viewBinding.proOverlayView.tiltRollDegrees = rollDeg
                viewBinding.proOverlayView.tiltPitchDegrees = pitchDeg
                val viewEisControlsRotation = (::gyroStabilizer.isInitialized && gyroStabilizer.isEnabled && cameraEffect == null)
                if (currentShootingEffect == "action_stabilization") {
                    viewBinding.viewFinder.rotation = -smoothRollDeg.coerceIn(-45f, 45f)
                } else if (currentShootingEffect == "hyperlapse") {
                    viewBinding.viewFinder.rotation = -(smoothRollDeg * 0.45f).coerceIn(-18f, 18f)
                } else if (!viewEisControlsRotation && viewBinding.viewFinder.rotation != 0f) {
                    viewBinding.viewFinder.rotation = 0f
                }

                // Remap axes for upright handheld portrait sweep yaw & pitch (fused into ImageAnalysis Panorama tracking)
                SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remappedMatrix)
                SensorManager.getOrientation(remappedMatrix, uprightAngles)
                latestUprightYawDeg = Math.toDegrees(uprightAngles[0].toDouble()).toFloat()
                latestUprightPitchDeg = Math.toDegrees(uprightAngles[1].toDouble()).toFloat()
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
        selectCreativeCategory(CreativeTarget.FILTERS, fromUser = false)
        updateShootingEffectButtons()
        updateActiveInstrumentsCount()
        isLastActionCreative = false
        viewBinding.modeScrollView.post { centerModeTab(cameraMode, smooth = false) }
        syncStylizeLoopState()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
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
        sensorManager.unregisterListener(sensorEventListener)
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

                    // Automated Panorama Sequence: CameraX ImageAnalysis Overlap Detection, Stitch Alignment & Guidance Arrows
                    if (cameraMode == CameraMode.PANORAMA && isPanoramaCapturing && !isPanoramaStitching) {
                        try {
                            val isFront = (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA)
                            val deltaYaw = normalizeAngleDelta(panoramaStartYawDeg, latestUprightYawDeg)
                            val pitchDrift = latestUprightPitchDeg - panoramaStartPitchDeg
                            val align = panoramaStitcher.analyzeFrame(
                                imageProxy = imageProxy,
                                isFrontFacing = isFront,
                                gyroDeltaYawDeg = deltaYaw,
                                gyroPitchDriftDeg = pitchDrift
                            )
                            panoramaDirection = align.panDirection

                            var newCount = align.sliceCount
                            var didAutoCapture = false
                            if (align.shouldTriggerAutoCapture) {
                                val sliceBmp = panoramaStitcher.imageProxyToBitmap(imageProxy, isFront, 1080)
                                if (sliceBmp != null) {
                                    val filtered = try {
                                        val f = filterManager.applyFilterToBitmap(sliceBmp)
                                        if (f != sliceBmp && !sliceBmp.isRecycled) sliceBmp.recycle()
                                        f
                                    } catch (_: Throwable) {
                                        sliceBmp
                                    }
                                    panoramaStitcher.lockCurrentFrameAsAnchor(deltaYaw, Math.abs(align.dxNorm))
                                    synchronized(panoramaLock) {
                                        if (isPanoramaCapturing) {
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
                            val shouldFinishNow = align.shouldAutoFinish || newCount >= 14

                            runOnUiThread {
                                if (!isPanoramaCapturing) return@runOnUiThread
                                viewBinding.proOverlayView.updatePanoramaAnalysis(
                                    guidance = uiGuidance,
                                    progress = align.estimatedProgress,
                                    overlapPct = overlapPct,
                                    dxNorm = align.dxNorm,
                                    dyNorm = align.dyNorm,
                                    confidence = align.confidence,
                                    yawDeg = deltaYaw,
                                    pitchDriftDeg = pitchDrift,
                                    sliceCount = newCount,
                                    direction = align.panDirection
                                )
                                if (didAutoCapture) {
                                    viewBinding.chronometer.text = "${newCount}/14 F · ${overlapPct}%"
                                    playCameraCue(ToneGenerator.TONE_PROP_BEEP, 30)
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
                            runOnUiThread {
                                viewBinding.proOverlayView.isNightModeHudActive = showHud
                                viewBinding.proOverlayView.updateNightAnalysis(
                                    avgLuma = nightInfo.avgLuma,
                                    darkRatio = nightInfo.darkPixelRatio,
                                    isLowLight = nightInfo.isLowLight,
                                    dwellProgress = nightInfo.dwellProgress,
                                    recommendedFrames = nightInfo.recommendedFrameCount,
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
                        viewBinding.arFaceOverlayView.enableComicOnomatopoeia ||
                        viewBinding.arFaceOverlayView.enableComicSpeechBubble)

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
                                val faces = arrayOfNulls<FaceDetector.Face>(1)
                                val count = FaceDetector(outW, outH, 1).findFaces(faceBmp, faces)
                                if (count > 0 && faces[0] != null) {
                                    val mid = PointF()
                                    faces[0]!!.getMidPoint(mid)
                                    val eyeDist = faces[0]!!.eyesDistance()
                                    viewBinding.arFaceOverlayView.updateSimpleFace(
                                        normEyeBridgeX = (mid.x / outW.toFloat()).coerceIn(0.05f, 0.95f),
                                        normEyeBridgeY = (mid.y / outH.toFloat()).coerceIn(0.05f, 0.95f),
                                        normEyeDist = (eyeDist / outW.toFloat()).coerceIn(0.04f, 0.55f),
                                        isFrontFacing = lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA
                                    )
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
        viewBinding.btnCapture.setOnTouchListener { _, event ->
            if (cameraMode == CameraMode.PANORAMA) {
                if (event.action == MotionEvent.ACTION_UP) {
                    togglePanoramaCapture()
                }
                return@setOnTouchListener true
            }
            if (cameraMode == CameraMode.AR_LENS) {
                // AR LENS Mode: Tap (< 1000ms) captures AR Photo; Hold (>= 1000ms) records AR Video until release
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isArHoldRecording = false
                        arHoldRunnable?.let { arHoldHandler.removeCallbacks(it) }
                        arHoldRunnable = Runnable {
                            if (cameraMode == CameraMode.AR_LENS && recording == null && !isCountingDown) {
                                isArHoldRecording = true
                                window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                viewBinding.arLensModeDock.root.visibility = View.GONE
                                viewBinding.arCalibrationDrawer.visibility = View.GONE
                                captureVideo()
                            }
                        }
                        arHoldHandler.postDelayed(arHoldRunnable!!, 1000L)
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        arHoldRunnable?.let { arHoldHandler.removeCallbacks(it) }
                        arHoldRunnable = null
                        if (isArHoldRecording) {
                            isArHoldRecording = false
                            if (recording != null) {
                                captureVideo()
                            }
                        } else if (event.action == MotionEvent.ACTION_UP && recording == null) {
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

                MotionEvent.ACTION_UP -> {
                    burstHandler.removeCallbacks(burstRunnable!!)
                    if (isBurstCapturing) {
                        // Conclude burst capture
                        isBurstCapturing = false
                        viewBinding.tvBurstBadge.visibility = View.GONE
                        Toast.makeText(this, "Captured $burstCount burst photos!", Toast.LENGTH_SHORT).show()
                    } else {
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

    private fun togglePanoramaCapture() {
        if (isPanoramaStitching) return
        if (!isPanoramaCapturing) {
            startPanoramaCapture()
        } else {
            finishPanoramaCapture()
        }
    }

    private fun startPanoramaCapture() {
        clearPanoramaSlices()
        panoramaStitcher.reset()
        isPanoramaCapturing = true
        panoramaStartYawDeg = latestUprightYawDeg
        panoramaStartPitchDeg = latestUprightPitchDeg
        panoramaDirection = 0

        viewBinding.proOverlayView.isPanoramaCapturing = true
        viewBinding.proOverlayView.updatePanoramaAnalysis(
            guidance = "PAN_RIGHT",
            progress = 0f,
            overlapPct = 100,
            dxNorm = 0f,
            dyNorm = 0f,
            confidence = 1f,
            yawDeg = 0f,
            pitchDriftDeg = 0f,
            sliceCount = 0,
            direction = 0
        )
        viewBinding.btnCapture.setImageResource(R.drawable.bg_record_button_active)
        viewBinding.tvRecordingStatus.text = "AUTO PANO"
        viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#FACC15"))
        viewBinding.chronometer.text = "0/14 F · 100%"
        playCameraCue(ToneGenerator.TONE_PROP_BEEP2, 90)
        showHudNotice("AUTO PANORAMA · FOLLOW ARROWS TO CAPTURE")
    }

    private fun cancelPanoramaCapture() {
        isPanoramaCapturing = false
        panoramaFallbackRunnable?.let { panoramaFallbackHandler.removeCallbacks(it) }
        panoramaFallbackRunnable = null
        panoramaStitcher.reset()
        viewBinding.proOverlayView.isPanoramaCapturing = false
        viewBinding.proOverlayView.updatePanoramaAnalysis("READY", 0f, 100, 0f, 0f, 0f, 0f, 0f, 0, 0)
        clearPanoramaSlices()
        if (cameraMode == CameraMode.PANORAMA) {
            viewBinding.btnCapture.setImageResource(R.drawable.bg_shutter_button)
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
        panoramaFallbackRunnable?.let { panoramaFallbackHandler.removeCallbacks(it) }
        panoramaFallbackRunnable = null
        viewBinding.proOverlayView.isPanoramaCapturing = false
        viewBinding.btnCapture.setImageResource(R.drawable.bg_shutter_button)

        val snapshot: List<PanoramaStitcher.PanoramaSlice>
        synchronized(panoramaLock) {
            snapshot = ArrayList(panoramaSlices)
            panoramaSlices.clear()
        }

        if (snapshot.isEmpty()) return
        if (snapshot.size == 1) {
            Toast.makeText(this, "Pan slowly along arrows before stopping (saved anchor frame)", Toast.LENGTH_SHORT).show()
        }

        isPanoramaStitching = true
        viewBinding.tvRecordingStatus.text = "STITCH"
        viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#38BDF8"))
        showHudNotice("ALIGNING & STITCHING ${snapshot.size} PANORAMA FRAMES...")

        cameraExecutor.execute {
            var stitched: Bitmap? = null
            try {
                // Delegate pixel-strip alignment refinement & feather Canvas stitching to PanoramaStitcher
                val cleanPano = panoramaStitcher.stitchSlices(snapshot) ?: return@execute
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

                val name = "PANO_" + SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
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
                        playCameraCue(ToneGenerator.TONE_PROP_BEEP2, 120)
                        viewBinding.tvRecordingStatus.text = "PANO"
                        viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#38BDF8"))
                        viewBinding.chronometer.text = "AUTO-STITCH"
                        showHudNotice("Saved Panorama (${outW}×${outH})")
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
                    if (cameraMode == CameraMode.PANORAMA && !isPanoramaCapturing) {
                        viewBinding.proOverlayView.updatePanoramaAnalysis("READY", 0f, 100, 0f, 0f, 0f, 0f, 0f, 0, 0)
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
        "panorama" -> "PANORAMA"
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

    private fun setArLens(lens: String, showNotice: Boolean = true) {
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
                        when (customLens.anchor.lowercase(Locale.ROOT)) {
                            "forehead" -> ARFaceOverlayView.ArLensMode.GOLDEN_CROWN
                            "eyes" -> ARFaceOverlayView.ArLensMode.CYBERPUNK_VISOR
                            "mouth", "nose_cheeks" -> ARFaceOverlayView.ArLensMode.SCIFI_HUD
                            else -> ARFaceOverlayView.ArLensMode.MESH_CONTOUR
                        }
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
        if (isAutoAdaptEnabled && lens != "none" && lens != prevLens) {
            val recommendedFront = lens in setOf("mesh_contour", "cyberpunk_visor", "golden_crown", "comic_frame") ||
                mappedMode == ARFaceOverlayView.ArLensMode.GLB_3D
            val isCurrentlyFront = (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA)
            if (recommendedFront != isCurrentlyFront && recording == null) {
                switchCamera()
            }
        }
        if (showNotice) {
            if (mappedMode == ARFaceOverlayView.ArLensMode.COMIC_FRAME && !isCartoonShaderActive()) {
                showHudNotice("AR Lens: COMIC FRAME (Activate Comic Book or Toon shader)", isWarning = true)
            } else if (mappedMode == ARFaceOverlayView.ArLensMode.GLB_3D) {
                val displayName = injectedCustomLenses.find { it.id == lens }?.name ?: lens
                showHudNotice("3D GLB AR Lens: ${displayName.uppercase(Locale.ROOT)}")
            } else {
                showHudNotice("AR Lens: ${lens.replace('_', ' ').uppercase(Locale.ROOT)}")
            }
        }
    }

    private fun updateArDockStatusLabel() {
        val total = (arLensOrder.size - 1).coerceAtLeast(1)
        val idx = arLensOrder.indexOf(currentArLensName)
        val rawDisplay = injectedCustomLenses.find { it.id == currentArLensName }?.name ?: currentArLensName
        val label = rawDisplay.replace("_", " ").replace(Regex("\\s+"), " ").trim().uppercase(Locale.ROOT)
        viewBinding.arLensModeDock.tvArDockStatus.text = when {
            currentArLensName == "comic_frame" && isCartoonShaderActive() -> "AR LENS: $label · PANEL ON ($idx/$total)"
            currentArLensName == "comic_frame" -> "AR LENS: $label · NEED TOON/COMIC"
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
            // silent (no popup)
        } else {
            viewBinding.btnCleanScreen.setImageResource(R.drawable.ic_eye)
            viewBinding.btnCleanScreen.setColorFilter(Color.parseColor("#CCCCCC"))
            if (cameraMode == CameraMode.AR_LENS && recording == null) {
                viewBinding.arLensModeDock.root.visibility = View.VISIBLE
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
                        playCameraCue(ToneGenerator.TONE_PROP_BEEP2, 95)
                        isRecordingPaused = false
                        viewBinding.btnCapture.setImageResource(R.drawable.bg_record_button_active)
                        viewBinding.recordingTimer.visibility = View.VISIBLE
                        viewBinding.tvRecordingStatus.text = if (cameraMode == CameraMode.AR_LENS) "AR REC" else "REC"
                        viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#EF4444"))
                        viewBinding.recordingDot.visibility = View.VISIBLE
                        viewBinding.btnPauseResume.visibility = if (cameraMode == CameraMode.AR_LENS) View.GONE else View.VISIBLE
                        viewBinding.btnGallery.visibility = View.VISIBLE
                        viewBinding.btnPauseResume.setImageResource(R.drawable.ic_pause)
                        if (cameraMode == CameraMode.AR_LENS) {
                            viewBinding.arLensModeDock.root.visibility = View.GONE
                            viewBinding.arCalibrationDrawer.visibility = View.GONE
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
                        playCameraCue(ToneGenerator.TONE_PROP_BEEP, 110)
                        stopAutoLoopTicker()
                        isArHoldRecording = false
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
                            viewBinding.chronometer.text = "TAP PHOTO · HOLD 1S VID"
                            viewBinding.chronometer.setTextColor(Color.parseColor("#F0ABFC"))
                            viewBinding.btnCapture.setImageResource(R.drawable.bg_shutter_button)
                            viewBinding.arLensModeDock.root.visibility = View.VISIBLE
                        } else {
                            viewBinding.tvRecordingStatus.text = "STANDBY"
                            viewBinding.tvRecordingStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
                            viewBinding.recordingDot.visibility = View.GONE
                            viewBinding.chronometer.text = "00:00:00"
                            viewBinding.btnCapture.setImageResource(R.drawable.bg_record_button_idle)
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
                startNightExposureStacking(
                    autoTriggered = false,
                    targetFrameCount = nightExposureStacker.lastRecommendedFrameCount
                )
            }
            return
        }
        val imageCapture = imageCapture ?: return

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
     * Captures a bracketed multi-frame exposure sequence (Night Mode) and fuses frames for low-light clarity.
     */
    private fun startNightExposureStacking(autoTriggered: Boolean, targetFrameCount: Int) {
        val cap = imageCapture ?: return
        if (isNightStacking || isPanoramaCapturing || isPanoramaStitching || recording != null) return

        isNightStacking = true
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

        viewBinding.proOverlayView.updateNightStackProgress(true, 1, totalFrames)
        viewBinding.tvRecordingStatus.text = "STACK"
        viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#A855F7"))
        viewBinding.chronometer.text = "1/${totalFrames} F"
        showHudNotice(
            if (autoTriggered) {
                "LOW LIGHT DETECTED · AUTO NIGHT STACK (${totalFrames} FRAMES)"
            } else {
                "NIGHT STACK · HOLD STEADY (${totalFrames} FRAMES)"
            }
        )
        playCameraCue(ToneGenerator.TONE_PROP_BEEP2, 70)

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
                runOnUiThread {
                    viewBinding.proOverlayView.updateNightStackProgress(false, 0, totalFrames)
                    viewBinding.tvRecordingStatus.text = if (cameraMode == CameraMode.NIGHT) "NIGHT" else "READY"
                }
                return
            }

            runOnUiThread {
                viewBinding.tvRecordingStatus.text = "FUSE"
                viewBinding.chronometer.text = "ALIGN ${snapshot.size}F"
                showHudNotice("ALIGNING & FUSING ${snapshot.size} NIGHT FRAMES...")
            }

            cameraExecutor.execute {
                var stackedBmp: Bitmap? = null
                try {
                    stackedBmp = nightExposureStacker.stackExposureFrames(snapshot, avgLumaHint)
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
                            playCameraCue(ToneGenerator.TONE_PROP_BEEP2, 110)
                            showHudNotice("Saved Multi-Frame Night Stack (${snapshot.size} Exposures)")
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
                    runOnUiThread {
                        viewBinding.proOverlayView.updateNightStackProgress(false, 0, totalFrames)
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
            runOnUiThread {
                viewBinding.proOverlayView.updateNightStackProgress(true, stepIndex + 1, totalFrames)
                viewBinding.chronometer.text = "${stepIndex + 1}/${totalFrames} F"
                viewBinding.viewFinder.animate().alpha(0.55f).setDuration(45).withEndAction {
                    viewBinding.viewFinder.animate().alpha(1.0f).setDuration(90).start()
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
                            runOnUiThread { playCameraCue(ToneGenerator.TONE_PROP_BEEP, 28) }
                            nightHandler.post { captureBracketStep(stepIndex + 1) }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "Night bracket capture error on frame $stepIndex", exception)
                            nightHandler.post { captureBracketStep(stepIndex + 1) }
                        }
                    }
                )
            }, if (stepIndex == 0) 40L else 105L)
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
                    val bandH = fh * 0.18f
                    val barPaint = Paint().apply { color = Color.BLACK }
                    canvas.drawRect(0f, 0f, fw, bandH, barPaint)
                    canvas.drawRect(0f, fh - bandH, fw, fh, barPaint)
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
            setArLens("none", showNotice = false)
        }
        cameraMode = mode
        nightExposureStacker.clearDarkEpisodeLock()
        viewBinding.proOverlayView.isNightModeHudActive = (mode == CameraMode.NIGHT || currentShootingEffect == "night_sight")
        isLastActionCreative = false
        showHudNotice(if (mode == CameraMode.AR_LENS) "AR LENS · Tap Photo · Hold 1s Video" else "Mode: ${getModeLabel(mode)}")
        viewBinding.proOverlayView.isPanoramaModeActive = (mode == CameraMode.PANORAMA)

        updateModeCarouselSlots(animated = true, slideDirection = slideDirection)

        val isVideoType = mode == CameraMode.VIDEO || mode == CameraMode.SLOW_MO
        viewBinding.recordingTimer.visibility = View.VISIBLE
        if (mode == CameraMode.AR_LENS) {
            viewBinding.effectsStudioDrawer.root.visibility = View.GONE
            viewBinding.proStudioDrawer.root.visibility = View.GONE
            viewBinding.layoutGuideFloatingBar.visibility = View.GONE
            viewBinding.arLensModeDock.root.visibility = View.VISIBLE
            viewBinding.btnCapture.setImageResource(R.drawable.bg_shutter_button)
            viewBinding.btnPauseResume.visibility = View.GONE
            viewBinding.layoutBtnTimer.visibility = View.VISIBLE
            viewBinding.btnAutoLoop.visibility = View.GONE
            viewBinding.recordingDot.setBackgroundResource(R.drawable.bg_green_dot)
            viewBinding.recordingDot.visibility = View.VISIBLE
            viewBinding.tvRecordingStatus.text = "AR LENS"
            viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#E879F9"))
            viewBinding.chronometer.text = "TAP PHOTO · HOLD 1S VID"
            viewBinding.chronometer.setTextColor(Color.parseColor("#F0ABFC"))
            val restoreLens = if (lastActiveArLensName == "none") "cyberpunk_visor" else lastActiveArLensName
            setArLens(restoreLens, showNotice = false)
            syncArDockFromCalibration()
        } else if (!isVideoType) {
            viewBinding.btnCapture.setImageResource(R.drawable.bg_shutter_button)
            viewBinding.btnPauseResume.visibility = View.GONE
            viewBinding.layoutBtnTimer.visibility = if (mode == CameraMode.PANORAMA) View.GONE else View.VISIBLE
            viewBinding.btnAutoLoop.visibility = View.GONE
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
        } else {
            viewBinding.btnCapture.setImageResource(R.drawable.bg_record_button_idle)
            viewBinding.layoutBtnTimer.visibility = View.GONE
            viewBinding.btnAutoLoop.visibility = View.VISIBLE
            viewBinding.btnPauseResume.visibility = View.GONE
            viewBinding.recordingDot.setBackgroundResource(R.drawable.bg_green_dot)
            viewBinding.recordingDot.visibility = View.VISIBLE
            viewBinding.tvRecordingStatus.text = "STANDBY"
            viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#10B981"))
            viewBinding.chronometer.text = "00:00:00"
            viewBinding.chronometer.setTextColor(Color.parseColor("#FFFFFF"))
            updateAutoLoopHud()
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
        val nextIdx = (safeIdx + 1) % arLensOrder.size
        setArLens(arLensOrder[nextIdx])
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun navigatePreviousArLens() {
        val currentIdx = arLensOrder.indexOf(currentArLensName)
        val safeIdx = if (currentIdx >= 0) currentIdx else 0
        val prevIdx = if (safeIdx <= 0) arLensOrder.size - 1 else safeIdx - 1
        setArLens(arLensOrder[prevIdx])
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

    private fun setupViewfinderGestures() {
        val scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentZoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                cameraControl?.setZoomRatio(currentZoomRatio * detector.scaleFactor)
                return true
            }
        })

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (suppressNextTapFocus) {
                    suppressNextTapFocus = false
                    return true
                }
                if (viewBinding.proOverlayView.currentGrid == ProOverlayView.GridType.GOLDEN_SPIRAL) {
                    val nextRot = (viewBinding.proOverlayView.spiralOrientation + 1) % 4
                    setSpiralOrientation(nextRot)
                    window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }

                
                val point = viewBinding.viewFinder.meteringPointFactory.createPoint(e.x, e.y)
                val action = FocusMeteringAction.Builder(point).build()
                cameraControl?.startFocusAndMetering(action)
                playCameraCue(ToneGenerator.TONE_PROP_ACK, 40)

                // Animate Focus Ring
                viewBinding.focusRing.x = e.x - (viewBinding.focusRing.width / 2)
                viewBinding.focusRing.y = e.y - (viewBinding.focusRing.height / 2)
                viewBinding.focusRing.visibility = View.VISIBLE
                viewBinding.focusRing.alpha = 1.0f
                viewBinding.focusRing.scaleX = 1.3f
                viewBinding.focusRing.scaleY = 1.3f
                viewBinding.focusRing.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .alpha(0.8f)
                    .setDuration(250)
                    .withEndAction {
                        viewBinding.focusRing.animate().alpha(0f).setStartDelay(900).setDuration(250).withEndAction {
                            viewBinding.focusRing.visibility = View.GONE
                        }.start()
                    }.start()
                
                return true
            }
        })

        viewBinding.proOverlayView.setOnTouchListener { view, event ->
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

                // Clean tap outside open drawers -> dismiss drawer without leaking tap
                if (!isMultiTouchGesture && Math.abs(diffX) < 22 && Math.abs(diffY) < 22) {
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

    private fun playCameraCue(toneType: Int, durationMs: Int = 80) {
        if (isAudioMuted) return
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_SYSTEM, 65)
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
            viewBinding.arLensModeDock.layoutArModeLensesContainer.addView(chip)
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

        val hasKnownFields = pixelOffsets != null ||
            normalizedOffsets != null ||
            transform != null ||
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

    private fun selectArDockTab(tab: ArDockTab) {
        currentArDockTab = tab
        val isLenses = tab == ArDockTab.LENSES
        val isAdjust = tab == ArDockTab.ADJUST
        val isComicText = tab == ArDockTab.COMIC_TEXT

        viewBinding.arLensModeDock.tabArDockLenses.setBackgroundResource(if (isLenses) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
        viewBinding.arLensModeDock.tabArDockLenses.setTextColor(Color.parseColor(if (isLenses) "#0F172A" else "#94A3B8"))
        viewBinding.arLensModeDock.tabArDockAdjust.setBackgroundResource(if (isAdjust) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
        viewBinding.arLensModeDock.tabArDockAdjust.setTextColor(Color.parseColor(if (isAdjust) "#0F172A" else "#94A3B8"))
        viewBinding.arLensModeDock.tabArDockComicText.setBackgroundResource(if (isComicText) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
        viewBinding.arLensModeDock.tabArDockComicText.setTextColor(Color.parseColor(if (isComicText) "#0F172A" else "#94A3B8"))

        viewBinding.arLensModeDock.scrollArModeLensesStrip.visibility = if (isLenses) View.VISIBLE else View.GONE
        viewBinding.arLensModeDock.layoutArModeAdjustPanel.visibility = if (isAdjust) View.VISIBLE else View.GONE
        viewBinding.arLensModeDock.layoutArModeComicTextPanel.visibility = if (isComicText) View.VISIBLE else View.GONE

        if (isArDockCollapsed) {
            isArDockCollapsed = false
            viewBinding.arLensModeDock.layoutArDockBody.visibility = View.VISIBLE
            viewBinding.arLensModeDock.btnArDockToggle.text = "v"
        }
        if (isAdjust) syncArDockFromCalibration()
    }

    private fun syncArDockFromCalibration() {
        isSyncingArDockSliders = true
        val dock = viewBinding.arLensModeDock
        val scaleProg = (((calibScale - 0.5f) / 1.5f) * 100f).roundToInt().coerceIn(0, 100)
        val offXProg = (((calibOffsetX * 600f) + 150f) / 3f).roundToInt().coerceIn(0, 100)
        val offYProg = (((calibOffsetY * 600f) + 150f) / 3f).roundToInt().coerceIn(0, 100)
        val rotProg = (calibRotDeg.roundToInt() + 45).coerceIn(0, 90)
        dock.sbArDockScale.progress = scaleProg
        dock.tvArDockScaleVal.text = String.format(Locale.US, "%.2fx", calibScale)
        dock.sbArDockOffsetX.progress = offXProg
        dock.tvArDockOffsetXVal.text = "${(calibOffsetX * 600f).roundToInt()}px"
        dock.sbArDockOffsetY.progress = offYProg
        dock.tvArDockOffsetYVal.text = "${(calibOffsetY * 600f).roundToInt()}px"
        dock.sbArDockRotation.progress = rotProg
        dock.tvArDockRotationVal.text = "${calibRotDeg.roundToInt()}°"
        dock.switchArDockMeshDebug.isChecked = calibDebug
        dock.switchArDockBubble.isChecked = viewBinding.arFaceOverlayView.enableComicSpeechBubble
        dock.switchArDockAutoAdapt.isChecked = isAutoAdaptEnabled
        isSyncingArDockSliders = false
    }

    private fun setupArLensModeDock() {
        val dock = viewBinding.arLensModeDock
        dock.tabArDockLenses.setOnClickListener { selectArDockTab(ArDockTab.LENSES) }
        dock.tabArDockAdjust.setOnClickListener { selectArDockTab(ArDockTab.ADJUST) }
        dock.tabArDockComicText.setOnClickListener { selectArDockTab(ArDockTab.COMIC_TEXT) }
        dock.btnArDockToggle.setOnClickListener {
            isArDockCollapsed = !isArDockCollapsed
            dock.layoutArDockBody.visibility = if (isArDockCollapsed) View.GONE else View.VISIBLE
            dock.btnArDockToggle.text = if (isArDockCollapsed) "^" else "v"
        }
        dock.btnArDockReset.setOnClickListener { resetArCalibrationValues() }

        dock.sbArDockScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (isSyncingArDockSliders || !fromUser) return
                val s = 0.5f + (progress / 100f) * 1.5f
                calibScale = s
                dock.tvArDockScaleVal.text = String.format(Locale.US, "%.2fx", s)
                viewBinding.sbCalibScale.progress = progress
                pushArCalibrationToOverlay()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        dock.sbArDockOffsetX.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (isSyncingArDockSliders || !fromUser) return
                val px = (progress - 50) * 3
                calibOffsetX = px / 600f
                dock.tvArDockOffsetXVal.text = "${px}px"
                viewBinding.sbCalibOffsetX.progress = (px + 150).coerceIn(0, 300)
                pushArCalibrationToOverlay()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        dock.sbArDockOffsetY.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (isSyncingArDockSliders || !fromUser) return
                val px = (progress - 50) * 3
                calibOffsetY = px / 600f
                dock.tvArDockOffsetYVal.text = "${px}px"
                viewBinding.sbCalibOffsetY.progress = (px + 150).coerceIn(0, 300)
                pushArCalibrationToOverlay()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        dock.sbArDockRotation.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (isSyncingArDockSliders || !fromUser) return
                val deg = (progress - 45).toFloat()
                calibRotDeg = deg
                dock.tvArDockRotationVal.text = "${deg.toInt()}°"
                viewBinding.sbCalibRotation.progress = progress
                pushArCalibrationToOverlay()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        dock.switchArDockMeshDebug.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingArDockSliders) return@setOnCheckedChangeListener
            calibDebug = isChecked
            viewBinding.switchArMeshDebug.isChecked = isChecked
            pushArCalibrationToOverlay()
        }
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
        selectArDockTab(ArDockTab.LENSES)
        syncArDockFromCalibration()
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
        sensorManager.unregisterListener(sensorEventListener)
        faceMeshHelper?.clear()
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

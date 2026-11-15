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
import android.media.ExifInterface
import android.media.FaceDetector
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
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.core.CameraEffect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.UseCaseGroup
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
import com.example.cameraxapp.ar.FaceMeshHelper
import com.example.cameraxapp.ar.ARFaceOverlayView
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    enum class CameraMode { SLOW_MO, VIDEO, PHOTO, PORTRAIT, NIGHT }

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

    // Real-Time Filter & AR Engine
    private lateinit var filterManager: FilterManager
    private var faceMeshHelper: FaceMeshHelper? = null
    private var openGLProcessor: OpenGLSurfaceProcessor? = null
    private var cameraEffect: CameraEffect? = null

    // Video Recording Pause/Resume state
    private var isRecordingPaused = false
    private var timeWhenStopped: Long = 0

    // Photo Pro Mode States
    private var countdownSeconds = 0 // 0 (Off), 3, 5, 10
    private var isCountingDown = false
    private var isBurstCapturing = false
    private var burstCount = 0
    private val maxBurstBuffer = 30
    private var remainingBurstBuffer = 30
    private val burstHandler = Handler(Looper.getMainLooper())
    private var burstRunnable: Runnable? = null
    private var isGridVisible = true

    // Auto Trigger Interval Loop Recorder (Auto Record -> Auto Pause -> Auto Resume)
    private var isAutoLoopEnabled = false
    private var autoRecordDuration = 5 // seconds of active recording take
    private var autoPauseDuration = 3 // seconds of auto pause standby
    private var autoLoopIteration = 0
    private var autoPhaseRemaining = 0
    private var isAutoLoopPausedPhase = false
    private val autoLoopHandler = Handler(Looper.getMainLooper())
    private var autoLoopRunnable: Runnable? = null

    // Electronic Virtual Horizon (60 FPS Sensor-Driven Roll & Pitch Level Gauge)
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

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
                if (currentShootingEffect == "action_stabilization") {
                    viewBinding.viewFinder.rotation = -smoothRollDeg.coerceIn(-45f, 45f)
                } else if (currentShootingEffect == "hyperlapse") {
                    viewBinding.viewFinder.rotation = -(smoothRollDeg * 0.45f).coerceIn(-18f, 18f)
                } else if (viewBinding.viewFinder.rotation != 0f) {
                    viewBinding.viewFinder.rotation = 0f
                }
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
    private val stylizeRunnable = object : Runnable {
        override fun run() {
            val hasComicPreset = isComicFilterPreset(currentFilterName)
            val useCpuLive = (isStylizedLiveEffect(currentShootingEffect) || hasComicPreset) &&
                ((cameraMode != CameraMode.VIDEO && cameraMode != CameraMode.SLOW_MO) || cameraEffect == null)
            if (useCpuLive && !isStylizingFrame && !isFinishing && !isDestroyed) {
                val tv = findTextureView(viewBinding.viewFinder)
                if (tv != null && tv.isAvailable) {
                    val vw = tv.width.coerceAtLeast(1)
                    val vh = tv.height.coerceAtLeast(1)
                    val maxEdge = 440
                    val sampleW = if (vw >= vh) maxEdge else Math.max(140, (maxEdge * vw / vh.toFloat()).toInt())
                    val sampleH = if (vh >= vw) maxEdge else Math.max(140, (maxEdge * vh / vw.toFloat()).toInt())
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
                                    filterManager.applyToonEffectToBitmap(frame, targetStyleId)
                                } else {
                                    null
                                }
                                val styled = gpuStyled ?: renderStylizedEffectBitmap(frame, targetStyleId, forLivePreview = true)
                                runOnUiThread {
                                    val stillValid = !isFinishing && !isDestroyed && (
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
            stylizeHandler.postDelayed(this, if (currentShootingEffect == "stop_motion") 145L else 58L)
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

        // Initialize Filter Engine
        filterManager = FilterManager(this)

        viewBinding.proOverlayView.sensorAspectRatio = currentAspectRatio

        setupClickListeners()
        setupBurstMode()
        setupExposureSlider()
        setupFilterAndArControls()
        selectCreativeCategory(CreativeTarget.FILTERS, fromUser = false)
        updateShootingEffectButtons()
        isLastActionCreative = false
        viewBinding.modeScrollView.post { centerModeTab(cameraMode, smooth = false) }
        stylizeHandler.postDelayed(stylizeRunnable, 120L)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(sensorEventListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(sensorEventListener)
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

    enum class CreativeTarget { FILTERS, LENSES, EFFECTS, ART, TOON }
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
    private val filterPresetOrder = listOf(
        "normal",
        "lut_rec709", "lut_flat_log", "lut_teal_orange", "lut_bleach_bypass",
        "lut_anamorphic_cyan", "lut_matte_cinema", "lut_midnight_neon", "lut_warm_documentary", "lut_noir_cinema",
        "golden_nostalgia", "velvia_landscape", "nordic_slate", "arctic_mist",
        "classic_mono", "high_contrast_noir", "ilford_grain", "silver_gelatin", "antique_sepia",
        "cinematic", "vintage", "bw", "cyberpunk", "hdr", "comic_book"
    )
    private val arLensOrder = listOf(
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
        "anamorphic_flare", "dolly_zoom", "motion_blur", "action_stabilization",
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

        // Mode Switcher: 5-Slot Infinite Rotating Carousel (Stationary center highlighter, endless rotation)
        viewBinding.tabSlotFarLeft.setOnClickListener {
            if (recording == null) {
                val curIdx = allCameraModes.indexOf(cameraMode)
                setMode(allCameraModes[(curIdx - 2 + 50) % 5], slideDirection = -1)
            }
        }
        viewBinding.tabSlotLeft.setOnClickListener {
            if (recording == null) {
                val curIdx = allCameraModes.indexOf(cameraMode)
                setMode(allCameraModes[(curIdx - 1 + 50) % 5], slideDirection = -1)
            }
        }
        viewBinding.tabSlotRight.setOnClickListener {
            if (recording == null) {
                val curIdx = allCameraModes.indexOf(cameraMode)
                setMode(allCameraModes[(curIdx + 1) % 5], slideDirection = 1)
            }
        }
        viewBinding.tabSlotFarRight.setOnClickListener {
            if (recording == null) {
                val curIdx = allCameraModes.indexOf(cameraMode)
                setMode(allCameraModes[(curIdx + 2) % 5], slideDirection = 1)
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

        // Video Pause/Resume Button
        viewBinding.btnPauseResume.setOnClickListener { toggleVideoPauseResume() }

        // Top Toolbar: Flash Button
        viewBinding.btnFlash.setOnClickListener { toggleFlashMode() }

        // Top Toolbar: Creative Filters / Effects Drawer (strictly Filters, Lens, and Effects only)
        viewBinding.btnFilter.setOnClickListener {
            val isVisible = viewBinding.effectsStudioDrawer.visibility == View.VISIBLE
            if (isVisible) {
                viewBinding.effectsStudioDrawer.visibility = View.GONE
                isLastActionCreative = false
            } else {
                viewBinding.proStudioDrawer.visibility = View.GONE
                viewBinding.layoutGuideFloatingBar.visibility = View.GONE
                viewBinding.effectsStudioDrawer.visibility = View.VISIBLE
                selectCreativeCategory(currentCreativeTarget, fromUser = true)
            }
            window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        viewBinding.btnCloseEffectsDrawer.setOnClickListener {
            viewBinding.effectsStudioDrawer.visibility = View.GONE
            isLastActionCreative = false
            window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        // Top Toolbar: Dedicated Framing Guides & Grids HUD
        viewBinding.btnGuide.setOnClickListener {
            val isVisible = viewBinding.layoutGuideFloatingBar.visibility == View.VISIBLE
            viewBinding.layoutGuideFloatingBar.visibility = if (isVisible) View.GONE else View.VISIBLE
            if (!isVisible) {
                viewBinding.effectsStudioDrawer.visibility = View.GONE
                isLastActionCreative = false
                viewBinding.proStudioDrawer.visibility = View.GONE
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

        // Creative Drawer Category Switcher Buttons (FILTERS, LENSES, EFFECTS, ART, TOON)
        viewBinding.btnCategoryFilters.setOnClickListener { selectCreativeCategory(CreativeTarget.FILTERS, fromUser = true) }
        viewBinding.btnCategoryLenses.setOnClickListener { selectCreativeCategory(CreativeTarget.LENSES, fromUser = true) }
        viewBinding.btnCategoryEffects.setOnClickListener { selectCreativeCategory(CreativeTarget.EFFECTS, fromUser = true) }
        viewBinding.btnCategoryArt.setOnClickListener { selectCreativeCategory(CreativeTarget.ART, fromUser = true) }
        viewBinding.btnCategoryToon.setOnClickListener { selectCreativeCategory(CreativeTarget.TOON, fromUser = true) }

        // Top Toolbar: Self-Timer Toggle (Photo Mode)
        viewBinding.btnTimer.setOnClickListener { cycleCountdownTimer() }

        // Top Toolbar: Auto Trigger Interval Loop (Video Mode)
        viewBinding.btnAutoLoop.setOnClickListener { cycleAutoLoopTimer() }

        // Top Toolbar: Aspect Ratio Quick Cycle (4:3 -> 16:9 -> 1:1)
        viewBinding.btnAspectRatio.setOnClickListener { cycleAspectRatio() }

        // Top Toolbar: Clean Screen 1-Tap Toggle (Hide all HUD overlays)
        viewBinding.btnCleanScreen.setOnClickListener { toggleCleanScreen() }
        viewBinding.btnCleanView.setOnClickListener {
            toggleCleanScreen()
            viewBinding.proStudioDrawer.visibility = View.GONE
            viewBinding.effectsStudioDrawer.visibility = View.GONE
            isLastActionCreative = false
            viewBinding.layoutGuideFloatingBar.visibility = View.GONE
        }

        // Top Toolbar: Pro Studio Drawer Open / Close (Single unified entry point)
        viewBinding.btnProStudio.setOnClickListener {
            toggleProDrawer()
        }
        viewBinding.btnCloseProDrawer.setOnClickListener {
            viewBinding.proStudioDrawer.visibility = View.GONE
        }

        // Top Toolbar: Audio Mute Toggle
        viewBinding.btnAudioMute.setOnClickListener { toggleAudioMute() }

        // 4 Category Tabs Bar for Camera Pro Tools
        viewBinding.tabCategoryGrids.setOnClickListener { selectShelfTab(ShelfCategory.GRIDS) }
        viewBinding.tabCategoryExposure.setOnClickListener { selectShelfTab(ShelfCategory.EXPOSURE) }
        viewBinding.tabCategoryOptics.setOnClickListener { selectShelfTab(ShelfCategory.OPTICS) }
        viewBinding.tabCategoryAudio.setOnClickListener { selectShelfTab(ShelfCategory.AUDIO) }

        // TAB 0: Looks, Filters (including Video Cinema LUTs), AR Lenses & Effects
        viewBinding.btnFilterNormal.setOnClickListener { setFilterPreset("normal") }
        viewBinding.btnFilterLutRec709.setOnClickListener { setFilterPreset("lut_rec709") }
        viewBinding.btnFilterLutFlatLog.setOnClickListener { setFilterPreset("lut_flat_log") }
        viewBinding.btnFilterLutTealOrange.setOnClickListener { setFilterPreset("lut_teal_orange") }
        viewBinding.btnFilterLutBleachBypass.setOnClickListener { setFilterPreset("lut_bleach_bypass") }
        viewBinding.btnFilterLutAnamorphicCyan.setOnClickListener { setFilterPreset("lut_anamorphic_cyan") }
        viewBinding.btnFilterLutMatteCinema.setOnClickListener { setFilterPreset("lut_matte_cinema") }
        viewBinding.btnFilterLutMidnightNeon.setOnClickListener { setFilterPreset("lut_midnight_neon") }
        viewBinding.btnFilterLutWarmDoc.setOnClickListener { setFilterPreset("lut_warm_documentary") }
        viewBinding.btnFilterLutNoirCinema.setOnClickListener { setFilterPreset("lut_noir_cinema") }
        viewBinding.btnFilterGoldenNostalgia.setOnClickListener { setFilterPreset("golden_nostalgia") }
        viewBinding.btnFilterVelvia.setOnClickListener { setFilterPreset("velvia_landscape") }
        viewBinding.btnFilterNordicSlate.setOnClickListener { setFilterPreset("nordic_slate") }
        viewBinding.btnFilterArcticMist.setOnClickListener { setFilterPreset("arctic_mist") }
        viewBinding.btnFilterClassicMono.setOnClickListener { setFilterPreset("classic_mono") }
        viewBinding.btnFilterNoir.setOnClickListener { setFilterPreset("high_contrast_noir") }
        viewBinding.btnFilterIlfordGrain.setOnClickListener { setFilterPreset("ilford_grain") }
        viewBinding.btnFilterSilverGelatin.setOnClickListener { setFilterPreset("silver_gelatin") }
        viewBinding.btnFilterAntiqueSepia.setOnClickListener { setFilterPreset("antique_sepia") }
        viewBinding.btnFilterCinematic.setOnClickListener { setFilterPreset("cinematic") }
        viewBinding.btnFilterVintage.setOnClickListener { setFilterPreset("vintage") }
        viewBinding.btnFilterBW.setOnClickListener { setFilterPreset("bw") }
        viewBinding.btnFilterCyber.setOnClickListener { setFilterPreset("cyberpunk") }
        viewBinding.btnFilterHDR.setOnClickListener { setFilterPreset("hdr") }
        viewBinding.btnFilterComicBook.setOnClickListener { setFilterPreset("comic_book") }

        viewBinding.btnArNone.setOnClickListener { setArLens("none") }
        viewBinding.btnArMesh.setOnClickListener { setArLens("mesh_contour") }
        viewBinding.btnArVisor.setOnClickListener { setArLens("cyberpunk_visor") }
        viewBinding.btnArCrown.setOnClickListener { setArLens("golden_crown") }
        viewBinding.btnArHud.setOnClickListener { setArLens("scifi_hud") }
        viewBinding.btnArComicFrame.setOnClickListener { setArLens("comic_frame") }
        viewBinding.btnArComicOnomatopoeia.setOnClickListener { toggleComicOnomatopoeia() }

        viewBinding.btnEffectOff.setOnClickListener { setShootingEffect("none") }
        viewBinding.btnArtOff.setOnClickListener { setShootingEffect("none") }
        viewBinding.btnToonOff.setOnClickListener { setShootingEffect("none") }
        viewBinding.btnEffectBokeh.setOnClickListener { setShootingEffect("portrait_bokeh") }
        viewBinding.btnEffectLongExp.setOnClickListener { setShootingEffect("long_exposure") }
        viewBinding.btnEffectLightPaint.setOnClickListener { setShootingEffect("light_painting") }
        viewBinding.btnEffectStarTrails.setOnClickListener { setShootingEffect("star_trails") }
        viewBinding.btnEffectNightSight.setOnClickListener { setShootingEffect("night_sight") }
        viewBinding.btnEffectMacro.setOnClickListener { setShootingEffect("macro_focus") }
        viewBinding.btnEffectTiltShift.setOnClickListener { setShootingEffect("tilt_shift") }
        viewBinding.btnEffectDoubleExp.setOnClickListener { setShootingEffect("double_exposure") }
        viewBinding.btnEffectPanorama.setOnClickListener { setShootingEffect("panorama") }
        viewBinding.btnEffectFilmGrain.setOnClickListener { setShootingEffect("film_grain") }
        viewBinding.btnEffectHalation.setOnClickListener { setShootingEffect("soft_halation") }
        viewBinding.btnEffectVignette.setOnClickListener { setShootingEffect("vignette") }
        viewBinding.btnEffectVideoBokeh.setOnClickListener { setShootingEffect("video_bokeh") }
        viewBinding.btnEffectSlowMotion.setOnClickListener { setShootingEffect("slow_motion_120_240") }
        viewBinding.btnEffectTimeLapse.setOnClickListener { setShootingEffect("time_lapse") }
        viewBinding.btnEffectHyperlapse.setOnClickListener { setShootingEffect("hyperlapse") }
        viewBinding.btnEffectAnamorphicFlare.setOnClickListener { setShootingEffect("anamorphic_flare") }
        viewBinding.btnEffectDollyZoom.setOnClickListener { setShootingEffect("dolly_zoom") }
        viewBinding.btnEffectMotionBlur.setOnClickListener { setShootingEffect("motion_blur") }
        viewBinding.btnEffectHorizonLock.setOnClickListener { setShootingEffect("action_stabilization") }
        viewBinding.btnEffectSuper8.setOnClickListener { setShootingEffect("vintage_super8") }
        viewBinding.btnEffectOilCanvas.setOnClickListener { setShootingEffect("oil_canvas") }
        viewBinding.btnEffectCharcoalSketch.setOnClickListener { setShootingEffect("charcoal_sketch") }
        viewBinding.btnEffectWatercolorWash.setOnClickListener { setShootingEffect("watercolor_wash") }
        viewBinding.btnEffectInkLine.setOnClickListener { setShootingEffect("ink_and_line") }
        viewBinding.btnEffectPastelChalk.setOnClickListener { setShootingEffect("pastel_chalk") }
        viewBinding.btnEffectHalftonePrint.setOnClickListener { setShootingEffect("halftone_print") }
        viewBinding.btnEffectCyanotype.setOnClickListener { setShootingEffect("cyanotype") }
        viewBinding.btnEffectWoodcut.setOnClickListener { setShootingEffect("woodcut_engraving") }
        viewBinding.btnEffectLithograph.setOnClickListener { setShootingEffect("lithograph") }
        viewBinding.btnEffectPrism.setOnClickListener { setShootingEffect("prism_refraction") }
        viewBinding.btnEffectKaleidoscope.setOnClickListener { setShootingEffect("kaleidoscope") }
        viewBinding.btnEffectSolarize.setOnClickListener { setShootingEffect("solarize") }
        viewBinding.btnEffectFilm16mm.setOnClickListener { setShootingEffect("film_16mm") }
        viewBinding.btnEffectVhsTape.setOnClickListener { setShootingEffect("vhs_tape") }
        viewBinding.btnEffectCrtMonitor.setOnClickListener { setShootingEffect("crt_monitor") }
        viewBinding.btnEffectStopMotion.setOnClickListener { setShootingEffect("stop_motion") }
        viewBinding.btnEffectNeonEdge.setOnClickListener { setShootingEffect("neon_edge") }
        viewBinding.btnEffectNoirNovel.setOnClickListener { setShootingEffect("noir_graphic_novel") }
        viewBinding.btnEffectThermalVision.setOnClickListener { setShootingEffect("thermal_vision") }
        viewBinding.btnEffectCelShaded.setOnClickListener { setShootingEffect("cel_shaded_studio") }
        viewBinding.btnEffectGraphicNoir.setOnClickListener { setShootingEffect("graphic_novel_noir") }
        viewBinding.btnEffectMangaInk.setOnClickListener { setShootingEffect("manga_ink") }
        viewBinding.btnEffectPopArt.setOnClickListener { setShootingEffect("pop_art_print") }
        viewBinding.btnEffectLineArt.setOnClickListener { setShootingEffect("line_art_minimalist") }
        viewBinding.btnEffectLigneClaire.setOnClickListener { setShootingEffect("ligne_claire") }
        viewBinding.btnEffectClayVinyl.setOnClickListener { setShootingEffect("clay_vinyl_3d") }

        viewBinding.switchFilmGrain.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.enableFilmGrain = isChecked
            if (!isUpdatingEffectSwitches) {
                currentCreativeTarget = CreativeTarget.EFFECTS
                isLastActionCreative = true
                if (isChecked) {
                    currentShootingEffect = "film_grain"
                    viewBinding.proOverlayView.activeShootingEffect = "film_grain"
                } else if (currentShootingEffect == "film_grain") {
                    currentShootingEffect = "none"
                    viewBinding.proOverlayView.activeShootingEffect = "none"
                }
                updateShootingEffectButtons()
                updateCreativeStatusUI()
                showHudNotice("35mm Film Grain ${if (isChecked) "ON" else "OFF"}")
            }
        }
        viewBinding.switchDateStamp.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.enableDateStamp = isChecked
            if (!isUpdatingEffectSwitches) {
                currentCreativeTarget = CreativeTarget.EFFECTS
                isLastActionCreative = true
                updateCreativeStatusUI()
                showHudNotice("Retro Date Stamp ${if (isChecked) "ON" else "OFF"}")
            }
        }
        viewBinding.switchCineFlare.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.enableCineFlare = isChecked
            if (!isUpdatingEffectSwitches) {
                currentCreativeTarget = CreativeTarget.EFFECTS
                isLastActionCreative = true
                if (isChecked) {
                    currentShootingEffect = "anamorphic_flare"
                    viewBinding.proOverlayView.activeShootingEffect = "anamorphic_flare"
                } else if (currentShootingEffect == "anamorphic_flare") {
                    currentShootingEffect = "none"
                    viewBinding.proOverlayView.activeShootingEffect = "none"
                }
                updateShootingEffectButtons()
                updateCreativeStatusUI()
                showHudNotice("Anamorphic Flare ${if (isChecked) "ON" else "OFF"}")
            }
        }

        // TAB 1: Framing Grids Symmetrical Buttons
        viewBinding.btnGridOff.setOnClickListener { setGridMode(ProOverlayView.GridType.NONE) }
        viewBinding.btnGridThirds.setOnClickListener { setGridMode(ProOverlayView.GridType.RULE_OF_THIRDS) }
        viewBinding.btnGridPhi.setOnClickListener { setGridMode(ProOverlayView.GridType.GOLDEN_RATIO_PHI) }
        viewBinding.btnGridSpiral.setOnClickListener { setGridMode(ProOverlayView.GridType.GOLDEN_SPIRAL) }
        viewBinding.btnGridDiagonal.setOnClickListener { setGridMode(ProOverlayView.GridType.DIAGONAL_SYMMETRY) }
        viewBinding.btnGridCinematic.setOnClickListener { setGridMode(ProOverlayView.GridType.CINEMATIC_239) }

        // Spiral Rotation Axis (0°, 90°, 180°, 270°)
        viewBinding.btnSpiral0.setOnClickListener { setSpiralOrientation(0) }
        viewBinding.btnSpiral90.setOnClickListener { setSpiralOrientation(1) }
        viewBinding.btnSpiral180.setOnClickListener { setSpiralOrientation(2) }
        viewBinding.btnSpiral270.setOnClickListener { setSpiralOrientation(3) }

        // Aspect Ratio Mattes Symmetrical Buttons
        viewBinding.btnMatteOff.setOnClickListener { setAspectMatteMode(ProOverlayView.AspectMatte.NONE) }
        viewBinding.btnMatte169.setOnClickListener { setAspectMatteMode(ProOverlayView.AspectMatte.WIDESCREEN_16_9) }
        viewBinding.btnMatteCine.setOnClickListener { setAspectMatteMode(ProOverlayView.AspectMatte.CINE_239) }
        viewBinding.btnMatteSquare.setOnClickListener { setAspectMatteMode(ProOverlayView.AspectMatte.SQUARE_1_1) }
        viewBinding.btnMatte916.setOnClickListener { setAspectMatteMode(ProOverlayView.AspectMatte.VERTICAL_9_16) }
        viewBinding.btnMatte45.setOnClickListener { setAspectMatteMode(ProOverlayView.AspectMatte.PORTRAIT_4_5) }

        // Gyro & Balance Switches
        viewBinding.switchHorizonLevel.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.showLevelGauge = isChecked
            viewBinding.tvHorizonLevelStatus.text = if (isChecked) "0.0° Level · Pitch and roll active" else "Horizon Level Gyro: Inactive"
            updateActiveInstrumentsCount()
        }
        viewBinding.switchVisualBalance.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.showVisualBalance = isChecked
            viewBinding.tvVisualBalanceStatus.text = if (isChecked) "Balanced subject framing active" else "Visual Balance Reticle: Inactive"
            updateActiveInstrumentsCount()
        }

        // TAB 2: Exposure Switches & Instruments
        viewBinding.switchZebra.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.showZebraStripes = isChecked
            viewBinding.tvZebraStatus.text = if (isChecked) "Clipping warning (${viewBinding.proOverlayView.zebraThreshold}% IRE)" else "Zebra warning disabled"
            updateActiveInstrumentsCount()
        }
        viewBinding.btnZebraThreshold.setOnClickListener {
            val next = if (viewBinding.proOverlayView.zebraThreshold == 240) 250 else 240
            viewBinding.proOverlayView.zebraThreshold = next
            viewBinding.btnZebraThreshold.text = if (next == 250) "98%" else "95%"
            viewBinding.tvZebraStatus.text = "Clipping warning (${if (next == 250) "98%" else "95%"} IRE)"
            // silent (no popup)
        }
        viewBinding.switchWaveform.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.showWaveform = isChecked
            updateActiveInstrumentsCount()
        }
        viewBinding.switchHistogram.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.showHistogram = isChecked
            updateActiveInstrumentsCount()
        }
        viewBinding.switchVectorScope.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.showVectorScope = isChecked
            updateActiveInstrumentsCount()
        }

        // TAB 3: Optics Lenses (0.6x, 1x, 2x, 5x)
        viewBinding.btnLens06.setOnClickListener { setZoomLevel(0.6f, 0) }
        viewBinding.btnLens1.setOnClickListener { setZoomLevel(1.0f, 1) }
        viewBinding.btnLens2.setOnClickListener { setZoomLevel(2.0f, 2) }
        viewBinding.btnLens5.setOnClickListener { setZoomLevel(5.0f, 3) }

        // De-Squeeze (OFF, 1.33x, 1.55x, 1.8x, 2.0x)
        viewBinding.btnSqueezeOff.setOnClickListener { setDeSqueeze("OFF", 0) }
        viewBinding.btnSqueeze133.setOnClickListener { setDeSqueeze("1.33x", 1) }
        viewBinding.btnSqueeze155.setOnClickListener { setDeSqueeze("1.55x", 2) }
        viewBinding.btnSqueeze180.setOnClickListener { setDeSqueeze("1.8x", 3) }
        viewBinding.btnSqueeze200.setOnClickListener { setDeSqueeze("2.0x", 4) }

        // TAB 4: Audio Switches
        viewBinding.switchAudioVuMeter.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.showAudioVuMeter = isChecked
            updateActiveInstrumentsCount()
        }
        viewBinding.switchMicInput.setOnCheckedChangeListener { _, isChecked ->
            isAudioMuted = !isChecked
            updateAudioMuteUI()
        }
        viewBinding.switchMultiCamPip.setOnCheckedChangeListener { _, isChecked ->
            // silent (no popup)
            updateActiveInstrumentsCount()
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

    /**
     * Bind Preview, ImageCapture, and VideoCapture to current lifecycle
     */
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

        // Preview Use Case
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
        }

        // ImageCapture Use Case (Force MAXIMIZE_QUALITY in NIGHT mode for full multi-frame ISP capture)
        val desiredCaptureMode = if (cameraMode == CameraMode.NIGHT) {
            ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        } else {
            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(desiredCaptureMode)
            .setFlashMode(flashMode)
            .build()

        // VideoCapture Use Case with Recorder
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.HD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                )
            )
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        // Real-Time Frame Analysis Engine: Live Histogram, Zebra Overexposure, Waveform & Vector Scope
        // Golden Rule: All pixel math is executed asynchronously on background cameraExecutor thread.
        val histoBins = IntArray(256)
        val overexposedList = ArrayList<Float>()
        val wfColsArray = FloatArray(36 * 2) // Pre-allocated [minIre, maxIre] pairs
        val vsPointsArray = FloatArray(48 * 2) // Pre-allocated [normCb, normCr] pairs

        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    if (!viewBinding.proOverlayView.isCleanScreen && (
                        viewBinding.proOverlayView.showHistogram ||
                        viewBinding.proOverlayView.showZebraStripes ||
                        viewBinding.proOverlayView.showVisualBalance ||
                        viewBinding.proOverlayView.showWaveform ||
                        viewBinding.proOverlayView.showVectorScope
                    )) {
                        try {
                            val plane = imageProxy.planes[0]
                            val buffer = plane.buffer
                            val width = imageProxy.width
                            val height = imageProxy.height
                            val rowStride = plane.rowStride
                            val pixelStride = plane.pixelStride
                            
                            histoBins.fill(0)
                            overexposedList.clear()
                            
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
                                            if (clipCount % 12 == 0) {
                                                val nX = x.toFloat() / width.toFloat()
                                                val nY = y.toFloat() / height.toFloat()
                                                overexposedList.add(nX - 0.04f)
                                                overexposedList.add(nY - 0.04f)
                                                overexposedList.add(nX + 0.04f)
                                                overexposedList.add(nY + 0.04f)
                                            }
                                        }
                                    }
                                }
                            }
                            
                            val balanceX = if (totalWeight > 0f) (sumWeightX / totalWeight) / width.toFloat() else 0.5f
                            val balanceY = if (totalWeight > 0f) (sumWeightY / totalWeight) / height.toFloat() else 0.5f
                            val overexposedArray = overexposedList.toFloatArray()

                            // Pass ONLY finalized arrays back to UI thread for instant 60fps rendering without pixel loops
                            runOnUiThread {
                                viewBinding.proOverlayView.updateLuminanceAnalysis(histoBins, overexposedArray, balanceX, balanceY)
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

                    // 4. Built-in Real-Time FaceDetector Tracking for AR Lenses (no external asset required)
                    analysisFrameCounter++
                    if ((viewBinding.arFaceOverlayView.currentLens != ARFaceOverlayView.ArLensMode.NONE &&
                            viewBinding.arFaceOverlayView.currentLens != ARFaceOverlayView.ArLensMode.COMIC_FRAME ||
                            viewBinding.arFaceOverlayView.enableComicOnomatopoeia ||
                            viewBinding.arFaceOverlayView.enableComicSpeechBubble) &&
                        analysisFrameCounter % 3 == 0
                    ) {
                        try {
                            val plane = imageProxy.planes[0]
                            val buffer = plane.buffer
                            val srcW = imageProxy.width
                            val srcH = imageProxy.height
                            val rowStride = plane.rowStride
                            val pixelStride = plane.pixelStride
                            val rot = imageProxy.imageInfo.rotationDegrees

                            val step = Math.max(2, Math.min(srcW, srcH) / 140)
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
                                    normEyeDist = (eyeDist / outW.toFloat()).coerceIn(0.06f, 0.55f),
                                    isFrontFacing = lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA
                                )
                            }
                            faceBmp.recycle()
                        } catch (e: Exception) {
                            Log.e(TAG, "Face tracking error", e)
                        }
                    }

                    imageProxy.close()
                }
            }

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

            val isPhotoType = cameraMode == CameraMode.PHOTO || cameraMode == CameraMode.PORTRAIT || cameraMode == CameraMode.NIGHT
            if (isPhotoType) {
                camera = cameraProvider.bindToLifecycle(this, effectiveSelector, preview, imageCapture!!, imageAnalysis!!)
            } else {
                val proc = openGLProcessor ?: OpenGLSurfaceProcessor().also { openGLProcessor = it }
                proc.setShootingEffect(currentShootingEffect)
                proc.setAdjustments(
                    viewBinding.proOverlayView.liveBrightnessPercent,
                    viewBinding.proOverlayView.liveContrastPercent,
                    viewBinding.proOverlayView.liveSaturationPercent
                )
                proc.setNightBoost(cameraMode == CameraMode.NIGHT || currentShootingEffect == "night_sight")
                var boundWithEffect = false
                try {
                    val effect = OpenGLSurfaceProcessor.createCameraEffect(proc, proc.getExecutor())
                    val groupBuilder = UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(videoCapture!!)
                        .addUseCase(imageAnalysis!!)
                        .addEffect(effect)
                    camera = cameraProvider.bindToLifecycle(this, lensFacing, groupBuilder.build())
                    cameraEffect = effect
                    boundWithEffect = true
                } catch (effectErr: Exception) {
                    Log.w(TAG, "CameraEffect VideoCapture binding unsupported on HAL, falling back to standard VideoCapture", effectErr)
                    cameraEffect = null
                }
                if (!boundWithEffect) {
                    camera = cameraProvider.bindToLifecycle(this, lensFacing, preview, videoCapture!!, imageAnalysis!!)
                }
            }

            cameraControl = camera?.cameraControl

            // Initialize Exposure compensation range from CameraInfo (auto-lifts EV in NIGHT mode)
            initExposureRange()

            // Re-apply real-time ColorMatrix / RenderEffect filter onto TextureView preview
            viewBinding.viewFinder.post { applyLivePreviewColorMatrix() }

        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed, attempting fallback without analysis", exc)
            try {
                cameraProvider.unbindAll()
                val isPhotoType = cameraMode == CameraMode.PHOTO || cameraMode == CameraMode.PORTRAIT || cameraMode == CameraMode.NIGHT
                if (isPhotoType) {
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
        takePhoto(isBurst = true)

        // Rapid capture every 200ms
        burstHandler.postDelayed({
            startBurstCapturingLoop()
        }, 200)
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
                viewBinding.tvCountdown.animate().scaleX(1.4f).scaleY(1.4f).setDuration(150).withEndAction {
                    viewBinding.tvCountdown.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }.start()
            }

            override fun onFinish() {
                viewBinding.tvCountdown.visibility = View.GONE
                isCountingDown = false
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

            viewBinding.exposureSlider.max = max - min
            viewBinding.exposureSlider.progress = (activeIndex - min).coerceIn(0, max - min)

            viewBinding.exposureLayout.visibility = View.VISIBLE
            updateExposureLabel(activeIndex, exposureState.exposureCompensationStep.toFloat())
        } else {
            viewBinding.exposureLayout.visibility = View.GONE
        }
    }

    private fun setupExposureSlider() {
        viewBinding.exposureSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
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
        viewBinding.tvExposureValue.text = String.format(Locale.US, "EV %s%.1f", prefix, evValue)
    }

    private var lastActiveGrid = ProOverlayView.GridType.RULE_OF_THIRDS

    private fun selectShelfTab(category: ShelfCategory) {
        currentShelfTab = category
        viewBinding.layoutCategoryGrids.visibility = if (category == ShelfCategory.GRIDS) View.VISIBLE else View.GONE
        viewBinding.layoutCategoryExposure.visibility = if (category == ShelfCategory.EXPOSURE) View.VISIBLE else View.GONE
        viewBinding.layoutCategoryOptics.visibility = if (category == ShelfCategory.OPTICS) View.VISIBLE else View.GONE
        viewBinding.layoutCategoryAudio.visibility = if (category == ShelfCategory.AUDIO) View.VISIBLE else View.GONE

        // Update tab styles: Single un-bordered row, sharp solid neon yellow horizontal line (2dp thick) positioned exactly beneath active text
        val tabs = listOf(
            Triple(viewBinding.tabCategoryGrids, viewBinding.indicatorGrids, ShelfCategory.GRIDS),
            Triple(viewBinding.tabCategoryExposure, viewBinding.indicatorExposure, ShelfCategory.EXPOSURE),
            Triple(viewBinding.tabCategoryOptics, viewBinding.indicatorOptics, ShelfCategory.OPTICS),
            Triple(viewBinding.tabCategoryAudio, viewBinding.indicatorAudio, ShelfCategory.AUDIO)
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
        val container = viewBinding.layoutArtContainer
        val picGroup = viewBinding.layoutPictureArtGroup
        val vidGroup = viewBinding.layoutVideoArtGroup
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

    private fun selectCreativeCategory(target: CreativeTarget, fromUser: Boolean = true) {
        currentCreativeTarget = target
        if (fromUser) {
            isLastActionCreative = true
        }

        val isFilter = target == CreativeTarget.FILTERS
        val isLens = target == CreativeTarget.LENSES
        val isEffect = target == CreativeTarget.EFFECTS
        val isArt = target == CreativeTarget.ART
        val isToon = target == CreativeTarget.TOON

        viewBinding.btnCategoryFilters.setBackgroundResource(if (isFilter) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
        viewBinding.btnCategoryFilters.setTextColor(Color.parseColor(if (isFilter) "#0F172A" else "#94A3B8"))

        viewBinding.btnCategoryLenses.setBackgroundResource(if (isLens) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
        viewBinding.btnCategoryLenses.setTextColor(Color.parseColor(if (isLens) "#0F172A" else "#94A3B8"))

        viewBinding.btnCategoryEffects.setBackgroundResource(if (isEffect) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
        viewBinding.btnCategoryEffects.setTextColor(Color.parseColor(if (isEffect) "#0F172A" else "#94A3B8"))

        viewBinding.btnCategoryArt.setBackgroundResource(if (isArt) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
        viewBinding.btnCategoryArt.setTextColor(Color.parseColor(if (isArt) "#0F172A" else "#94A3B8"))

        viewBinding.btnCategoryToon.setBackgroundResource(if (isToon) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
        viewBinding.btnCategoryToon.setTextColor(Color.parseColor(if (isToon) "#0F172A" else "#94A3B8"))

        viewBinding.scrollFiltersStrip.visibility = if (isFilter) View.VISIBLE else View.GONE
        viewBinding.scrollLensesStrip.visibility = if (isLens) View.VISIBLE else View.GONE
        viewBinding.scrollEffectsStrip.visibility = if (isEffect) View.VISIBLE else View.GONE
        viewBinding.scrollArtStrip.visibility = if (isArt) View.VISIBLE else View.GONE
        viewBinding.scrollToonStrip.visibility = if (isToon) View.VISIBLE else View.GONE

        if (isArt) {
            updateArtStripModeOrder()
            updateArtEffectButtons()
        } else if (isEffect) {
            updateShootingEffectButtons()
        } else if (isToon) {
            updateToonEffectButtons()
        }

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

    private fun updateCreativeStatusUI() {
        val statusText = when (currentCreativeTarget) {
            CreativeTarget.FILTERS -> {
                val total = (filterPresetOrder.size - 1).coerceAtLeast(1)
                val idx = filterPresetOrder.indexOf(currentFilterName)
                val label = currentFilterName.replace("_", " ").uppercase(Locale.ROOT)
                if (idx > 0) "FILTER: $label ($idx/$total)" else "FILTER: NORMAL (0/$total)"
            }
            CreativeTarget.LENSES -> {
                val total = (arLensOrder.size - 1).coerceAtLeast(1)
                val idx = arLensOrder.indexOf(currentArLensName)
                val label = currentArLensName.replace("_", " ").uppercase(Locale.ROOT)
                when {
                    currentArLensName == "comic_frame" && isCartoonShaderActive() -> "LENS: $label · PANEL ON ($idx/$total)"
                    currentArLensName == "comic_frame" -> "LENS: $label · NEED TOON/COMIC ($idx/$total)"
                    idx > 0 -> "LENS: $label ($idx/$total)"
                    else -> "LENS: OFF (0/$total)"
                }
            }
            CreativeTarget.ART -> {
                val artList = getModeOrderedArtEffects()
                val total = (artList.size - 1).coerceAtLeast(1)
                val idx = artList.indexOf(currentShootingEffect)
                if (idx > 0) {
                    "ART: ${getShootingEffectLabel(currentShootingEffect)} ($idx/$total)"
                } else {
                    "ART: OFF (0/$total)"
                }
            }
            CreativeTarget.TOON -> {
                val total = (toonStyleOrder.size - 1).coerceAtLeast(1)
                val idx = toonStyleOrder.indexOf(currentShootingEffect)
                if (idx > 0) {
                    "TOON: ${getShootingEffectLabel(currentShootingEffect)} ($idx/$total)"
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
        viewBinding.tvEffectsActiveStatus.text = statusText
        viewBinding.tvEffectsActiveStatus.isSelected = true
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
        "portrait_bokeh", "video_bokeh", "tilt_shift" -> true
        else -> false
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
        val isIdentity = stylizedActive || (currentFilterName == "normal" && bri == 0 && con == 0 && sat == 0 &&
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
        }

        // 1b. Night Mode & Optical Shooting Effects live luminance boosts
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

        val colorFilter = ColorMatrixColorFilter(combined)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            targetView.setRenderEffect(RenderEffect.createColorFilterEffect(colorFilter))
        } else {
            val paint = Paint().apply { this.colorFilter = colorFilter }
            targetView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        }
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
            else -> FilterManager.FilterPreset.NORMAL
        }
        filterManager.setPreset(filterPreset)
        openGLProcessor?.setFilterPreset(filterPreset)
        viewBinding.proOverlayView.activeFilterPreset = preset
        syncComicFrameOverlayState()
        if (!isStylizedLiveEffect(currentShootingEffect) && !isComicFilterPreset(preset)) {
            viewBinding.proOverlayView.updateProcessedEffectBitmap(null)
        }
        applyLivePreviewColorMatrix()

        val filterButtons = listOf(
            viewBinding.btnFilterNormal to "normal",
            viewBinding.btnFilterLutRec709 to "lut_rec709",
            viewBinding.btnFilterLutFlatLog to "lut_flat_log",
            viewBinding.btnFilterLutTealOrange to "lut_teal_orange",
            viewBinding.btnFilterLutBleachBypass to "lut_bleach_bypass",
            viewBinding.btnFilterLutAnamorphicCyan to "lut_anamorphic_cyan",
            viewBinding.btnFilterLutMatteCinema to "lut_matte_cinema",
            viewBinding.btnFilterLutMidnightNeon to "lut_midnight_neon",
            viewBinding.btnFilterLutWarmDoc to "lut_warm_documentary",
            viewBinding.btnFilterLutNoirCinema to "lut_noir_cinema",
            viewBinding.btnFilterGoldenNostalgia to "golden_nostalgia",
            viewBinding.btnFilterVelvia to "velvia_landscape",
            viewBinding.btnFilterNordicSlate to "nordic_slate",
            viewBinding.btnFilterArcticMist to "arctic_mist",
            viewBinding.btnFilterClassicMono to "classic_mono",
            viewBinding.btnFilterNoir to "high_contrast_noir",
            viewBinding.btnFilterIlfordGrain to "ilford_grain",
            viewBinding.btnFilterSilverGelatin to "silver_gelatin",
            viewBinding.btnFilterAntiqueSepia to "antique_sepia",
            viewBinding.btnFilterCinematic to "cinematic",
            viewBinding.btnFilterVintage to "vintage",
            viewBinding.btnFilterBW to "bw",
            viewBinding.btnFilterCyber to "cyberpunk",
            viewBinding.btnFilterHDR to "hdr",
            viewBinding.btnFilterComicBook to "comic_book"
        )
        for ((btn, name) in filterButtons) {
            val isActive = name == preset
            if (isActive) {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_active)
                btn.setTextColor(Color.parseColor("#0F172A"))
                scrollChipIntoView(viewBinding.scrollFiltersStrip, btn)
            } else {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_inactive)
                btn.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
        updateCreativeStatusUI()
        if (showNotice) {
            showHudNotice("Filter: ${preset.replace('_', ' ').uppercase(Locale.ROOT)}")
        }
    }

    private fun setArLens(lens: String, showNotice: Boolean = true) {
        currentArLensName = lens
        if (showNotice) {
            currentCreativeTarget = CreativeTarget.LENSES
            isLastActionCreative = true
        }
        val mode = when (lens.lowercase(Locale.ROOT)) {
            "mesh_contour", "mesh" -> ARFaceOverlayView.ArLensMode.MESH_CONTOUR
            "cyberpunk_visor", "visor" -> ARFaceOverlayView.ArLensMode.CYBERPUNK_VISOR
            "golden_crown", "crown" -> ARFaceOverlayView.ArLensMode.GOLDEN_CROWN
            "scifi_hud", "hud" -> ARFaceOverlayView.ArLensMode.SCIFI_HUD
            "comic_frame", "comic", "comic_panel" -> ARFaceOverlayView.ArLensMode.COMIC_FRAME
            else -> ARFaceOverlayView.ArLensMode.NONE
        }
        syncComicFrameOverlayState()
        viewBinding.arFaceOverlayView.currentLens = mode
        if (mode == ARFaceOverlayView.ArLensMode.NONE) {
            viewBinding.arFaceOverlayView.clear()
        }
        val lensButtons = listOf(
            viewBinding.btnArNone to "none",
            viewBinding.btnArMesh to "mesh_contour",
            viewBinding.btnArVisor to "cyberpunk_visor",
            viewBinding.btnArCrown to "golden_crown",
            viewBinding.btnArHud to "scifi_hud",
            viewBinding.btnArComicFrame to "comic_frame"
        )
        for ((btn, name) in lensButtons) {
            val isActive = name == lens
            if (isActive) {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_active)
                btn.setTextColor(Color.parseColor("#0F172A"))
                scrollChipIntoView(viewBinding.scrollLensesStrip, btn)
            } else {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_inactive)
                btn.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
        updateCreativeStatusUI()
        if (showNotice) {
            if (mode == ARFaceOverlayView.ArLensMode.COMIC_FRAME && !isCartoonShaderActive()) {
                showHudNotice("AR Lens: COMIC FRAME (Activate Comic Book or Toon shader)", isWarning = true)
            } else {
                showHudNotice("AR Lens: ${lens.replace('_', ' ').uppercase(Locale.ROOT)}")
            }
        }
    }

    private fun toggleComicOnomatopoeia() {
        val next = !viewBinding.arFaceOverlayView.enableComicOnomatopoeia
        viewBinding.arFaceOverlayView.enableComicOnomatopoeia = next
        viewBinding.btnArComicOnomatopoeia.setBackgroundResource(
            if (next) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive
        )
        viewBinding.btnArComicOnomatopoeia.setTextColor(
            Color.parseColor(if (next) "#0F172A" else "#FACC15")
        )
        viewBinding.btnArComicOnomatopoeia.text = if (next) "POW! ON" else "POW! COMIC"
        if (next && !isAudioMuted) {
            playCameraCue(ToneGenerator.TONE_PROP_BEEP2, 110)
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
                "anamorphic_flare", "dolly_zoom", "motion_blur", "action_stabilization",
                "vintage_super8", "film_16mm", "vhs_tape", "crt_monitor", "stop_motion",
                "tilt_shift", "film_grain", "soft_halation", "vignette"
            )
        } else {
            listOf(
                "none",
                "portrait_bokeh", "long_exposure", "light_painting", "star_trails",
                "night_sight", "macro_focus", "tilt_shift", "double_exposure", "panorama",
                "film_grain", "soft_halation", "vignette"
            )
        }
    }

    private fun updateShootingEffectButtons() {
        val isVideoMode = (cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.SLOW_MO)
        val effectButtons = listOf(
            viewBinding.btnEffectOff to "none",
            viewBinding.btnEffectBokeh to "portrait_bokeh",
            viewBinding.btnEffectLongExp to "long_exposure",
            viewBinding.btnEffectLightPaint to "light_painting",
            viewBinding.btnEffectStarTrails to "star_trails",
            viewBinding.btnEffectNightSight to "night_sight",
            viewBinding.btnEffectMacro to "macro_focus",
            viewBinding.btnEffectTiltShift to "tilt_shift",
            viewBinding.btnEffectDoubleExp to "double_exposure",
            viewBinding.btnEffectPanorama to "panorama",
            viewBinding.btnEffectFilmGrain to "film_grain",
            viewBinding.btnEffectHalation to "soft_halation",
            viewBinding.btnEffectVignette to "vignette",
            viewBinding.btnEffectVideoBokeh to "video_bokeh",
            viewBinding.btnEffectSlowMotion to "slow_motion_120_240",
            viewBinding.btnEffectTimeLapse to "time_lapse",
            viewBinding.btnEffectHyperlapse to "hyperlapse",
            viewBinding.btnEffectAnamorphicFlare to "anamorphic_flare",
            viewBinding.btnEffectDollyZoom to "dolly_zoom",
            viewBinding.btnEffectMotionBlur to "motion_blur",
            viewBinding.btnEffectHorizonLock to "action_stabilization",
            viewBinding.btnEffectSuper8 to "vintage_super8",
            viewBinding.btnEffectFilm16mm to "film_16mm",
            viewBinding.btnEffectVhsTape to "vhs_tape",
            viewBinding.btnEffectCrtMonitor to "crt_monitor",
            viewBinding.btnEffectStopMotion to "stop_motion"
        )
        val activeShootingId = if (artEffectIds.contains(currentShootingEffect) || toonEffectIds.contains(currentShootingEffect)) "none" else currentShootingEffect
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
                scrollChipIntoView(viewBinding.scrollEffectsStrip, btn)
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
            viewBinding.btnArtOff to "none",
            viewBinding.btnEffectOilCanvas to "oil_canvas",
            viewBinding.btnEffectCharcoalSketch to "charcoal_sketch",
            viewBinding.btnEffectWatercolorWash to "watercolor_wash",
            viewBinding.btnEffectInkLine to "ink_and_line",
            viewBinding.btnEffectPastelChalk to "pastel_chalk",
            viewBinding.btnEffectHalftonePrint to "halftone_print",
            viewBinding.btnEffectCyanotype to "cyanotype",
            viewBinding.btnEffectWoodcut to "woodcut_engraving",
            viewBinding.btnEffectLithograph to "lithograph",
            viewBinding.btnEffectPrism to "prism_refraction",
            viewBinding.btnEffectKaleidoscope to "kaleidoscope",
            viewBinding.btnEffectSolarize to "solarize",
            viewBinding.btnEffectNeonEdge to "neon_edge",
            viewBinding.btnEffectNoirNovel to "noir_graphic_novel",
            viewBinding.btnEffectThermalVision to "thermal_vision"
        )
        val activeArtId = if (artEffectIds.contains(currentShootingEffect)) currentShootingEffect else "none"
        for ((btn, id) in artButtons) {
            val isActive = id == activeArtId
            if (isActive) {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_active)
                btn.setTextColor(Color.parseColor("#0F172A"))
                scrollChipIntoView(viewBinding.scrollArtStrip, btn)
            } else {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_inactive)
                btn.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
    }

    private fun updateToonEffectButtons() {
        val toonButtons = listOf(
            viewBinding.btnToonOff to "none",
            viewBinding.btnEffectCelShaded to "cel_shaded_studio",
            viewBinding.btnEffectGraphicNoir to "graphic_novel_noir",
            viewBinding.btnEffectMangaInk to "manga_ink",
            viewBinding.btnEffectPopArt to "pop_art_print",
            viewBinding.btnEffectLineArt to "line_art_minimalist",
            viewBinding.btnEffectLigneClaire to "ligne_claire",
            viewBinding.btnEffectClayVinyl to "clay_vinyl_3d"
        )
        val activeToonId = if (toonEffectIds.contains(currentShootingEffect)) currentShootingEffect else "none"
        for ((btn, id) in toonButtons) {
            val isActive = id == activeToonId
            if (isActive) {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_active)
                btn.setTextColor(Color.parseColor("#0F172A"))
                scrollChipIntoView(viewBinding.scrollToonStrip, btn)
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
        if (!isStylizedLiveEffect(effectId) && !isComicFilterPreset(currentFilterName)) {
            viewBinding.proOverlayView.updateProcessedEffectBitmap(null)
        }
        openGLProcessor?.setShootingEffect(effectId)
        openGLProcessor?.setNightBoost(cameraMode == CameraMode.NIGHT || effectId == "night_sight")
        viewBinding.proOverlayView.enableVignette = (effectId == "vignette")

        isUpdatingEffectSwitches = true
        try {
            val isGrain = (effectId == "film_grain")
            viewBinding.switchFilmGrain.isChecked = isGrain
            viewBinding.proOverlayView.enableFilmGrain = isGrain

            val isFlare = (effectId == "anamorphic_flare")
            viewBinding.switchCineFlare.isChecked = isFlare
            viewBinding.proOverlayView.enableCineFlare = isFlare
        } finally {
            isUpdatingEffectSwitches = false
        }

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
            val cropScale = when (effectId) {
                "macro_focus" -> 1.26f
                "action_stabilization" -> 1.15f
                "hyperlapse" -> 1.10f
                else -> 1.0f
            }
            viewBinding.viewFinder.animate().scaleX(cropScale).scaleY(cropScale).setDuration(180).start()
        }

        applyLivePreviewColorMatrix()
        updateShootingEffectButtons()
        updateCreativeStatusUI()
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
        val w = srcBmp.width.coerceAtLeast(1)
        val h = srcBmp.height.coerceAtLeast(1)
        val maxSample = if (forLivePreview) 420 else 860
        val sw: Int
        val sh: Int
        if (w >= h) {
            sw = Math.min(w, maxSample)
            sh = Math.max(64, (sw * (h / w.toFloat())).toInt())
        } else {
            sh = Math.min(h, maxSample)
            sw = Math.max(64, (sh * (w / h.toFloat())).toInt())
        }

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
            "oil_canvas", "watercolor_wash" -> if (forLivePreview) 3 else 5
            "pastel_chalk", "lithograph" -> if (forLivePreview) 2 else 4
            else -> 0
        }
        val baseArr = if (kuwaharaRadius > 0) applyKuwaharaIntArray(rawArr, sw, sh, kuwaharaRadius) else rawArr

        val smoothPasses = when (effectId) {
            // Toon & Comic-Book styles get heavy edge-preserving bilateral flattening for clean cartoon/anime/comic regions
            "comic_book", "comic", "cartoon", "cel_shaded_studio", "ligne_claire", "pop_art_print", "clay_vinyl_3d" -> if (forLivePreview) 3 else 4
            "graphic_novel_noir", "noir_graphic_novel", "manga_ink", "line_art_minimalist" -> if (forLivePreview) 3 else 4
            // Art styles get smooth bilateral blending after Kuwahara to erase box corners cleanly
            "oil_canvas", "watercolor_wash", "pastel_chalk", "lithograph" -> if (forLivePreview) 2 else 3
            "charcoal_sketch", "ink_and_line", "woodcut_engraving", "halftone_print" -> if (forLivePreview) 2 else 3
            else -> 0
        }
        val smoothThresh = when (effectId) {
            "comic_book", "comic", "cartoon", "cel_shaded_studio", "ligne_claire", "pop_art_print", "clay_vinyl_3d" -> 44f
            "oil_canvas", "watercolor_wash", "pastel_chalk" -> 34f
            else -> 30f
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
            val t = ((v - e0) / Math.max(0.001f, e1 - e0)).coerceIn(0f, 1f)
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
            val rad = Math.sqrt(darkness.coerceIn(0f, 1f).toDouble()).toFloat() * 0.50f
            return 1f - smoothstep(rad - 0.08f, rad + 0.08f, dist)
        }

        fun packRgb(r: Int, g: Int, b: Int): Int {
            return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }

        when (effectId) {
            "portrait_bokeh", "video_bokeh", "tilt_shift" -> {
                val blurred = smoothIntArray(rawArr, sw, sh, if (forLivePreview) 4 else 6, 60f)
                val isTilt = effectId == "tilt_shift"
                for (y in 0 until sh) {
                    val ny = y / sh.toFloat()
                    val row = y * sw
                    for (x in 0 until sw) {
                        val idx = row + x
                        val nx = x / sw.toFloat()
                        val mask = if (isTilt) {
                            (Math.abs(ny - 0.5f) - 0.14f) / 0.24f
                        } else {
                            val d = Math.hypot((nx - 0.5).toDouble(), ((ny - 0.45) * 1.15).toDouble()).toFloat()
                            (d - 0.22f) / 0.28f
                        }.coerceIn(0f, 1f)
                        val o = rawArr[idx]
                        val b = blurred[idx]
                        outArr[idx] = packRgb(
                            (((o shr 16) and 0xFF) * (1f - mask) + ((b shr 16) and 0xFF) * mask).toInt(),
                            (((o shr 8) and 0xFF) * (1f - mask) + ((b shr 8) and 0xFF) * mask).toInt(),
                            ((o and 0xFF) * (1f - mask) + (b and 0xFF) * mask).toInt()
                        )
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
                val dotPitch = Math.max(4.2f, sw / 92f)
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
                val dotPitch = Math.max(4.0f, sw / 82f)
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
                val dotPitch = Math.max(3.8f, sw / 86f)
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
                val dotPitch = Math.max(4.4f, sw / 68f)
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
                // Smooth 3D Toy / Pixar Vinyl Toon: 4-pass bilateral surface (no blocky Kuwahara) + 2px smooth normals + vinyl specular
                for (y in 0 until sh) {
                    val ym = (y - 2).coerceIn(0, sh - 1) * sw
                    val yp = (y + 2).coerceIn(0, sh - 1) * sw
                    val row = y * sw
                    for (x in 0 until sw) {
                        val xm = (x - 2).coerceIn(0, sw - 1)
                        val xp = (x + 2).coerceIn(0, sw - 1)
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
                // Organic painterly oil canvas: wide 2px gradient impasto on bilateral-smoothed Kuwahara + fine linen weave (zero square grid!)
                for (y in 0 until sh) {
                    val ym = (y - 2).coerceIn(0, sh - 1) * sw
                    val yp = (y + 2).coerceIn(0, sh - 1) * sw
                    val row = y * sw
                    for (x in 0 until sw) {
                        val xm = (x - 2).coerceIn(0, sw - 1)
                        val xp = (x + 2).coerceIn(0, sw - 1)
                        val idx = row + x
                        val c = pxArr[idx]
                        val r0 = (c shr 16) and 0xFF
                        val g0 = (c shr 8) and 0xFF
                        val b0 = c and 0xFF
                        val l = lum[idx]
                        // Smooth 2px-radius directional paint ridge (no sharp 1px box-grid edges!)
                        val dx = lum[row + xp] - lum[row + xm]
                        val dy = lum[yp + x] - lum[ym + x]
                        val gradMag = Math.abs(dx) + Math.abs(dy)
                        val ridge = ((-dx * 0.45f - dy * 0.55f) * 0.22f).coerceIn(-12f, 16f)
                        // Organic curved bristle streak along diagonal strokes + fine canvas tooth
                        val bristle = (Math.sin(x * 0.58 + y * 0.52 + Math.sin((x - y) * 0.14) * 1.8) * 3.8).toFloat()
                        val weave = (Math.sin(x * 1.9) * Math.sin(y * 1.9) * 2.2).toFloat()
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
                    for (x in 0 until sw) {
                        val idx = row + x
                        val edgeInk = smoothstep(22f, 52f, edges[idx])
                        val l = lum[idx]
                        val h1 = 0.5f + 0.5f * Math.sin((x + y) * 0.88 + Math.sin(y * 0.12)).toFloat()
                        val h2 = 0.5f + 0.5f * Math.sin((x - y) * 0.88 + Math.cos(x * 0.12)).toFloat()
                        val midShade = smoothstep(158f, 76f, l) * (0.26f + 0.40f * h1)
                        val deepShade = smoothstep(82f, 24f, l) * (0.42f + 0.46f * h2)
                        val shade = Math.max(edgeInk * 0.94f, Math.max(midShade, deepShade))
                        val tooth = (Math.sin(x * 1.6 + y * 0.8) * Math.cos(y * 1.2 - x * 0.6) * 5.0).toFloat()
                        val paperL = 245f - (1f - (l / 255f).coerceIn(0f, 1f)) * 18f + tooth
                        val v = paperL * (1f - shade) + 20f * shade
                        outArr[idx] = packRgb((v + 3f).toInt(), v.toInt(), (v - 6f).toInt())
                    }
                }
            }
            "ink_and_line" -> {
                for (y in 0 until sh) {
                    val row = y * sw
                    for (x in 0 until sw) {
                        val idx = row + x
                        val edgeInk = smoothstep(22f, 48f, edges[idx])
                        val l = lum[idx]
                        val solidDark = smoothstep(42f, 20f, l)
                        val w1 = smoothstep(0.62f, 0.92f, 0.5f + 0.5f * Math.sin((x + y) * 1.15).toFloat())
                        val w2 = smoothstep(0.62f, 0.92f, 0.5f + 0.5f * Math.sin((x - y) * 1.15).toFloat())
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
                    for (x in 0 until sw) {
                        val idx = row + x
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
                        val tooth = ((Math.sin(x * 0.8 + y * 0.45) + Math.cos(y * 0.7 - x * 0.35)) * 3.0).toFloat()
                        outArr[idx] = packRgb((r * pool + tooth).toInt(), (g * pool + tooth).toInt(), (b * pool + tooth).toInt())
                    }
                }
            }
            "pastel_chalk" -> {
                for (y in 0 until sh) {
                    val row = y * sw
                    for (x in 0 until sw) {
                        val idx = row + x
                        val c = pxArr[idx]
                        val r0 = (c shr 16) and 0xFF
                        val g0 = (c shr 8) and 0xFF
                        val b0 = c and 0xFF
                        val l = Math.max(1f, lum[idx])
                        val softL = softArtBand(l, 7)
                        val sc = softL / l
                        val chalkTooth = (Math.sin(x * 0.42 + y * 0.32) * 2.2 + Math.cos(y * 0.80) * 1.4).toFloat()
                        val outline = smoothstep(32f, 80f, edges[idx]) * 0.16f
                        val r = ((l + (r0 - l) * 0.88f) * sc * 0.78f + 56f + chalkTooth) * (1f - outline)
                        val g = ((l + (g0 - l) * 0.88f) * sc * 0.78f + 54f + chalkTooth) * (1f - outline)
                        val b = ((l + (b0 - l) * 0.88f) * sc * 0.78f + 58f + chalkTooth) * (1f - outline)
                        outArr[idx] = packRgb(r.toInt().coerceIn(30, 255), g.toInt().coerceIn(28, 255), b.toInt().coerceIn(32, 255))
                    }
                }
            }
            "halftone_print" -> {
                val pitch = Math.max(3.8f, sw / 82f)
                for (y in 0 until sh) {
                    val row = y * sw
                    for (x in 0 until sw) {
                        val idx = row + x
                        val l = (lum[idx] / 255f).coerceIn(0f, 1f)
                        val darkness = (1f - Math.pow(l.toDouble(), 0.92).toFloat()).coerceIn(0f, 1f)
                        val dotInk = rotatedHalftoneDot(x, y, darkness, pitch)
                        val crispEdge = smoothstep(28f, 60f, edges[idx]) * 0.65f
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
                    for (x in 0 until sw) {
                        val idx = row + x
                        val l = (lum[idx] / 255f).coerceIn(0f, 1f)
                        val wave = 0.5f + 0.5f * Math.sin(y * 1.35 + Math.sin(x * 0.05) * 2.2).toFloat()
                        val burin = smoothstep(l - 0.14f, l + 0.14f, wave)
                        val borderInk = smoothstep(24f, 54f, edges[idx])
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
                    for (x in 0 until sw) {
                        val idx = row + x
                        val c = pxArr[idx]
                        val r0 = (c shr 16) and 0xFF
                        val g0 = (c shr 8) and 0xFF
                        val b0 = c and 0xFF
                        val l = Math.max(1f, lum[idx])
                        val stoneBand = softArtBand(l, 5)
                        val sc = stoneBand / l
                        val stipple = (Math.sin(x * 2.1 + y * 1.7) * Math.cos(x * 1.3 - y * 2.3) * 6.0).toFloat()
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

        val artBmp = Bitmap.createBitmap(outArr, sw, sh, Bitmap.Config.ARGB_8888)
        if (forLivePreview) return artBmp
        return Bitmap.createScaledBitmap(artBmp, w, h, true).also {
            if (it != artBmp) artBmp.recycle()
        }
    }

    private fun toggleProDrawer() {
        val isVisible = viewBinding.proStudioDrawer.visibility == View.VISIBLE
        viewBinding.proStudioDrawer.visibility = if (isVisible) View.GONE else View.VISIBLE
        if (!isVisible) {
            viewBinding.effectsStudioDrawer.visibility = View.GONE
            isLastActionCreative = false
            selectShelfTab(currentShelfTab)
        }
    }

    private fun toggleCleanScreen() {
        val isClean = !viewBinding.proOverlayView.isCleanScreen
        viewBinding.proOverlayView.isCleanScreen = isClean
        if (isClean) {
            viewBinding.btnCleanScreen.setImageResource(R.drawable.ic_eye_off)
            viewBinding.btnCleanScreen.setColorFilter(Color.parseColor("#E2E8F0"))
            // silent (no popup)
        } else {
            viewBinding.btnCleanScreen.setImageResource(R.drawable.ic_eye)
            viewBinding.btnCleanScreen.setColorFilter(Color.parseColor("#CCCCCC"))
            // silent (no popup)
        }
    }

    private fun toggleAudioMute() {
        isAudioMuted = !isAudioMuted
        viewBinding.switchMicInput.isChecked = !isAudioMuted
        updateAudioMuteUI()
    }

    private fun updateAudioMuteUI() {
        if (isAudioMuted) {
            viewBinding.btnAudioMute.setImageResource(R.drawable.ic_volume_off)
            viewBinding.btnAudioMute.setColorFilter(Color.parseColor("#EF4444"))
            viewBinding.tvMicInputStatus.text = "Microphone muted"
            // silent (no popup)
        } else {
            viewBinding.btnAudioMute.setImageResource(R.drawable.ic_volume_up)
            viewBinding.btnAudioMute.setColorFilter(Color.parseColor("#CCCCCC"))
            viewBinding.tvMicInputStatus.text = "Studio stereo input active"
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
            viewBinding.btnSpiral0 to 0,
            viewBinding.btnSpiral90 to 1,
            viewBinding.btnSpiral180 to 2,
            viewBinding.btnSpiral270 to 3
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
            viewBinding.btnSqueezeOff to 0,
            viewBinding.btnSqueeze133 to 1,
            viewBinding.btnSqueeze155 to 2,
            viewBinding.btnSqueeze180 to 3,
            viewBinding.btnSqueeze200 to 4
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
        if (viewBinding.proOverlayView.showVisualBalance) count++
        if (viewBinding.proOverlayView.showZebraStripes) count++
        if (viewBinding.proOverlayView.showWaveform) count++
        if (viewBinding.proOverlayView.showHistogram) count++
        if (viewBinding.proOverlayView.showVectorScope) count++
        if (viewBinding.proOverlayView.showAudioVuMeter) count++

        if (count > 0) {
            viewBinding.viewStudioBadge.visibility = View.VISIBLE
            viewBinding.tvDrawerSubtitle.text = "$count ACTIVE INSTRUMENTS"
            viewBinding.btnProStudio.setColorFilter(Color.parseColor("#E2E8F0"))
        } else {
            viewBinding.viewStudioBadge.visibility = View.GONE
            viewBinding.tvDrawerSubtitle.text = "INSTRUMENTS IN STANDBY"
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
        val name = when (nextMode) {
            ProOverlayView.GridType.NONE -> "Grid: OFF (No Grid)"
            ProOverlayView.GridType.RULE_OF_THIRDS -> "Grid: Rule of Thirds"
            ProOverlayView.GridType.GOLDEN_SPIRAL -> "Grid: Golden Spiral"
            ProOverlayView.GridType.GOLDEN_RATIO_PHI -> "Grid: Golden Ratio (Phi)"
            ProOverlayView.GridType.DIAGONAL_SYMMETRY -> "Grid: Dynamic Symmetry"
            ProOverlayView.GridType.CINEMATIC_239 -> "Grid: 2.39:1 CinemaScope"
        }
        // silent (no popup)
    }

    private fun setGridMode(mode: ProOverlayView.GridType) {
        viewBinding.proOverlayView.currentGrid = mode
        val isSpiral = mode == ProOverlayView.GridType.GOLDEN_SPIRAL
        viewBinding.layoutSpiralRotation.visibility = if (isSpiral) View.VISIBLE else View.GONE
        viewBinding.layoutQuickSpiralRotate.visibility = if (isSpiral) View.VISIBLE else View.GONE

        // Update Guide button icon color and active badge
        val hasActiveGrid = mode != ProOverlayView.GridType.NONE
        viewBinding.viewGuideBadge.visibility = if (hasActiveGrid) View.VISIBLE else View.GONE
        viewBinding.btnGuide.setColorFilter(if (hasActiveGrid) Color.parseColor("#E2E8F0") else Color.parseColor("#94A3B8"))

        // Update Pro Drawer grid buttons
        val gridButtons = listOf(
            viewBinding.btnGridOff to ProOverlayView.GridType.NONE,
            viewBinding.btnGridThirds to ProOverlayView.GridType.RULE_OF_THIRDS,
            viewBinding.btnGridPhi to ProOverlayView.GridType.GOLDEN_RATIO_PHI,
            viewBinding.btnGridSpiral to ProOverlayView.GridType.GOLDEN_SPIRAL,
            viewBinding.btnGridDiagonal to ProOverlayView.GridType.DIAGONAL_SYMMETRY,
            viewBinding.btnGridCinematic to ProOverlayView.GridType.CINEMATIC_239
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
            viewBinding.btnMatteOff to ProOverlayView.AspectMatte.NONE,
            viewBinding.btnMatte169 to ProOverlayView.AspectMatte.WIDESCREEN_16_9,
            viewBinding.btnMatteCine to ProOverlayView.AspectMatte.CINE_239,
            viewBinding.btnMatteSquare to ProOverlayView.AspectMatte.SQUARE_1_1,
            viewBinding.btnMatte916 to ProOverlayView.AspectMatte.VERTICAL_9_16,
            viewBinding.btnMatte45 to ProOverlayView.AspectMatte.PORTRAIT_4_5
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

        val label = when (matte) {
            ProOverlayView.AspectMatte.NONE -> "Matte: OFF (Full Sensor)"
            ProOverlayView.AspectMatte.CINE_239 -> "Matte: 2.39:1 CinemaScope"
            ProOverlayView.AspectMatte.SQUARE_1_1 -> "Matte: 1:1 Square Feed"
            ProOverlayView.AspectMatte.VERTICAL_9_16 -> "Matte: 9:16 Vertical Reel"
            ProOverlayView.AspectMatte.PORTRAIT_4_5 -> "Matte: 4:5 Social Portrait"
            ProOverlayView.AspectMatte.WIDESCREEN_16_9 -> "Matte: 16:9 Cinema"
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
        val lensButtons = listOf(viewBinding.btnLens06, viewBinding.btnLens1, viewBinding.btnLens2, viewBinding.btnLens5)
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
                        isRecordingPaused = false
                        viewBinding.btnCapture.setImageResource(R.drawable.bg_record_button_active)
                        viewBinding.recordingTimer.visibility = View.VISIBLE
                        viewBinding.tvRecordingStatus.text = "REC"
                        viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#EF4444"))
                        viewBinding.recordingDot.visibility = View.VISIBLE
                        viewBinding.btnPauseResume.visibility = View.VISIBLE
                        viewBinding.btnGallery.visibility = View.VISIBLE
                        viewBinding.btnPauseResume.setImageResource(R.drawable.ic_pause)

                        // Reset stopped offset and start chronometer from 00:00
                        timeWhenStopped = 0L
                        viewBinding.chronometer.base = SystemClock.elapsedRealtime()
                        viewBinding.chronometer.start()

                        startAutoLoopTicker()

                        viewBinding.tabPhoto.isEnabled = false
                        viewBinding.tabVideo.isEnabled = false
                        viewBinding.btnSwitchCamera.isEnabled = false
                    }

                    is VideoRecordEvent.Pause -> {
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
                        isRecordingPaused = false
                        // Offset base by saved elapsed time so counting resumes seamlessly from where it stopped
                        viewBinding.chronometer.base = SystemClock.elapsedRealtime() - timeWhenStopped
                        viewBinding.chronometer.start()
                        viewBinding.tvRecordingStatus.text = "REC"
                        viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#EF4444"))
                        viewBinding.recordingDot.visibility = View.VISIBLE
                        viewBinding.btnPauseResume.setImageResource(R.drawable.ic_pause)
                        // silent (no popup)
                    }

                    is VideoRecordEvent.Finalize -> {
                        stopAutoLoopTicker()
                        viewBinding.chronometer.stop()
                        timeWhenStopped = 0L
                        viewBinding.tvRecordingStatus.text = "STANDBY"
                        viewBinding.tvRecordingStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
                        viewBinding.recordingDot.visibility = View.GONE
                        viewBinding.chronometer.text = "00:00:00"
                        viewBinding.btnPauseResume.visibility = View.GONE
                        viewBinding.btnGallery.visibility = View.VISIBLE
                        viewBinding.btnCapture.setImageResource(R.drawable.bg_record_button_idle)

                        viewBinding.tabPhoto.isEnabled = true
                        viewBinding.tabVideo.isEnabled = true
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
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
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

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    if (cameraMode == CameraMode.NIGHT) {
                        runOnUiThread {
                            viewBinding.tvRecordingStatus.text = "NIGHT"
                            viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#818CF8"))
                        }
                    }
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri
                    Log.d(TAG, "Photo saved: ${savedUri}")
                    if (savedUri != null) {
                        lastCapturedUri = savedUri
                        lastCapturedMimeType = "image/jpeg"

                        val needsPostProcess = cameraMode == CameraMode.NIGHT ||
                            currentFilterName != "normal" ||
                            currentShootingEffect != "none" ||
                            viewBinding.proOverlayView.liveBrightnessPercent != 0 ||
                            viewBinding.proOverlayView.liveContrastPercent != 0 ||
                            viewBinding.proOverlayView.liveSaturationPercent != 0 ||
                            viewBinding.arFaceOverlayView.currentLens != ARFaceOverlayView.ArLensMode.NONE ||
                            viewBinding.arFaceOverlayView.enableComicOnomatopoeia ||
                            viewBinding.arFaceOverlayView.enableComicSpeechBubble ||
                            viewBinding.proOverlayView.enableFilmGrain ||
                            viewBinding.proOverlayView.enableDateStamp ||
                            viewBinding.proOverlayView.enableCineFlare ||
                            viewBinding.proOverlayView.enableVignette

                        if (needsPostProcess) {
                            cameraExecutor.execute {
                                applyCreativeLookToSavedPhoto(savedUri)
                                runOnUiThread {
                                    if (cameraMode == CameraMode.NIGHT) {
                                        viewBinding.tvRecordingStatus.text = "NIGHT"
                                        viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#818CF8"))
                                    }
                                    try {
                                        viewBinding.ivGalleryThumbnail.setImageURI(null)
                                        viewBinding.ivGalleryThumbnail.setImageURI(savedUri)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed updating gallery thumbnail", e)
                                    }
                                }
                            }
                        } else {
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

    private fun applyCreativeLookToSavedPhoto(savedUri: Uri) {
        try {
            var orientation = ExifInterface.ORIENTATION_NORMAL
            contentResolver.openInputStream(savedUri)?.use { exifStream ->
                val exif = ExifInterface(exifStream)
                orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }

            val rawBmp = contentResolver.openInputStream(savedUri)?.use { input ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
                BitmapFactory.decodeStream(input, null, opts)
            } ?: return

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

            val filtered = filterManager.applyFilterToBitmap(orientedBmp)
            var finalBmp = filtered.copy(Bitmap.Config.ARGB_8888, true)
            if (filtered != orientedBmp) filtered.recycle()
            if (finalBmp != orientedBmp && !orientedBmp.isRecycled) orientedBmp.recycle()

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

            // Computational Night Mode / Night Sight adaptive shadow-recovery LUT
            if (cameraMode == CameraMode.NIGHT || currentShootingEffect == "night_sight") {
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
                "long_exposure", "motion_blur" -> {
                    val streakSrc = finalBmp.copy(Bitmap.Config.ARGB_8888, false)
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = if (currentShootingEffect == "motion_blur") 62 else 48 }
                    val shiftAmt = if (currentShootingEffect == "motion_blur") 0.022f else 0.012f
                    canvas.drawBitmap(streakSrc, -fw * shiftAmt, 0f, p)
                    canvas.drawBitmap(streakSrc, fw * shiftAmt, 0f, p)
                    streakSrc.recycle()
                    if (currentShootingEffect == "long_exposure") {
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
                    val gpuToon = filterManager.applyToonEffectToBitmap(finalBmp, currentShootingEffect)
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

            // Bake Anamorphic Cine Flare if enabled
            if (viewBinding.proOverlayView.enableCineFlare) {
                val cy = finalBmp.height * 0.48f
                val streakH = (finalBmp.height * 0.015f).coerceAtLeast(14f)
                val flarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = android.graphics.LinearGradient(
                        0f, cy, finalBmp.width.toFloat(), cy,
                        intArrayOf(Color.TRANSPARENT, Color.parseColor("#B300D4FF"), Color.parseColor("#E6FFFFFF"), Color.parseColor("#B300D4FF"), Color.TRANSPARENT),
                        floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f),
                        android.graphics.Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(0f, cy - streakH, finalBmp.width.toFloat(), cy + streakH, flarePaint)
            }

            // Bake AR Face Lens overlay onto captured image
            syncComicFrameOverlayState()
            if (viewBinding.arFaceOverlayView.currentLens == ARFaceOverlayView.ArLensMode.COMIC_FRAME) {
                if (isCartoonShaderActive()) {
                    viewBinding.arFaceOverlayView.drawComicFrameBorders(canvas, fw, fh)
                }
                if (viewBinding.arFaceOverlayView.enableComicOnomatopoeia || viewBinding.arFaceOverlayView.enableComicSpeechBubble) {
                    canvas.save()
                    val offsetX = (finalBmp.width - overlayW * scale) / 2f
                    val offsetY = (finalBmp.height - overlayH * scale) / 2f
                    canvas.translate(offsetX, offsetY)
                    canvas.scale(scale, scale)
                    viewBinding.arFaceOverlayView.drawFaceTrackedOverlays(canvas, overlayW, overlayH)
                    canvas.restore()
                }
            } else if (viewBinding.arFaceOverlayView.currentLens != ARFaceOverlayView.ArLensMode.NONE ||
                viewBinding.arFaceOverlayView.enableComicOnomatopoeia ||
                viewBinding.arFaceOverlayView.enableComicSpeechBubble
            ) {
                canvas.save()
                val offsetX = (finalBmp.width - overlayW * scale) / 2f
                val offsetY = (finalBmp.height - overlayH * scale) / 2f
                canvas.translate(offsetX, offsetY)
                canvas.scale(scale, scale)
                viewBinding.arFaceOverlayView.draw(canvas)
                canvas.restore()
            }

            contentResolver.openOutputStream(savedUri, "wt")?.use { out ->
                finalBmp.compress(Bitmap.CompressFormat.JPEG, 94, out)
            }
            finalBmp.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Failed post-processing photo creative look", e)
        }
    }

    private val allCameraModes = listOf(
        CameraMode.SLOW_MO,
        CameraMode.VIDEO,
        CameraMode.PHOTO,
        CameraMode.PORTRAIT,
        CameraMode.NIGHT
    )

    private fun getModeLabel(mode: CameraMode): String = when (mode) {
        CameraMode.SLOW_MO -> "SLOW-MO"
        CameraMode.VIDEO -> "VIDEO"
        CameraMode.PHOTO -> "PHOTO"
        CameraMode.PORTRAIT -> "PORTRAIT"
        CameraMode.NIGHT -> "NIGHT"
    }

    private fun updateModeCarouselSlots(animated: Boolean = true, slideDirection: Int = 0) {
        val curIdx = allCameraModes.indexOf(cameraMode)
        val farLeftMode = allCameraModes[(curIdx - 2 + 50) % 5]
        val leftMode = allCameraModes[(curIdx - 1 + 50) % 5]
        val centerMode = allCameraModes[curIdx]
        val rightMode = allCameraModes[(curIdx + 1) % 5]
        val farRightMode = allCameraModes[(curIdx + 2) % 5]

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
            viewBinding.tabNight to CameraMode.NIGHT
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
        if (recording != null) return
        cameraMode = mode
        isLastActionCreative = false
        showHudNotice("Mode: ${getModeLabel(mode)}")

        updateModeCarouselSlots(animated = true, slideDirection = slideDirection)

        val isVideoType = mode == CameraMode.VIDEO || mode == CameraMode.SLOW_MO
        viewBinding.recordingTimer.visibility = View.VISIBLE
        if (!isVideoType) {
            viewBinding.btnCapture.setImageResource(R.drawable.bg_shutter_button)
            viewBinding.btnPauseResume.visibility = View.GONE
            viewBinding.layoutBtnTimer.visibility = View.VISIBLE
            viewBinding.btnAutoLoop.visibility = View.GONE
            viewBinding.recordingDot.setBackgroundResource(R.drawable.bg_green_dot)
            viewBinding.recordingDot.visibility = View.VISIBLE
            if (mode == CameraMode.NIGHT) {
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

    private fun centerModeTab(mode: CameraMode, smooth: Boolean = true) {
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

    private fun navigateNextEffectOrFilter() {
        when (currentCreativeTarget) {
            CreativeTarget.LENSES -> {
                val currentIdx = arLensOrder.indexOf(currentArLensName)
                val safeIdx = if (currentIdx >= 0) currentIdx else 0
                val nextIdx = (safeIdx + 1) % arLensOrder.size
                setArLens(arLensOrder[nextIdx])
            }
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
            CreativeTarget.LENSES -> {
                val currentIdx = arLensOrder.indexOf(currentArLensName)
                val safeIdx = if (currentIdx >= 0) currentIdx else 0
                val prevIdx = if (safeIdx <= 0) arLensOrder.size - 1 else safeIdx - 1
                setArLens(arLensOrder[prevIdx])
            }
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
        if (recording != null) return
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
                if (viewBinding.proOverlayView.currentGrid == ProOverlayView.GridType.GOLDEN_SPIRAL) {
                    val nextRot = (viewBinding.proOverlayView.spiralOrientation + 1) % 4
                    setSpiralOrientation(nextRot)
                    window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }

                
                val point = viewBinding.viewFinder.meteringPointFactory.createPoint(e.x, e.y)
                val action = FocusMeteringAction.Builder(point).build()
                cameraControl?.startFocusAndMetering(action)

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
            var handled = scaleGestureDetector.onTouchEvent(event)
            if (!scaleGestureDetector.isInProgress) {
                handled = gestureDetector.onTouchEvent(event) || handled
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
        viewBinding.sbBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
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

        viewBinding.sbContrast.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
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

        viewBinding.sbSaturation.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
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
        if (viewBinding.effectsStudioDrawer.visibility == View.VISIBLE) {
            if (isNext) navigateNextEffectOrFilter() else navigatePreviousEffectOrFilter()
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
                isMultiTouchGesture = ev.pointerCount > 1

                downInModeCarousel = isPointInView(viewBinding.modeScrollView, ev.rawX, ev.rawY)
                downInIgnoredControl =
                    isPointInView(viewBinding.proModeBar, ev.rawX, ev.rawY) ||
                    isPointInView(viewBinding.bottomControls, ev.rawX, ev.rawY) ||
                    isPointInView(viewBinding.zoomControls, ev.rawX, ev.rawY) ||
                    isPointInView(viewBinding.proStudioDrawer, ev.rawX, ev.rawY) ||
                    (viewBinding.effectsStudioDrawer.visibility == View.VISIBLE && (
                        isPointInView(viewBinding.layoutEffectsHeader, ev.rawX, ev.rawY) ||
                        isPointInView(viewBinding.layoutEffectsSubItems, ev.rawX, ev.rawY)
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
                    if (viewBinding.proStudioDrawer.visibility == View.VISIBLE &&
                        !isPointInView(viewBinding.proStudioDrawer, ev.rawX, ev.rawY) &&
                        !isPointInView(viewBinding.layoutBtnProStudio, ev.rawX, ev.rawY)
                    ) {
                        viewBinding.proStudioDrawer.visibility = View.GONE
                        closedDrawer = true
                    }
                    if (viewBinding.layoutGuideFloatingBar.visibility == View.VISIBLE &&
                        !isPointInView(viewBinding.layoutGuideFloatingBar, ev.rawX, ev.rawY) &&
                        !isPointInView(viewBinding.layoutBtnGuide, ev.rawX, ev.rawY)
                    ) {
                        viewBinding.layoutGuideFloatingBar.visibility = View.GONE
                        closedDrawer = true
                    }
                    if (viewBinding.effectsStudioDrawer.visibility == View.VISIBLE &&
                        !isPointInView(viewBinding.layoutEffectsHeader, ev.rawX, ev.rawY) &&
                        !isPointInView(viewBinding.layoutEffectsSubItems, ev.rawX, ev.rawY) &&
                        !isPointInView(viewBinding.btnFilter, ev.rawX, ev.rawY) &&
                        !isPointInView(viewBinding.modeScrollView, ev.rawX, ev.rawY)
                    ) {
                        viewBinding.effectsStudioDrawer.visibility = View.GONE
                        isLastActionCreative = false
                        closedDrawer = true
                    }
                    if (closedDrawer) {
                        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        super.onDestroy()
        dollyZoomAnimator?.cancel()
        stylizeHandler.removeCallbacksAndMessages(null)
        stylizeExecutor?.shutdownNow()
        openGLProcessor?.release()
        sensorManager.unregisterListener(sensorEventListener)
        faceMeshHelper?.clear()
        cameraExecutor.shutdown()
        burstHandler.removeCallbacksAndMessages(null)
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

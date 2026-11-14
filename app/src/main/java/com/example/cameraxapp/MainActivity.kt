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
        isLastActionCreative = false
        viewBinding.modeScrollView.post { centerModeTab(cameraMode, smooth = false) }

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
        "super8_film", "film_16mm", "vhs_tape", "crt_monitor",
        "stop_motion", "neon_edge", "noir_graphic_novel", "thermal_vision"
    )
    private val toonEffectIds = setOf(
        "cel_shaded_studio", "graphic_novel_noir", "manga_ink", "pop_art_print",
        "line_art_minimalist", "ligne_claire", "clay_vinyl_3d"
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
        "super8_film", "film_16mm", "vhs_tape", "crt_monitor",
        "stop_motion", "neon_edge", "noir_graphic_novel", "thermal_vision"
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

        viewBinding.btnArNone.setOnClickListener { setArLens("none") }
        viewBinding.btnArMesh.setOnClickListener { setArLens("mesh_contour") }
        viewBinding.btnArVisor.setOnClickListener { setArLens("cyberpunk_visor") }
        viewBinding.btnArCrown.setOnClickListener { setArLens("golden_crown") }
        viewBinding.btnArHud.setOnClickListener { setArLens("scifi_hud") }

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
        viewBinding.btnEffectSuper8Film.setOnClickListener { setShootingEffect("super8_film") }
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
            bindCameraUseCases()
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

        // ImageCapture Use Case
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
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
                    if (viewBinding.arFaceOverlayView.currentLens != ARFaceOverlayView.ArLensMode.NONE &&
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

        try {
            cameraProvider.unbindAll()

            val isPhotoType = cameraMode == CameraMode.PHOTO || cameraMode == CameraMode.PORTRAIT || cameraMode == CameraMode.NIGHT
            if (isPhotoType) {
                camera = cameraProvider.bindToLifecycle(this, lensFacing, preview, imageCapture!!, imageAnalysis!!)
            } else {
                camera = cameraProvider.bindToLifecycle(this, lensFacing, preview, videoCapture!!, imageAnalysis!!)
            }

            cameraControl = camera?.cameraControl

            // Initialize Exposure compensation range from CameraInfo
            initExposureRange()

            // Re-apply real-time ColorMatrix / RenderEffect filter onto TextureView preview
            viewBinding.viewFinder.post { applyLivePreviewColorMatrix() }

        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed, attempting without analysis", exc)
            try {
                val isPhotoType = cameraMode == CameraMode.PHOTO || cameraMode == CameraMode.PORTRAIT || cameraMode == CameraMode.NIGHT
                if (isPhotoType) {
                    camera = cameraProvider.bindToLifecycle(this, lensFacing, preview, imageCapture!!)
                } else {
                    camera = cameraProvider.bindToLifecycle(this, lensFacing, preview, videoCapture!!)
                }
                cameraControl = camera?.cameraControl
                initExposureRange()
                viewBinding.viewFinder.post { applyLivePreviewColorMatrix() }
            } catch (e: Exception) {
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
                    // Prepare burst check after 400ms hold
                    isBurstCapturing = false
                    burstCount = 0
                    burstRunnable = Runnable {
                        if (!isCountingDown) {
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
            viewBinding.exposureSlider.max = max - min
            viewBinding.exposureSlider.progress = exposureState.exposureCompensationIndex - min

            viewBinding.exposureLayout.visibility = View.VISIBLE
            updateExposureLabel(exposureState.exposureCompensationIndex, exposureState.exposureCompensationStep.toFloat())
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
        "vintage_super8" -> "VINTAGE SUPER 8"
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
        "super8_film" -> "SUPER 8 VINTAGE"
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
            CreativeTarget.FILTERS -> "FILTER: " + currentFilterName.replace("_", " ").uppercase(Locale.ROOT)
            CreativeTarget.LENSES -> "LENS: " + currentArLensName.replace("_", " ").uppercase(Locale.ROOT)
            CreativeTarget.ART -> {
                if (artEffectIds.contains(currentShootingEffect)) {
                    "ART: " + getShootingEffectLabel(currentShootingEffect)
                } else {
                    "ART: OFF"
                }
            }
            CreativeTarget.TOON -> {
                if (toonEffectIds.contains(currentShootingEffect)) {
                    "TOON: " + getShootingEffectLabel(currentShootingEffect)
                } else {
                    "TOON: OFF"
                }
            }
            CreativeTarget.EFFECTS -> {
                if (currentShootingEffect != "none" && !artEffectIds.contains(currentShootingEffect) && !toonEffectIds.contains(currentShootingEffect)) {
                    "EFFECT: " + getShootingEffectLabel(currentShootingEffect)
                } else {
                    val g = viewBinding.proOverlayView.enableFilmGrain
                    val d = viewBinding.proOverlayView.enableDateStamp
                    val f = viewBinding.proOverlayView.enableCineFlare
                    when {
                        g && d && f -> "EFFECT: CINEMA COMBO"
                        g -> "EFFECT: 35MM GRAIN"
                        d -> "EFFECT: 90S STAMP"
                        f -> "EFFECT: CINE FLARE"
                        else -> "EFFECT: OFF"
                    }
                }
            }
        }
        viewBinding.tvEffectsActiveStatus.text = statusText
    }

    private fun scrollChipIntoView(scrollView: android.widget.HorizontalScrollView, child: View) {
        scrollView.post {
            val scrollX = (child.left - (scrollView.width / 2) + (child.width / 2)).coerceAtLeast(0)
            scrollView.smoothScrollTo(scrollX, 0)
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

    private fun applyLivePreviewColorMatrix() {
        val targetView = findTextureView(viewBinding.viewFinder)
        if (targetView == null) {
            viewBinding.viewFinder.post { applyLivePreviewColorMatrix() }
            return
        }

        val bri = viewBinding.proOverlayView.liveBrightnessPercent
        val con = viewBinding.proOverlayView.liveContrastPercent
        val sat = viewBinding.proOverlayView.liveSaturationPercent
        val artColorEffects = setOf(
            "oil_canvas", "charcoal_sketch", "watercolor_wash", "ink_and_line",
            "pastel_chalk", "halftone_print", "cyanotype", "woodcut_engraving",
            "lithograph", "prism_refraction", "kaleidoscope", "solarize",
            "super8_film", "film_16mm", "vhs_tape", "crt_monitor",
            "stop_motion", "neon_edge", "noir_graphic_novel", "thermal_vision",
            "cel_shaded_studio", "graphic_novel_noir", "manga_ink", "pop_art_print",
            "line_art_minimalist", "ligne_claire", "clay_vinyl_3d"
        )
        val isIdentity = currentFilterName == "normal" && bri == 0 && con == 0 && sat == 0 &&
            currentShootingEffect != "night_sight" &&
            currentShootingEffect != "tilt_shift" &&
            currentShootingEffect != "macro_focus" &&
            currentShootingEffect != "double_exposure" &&
            currentShootingEffect != "vintage_super8" &&
            !artColorEffects.contains(currentShootingEffect)

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

        // 1b. Optical Shooting Effects live luminance & miniature tone boosts
        when (currentShootingEffect) {
            "night_sight" -> {
                combined.postConcat(ColorMatrix().apply { setSaturation(1.08f) })
                combined.postConcat(ColorMatrix(floatArrayOf(
                    1.24f, 0f, 0f, 0f, 22f,
                    0f, 1.26f, 0f, 0f, 24f,
                    0f, 0f, 1.24f, 0f, 22f,
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
            else -> FilterManager.FilterPreset.NORMAL
        }
        filterManager.setPreset(filterPreset)
        viewBinding.proOverlayView.activeFilterPreset = preset
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
            viewBinding.btnFilterHDR to "hdr"
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
            else -> ARFaceOverlayView.ArLensMode.NONE
        }
        viewBinding.arFaceOverlayView.currentLens = mode
        if (mode == ARFaceOverlayView.ArLensMode.NONE) {
            viewBinding.arFaceOverlayView.clear()
        }
        val lensButtons = listOf(
            viewBinding.btnArNone to "none",
            viewBinding.btnArMesh to "mesh_contour",
            viewBinding.btnArVisor to "cyberpunk_visor",
            viewBinding.btnArCrown to "golden_crown",
            viewBinding.btnArHud to "scifi_hud"
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
            showHudNotice("AR Lens: ${lens.replace('_', ' ').uppercase(Locale.ROOT)}")
        }
    }

    private val allShootingEffects = listOf(
        "none",
        "portrait_bokeh",
        "long_exposure",
        "light_painting",
        "star_trails",
        "night_sight",
        "macro_focus",
        "tilt_shift",
        "double_exposure",
        "panorama",
        "film_grain",
        "soft_halation",
        "vignette",
        "video_bokeh",
        "slow_motion_120_240",
        "time_lapse",
        "hyperlapse",
        "anamorphic_flare",
        "dolly_zoom",
        "motion_blur",
        "action_stabilization",
        "vintage_super8"
    )

    private fun getModeOrderedArtEffects(): List<String> {
        val isVideoMode = (cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.SLOW_MO)
        return listOf("none") + if (isVideoMode) (videoArtOrder + pictureArtOrder) else (pictureArtOrder + videoArtOrder)
    }

    private fun updateShootingEffectButtons() {
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
            viewBinding.btnEffectSuper8 to "vintage_super8"
        )
        val activeShootingId = if (artEffectIds.contains(currentShootingEffect) || toonEffectIds.contains(currentShootingEffect)) "none" else currentShootingEffect
        for ((btn, id) in effectButtons) {
            val isActive = id == activeShootingId
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
            viewBinding.btnEffectSuper8Film to "super8_film",
            viewBinding.btnEffectFilm16mm to "film_16mm",
            viewBinding.btnEffectVhsTape to "vhs_tape",
            viewBinding.btnEffectCrtMonitor to "crt_monitor",
            viewBinding.btnEffectStopMotion to "stop_motion",
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
        currentTextureEffectIndex = allShootingEffects.indexOf(effectId).coerceAtLeast(0)
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
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri
                    Log.d(TAG, "Photo saved: ${savedUri}")
                    if (savedUri != null) {
                        lastCapturedUri = savedUri
                        lastCapturedMimeType = "image/jpeg"

                        val needsPostProcess = currentFilterName != "normal" ||
                            currentShootingEffect != "none" ||
                            viewBinding.proOverlayView.liveBrightnessPercent != 0 ||
                            viewBinding.proOverlayView.liveContrastPercent != 0 ||
                            viewBinding.proOverlayView.liveSaturationPercent != 0 ||
                            viewBinding.arFaceOverlayView.currentLens != ARFaceOverlayView.ArLensMode.NONE ||
                            viewBinding.proOverlayView.enableFilmGrain ||
                            viewBinding.proOverlayView.enableDateStamp ||
                            viewBinding.proOverlayView.enableCineFlare ||
                            viewBinding.proOverlayView.enableVignette

                        if (needsPostProcess) {
                            cameraExecutor.execute {
                                applyCreativeLookToSavedPhoto(savedUri)
                                runOnUiThread {
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
                    val nightCm = ColorMatrix().apply {
                        setSaturation(1.10f)
                        postConcat(ColorMatrix(floatArrayOf(
                            1.25f, 0f, 0f, 0f, 22f,
                            0f, 1.27f, 0f, 0f, 24f,
                            0f, 0f, 1.25f, 0f, 22f,
                            0f, 0f, 0f, 1f, 0f
                        )))
                    }
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
                "oil_canvas" -> {
                    // Impasto painterly brushstrokes sampled from photo grid + canvas weave
                    val cols = 38
                    val rows = 50
                    val sample = Bitmap.createScaledBitmap(finalBmp, cols, rows, true)
                    val cellW = fw / cols
                    val cellH = fh / rows
                    val brushP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; alpha = 155 }
                    for (sy in 0 until rows) {
                        for (sx in 0 until cols) {
                            val px = sample.getPixel(sx, sy)
                            val r = ((Color.red(px) / 28) * 28 + 14).coerceIn(0, 255)
                            val g = ((Color.green(px) / 28) * 28 + 14).coerceIn(0, 255)
                            val b = ((Color.blue(px) / 28) * 28 + 14).coerceIn(0, 255)
                            brushP.color = Color.argb(165, r, g, b)
                            val ang = ((r * 3 + g * 5 + sx * 17 + sy * 23) % 180).toFloat()
                            canvas.save()
                            canvas.translate((sx + 0.5f) * cellW, (sy + 0.5f) * cellH)
                            canvas.rotate(ang)
                            canvas.drawOval(RectF(-cellW * 0.9f, -cellH * 0.42f, cellW * 0.9f, cellH * 0.42f), brushP)
                            canvas.restore()
                        }
                    }
                    sample.recycle()
                }
                "charcoal_sketch", "ink_and_line" -> {
                    // Sobel contour edge detection on paper base
                    val sw = 240
                    val sh = (240f * (fh / fw)).toInt().coerceIn(120, 420)
                    val small = Bitmap.createScaledBitmap(finalBmp, sw, sh, true)
                    val pxArr = IntArray(sw * sh)
                    small.getPixels(pxArr, 0, sw, 0, 0, sw, sh)
                    val outArr = IntArray(sw * sh)
                    val isInk = currentShootingEffect == "ink_and_line"
                    fun lumAt(px: Int, py: Int): Float {
                        val cx = px.coerceIn(0, sw - 1)
                        val cy = py.coerceIn(0, sh - 1)
                        val c = pxArr[cy * sw + cx]
                        return 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
                    }
                    for (y in 0 until sh) {
                        for (x in 0 until sw) {
                            val gx = -lumAt(x - 1, y - 1) + lumAt(x + 1, y - 1) - 2f * lumAt(x - 1, y) + 2f * lumAt(x + 1, y) - lumAt(x - 1, y + 1) + lumAt(x + 1, y + 1)
                            val gy = -lumAt(x - 1, y - 1) - 2f * lumAt(x, y - 1) - lumAt(x + 1, y - 1) + lumAt(x - 1, y + 1) + 2f * lumAt(x, y + 1) + lumAt(x + 1, y + 1)
                            val edge = Math.sqrt((gx * gx + gy * gy).toDouble()).toFloat()
                            val base = lumAt(x, y)
                            if (isInk) {
                                val v = if (edge > 66f || base < 38f) 16 else 252
                                outArr[y * sw + x] = Color.rgb(v, v, v)
                            } else {
                                val shade = if (base < 88f && (x + y) % 4 == 0) 70f else base * 0.28f + 165f
                                val ink = (shade - edge * 0.92f).toInt().coerceIn(22, 244)
                                outArr[y * sw + x] = Color.rgb((ink + 4).coerceAtMost(248), (ink + 1).coerceAtMost(244), (ink - 6).coerceAtLeast(18))
                            }
                        }
                    }
                    val artBmp = Bitmap.createBitmap(outArr, sw, sh, Bitmap.Config.ARGB_8888)
                    val up = Bitmap.createScaledBitmap(artBmp, finalBmp.width, finalBmp.height, true)
                    canvas.drawBitmap(up, 0f, 0f, null)
                    artBmp.recycle()
                    up.recycle()
                    small.recycle()
                }
                "watercolor_wash", "pastel_chalk" -> {
                    // Soft downsampled pigment bleed wash + warm matte paper/chalk finish
                    val smallW = (finalBmp.width / 18).coerceAtLeast(24)
                    val smallH = (finalBmp.height / 18).coerceAtLeast(24)
                    val down = Bitmap.createScaledBitmap(finalBmp, smallW, smallH, true)
                    val soft = Bitmap.createScaledBitmap(down, finalBmp.width, finalBmp.height, true)
                    down.recycle()
                    val blendP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        alpha = if (currentShootingEffect == "watercolor_wash") 145 else 135
                    }
                    canvas.drawBitmap(soft, 0f, 0f, blendP)
                    soft.recycle()
                    val washP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = LinearGradient(
                            0f, 0f, fw, fh,
                            if (currentShootingEffect == "watercolor_wash")
                                intArrayOf(Color.parseColor("#24FBBF24"), Color.parseColor("#2238BDF8"), Color.parseColor("#2CF472B6"))
                            else
                                intArrayOf(Color.parseColor("#32FBCFE8"), Color.parseColor("#24FEF08A"), Color.parseColor("#32BAE6FD")),
                            floatArrayOf(0f, 0.5f, 1f),
                            Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawRect(0f, 0f, fw, fh, washP)
                }
                "halftone_print" -> {
                    // Newspaper / graphic novel dot grid sampled from luminance
                    val cols = 52
                    val rows = (52f * (fh / fw)).toInt().coerceIn(36, 90)
                    val sample = Bitmap.createScaledBitmap(finalBmp, cols, rows, true)
                    canvas.drawColor(Color.parseColor("#F6F1E4"))
                    val cellW = fw / cols
                    val cellH = fh / rows
                    val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = Color.parseColor("#12161C")
                    }
                    for (sy in 0 until rows) {
                        val xOff = if (sy % 2 == 0) 0f else cellW * 0.25f
                        for (sx in 0 until cols) {
                            val px = sample.getPixel(sx, sy)
                            val luma = (0.299f * Color.red(px) + 0.587f * Color.green(px) + 0.114f * Color.blue(px)) / 255f
                            val rad = (1f - luma) * (Math.min(cellW, cellH) * 0.54f)
                            if (rad > 0.8f) {
                                canvas.drawCircle((sx + 0.5f) * cellW + xOff, (sy + 0.5f) * cellH, rad, dotP)
                            }
                        }
                    }
                    sample.recycle()
                }
                "cyanotype" -> {
                    // Deep Prussian-blue architectural blueprint conversion (#08224A -> #E0F2FE)
                    val sw = 320
                    val sh = (320f * (fh / fw)).toInt().coerceIn(180, 560)
                    val small = Bitmap.createScaledBitmap(finalBmp, sw, sh, true)
                    val pxArr = IntArray(sw * sh)
                    small.getPixels(pxArr, 0, sw, 0, 0, sw, sh)
                    for (i in pxArr.indices) {
                        val c = pxArr[i]
                        val l = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
                        pxArr[i] = Color.rgb((8 + l * 216).toInt(), (34 + l * 208).toInt(), (78 + l * 176).toInt())
                    }
                    val artBmp = Bitmap.createBitmap(pxArr, sw, sh, Bitmap.Config.ARGB_8888)
                    val up = Bitmap.createScaledBitmap(artBmp, finalBmp.width, finalBmp.height, true)
                    canvas.drawBitmap(up, 0f, 0f, null)
                    artBmp.recycle(); up.recycle(); small.recycle()
                    val bpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = (fw * 0.003f).coerceAtLeast(3f)
                        color = Color.parseColor("#88E0F2FE")
                    }
                    canvas.drawRect(fw * 0.025f, fh * 0.025f, fw * 0.975f, fh * 0.975f, bpPaint)
                }
                "woodcut_engraving" -> {
                    // Carved high-contrast relief ink block with horizontal woodcut grooves
                    val sw = 300
                    val sh = (300f * (fh / fw)).toInt().coerceIn(160, 520)
                    val small = Bitmap.createScaledBitmap(finalBmp, sw, sh, true)
                    val pxArr = IntArray(sw * sh)
                    small.getPixels(pxArr, 0, sw, 0, 0, sw, sh)
                    for (y in 0 until sh) {
                        val groove = if (y % 3 == 0) 18f else 0f
                        for (x in 0 until sw) {
                            val c = pxArr[y * sw + x]
                            val l = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
                            val v = if (l + groove > 122f) 242 else 12
                            pxArr[y * sw + x] = Color.rgb(v, (v - 4).coerceAtLeast(10), (v - 10).coerceAtLeast(8))
                        }
                    }
                    val artBmp = Bitmap.createBitmap(pxArr, sw, sh, Bitmap.Config.ARGB_8888)
                    val up = Bitmap.createScaledBitmap(artBmp, finalBmp.width, finalBmp.height, true)
                    canvas.drawBitmap(up, 0f, 0f, null)
                    artBmp.recycle(); up.recycle(); small.recycle()
                }
                "lithograph" -> {
                    // Muted museum ink tones + fine limestone stipple grain
                    val lithoCm = ColorMatrix().apply {
                        setSaturation(0.36f)
                        postConcat(ColorMatrix(floatArrayOf(
                            1.28f, 0f, 0f, 0f, -24f,
                            0f, 1.26f, 0f, 0f, -26f,
                            0f, 0f, 1.22f, 0f, -30f,
                            0f, 0f, 0f, 1f, 0f
                        )))
                    }
                    val copy = finalBmp.copy(Bitmap.Config.ARGB_8888, false)
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(lithoCm) }
                    canvas.drawBitmap(copy, 0f, 0f, p)
                    copy.recycle()
                    val stipP = Paint(Paint.ANTI_ALIAS_FLAG)
                    val rng = java.util.Random(77L)
                    for (i in 0..900) {
                        stipP.color = Color.argb(48, 38, 35, 32)
                        canvas.drawCircle(rng.nextFloat() * fw, rng.nextFloat() * fh, (fw * 0.0014f).coerceAtLeast(1.5f), stipP)
                    }
                }
                "prism_refraction" -> {
                    // Spectral edge chromatic dispersion ghosting + rainbow corner caustics
                    val srcCopy = finalBmp.copy(Bitmap.Config.ARGB_8888, false)
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 78 }
                    canvas.drawBitmap(srcCopy, -fw * 0.018f, -fh * 0.01f, p)
                    canvas.drawBitmap(srcCopy, fw * 0.018f, fh * 0.01f, p)
                    srcCopy.recycle()
                    val prismP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = LinearGradient(
                            0f, 0f, fw * 0.5f, fh * 0.5f,
                            intArrayOf(Color.parseColor("#66F43F5E"), Color.parseColor("#44FACC15"), Color.parseColor("#5522D3EE"), Color.TRANSPARENT),
                            floatArrayOf(0f, 0.35f, 0.7f, 1f),
                            Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawRect(0f, 0f, fw, fh, prismP)
                }
                "kaleidoscope" -> {
                    // 8-fold radial symmetrical mandala mirrored around center
                    val srcCopy = finalBmp.copy(Bitmap.Config.ARGB_8888, false)
                    canvas.drawColor(Color.parseColor("#020617"))
                    val cx = fw * 0.5f
                    val cy = fh * 0.5f
                    val rad = Math.hypot(fw.toDouble(), fh.toDouble()).toFloat() * 0.6f
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
                        canvas.drawBitmap(srcCopy, -cx, -cy, null)
                        canvas.restore()
                    }
                    srcCopy.recycle()
                }
                "solarize" -> {
                    // Authentic Sabattier darkroom curve reversing bright highlights into metallic silver tones
                    val sw = 360
                    val sh = (360f * (fh / fw)).toInt().coerceIn(200, 620)
                    val small = Bitmap.createScaledBitmap(finalBmp, sw, sh, true)
                    val pxArr = IntArray(sw * sh)
                    small.getPixels(pxArr, 0, sw, 0, 0, sw, sh)
                    for (i in pxArr.indices) {
                        val c = pxArr[i]
                        val r = Color.red(c)
                        val g = Color.green(c)
                        val b = Color.blue(c)
                        val sr = if (r > 128) (255 - ((r - 128) * 1.9f).toInt()).coerceIn(0, 255) else r
                        val sg = if (g > 128) (255 - ((g - 128) * 1.85f).toInt()).coerceIn(0, 255) else g
                        val sb = if (b > 128) (255 - ((b - 128) * 1.75f).toInt()).coerceIn(0, 255) else b
                        pxArr[i] = Color.rgb(sr, sg, sb)
                    }
                    val artBmp = Bitmap.createBitmap(pxArr, sw, sh, Bitmap.Config.ARGB_8888)
                    val up = Bitmap.createScaledBitmap(artBmp, finalBmp.width, finalBmp.height, true)
                    canvas.drawBitmap(up, 0f, 0f, null)
                    artBmp.recycle(); up.recycle(); small.recycle()
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
                "neon_edge" -> {
                    val sw = 260
                    val sh = (260f * (fh / fw)).toInt().coerceIn(140, 460)
                    val small = Bitmap.createScaledBitmap(finalBmp, sw, sh, true)
                    val pxArr = IntArray(sw * sh)
                    small.getPixels(pxArr, 0, sw, 0, 0, sw, sh)
                    val outArr = IntArray(sw * sh)
                    fun edgeLum(px: Int, py: Int): Float {
                        val cx = px.coerceIn(0, sw - 1)
                        val cy = py.coerceIn(0, sh - 1)
                        val c = pxArr[cy * sw + cx]
                        return 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
                    }
                    for (y in 0 until sh) {
                        val vy = y.toFloat() / sh
                        for (x in 0 until sw) {
                            val vx = x.toFloat() / sw
                            val gx = -edgeLum(x - 1, y - 1) + edgeLum(x + 1, y - 1) - 2f * edgeLum(x - 1, y) + 2f * edgeLum(x + 1, y) - edgeLum(x - 1, y + 1) + edgeLum(x + 1, y + 1)
                            val gy = -edgeLum(x - 1, y - 1) - 2f * edgeLum(x, y - 1) - edgeLum(x + 1, y - 1) + edgeLum(x - 1, y + 1) + 2f * edgeLum(x, y + 1) + edgeLum(x + 1, y + 1)
                            val edge = (((Math.sqrt((gx * gx + gy * gy).toDouble()).toFloat()) - 22f) / 110f).coerceIn(0f, 1f)
                            val nr = (6f + edge * (24f + vx * 225f)).toInt().coerceIn(0, 255)
                            val ng = (8f + edge * (240f - vy * 145f)).toInt().coerceIn(0, 255)
                            val nb = (18f + edge * 237f).toInt().coerceIn(0, 255)
                            outArr[y * sw + x] = Color.rgb(nr, ng, nb)
                        }
                    }
                    val artBmp = Bitmap.createBitmap(outArr, sw, sh, Bitmap.Config.ARGB_8888)
                    val up = Bitmap.createScaledBitmap(artBmp, finalBmp.width, finalBmp.height, true)
                    canvas.drawBitmap(up, 0f, 0f, null)
                    artBmp.recycle(); up.recycle(); small.recycle()
                }
                "noir_graphic_novel" -> {
                    val sw = 260
                    val sh = (260f * (fh / fw)).toInt().coerceIn(140, 460)
                    val small = Bitmap.createScaledBitmap(finalBmp, sw, sh, true)
                    val pxArr = IntArray(sw * sh)
                    small.getPixels(pxArr, 0, sw, 0, 0, sw, sh)
                    val outArr = IntArray(sw * sh)
                    fun comicLum(px: Int, py: Int): Float {
                        val cx = px.coerceIn(0, sw - 1)
                        val cy = py.coerceIn(0, sh - 1)
                        val c = pxArr[cy * sw + cx]
                        return 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
                    }
                    for (y in 0 until sh) {
                        for (x in 0 until sw) {
                            val gx = -comicLum(x - 1, y - 1) + comicLum(x + 1, y - 1) - 2f * comicLum(x - 1, y) + 2f * comicLum(x + 1, y) - comicLum(x - 1, y + 1) + comicLum(x + 1, y + 1)
                            val gy = -comicLum(x - 1, y - 1) - 2f * comicLum(x, y - 1) - comicLum(x + 1, y - 1) + comicLum(x - 1, y + 1) + 2f * comicLum(x, y + 1) + comicLum(x + 1, y + 1)
                            val edge = Math.sqrt((gx * gx + gy * gy).toDouble()).toFloat()
                            val l = comicLum(x, y)
                            val v = if (edge > 64f || l < 76f) 10 else if (l < 138f) 112 else 246
                            outArr[y * sw + x] = Color.rgb(v, v, v)
                        }
                    }
                    val artBmp = Bitmap.createBitmap(outArr, sw, sh, Bitmap.Config.ARGB_8888)
                    val up = Bitmap.createScaledBitmap(artBmp, finalBmp.width, finalBmp.height, true)
                    canvas.drawBitmap(up, 0f, 0f, null)
                    artBmp.recycle(); up.recycle(); small.recycle()
                }
                "thermal_vision" -> {
                    val sw = 320
                    val sh = (320f * (fh / fw)).toInt().coerceIn(180, 560)
                    val small = Bitmap.createScaledBitmap(finalBmp, sw, sh, true)
                    val pxArr = IntArray(sw * sh)
                    small.getPixels(pxArr, 0, sw, 0, 0, sw, sh)
                    for (i in pxArr.indices) {
                        val c = pxArr[i]
                        val t = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
                        val r: Int
                        val g: Int
                        val b: Int
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
                        pxArr[i] = Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
                    }
                    val artBmp = Bitmap.createBitmap(pxArr, sw, sh, Bitmap.Config.ARGB_8888)
                    val up = Bitmap.createScaledBitmap(artBmp, finalBmp.width, finalBmp.height, true)
                    canvas.drawBitmap(up, 0f, 0f, null)
                    artBmp.recycle(); up.recycle(); small.recycle()
                }
                "cel_shaded_studio", "ligne_claire" -> {
                    val sw = 320
                    val sh = (320f * (fh / fw)).toInt().coerceIn(180, 560)
                    val small = Bitmap.createScaledBitmap(finalBmp, sw, sh, true)
                    val pxArr = IntArray(sw * sh)
                    small.getPixels(pxArr, 0, sw, 0, 0, sw, sh)
                    val outArr = IntArray(sw * sh)
                    val isLigne = currentShootingEffect == "ligne_claire"
                    fun tLum(px: Int, py: Int): Float {
                        val cx = px.coerceIn(0, sw - 1)
                        val cy = py.coerceIn(0, sh - 1)
                        val c = pxArr[cy * sw + cx]
                        return 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
                    }
                    for (y in 0 until sh) {
                        for (x in 0 until sw) {
                            val gx = -tLum(x - 1, y - 1) + tLum(x + 1, y - 1) - 2f * tLum(x - 1, y) + 2f * tLum(x + 1, y) - tLum(x - 1, y + 1) + tLum(x + 1, y + 1)
                            val gy = -tLum(x - 1, y - 1) - 2f * tLum(x, y - 1) - tLum(x + 1, y - 1) + tLum(x - 1, y + 1) + 2f * tLum(x, y + 1) + tLum(x + 1, y + 1)
                            val edge = Math.sqrt((gx * gx + gy * gy).toDouble()).toFloat()
                            val c = pxArr[y * sw + x]
                            if (edge > if (isLigne) 58f else 62f) {
                                outArr[y * sw + x] = if (isLigne) Color.rgb(24, 24, 30) else Color.rgb(12, 15, 24)
                            } else if (isLigne) {
                                val pr = (Color.red(c) / 36) * 36
                                val pg = (Color.green(c) / 36) * 36
                                val pb = (Color.blue(c) / 36) * 36
                                outArr[y * sw + x] = Color.rgb(
                                    (pr * 0.72f + 72f).toInt().coerceIn(0, 248),
                                    (pg * 0.72f + 70f).toInt().coerceIn(0, 246),
                                    (pb * 0.72f + 66f).toInt().coerceIn(0, 244)
                                )
                            } else {
                                outArr[y * sw + x] = Color.rgb(
                                    ((Color.red(c) / 52) * 52 + 12).coerceIn(0, 255),
                                    ((Color.green(c) / 52) * 52 + 12).coerceIn(0, 255),
                                    ((Color.blue(c) / 52) * 52 + 12).coerceIn(0, 255)
                                )
                            }
                        }
                    }
                    val artBmp = Bitmap.createBitmap(outArr, sw, sh, Bitmap.Config.ARGB_8888)
                    val up = Bitmap.createScaledBitmap(artBmp, finalBmp.width, finalBmp.height, true)
                    canvas.drawBitmap(up, 0f, 0f, null)
                    artBmp.recycle(); up.recycle(); small.recycle()
                }
                "graphic_novel_noir", "manga_ink", "line_art_minimalist" -> {
                    val sw = 320
                    val sh = (320f * (fh / fw)).toInt().coerceIn(180, 560)
                    val small = Bitmap.createScaledBitmap(finalBmp, sw, sh, true)
                    val pxArr = IntArray(sw * sh)
                    small.getPixels(pxArr, 0, sw, 0, 0, sw, sh)
                    val outArr = IntArray(sw * sh)
                    fun mLum(px: Int, py: Int): Float {
                        val cx = px.coerceIn(0, sw - 1)
                        val cy = py.coerceIn(0, sh - 1)
                        val c = pxArr[cy * sw + cx]
                        return 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
                    }
                    for (y in 0 until sh) {
                        for (x in 0 until sw) {
                            val gx = -mLum(x - 1, y - 1) + mLum(x + 1, y - 1) - 2f * mLum(x - 1, y) + 2f * mLum(x + 1, y) - mLum(x - 1, y + 1) + mLum(x + 1, y + 1)
                            val gy = -mLum(x - 1, y - 1) - 2f * mLum(x, y - 1) - mLum(x + 1, y - 1) + mLum(x - 1, y + 1) + 2f * mLum(x, y + 1) + mLum(x + 1, y + 1)
                            val edge = Math.sqrt((gx * gx + gy * gy).toDouble()).toFloat()
                            val l = mLum(x, y)
                            val v = when (currentShootingEffect) {
                                "line_art_minimalist" -> if (edge > 54f) 12 else 254
                                "manga_ink" -> {
                                    val screenDot = l < 155f && ((x + (y % 2) * 2) % 4 == 0 && y % 3 == 0)
                                    val denseDot = l < 104f && ((x + y) % 2 == 0)
                                    if (edge > 56f || l < 40f || screenDot || denseDot) 14 else 250
                                }
                                else -> if (edge > 58f || l < 82f) 8 else if (l < 138f) 116 else 248
                            }
                            outArr[y * sw + x] = Color.rgb(v, v, v)
                        }
                    }
                    val artBmp = Bitmap.createBitmap(outArr, sw, sh, Bitmap.Config.ARGB_8888)
                    val up = Bitmap.createScaledBitmap(artBmp, finalBmp.width, finalBmp.height, true)
                    canvas.drawBitmap(up, 0f, 0f, null)
                    artBmp.recycle(); up.recycle(); small.recycle()
                }
                "pop_art_print" -> {
                    val sw = 300
                    val sh = (300f * (fh / fw)).toInt().coerceIn(160, 520)
                    val small = Bitmap.createScaledBitmap(finalBmp, sw, sh, true)
                    val pxArr = IntArray(sw * sh)
                    small.getPixels(pxArr, 0, sw, 0, 0, sw, sh)
                    val outArr = IntArray(sw * sh)
                    fun pLum(px: Int, py: Int): Float {
                        val cx = px.coerceIn(0, sw - 1)
                        val cy = py.coerceIn(0, sh - 1)
                        val c = pxArr[cy * sw + cx]
                        return 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
                    }
                    for (y in 0 until sh) {
                        for (x in 0 until sw) {
                            val gx = -pLum(x - 1, y - 1) + pLum(x + 1, y - 1) - 2f * pLum(x - 1, y) + 2f * pLum(x + 1, y) - pLum(x - 1, y + 1) + pLum(x + 1, y + 1)
                            val gy = -pLum(x - 1, y - 1) - 2f * pLum(x, y - 1) - pLum(x + 1, y - 1) + pLum(x - 1, y + 1) + 2f * pLum(x, y + 1) + pLum(x + 1, y + 1)
                            val edge = Math.sqrt((gx * gx + gy * gy).toDouble()).toFloat()
                            val c = pxArr[y * sw + x]
                            outArr[y * sw + x] = if (edge > 56f) {
                                Color.rgb(10, 10, 14)
                            } else {
                                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                                val l = 0.299f * r + 0.587f * g + 0.114f * b
                                val isDot = (x % 4 == 0 && y % 4 == 0)
                                if (l > 190f) Color.rgb(252, if (isDot) 210 else 238, if (isDot) 35 else 88)
                                else if (r >= g && r >= b) Color.rgb(239, if (isDot) 48 else 35, if (isDot) 168 else 68)
                                else if (b >= r && b >= g) Color.rgb(if (isDot) 245 else 37, if (isDot) 55 else 99, 235)
                                else Color.rgb(14, if (isDot) 225 else 185, 224)
                            }
                        }
                    }
                    val artBmp = Bitmap.createBitmap(outArr, sw, sh, Bitmap.Config.ARGB_8888)
                    val up = Bitmap.createScaledBitmap(artBmp, finalBmp.width, finalBmp.height, true)
                    canvas.drawBitmap(up, 0f, 0f, null)
                    artBmp.recycle(); up.recycle(); small.recycle()
                }
                "clay_vinyl_3d" -> {
                    val smallW = (finalBmp.width / 14).coerceAtLeast(28)
                    val smallH = (finalBmp.height / 14).coerceAtLeast(28)
                    val down = Bitmap.createScaledBitmap(finalBmp, smallW, smallH, true)
                    val soft = Bitmap.createScaledBitmap(down, finalBmp.width, finalBmp.height, true)
                    down.recycle()
                    canvas.drawBitmap(soft, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 155 })
                    soft.recycle()
                    val sheenP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = RadialGradient(
                            fw * 0.36f, fh * 0.28f, Math.max(fw, fh) * 0.46f,
                            intArrayOf(Color.parseColor("#54FFFFFF"), Color.parseColor("#22E0F2FE"), Color.TRANSPARENT),
                            floatArrayOf(0f, 0.45f, 1f),
                            Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawRect(0f, 0f, fw, fh, sheenP)
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
            if (viewBinding.arFaceOverlayView.currentLens != ARFaceOverlayView.ArLensMode.NONE) {
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
            viewBinding.tvRecordingStatus.text = "READY"
            viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#10B981"))
            viewBinding.chronometer.text = "12MP 4:3"
            viewBinding.chronometer.setTextColor(Color.parseColor("#94A3B8"))
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

        updateArtStripModeOrder()
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
                val lensList = listOf("none", "mesh_contour", "cyberpunk_visor", "golden_crown", "scifi_hud")
                val currentIdx = lensList.indexOf(currentArLensName)
                val safeIdx = if (currentIdx >= 0) currentIdx else 0
                val nextIdx = (safeIdx + 1) % lensList.size
                setArLens(lensList[nextIdx])
            }
            CreativeTarget.EFFECTS -> {
                val currentIdx = allShootingEffects.indexOf(currentShootingEffect)
                val safeIdx = if (currentIdx >= 0) currentIdx else 0
                val nextIdx = (safeIdx + 1) % allShootingEffects.size
                setShootingEffect(allShootingEffects[nextIdx])
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
                val filterList = listOf(
                    "normal",
                    "lut_rec709",
                    "lut_flat_log",
                    "lut_teal_orange",
                    "lut_bleach_bypass",
                    "lut_anamorphic_cyan",
                    "lut_matte_cinema",
                    "lut_midnight_neon",
                    "lut_warm_documentary",
                    "lut_noir_cinema",
                    "golden_nostalgia",
                    "velvia_landscape",
                    "nordic_slate",
                    "arctic_mist",
                    "classic_mono",
                    "high_contrast_noir",
                    "ilford_grain",
                    "silver_gelatin",
                    "antique_sepia",
                    "cinematic",
                    "vintage",
                    "bw",
                    "cyberpunk",
                    "hdr"
                )
                val currentIdx = filterList.indexOf(currentFilterName)
                val safeIdx = if (currentIdx >= 0) currentIdx else 0
                val nextIdx = (safeIdx + 1) % filterList.size
                setFilterPreset(filterList[nextIdx])
            }
        }
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun navigatePreviousEffectOrFilter() {
        when (currentCreativeTarget) {
            CreativeTarget.LENSES -> {
                val lensList = listOf("none", "mesh_contour", "cyberpunk_visor", "golden_crown", "scifi_hud")
                val currentIdx = lensList.indexOf(currentArLensName)
                val safeIdx = if (currentIdx >= 0) currentIdx else 0
                val prevIdx = if (safeIdx <= 0) lensList.size - 1 else safeIdx - 1
                setArLens(lensList[prevIdx])
            }
            CreativeTarget.EFFECTS -> {
                val currentIdx = allShootingEffects.indexOf(currentShootingEffect)
                val safeIdx = if (currentIdx >= 0) currentIdx else 0
                val prevIdx = if (safeIdx <= 0) allShootingEffects.size - 1 else safeIdx - 1
                setShootingEffect(allShootingEffects[prevIdx])
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
                val filterList = listOf(
                    "normal",
                    "lut_rec709",
                    "lut_flat_log",
                    "lut_teal_orange",
                    "lut_bleach_bypass",
                    "lut_anamorphic_cyan",
                    "lut_matte_cinema",
                    "lut_midnight_neon",
                    "lut_warm_documentary",
                    "lut_noir_cinema",
                    "golden_nostalgia",
                    "velvia_landscape",
                    "nordic_slate",
                    "arctic_mist",
                    "classic_mono",
                    "high_contrast_noir",
                    "ilford_grain",
                    "silver_gelatin",
                    "antique_sepia",
                    "cinematic",
                    "vintage",
                    "bw",
                    "cyberpunk",
                    "hdr"
                )
                val currentIdx = filterList.indexOf(currentFilterName)
                val safeIdx = if (currentIdx >= 0) currentIdx else 0
                val prevIdx = if (safeIdx <= 0) filterList.size - 1 else safeIdx - 1
                setFilterPreset(filterList[prevIdx])
            }
        }
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun applyTextureEffectPreset(index: Int) {
        val safeIdx = ((index % allShootingEffects.size) + allShootingEffects.size) % allShootingEffects.size
        setShootingEffect(allShootingEffects[safeIdx])
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

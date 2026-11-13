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
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RenderEffect
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
                viewBinding.proOverlayView.tiltRollDegrees = rollDeg
                viewBinding.proOverlayView.tiltPitchDegrees = pitchDeg
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

    enum class CreativeTarget { FILTERS, LENSES, EFFECTS }
    private var currentCreativeTarget = CreativeTarget.FILTERS
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

        // Creative Drawer Category Switcher Buttons (FILTERS, LENSES, EFFECTS)
        viewBinding.btnCategoryFilters.setOnClickListener { selectCreativeCategory(CreativeTarget.FILTERS, fromUser = true) }
        viewBinding.btnCategoryLenses.setOnClickListener { selectCreativeCategory(CreativeTarget.LENSES, fromUser = true) }
        viewBinding.btnCategoryEffects.setOnClickListener { selectCreativeCategory(CreativeTarget.EFFECTS, fromUser = true) }

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

        // TAB 0: Looks, Filters, AR Lenses & Effects
        viewBinding.btnFilterNormal.setOnClickListener { setFilterPreset("normal") }
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

        viewBinding.switchFilmGrain.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.proOverlayView.enableFilmGrain = isChecked
            if (!isUpdatingEffectSwitches) {
                currentCreativeTarget = CreativeTarget.EFFECTS
                isLastActionCreative = true
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

    private fun selectCreativeCategory(target: CreativeTarget, fromUser: Boolean = true) {
        currentCreativeTarget = target
        if (fromUser) {
            isLastActionCreative = true
        }

        val isFilter = target == CreativeTarget.FILTERS
        val isLens = target == CreativeTarget.LENSES
        val isEffect = target == CreativeTarget.EFFECTS

        viewBinding.btnCategoryFilters.setBackgroundResource(if (isFilter) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
        viewBinding.btnCategoryFilters.setTextColor(Color.parseColor(if (isFilter) "#0F172A" else "#94A3B8"))

        viewBinding.btnCategoryLenses.setBackgroundResource(if (isLens) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
        viewBinding.btnCategoryLenses.setTextColor(Color.parseColor(if (isLens) "#0F172A" else "#94A3B8"))

        viewBinding.btnCategoryEffects.setBackgroundResource(if (isEffect) R.drawable.bg_cinema_button_active else R.drawable.bg_cinema_button_inactive)
        viewBinding.btnCategoryEffects.setTextColor(Color.parseColor(if (isEffect) "#0F172A" else "#94A3B8"))

        viewBinding.scrollFiltersStrip.visibility = if (isFilter) View.VISIBLE else View.GONE
        viewBinding.scrollLensesStrip.visibility = if (isLens) View.VISIBLE else View.GONE
        viewBinding.scrollEffectsStrip.visibility = if (isEffect) View.VISIBLE else View.GONE

        updateCreativeStatusUI()
    }

    private fun updateCreativeStatusUI() {
        val statusText = when (currentCreativeTarget) {
            CreativeTarget.FILTERS -> "FILTER: " + currentFilterName.uppercase(Locale.ROOT)
            CreativeTarget.LENSES -> "LENS: " + currentArLensName.replace("_", " ").uppercase(Locale.ROOT)
            CreativeTarget.EFFECTS -> {
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
        val isIdentity = currentFilterName == "normal" && bri == 0 && con == 0 && sat == 0

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
                            viewBinding.proOverlayView.liveBrightnessPercent != 0 ||
                            viewBinding.proOverlayView.liveContrastPercent != 0 ||
                            viewBinding.proOverlayView.liveSaturationPercent != 0 ||
                            viewBinding.arFaceOverlayView.currentLens != ARFaceOverlayView.ArLensMode.NONE ||
                            viewBinding.proOverlayView.enableFilmGrain ||
                            viewBinding.proOverlayView.enableDateStamp ||
                            viewBinding.proOverlayView.enableCineFlare

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
            val finalBmp = filtered.copy(Bitmap.Config.ARGB_8888, true)
            if (filtered != orientedBmp) filtered.recycle()
            if (finalBmp != orientedBmp && !orientedBmp.isRecycled) orientedBmp.recycle()

            val canvas = Canvas(finalBmp)
            val overlayW = viewBinding.proOverlayView.width.toFloat().coerceAtLeast(1f)
            val overlayH = viewBinding.proOverlayView.height.toFloat().coerceAtLeast(1f)
            val scaleX = finalBmp.width.toFloat() / overlayW
            val scaleY = finalBmp.height.toFloat() / overlayH
            val scale = Math.min(scaleX, scaleY)

            // Bake Film Grain if Film Grain toggle OR Ilford Grain filter is active
            if (viewBinding.proOverlayView.enableFilmGrain || currentFilterName == "ilford_grain") {
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
                currentTextureEffectIndex = (currentTextureEffectIndex + 1) % 5
                applyTextureEffectPreset(currentTextureEffectIndex)
            }
            else -> {
                val filterList = listOf(
                    "normal",
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
                currentTextureEffectIndex = if (currentTextureEffectIndex <= 0) 4 else currentTextureEffectIndex - 1
                applyTextureEffectPreset(currentTextureEffectIndex)
            }
            else -> {
                val filterList = listOf(
                    "normal",
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
        currentCreativeTarget = CreativeTarget.EFFECTS
        isLastActionCreative = true
        isUpdatingEffectSwitches = true
        try {
            when (index) {
                0 -> {
                    viewBinding.switchFilmGrain.isChecked = false
                    viewBinding.switchDateStamp.isChecked = false
                    viewBinding.switchCineFlare.isChecked = false
                    showHudNotice("Effect: OFF")
                }
                1 -> {
                    viewBinding.switchFilmGrain.isChecked = true
                    viewBinding.switchDateStamp.isChecked = false
                    viewBinding.switchCineFlare.isChecked = false
                    showHudNotice("Effect: 35mm Film Grain")
                }
                2 -> {
                    viewBinding.switchFilmGrain.isChecked = false
                    viewBinding.switchDateStamp.isChecked = true
                    viewBinding.switchCineFlare.isChecked = false
                    showHudNotice("Effect: Retro Date Stamp")
                }
                3 -> {
                    viewBinding.switchFilmGrain.isChecked = false
                    viewBinding.switchDateStamp.isChecked = false
                    viewBinding.switchCineFlare.isChecked = true
                    showHudNotice("Effect: Anamorphic Flare")
                }
                else -> {
                    viewBinding.switchFilmGrain.isChecked = true
                    viewBinding.switchDateStamp.isChecked = true
                    viewBinding.switchCineFlare.isChecked = true
                    showHudNotice("Effect: Cinema Combo")
                }
            }
        } finally {
            isUpdatingEffectSwitches = false
        }
        updateCreativeStatusUI()
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

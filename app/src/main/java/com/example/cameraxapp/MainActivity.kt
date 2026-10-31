package com.example.cameraxapp

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
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

    enum class CameraMode { PHOTO, VIDEO }

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

        setupClickListeners()
        setupBurstMode()
        setupExposureSlider()
        setupFilterAndArControls()

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

    private fun setupClickListeners() {
        // Lens Flip Switch Listener
        viewBinding.btnSwitchCamera.setOnClickListener { switchCamera() }

        // Mode Switcher: Photo vs Video
        viewBinding.tabPhoto.setOnClickListener { setMode(CameraMode.PHOTO) }
        viewBinding.tabVideo.setOnClickListener { setMode(CameraMode.VIDEO) }

        // Video Pause/Resume Button
        viewBinding.btnPauseResume.setOnClickListener { toggleVideoPauseResume() }

        // Top Toolbar: Flash Button
        viewBinding.btnFlash.setOnClickListener { toggleFlashMode() }

        // Top Toolbar: Creative Filters / Quick Tone
        viewBinding.btnFilter.setOnClickListener {
            viewBinding.proStudioDrawer.visibility = View.VISIBLE
            selectShelfTab(ShelfCategory.EXPOSURE)
        }

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
        }

        // Top Toolbar: Pro Studio Drawer Open / Close
        viewBinding.btnProStudio.setOnClickListener {
            toggleProDrawer()
        }
        viewBinding.btnQuickPreset.setOnClickListener {
            toggleProDrawer()
        }
        viewBinding.btnCloseProDrawer.setOnClickListener {
            viewBinding.proStudioDrawer.visibility = View.GONE
        }

        // Top Toolbar: Audio Mute Toggle
        viewBinding.btnAudioMute.setOnClickListener { toggleAudioMute() }

        // 4 Category Tabs Bar
        viewBinding.tabCategoryGrids.setOnClickListener { selectShelfTab(ShelfCategory.GRIDS) }
        viewBinding.tabCategoryExposure.setOnClickListener { selectShelfTab(ShelfCategory.EXPOSURE) }
        viewBinding.tabCategoryOptics.setOnClickListener { selectShelfTab(ShelfCategory.OPTICS) }
        viewBinding.tabCategoryAudio.setOnClickListener { selectShelfTab(ShelfCategory.AUDIO) }

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
            Toast.makeText(this, "Zebra Threshold: ${if (next == 250) "98%" else "95%"} IRE", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, if (isChecked) "Multi-Camera PiP Deck: Active" else "Multi-Camera PiP: Inactive", Toast.LENGTH_SHORT).show()
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
                    imageProxy.close()
                }
            }

        try {
            cameraProvider.unbindAll()

            if (cameraMode == CameraMode.PHOTO) {
                camera = cameraProvider.bindToLifecycle(this, lensFacing, preview, imageCapture!!, imageAnalysis!!)
            } else {
                camera = cameraProvider.bindToLifecycle(this, lensFacing, preview, videoCapture!!, imageAnalysis!!)
            }

            cameraControl = camera?.cameraControl

            // Initialize Exposure compensation range from CameraInfo
            initExposureRange()

        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed, attempting without analysis", exc)
            try {
                if (cameraMode == CameraMode.PHOTO) {
                    camera = cameraProvider.bindToLifecycle(this, lensFacing, preview, imageCapture!!)
                } else {
                    camera = cameraProvider.bindToLifecycle(this, lensFacing, preview, videoCapture!!)
                }
                cameraControl = camera?.cameraControl
                initExposureRange()
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
            if (cameraMode != CameraMode.PHOTO) {
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

        burstCount++
        viewBinding.tvBurstBadge.text = "BURST: $burstCount"
        takePhoto(isBurst = true)

        // Rapid capture every 250ms
        burstHandler.postDelayed({
            startBurstCapturingLoop()
        }, 250)
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
            viewBinding.btnTimer.setColorFilter(Color.parseColor("#FACC15"))
        }
        Toast.makeText(this, "Timer: ${if (countdownSeconds == 0) "Off" else "${countdownSeconds}s"}", Toast.LENGTH_SHORT).show()
    }

    // Auto Trigger Interval Loop Cycle (Auto Record -> Auto Pause -> Auto Resume Loop)
    private fun cycleAutoLoopTimer() {
        when {
            !isAutoLoopEnabled -> {
                isAutoLoopEnabled = true
                autoRecordDuration = 5
                autoPauseDuration = 3
                viewBinding.btnAutoLoop.setColorFilter(Color.parseColor("#FACC15"))
                Toast.makeText(this, "Auto Loop: 5s Record / 3s Pause", Toast.LENGTH_SHORT).show()
            }
            autoRecordDuration == 5 -> {
                autoRecordDuration = 10
                autoPauseDuration = 5
                viewBinding.btnAutoLoop.setColorFilter(Color.parseColor("#FACC15"))
                Toast.makeText(this, "Auto Loop: 10s Record / 5s Pause", Toast.LENGTH_SHORT).show()
            }
            autoRecordDuration == 10 -> {
                autoRecordDuration = 15
                autoPauseDuration = 5
                viewBinding.btnAutoLoop.setColorFilter(Color.parseColor("#FACC15"))
                Toast.makeText(this, "Auto Loop: 15s Record / 5s Pause", Toast.LENGTH_SHORT).show()
            }
            autoRecordDuration == 15 -> {
                autoRecordDuration = 30
                autoPauseDuration = 10
                viewBinding.btnAutoLoop.setColorFilter(Color.parseColor("#FACC15"))
                Toast.makeText(this, "Auto Loop: 30s Record / 10s Pause", Toast.LENGTH_SHORT).show()
            }
            else -> {
                isAutoLoopEnabled = false
                viewBinding.btnAutoLoop.setColorFilter(Color.parseColor("#CCCCCC"))
                Toast.makeText(this, "Auto Loop: Disabled", Toast.LENGTH_SHORT).show()
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

        // Update tab styles: Solid yellow background for active, translucent slate for inactive
        val tabs = listOf(
            Pair(viewBinding.tabCategoryGrids, ShelfCategory.GRIDS),
            Pair(viewBinding.tabCategoryExposure, ShelfCategory.EXPOSURE),
            Pair(viewBinding.tabCategoryOptics, ShelfCategory.OPTICS),
            Pair(viewBinding.tabCategoryAudio, ShelfCategory.AUDIO)
        )
        for ((tabView, cat) in tabs) {
            val isActive = cat == category
            if (isActive) {
                tabView.setBackgroundResource(R.drawable.bg_tab_active)
                tabView.setTextColor(Color.parseColor("#000000"))
            } else {
                tabView.setBackgroundResource(R.drawable.bg_tab_inactive)
                tabView.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
    }

    private fun toggleProDrawer() {
        val isVisible = viewBinding.proStudioDrawer.visibility == View.VISIBLE
        viewBinding.proStudioDrawer.visibility = if (isVisible) View.GONE else View.VISIBLE
    }

    private fun toggleCleanScreen() {
        val isClean = !viewBinding.proOverlayView.isCleanScreen
        viewBinding.proOverlayView.isCleanScreen = isClean
        if (isClean) {
            viewBinding.btnCleanScreen.setImageResource(R.drawable.ic_eye_off)
            viewBinding.btnCleanScreen.setColorFilter(Color.parseColor("#FACC15"))
            Toast.makeText(this, "Clean Screen: All overlays hidden", Toast.LENGTH_SHORT).show()
        } else {
            viewBinding.btnCleanScreen.setImageResource(R.drawable.ic_eye)
            viewBinding.btnCleanScreen.setColorFilter(Color.parseColor("#CCCCCC"))
            Toast.makeText(this, "HUD Overlays Restored", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Microphone: Muted", Toast.LENGTH_SHORT).show()
        } else {
            viewBinding.btnAudioMute.setImageResource(R.drawable.ic_volume_up)
            viewBinding.btnAudioMute.setColorFilter(Color.parseColor("#CCCCCC"))
            viewBinding.tvMicInputStatus.text = "Studio stereo input active"
            Toast.makeText(this, "Microphone: Active", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cycleAspectRatio() {
        currentAspectRatio = when (currentAspectRatio) {
            "4:3" -> "16:9"
            "16:9" -> "1:1"
            else -> "4:3"
        }
        viewBinding.btnAspectRatio.text = currentAspectRatio
        Toast.makeText(this, "Sensor Ratio: $currentAspectRatio", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, "Anamorphic De-Squeeze: $label", Toast.LENGTH_SHORT).show()
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
            viewBinding.btnProStudio.setColorFilter(Color.parseColor("#FACC15"))
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
        Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
    }

    private fun setGridMode(mode: ProOverlayView.GridType) {
        viewBinding.proOverlayView.currentGrid = mode
        val isSpiral = mode == ProOverlayView.GridType.GOLDEN_SPIRAL
        viewBinding.layoutSpiralRotation.visibility = if (isSpiral) View.VISIBLE else View.GONE

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
                btn.setTextColor(Color.parseColor("#000000"))
            } else {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_inactive)
                btn.setTextColor(Color.parseColor("#94A3B8"))
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
                btn.setTextColor(Color.parseColor("#000000"))
            } else {
                btn.setBackgroundResource(R.drawable.bg_cinema_button_inactive)
                btn.setTextColor(Color.parseColor("#94A3B8"))
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
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
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
                pillViews[i].setTextColor(Color.parseColor("#FACC15"))
            } else {
                pillViews[i].setBackgroundResource(android.R.color.transparent)
                pillViews[i].setTextColor(Color.parseColor("#94A3B8"))
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
                title?.setTextColor(Color.parseColor("#000000"))
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
                        Toast.makeText(this, "Recording Paused", Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this, "Recording Resumed", Toast.LENGTH_SHORT).show()
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
                        viewBinding.btnCapture.setImageResource(R.drawable.bg_record_button_idle)

                        viewBinding.tabPhoto.isEnabled = true
                        viewBinding.tabVideo.isEnabled = true
                        viewBinding.btnSwitchCamera.isEnabled = true

                        if (!recordEvent.hasError()) {
                            val msg = "Video saved: ${recordEvent.outputResults.outputUri}"
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                            Log.d(TAG, msg)
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
                    if (!isBurst) {
                        Toast.makeText(baseContext, "Photo saved: ${output.savedUri}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun setMode(mode: CameraMode) {
        if (recording != null) return
        cameraMode = mode
        if (mode == CameraMode.PHOTO) {
            viewBinding.tabPhoto.setTextColor(Color.parseColor("#FFFFFF"))
            viewBinding.tabVideo.setTextColor(Color.parseColor("#777777"))
            viewBinding.btnCapture.setImageResource(R.drawable.bg_shutter_button)
            viewBinding.recordingTimer.visibility = View.GONE
            viewBinding.btnPauseResume.visibility = View.GONE
            viewBinding.btnQuickPreset.visibility = View.VISIBLE
            viewBinding.layoutBtnTimer.visibility = View.VISIBLE
            viewBinding.btnAutoLoop.visibility = View.GONE
        } else {
            viewBinding.tabVideo.setTextColor(Color.parseColor("#FFFFFF"))
            viewBinding.tabPhoto.setTextColor(Color.parseColor("#777777"))
            viewBinding.btnCapture.setImageResource(R.drawable.bg_record_button_idle)
            viewBinding.btnQuickPreset.visibility = View.GONE
            viewBinding.layoutBtnTimer.visibility = View.GONE
            viewBinding.btnAutoLoop.visibility = View.VISIBLE
            viewBinding.recordingTimer.visibility = View.VISIBLE
            viewBinding.tvRecordingStatus.text = "STANDBY"
            viewBinding.tvRecordingStatus.setTextColor(Color.parseColor("#94A3B8"))
            viewBinding.recordingDot.visibility = View.GONE
            viewBinding.chronometer.text = "00:00:00"
            updateAutoLoopHud()
        }
        bindCameraUseCases()
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

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Touch to change grid! Double-tap on viewfinder cycles through composition grids
                cycleToNextGrid()
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
        // Live Adjustments: Brightness, Contrast, Saturation
        viewBinding.sbBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) filterManager.setBrightnessPercent(progress - 100)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        viewBinding.sbContrast.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) filterManager.setContrastPercent(progress - 100)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        viewBinding.sbSaturation.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) filterManager.setSaturationPercent(progress - 100)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
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

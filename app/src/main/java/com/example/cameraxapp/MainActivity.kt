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
import android.widget.SeekBar
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

    private fun setupClickListeners() {
        // Lens Switch Listener
        viewBinding.btnSwitchCamera.setOnClickListener { switchCamera() }

        // Mode Switcher: Photo vs Video
        viewBinding.tabPhoto.setOnClickListener { setMode(CameraMode.PHOTO) }
        viewBinding.tabVideo.setOnClickListener { setMode(CameraMode.VIDEO) }

        // Video Pause/Resume Button
        viewBinding.btnPauseResume.setOnClickListener { toggleVideoPauseResume() }

        // Pro Mode: Countdown Timer Toggle (Off -> 3s -> 5s -> 10s)
        viewBinding.btnTimer.setOnClickListener { cycleCountdownTimer() }

        // Pro Mode: Grid Quick Touch & Cycle (Tap top button to cycle immediately, long-press to turn off)
        viewBinding.btnGrid.setOnClickListener { cycleToNextGrid() }
        viewBinding.btnGrid.setOnLongClickListener {
            setGridMode(ProOverlayView.GridType.NONE)
            Toast.makeText(this, "Grid: OFF (No Grid)", Toast.LENGTH_SHORT).show()
            true
        }
        viewBinding.btnRotateSpiral.setOnClickListener {
            val current = viewBinding.proOverlayView.spiralOrientation
            val next = (current + 1) % 4
            viewBinding.proOverlayView.spiralOrientation = next
            viewBinding.btnRotateSpiral.text = "⟳ ${next * 90}°"
            Toast.makeText(this, "Spiral Orientation: ${next * 90}°", Toast.LENGTH_SHORT).show()
        }

        // Pro Studio Drawer Open / Close
        viewBinding.btnProStudio.setOnClickListener {
            viewBinding.proStudioDrawer.visibility = View.VISIBLE
        }
        viewBinding.btnQuickPreset.setOnClickListener {
            viewBinding.proStudioDrawer.visibility = View.VISIBLE
        }
        viewBinding.btnCloseProDrawer.setOnClickListener {
            viewBinding.proStudioDrawer.visibility = View.GONE
        }

        // Quick Zoom Pills (.6x, 1x, 2x, 5x)
        viewBinding.btnZoom06.setOnClickListener { setZoomLevel(0.6f, viewBinding.btnZoom06) }
        viewBinding.btnZoom1.setOnClickListener { setZoomLevel(1.0f, viewBinding.btnZoom1) }
        viewBinding.btnZoom2.setOnClickListener { setZoomLevel(2.0f, viewBinding.btnZoom2) }
        viewBinding.btnZoom5.setOnClickListener { setZoomLevel(5.0f, viewBinding.btnZoom5) }

        viewBinding.btnFlash.setOnClickListener { toggleFlashMode() }
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

        try {
            cameraProvider.unbindAll()

            if (cameraMode == CameraMode.PHOTO) {
                camera = cameraProvider.bindToLifecycle(this, lensFacing, preview, imageCapture!!)
            } else {
                camera = cameraProvider.bindToLifecycle(this, lensFacing, preview, videoCapture!!)
            }

            cameraControl = camera?.cameraControl

            // Initialize Exposure compensation range from CameraInfo
            initExposureRange()

        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            Toast.makeText(this, "Camera start error: ${exc.message}", Toast.LENGTH_SHORT).show()
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
        viewBinding.btnTimer.text = if (countdownSeconds == 0) "TIMER OFF" else "${countdownSeconds}s"
        Toast.makeText(this, "Timer: ${if (countdownSeconds == 0) "Off" else "${countdownSeconds}s"}", Toast.LENGTH_SHORT).show()
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

    private fun toggleGridLines() {
        if (viewBinding.proOverlayView.currentGrid != ProOverlayView.GridType.NONE) {
            // Immediately toggle OFF
            lastActiveGrid = viewBinding.proOverlayView.currentGrid
            setGridMode(ProOverlayView.GridType.NONE)
            Toast.makeText(this, "Grid: OFF (No Grid)", Toast.LENGTH_SHORT).show()
        } else {
            // Toggle ON last active grid
            setGridMode(lastActiveGrid)
            Toast.makeText(this, "Grid: ON", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setGridMode(mode: ProOverlayView.GridType) {
        viewBinding.proOverlayView.currentGrid = mode
        val isOff = mode == ProOverlayView.GridType.NONE
        viewBinding.btnGrid.alpha = if (isOff) 0.5f else 1.0f

        val isSpiral = mode == ProOverlayView.GridType.GOLDEN_SPIRAL
        viewBinding.btnRotateSpiral.visibility = if (isSpiral) View.VISIBLE else View.GONE
        viewBinding.btnRotateSpiral.text = "⟳ ${viewBinding.proOverlayView.spiralOrientation * 90}°"

        val activeBg = ContextCompat.getColorStateList(this, android.R.color.holo_blue_dark)
        val inactiveBg = ContextCompat.getColorStateList(this, android.R.color.darker_gray)
        val offBg = ContextCompat.getColorStateList(this, if (isOff) android.R.color.holo_red_dark else android.R.color.darker_gray)

        viewBinding.btnGridOff.backgroundTintList = offBg
        viewBinding.btnGridThirds.backgroundTintList = if (mode == ProOverlayView.GridType.RULE_OF_THIRDS) activeBg else inactiveBg
        viewBinding.btnGridSpiral.backgroundTintList = if (mode == ProOverlayView.GridType.GOLDEN_SPIRAL) activeBg else inactiveBg
        viewBinding.btnGridPhi.backgroundTintList = if (mode == ProOverlayView.GridType.GOLDEN_RATIO_PHI) activeBg else inactiveBg
        viewBinding.btnGridDiagonal.backgroundTintList = if (mode == ProOverlayView.GridType.DIAGONAL_SYMMETRY) activeBg else inactiveBg
        viewBinding.btnGridCinematic.backgroundTintList = if (mode == ProOverlayView.GridType.CINEMATIC_239) activeBg else inactiveBg

        if (!isOff) {
            lastActiveGrid = mode
        }
    }

    private fun setZoomLevel(ratio: Float, selectedView: View) {
        val zoomState = camera?.cameraInfo?.zoomState?.value
        val minZoom = zoomState?.minZoomRatio ?: 1.0f
        val maxZoom = zoomState?.maxZoomRatio ?: 8.0f
        val clamped = ratio.coerceIn(minZoom, maxZoom)
        cameraControl?.setZoomRatio(clamped)

        val zoomViews = listOf(viewBinding.btnZoom06, viewBinding.btnZoom1, viewBinding.btnZoom2, viewBinding.btnZoom5)
        for (v in zoomViews) {
            if (v == selectedView) {
                v.setBackgroundResource(R.drawable.bg_zoom_pill_active)
            } else {
                v.setBackgroundResource(android.R.color.transparent)
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
            viewBinding.tabPhoto.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            viewBinding.tabVideo.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            viewBinding.btnCapture.setImageResource(R.drawable.bg_shutter_button)
            viewBinding.recordingTimer.visibility = View.GONE
            viewBinding.btnPauseResume.visibility = View.GONE
            viewBinding.proModeBar.visibility = View.VISIBLE
        } else {
            viewBinding.tabVideo.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            viewBinding.tabPhoto.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            viewBinding.btnCapture.setImageResource(R.drawable.bg_record_button_idle)
            viewBinding.proModeBar.visibility = View.GONE
            viewBinding.recordingTimer.visibility = View.VISIBLE
            viewBinding.tvRecordingStatus.text = "STANDBY"
            viewBinding.tvRecordingStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            viewBinding.recordingDot.visibility = View.GONE
            viewBinding.chronometer.text = "00:00:00"
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
     * 6. Real-Time Filter & AR Lens Controls
     */
    private fun setupFilterAndArControls() {
        // Color Preset Selection
        viewBinding.btnFilterPresetCinematic.setOnClickListener {
            filterManager.setPreset(FilterManager.FilterPreset.CINEMATIC)
            openGLProcessor?.setFilterPreset(FilterManager.FilterPreset.CINEMATIC)
            Toast.makeText(this, "Filter: Cinematic (Teal/Orange)", Toast.LENGTH_SHORT).show()
        }
        viewBinding.btnFilterPresetVintage.setOnClickListener {
            filterManager.setPreset(FilterManager.FilterPreset.VINTAGE)
            openGLProcessor?.setFilterPreset(FilterManager.FilterPreset.VINTAGE)
            Toast.makeText(this, "Filter: Vintage 70s", Toast.LENGTH_SHORT).show()
        }
        viewBinding.btnFilterPresetBw.setOnClickListener {
            filterManager.setPreset(FilterManager.FilterPreset.BLACK_AND_WHITE)
            openGLProcessor?.setFilterPreset(FilterManager.FilterPreset.BLACK_AND_WHITE)
            Toast.makeText(this, "Filter: Noir B&W", Toast.LENGTH_SHORT).show()
        }
        viewBinding.btnFilterPresetHdr.setOnClickListener {
            filterManager.setPreset(FilterManager.FilterPreset.HDR)
            openGLProcessor?.setFilterPreset(FilterManager.FilterPreset.HDR)
            Toast.makeText(this, "Filter: Vibrant HDR", Toast.LENGTH_SHORT).show()
        }
        viewBinding.btnFilterPresetCyberpunk.setOnClickListener {
            filterManager.setPreset(FilterManager.FilterPreset.CYBERPUNK)
            openGLProcessor?.setFilterPreset(FilterManager.FilterPreset.CYBERPUNK)
            Toast.makeText(this, "Filter: Cyberpunk Neon", Toast.LENGTH_SHORT).show()
        }
        viewBinding.btnFilterPresetNormal.setOnClickListener {
            filterManager.setPreset(FilterManager.FilterPreset.NORMAL)
            openGLProcessor?.setFilterPreset(FilterManager.FilterPreset.NORMAL)
            Toast.makeText(this, "Filter: Standard", Toast.LENGTH_SHORT).show()
        }

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

        // Framing Grid Direct Selection (Including Explicit NO GRID Button)
        viewBinding.btnGridOff.setOnClickListener {
            setGridMode(ProOverlayView.GridType.NONE)
            Toast.makeText(this, "Grid: OFF (No Grid)", Toast.LENGTH_SHORT).show()
        }
        viewBinding.btnGridThirds.setOnClickListener {
            setGridMode(ProOverlayView.GridType.RULE_OF_THIRDS)
            Toast.makeText(this, "Rule of Thirds", Toast.LENGTH_SHORT).show()
        }
        viewBinding.btnGridSpiral.setOnClickListener {
            setGridMode(ProOverlayView.GridType.GOLDEN_SPIRAL)
            Toast.makeText(this, "Golden Spiral (Fibonacci)", Toast.LENGTH_SHORT).show()
        }
        viewBinding.btnGridPhi.setOnClickListener {
            setGridMode(ProOverlayView.GridType.GOLDEN_RATIO_PHI)
            Toast.makeText(this, "Golden Ratio (Phi)", Toast.LENGTH_SHORT).show()
        }
        viewBinding.btnGridDiagonal.setOnClickListener {
            setGridMode(ProOverlayView.GridType.DIAGONAL_SYMMETRY)
            Toast.makeText(this, "Dynamic Symmetry", Toast.LENGTH_SHORT).show()
        }
        viewBinding.btnGridCinematic.setOnClickListener {
            setGridMode(ProOverlayView.GridType.CINEMATIC_239)
            Toast.makeText(this, "2.39:1 CinemaScope", Toast.LENGTH_SHORT).show()
        }

        // Pro Composition & Sensor Overlays
        viewBinding.btnFlipSpiral.setOnClickListener {
            viewBinding.proOverlayView.spiralOrientation++
            Toast.makeText(this, "Golden Spiral Rotated", Toast.LENGTH_SHORT).show()
        }
        viewBinding.btnToggleLevel.setOnClickListener {
            val newState = !viewBinding.proOverlayView.showLevelGauge
            viewBinding.proOverlayView.showLevelGauge = newState
            viewBinding.btnToggleLevel.text = if (newState) "Horizon: ON" else "Horizon: OFF"
            Toast.makeText(this, if (newState) "Electronic Horizon ON" else "Electronic Horizon OFF", Toast.LENGTH_SHORT).show()
        }
        viewBinding.btnTogglePeaking.setOnClickListener {
            val newState = !viewBinding.proOverlayView.showFocusPeaking
            viewBinding.proOverlayView.showFocusPeaking = newState
            viewBinding.btnTogglePeaking.text = if (newState) "Peaking: ON" else "Peaking: OFF"
            Toast.makeText(this, if (newState) "Focus Peaking ON" else "Focus Peaking OFF", Toast.LENGTH_SHORT).show()
        }
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

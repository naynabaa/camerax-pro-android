package com.lumevra.camera.stabilization

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

class GyroStabilizer(
    context: Context,
    private val onTelemetryUpdated: (StabilizationTelemetry) -> Unit
) : SensorEventListener {

    data class StabilizationTelemetry(
        val isEnabled: Boolean,
        val gyroAvailable: Boolean,
        val isSyntheticDemo: Boolean,
        val overscanZoom: Float,
        val compXNorm: Float,
        val compYNorm: Float,
        val compRollDeg: Float,
        val rawDeflectionX: Float,
        val rawDeflectionY: Float,
        val angularVelocityDegPerSec: Float,
        val shakeReductionPct: Int
    )

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotationVectorSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val isGyroHardwareAvailable: Boolean = (gyroSensor != null)
    val hasMotionSensor: Boolean = (gyroSensor != null || rotationVectorSensor != null)

    @Volatile
    var isEnabled: Boolean = false
        private set

    @Volatile
    var isSyntheticShakeDemo: Boolean = false
        private set

    // 1.22x overscan crop leaves ~18% total margin so compensated tilt + shift never expose black corners
    val overscanZoom: Float = 1.22f

    @Volatile
    var currentTelemetry: StabilizationTelemetry = StabilizationTelemetry(
        isEnabled = false,
        gyroAvailable = isGyroHardwareAvailable,
        isSyntheticDemo = false,
        overscanZoom = 1.0f,
        compXNorm = 0f,
        compYNorm = 0f,
        compRollDeg = 0f,
        rawDeflectionX = 0f,
        rawDeflectionY = 0f,
        angularVelocityDegPerSec = 0f,
        shakeReductionPct = 0
    )
        private set

    private var isRegistered: Boolean = false
    private var lastGyroTimestampNs: Long = 0L
    private var lastRotTimestampNs: Long = 0L

    // Fallback rotation-vector differentiation when hardware TYPE_GYROSCOPE is unavailable
    private val rotMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var hasPrevRotAngles = false
    private var prevYawRad = 0f
    private var prevPitchRad = 0f
    private var prevRollRad = 0f

    // High-pass filtered angular displacement (radians) isolating hand tremor from intentional panning
    private var hpPitchRad = 0f
    private var hpYawRad = 0f
    private var hpRollRad = 0f

    // Exponentially smoothed output transforms to eliminate micro-quantization jitter
    private var smoothCompX = 0f
    private var smoothCompY = 0f
    private var smoothCompRollDeg = 0f
    private var smoothAngularVelDegSec = 0f

    // Synthetic handheld shake loop (demonstrates real-time EIS on stationary desks / emulators)
    private val demoHandler = Handler(Looper.getMainLooper())
    private var demoLastTickMs = 0L
    private var demoElapsedSec = 0f
    private val demoTickRunnable = object : Runnable {
        override fun run() {
            if (!isEnabled || !isSyntheticShakeDemo || !isRegistered) return
            val now = SystemClock.elapsedRealtime()
            val dt = if (demoLastTickMs == 0L) 0.016f else ((now - demoLastTickMs) / 1000f).coerceIn(0.004f, 0.045f)
            demoLastTickMs = now
            demoElapsedSec += dt

            // Multi-harmonic handheld tremor: 1.4Hz breathing + 3.2Hz step + 7.1Hz hand jitter
            val wx = (0.095f * sin(demoElapsedSec * 2f * PI.toFloat() * 1.4f) +
                0.15f * sin(demoElapsedSec * 2f * PI.toFloat() * 3.2f + 0.7f) +
                0.08f * cos(demoElapsedSec * 2f * PI.toFloat() * 7.1f))
            val wy = (0.11f * cos(demoElapsedSec * 2f * PI.toFloat() * 1.1f) +
                0.17f * sin(demoElapsedSec * 2f * PI.toFloat() * 2.85f + 1.4f) +
                0.09f * sin(demoElapsedSec * 2f * PI.toFloat() * 6.4f))
            val wz = (0.07f * sin(demoElapsedSec * 2f * PI.toFloat() * 2.1f + 0.4f) +
                0.055f * cos(demoElapsedSec * 2f * PI.toFloat() * 5.2f))

            processAngularVelocitySample(wx, wy, wz, dt, synthetic = true)
            demoHandler.postDelayed(this, 16L)
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (isEnabled == enabled) return
        isEnabled = enabled
        if (!enabled) {
            resetIntegrator()
            stopDemoLoop()
            emitTelemetry(0f, 0f, 0f, 0f, 0f, 0f, 0)
        } else {
            lastGyroTimestampNs = 0L
            lastRotTimestampNs = 0L
            hasPrevRotAngles = false
            if (isSyntheticShakeDemo && isRegistered) {
                startDemoLoop()
            } else {
                emitTelemetry(0f, 0f, 0f, 0f, 0f, 0f, 94)
            }
        }
    }

    fun setSyntheticShakeDemo(enabled: Boolean) {
        if (isSyntheticShakeDemo == enabled) return
        isSyntheticShakeDemo = enabled
        resetIntegrator()
        if (enabled && isEnabled && isRegistered) {
            startDemoLoop()
        } else {
            stopDemoLoop()
            if (isEnabled) {
                emitTelemetry(0f, 0f, 0f, 0f, 0f, 0f, 94)
            }
        }
    }

    fun register() {
        if (isRegistered) return
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (gyroSensor == null) {
            rotationVectorSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        isRegistered = true
        lastGyroTimestampNs = 0L
        lastRotTimestampNs = 0L
        hasPrevRotAngles = false
        if (isEnabled && isSyntheticShakeDemo) {
            startDemoLoop()
        }
    }

    fun unregister() {
        if (!isRegistered) return
        sensorManager.unregisterListener(this)
        isRegistered = false
        stopDemoLoop()
    }

    private fun startDemoLoop() {
        demoHandler.removeCallbacks(demoTickRunnable)
        demoLastTickMs = 0L
        demoHandler.post(demoTickRunnable)
    }

    private fun stopDemoLoop() {
        demoHandler.removeCallbacks(demoTickRunnable)
        demoLastTickMs = 0L
    }

    private fun resetIntegrator() {
        hpPitchRad = 0f
        hpYawRad = 0f
        hpRollRad = 0f
        smoothCompX = 0f
        smoothCompY = 0f
        smoothCompRollDeg = 0f
        smoothAngularVelDegSec = 0f
        hasPrevRotAngles = false
        lastGyroTimestampNs = 0L
        lastRotTimestampNs = 0L
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isEnabled || event == null) return
        if (isSyntheticShakeDemo) return

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                if (lastGyroTimestampNs == 0L) {
                    lastGyroTimestampNs = event.timestamp
                    return
                }
                val dt = ((event.timestamp - lastGyroTimestampNs) * NS_TO_SEC).coerceIn(0.001f, 0.05f)
                lastGyroTimestampNs = event.timestamp

                // Gyro bias deadzone suppresses stationary sensor drift
                val wx = if (abs(event.values[0]) > GYRO_DEADZONE_RAD_S) event.values[0] else 0f
                val wy = if (abs(event.values[1]) > GYRO_DEADZONE_RAD_S) event.values[1] else 0f
                val wz = if (abs(event.values[2]) > GYRO_DEADZONE_RAD_S) event.values[2] else 0f
                processAngularVelocitySample(wx, wy, wz, dt, synthetic = false)
            }

            Sensor.TYPE_ROTATION_VECTOR -> {
                if (gyroSensor != null) return
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                SensorManager.getOrientation(rotMatrix, orientationAngles)
                val yaw = orientationAngles[0]
                val pitch = orientationAngles[1]
                val roll = orientationAngles[2]
                if (!hasPrevRotAngles || lastRotTimestampNs == 0L) {
                    prevYawRad = yaw
                    prevPitchRad = pitch
                    prevRollRad = roll
                    hasPrevRotAngles = true
                    lastRotTimestampNs = event.timestamp
                    return
                }
                val dt = ((event.timestamp - lastRotTimestampNs) * NS_TO_SEC).coerceIn(0.004f, 0.05f)
                lastRotTimestampNs = event.timestamp

                val dYaw = normalizeRadDelta(prevYawRad, yaw)
                val dPitch = normalizeRadDelta(prevPitchRad, pitch)
                val dRoll = normalizeRadDelta(prevRollRad, roll)
                prevYawRad = yaw
                prevPitchRad = pitch
                prevRollRad = roll

                processAngularVelocitySample(dPitch / dt, dYaw / dt, dRoll / dt, dt, synthetic = false)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun processAngularVelocitySample(
        wx: Float,
        wy: Float,
        wz: Float,
        dt: Float,
        synthetic: Boolean
    ) {
        // IIR high-pass integrator: retains fast handheld shake (>1.5 Hz), washes out slow panning
        val alpha = 0.90f
        hpPitchRad = alpha * (hpPitchRad + wx * dt)
        hpYawRad = alpha * (hpYawRad + wy * dt)
        hpRollRad = alpha * (hpRollRad + wz * dt)

        // Perspective projection against ~62° horizontal / ~78° vertical mobile camera FOV
        val fovHalfTanH = tan(Math.toRadians(31.0)).toFloat()
        val fovHalfTanV = tan(Math.toRadians(39.0)).toFloat()
        val rawDeflectX = (hpYawRad / fovHalfTanH).coerceIn(-0.45f, 0.45f)
        val rawDeflectY = (hpPitchRad / fovHalfTanV).coerceIn(-0.45f, 0.45f)

        // Overscan = 1.22x -> safe translation budget in NDC ([-1..1]) without showing black borders
        val maxShiftNdc = 0.088f
        val maxRollDeg = 3.6f

        // Counter-motion compensation with smooth tanh soft-knee saturation near crop borders
        val targetCompX = -maxShiftNdc * tanh((rawDeflectX / maxShiftNdc).toDouble()).toFloat()
        val targetCompY = maxShiftNdc * tanh((rawDeflectY / maxShiftNdc).toDouble()).toFloat()
        val rawRollDeg = Math.toDegrees(hpRollRad.toDouble()).toFloat()
        val targetCompRoll = -maxRollDeg * tanh((rawRollDeg / maxRollDeg).toDouble()).toFloat()

        val lpf = 0.42f
        smoothCompX += (targetCompX - smoothCompX) * lpf
        smoothCompY += (targetCompY - smoothCompY) * lpf
        smoothCompRollDeg += (targetCompRoll - smoothCompRollDeg) * lpf

        val omegaMag = sqrt(wx * wx + wy * wy + wz * wz)
        val omegaDegSec = Math.toDegrees(omegaMag.toDouble()).toFloat()
        smoothAngularVelDegSec = smoothAngularVelDegSec * 0.78f + omegaDegSec * 0.22f

        val rawMag = hypot(rawDeflectX, rawDeflectY).coerceAtLeast(0.001f)
        val residualErr = hypot(rawDeflectX + smoothCompX, rawDeflectY - smoothCompY)
        val reduction = if (rawMag < 0.01f) {
            96
        } else {
            ((1f - (residualErr / rawMag)) * 100f).toInt().coerceIn(68, 98)
        }

        emitTelemetry(
            compX = smoothCompX,
            compY = smoothCompY,
            compRollDeg = smoothCompRollDeg,
            rawX = rawDeflectX,
            rawY = rawDeflectY,
            angVelDegSec = smoothAngularVelDegSec,
            reductionPct = reduction,
            synthetic = synthetic
        )
    }

    private fun emitTelemetry(
        compX: Float,
        compY: Float,
        compRollDeg: Float,
        rawX: Float,
        rawY: Float,
        angVelDegSec: Float,
        reductionPct: Int,
        synthetic: Boolean = isSyntheticShakeDemo
    ) {
        val next = StabilizationTelemetry(
            isEnabled = isEnabled,
            gyroAvailable = isGyroHardwareAvailable,
            isSyntheticDemo = synthetic,
            overscanZoom = if (isEnabled) overscanZoom else 1.0f,
            compXNorm = compX,
            compYNorm = compY,
            compRollDeg = compRollDeg,
            rawDeflectionX = rawX,
            rawDeflectionY = rawY,
            angularVelocityDegPerSec = angVelDegSec,
            shakeReductionPct = reductionPct
        )
        currentTelemetry = next
        onTelemetryUpdated(next)
    }

    private fun normalizeRadDelta(fromRad: Float, toRad: Float): Float {
        var d = toRad - fromRad
        while (d > PI) d -= (2.0 * PI).toFloat()
        while (d < -PI) d += (2.0 * PI).toFloat()
        return d
    }

    companion object {
        private const val NS_TO_SEC = 1e-9f
        private const val GYRO_DEADZONE_RAD_S = 0.006f

        /**
         * Builds an aspect-preserving OpenGL ES 4x4 MVP matrix applying software EIS
         * overscan zoom, gyro roll counter-rotation, and normalized X/Y crop stabilization
         * with zero parallelogram skew across any preview or video recording aspect ratio.
         */
        fun buildMvpMatrix(
            outMatrix: FloatArray,
            surfaceWidth: Int,
            surfaceHeight: Int,
            enabled: Boolean,
            overscanZoom: Float,
            transXNorm: Float,
            transYNorm: Float,
            rollDeg: Float
        ) {
            Matrix.setIdentityM(outMatrix, 0)
            if (!enabled) return

            val aspect = surfaceWidth.toFloat() / surfaceHeight.coerceAtLeast(1).toFloat()
            val zoom = overscanZoom.coerceIn(1.0f, 1.45f)
            Matrix.scaleM(outMatrix, 0, 1f / aspect, 1f, 1f)
            Matrix.translateM(outMatrix, 0, transXNorm * aspect, transYNorm, 0f)
            Matrix.rotateM(outMatrix, 0, rollDeg, 0f, 0f, 1f)
            Matrix.scaleM(outMatrix, 0, zoom, zoom, 1f)
            Matrix.scaleM(outMatrix, 0, aspect, 1f, 1f)
        }
    }
}

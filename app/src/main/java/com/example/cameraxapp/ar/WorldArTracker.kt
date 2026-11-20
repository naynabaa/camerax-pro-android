package com.example.cameraxapp.ar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.atan2
import kotlin.math.sqrt

class WorldArTracker(
    context: Context,
    private val listener: WorldOrientationListener
) : SensorEventListener {

    interface WorldOrientationListener {
        fun onOrientationUpdated(
            pitchDeg: Float,
            yawDeltaDeg: Float,
            rollDeg: Float,
            detectedSurface: String
        )
    }

    private val sensorManager: SensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometerSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var isStarted = false
    private var isLowPowerMode = false
    private var minDispatchIntervalMs = 16L
    private var hasReferenceYaw = false
    private var referenceYawDeg = 0f
    private var lastRawYawDeg = 0f
    private var unwrappedYawDeg = 0f

    private var smoothedPitchDeg = 22f
    private var smoothedYawDeltaDeg = 0f
    private var smoothedRollDeg = 0f
    private var lastDispatchMs = 0L

    fun start() {
        if (isStarted) return
        val sensor = rotationVectorSensor ?: accelerometerSensor
        if (sensor == null) {
            Log.w(TAG, "No IMU rotation/accelerometer sensor available; using default plane orientation.")
            return
        }
        val samplingDelay = if (isLowPowerMode) {
            SensorManager.SENSOR_DELAY_UI
        } else {
            SensorManager.SENSOR_DELAY_GAME
        }
        val registered = sensorManager.registerListener(
            this,
            sensor,
            samplingDelay
        )
        isStarted = registered
        Log.d(TAG, "WorldArTracker started sensor=${sensor.name} lowPower=$isLowPowerMode registered=$registered")
    }

    fun stop() {
        if (!isStarted) return
        sensorManager.unregisterListener(this)
        isStarted = false
    }

    fun setLowPowerMode(enabled: Boolean) {
        if (isLowPowerMode == enabled) return
        isLowPowerMode = enabled
        minDispatchIntervalMs = if (enabled) 33L else 16L
        if (isStarted) {
            // Re-register with updated sampling delay while preserving current world yaw reference
            sensorManager.unregisterListener(this)
            isStarted = false
            start()
        }
    }

    fun isActive(): Boolean = isStarted

    fun isLowPower(): Boolean = isLowPowerMode

    fun resetReferenceYaw() {
        hasReferenceYaw = false
        smoothedYawDeltaDeg = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastDispatchMs < minDispatchIntervalMs) return
        lastDispatchMs = nowMs

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            // Remap for portrait camera viewing (camera looks out -Z)
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_X,
                SensorManager.AXIS_Z,
                remappedMatrix
            )
            SensorManager.getOrientation(remappedMatrix, orientationAngles)
            val rawYawDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            // Pitch down toward floor yields positive pitchDeg
            val rawPitchDeg = (-Math.toDegrees(orientationAngles[1].toDouble()).toFloat()).coerceIn(-45f, 75f)
            val rawRollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat().coerceIn(-45f, 45f)

            if (!hasReferenceYaw) {
                referenceYawDeg = rawYawDeg
                lastRawYawDeg = rawYawDeg
                unwrappedYawDeg = 0f
                hasReferenceYaw = true
            } else {
                var delta = rawYawDeg - lastRawYawDeg
                while (delta > 180f) delta -= 360f
                while (delta < -180f) delta += 360f
                unwrappedYawDeg += delta
                lastRawYawDeg = rawYawDeg
            }

            val alpha = 0.24f
            smoothedPitchDeg += (rawPitchDeg - smoothedPitchDeg) * alpha
            smoothedYawDeltaDeg += (unwrappedYawDeg - smoothedYawDeltaDeg) * alpha
            smoothedRollDeg += (rawRollDeg - smoothedRollDeg) * alpha
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val gx = event.values[0]
            val gy = event.values[1]
            val gz = event.values[2]
            val norm = sqrt((gx * gx + gy * gy + gz * gz).toDouble()).toFloat().coerceAtLeast(0.001f)
            val rawPitchDeg = Math.toDegrees(atan2((gz / norm).toDouble(), (gy / norm).toDouble())).toFloat().coerceIn(-45f, 75f)
            val rawRollDeg = Math.toDegrees(atan2((-gx / norm).toDouble(), (gy / norm).toDouble())).toFloat().coerceIn(-45f, 45f)
            val alpha = 0.20f
            smoothedPitchDeg += (rawPitchDeg - smoothedPitchDeg) * alpha
            smoothedRollDeg += (rawRollDeg - smoothedRollDeg) * alpha
        }

        val detectedSurface = if (smoothedPitchDeg >= 16f) "HORIZONTAL_FLOOR" else "VERTICAL_WALL"
        listener.onOrientationUpdated(
            pitchDeg = smoothedPitchDeg,
            yawDeltaDeg = smoothedYawDeltaDeg,
            rollDeg = smoothedRollDeg,
            detectedSurface = detectedSurface
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    companion object {
        private const val TAG = "WorldArTracker"
    }
}

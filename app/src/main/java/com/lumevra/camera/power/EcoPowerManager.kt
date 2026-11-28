package com.lumevra.camera.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size

class EcoPowerManager(
    private val context: Context,
    private val onPolicyChanged: (EcoPolicy) -> Unit
) {

    enum class EcoTier {
        NORMAL,
        ECO_SAVER,
        ULTRA_ECO
    }

    data class EcoPolicy(
        val tier: EcoTier,
        val batteryPercent: Int,
        val isCharging: Boolean,
        val isPowerSaveMode: Boolean,
        val temperatureC: Float,
        val targetAnalysisFps: Int,
        val minAnalysisIntervalMs: Long,
        val disableHeavyAr: Boolean,
        val disableHeavyMeters: Boolean,
        val disableMultiCamPip: Boolean,
        val targetAnalysisResolution: Size
    )

    @Volatile
    var currentPolicy: EcoPolicy = EcoPolicy(
        tier = EcoTier.NORMAL,
        batteryPercent = 100,
        isCharging = false,
        isPowerSaveMode = false,
        temperatureC = 28f,
        targetAnalysisFps = 30,
        minAnalysisIntervalMs = 0L,
        disableHeavyAr = false,
        disableHeavyMeters = false,
        disableMultiCamPip = false,
        targetAnalysisResolution = Size(640, 480)
    )
        private set

    @Volatile
    private var lastAnalyzedTimestampMs: Long = 0L
    private var isRegistered: Boolean = false

    val isEcoActive: Boolean
        get() = currentPolicy.tier != EcoTier.NORMAL

    val isArDisabled: Boolean
        get() = currentPolicy.disableHeavyAr

    val isHeavyMetersDisabled: Boolean
        get() = currentPolicy.disableHeavyMeters

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            evaluateFromIntent(intent)
        }
    }

    fun register() {
        if (!isRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            val stickyIntent = context.registerReceiver(batteryReceiver, filter)
            evaluateFromIntent(stickyIntent)
            isRegistered = true
        } else {
            refreshNow()
        }
    }

    fun unregister() {
        if (isRegistered) {
            try {
                context.unregisterReceiver(batteryReceiver)
            } catch (_: IllegalArgumentException) {
            }
            isRegistered = false
        }
    }

    fun refreshNow() {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        evaluateFromIntent(sticky)
    }

    /**
     * Thread-safe gate called at the start of CameraX ImageAnalysis.Analyzer.
     * Dynamically throttles frame analysis cadence (e.g., 30 FPS -> 10 FPS -> 4 FPS) without
     * rebinding the camera pipeline on every frame.
     */
    fun shouldProcessAnalysisFrame(): Boolean {
        val minInterval = currentPolicy.minAnalysisIntervalMs
        if (minInterval <= 0L) return true
        val now = SystemClock.elapsedRealtime()
        if ((now - lastAnalyzedTimestampMs) >= minInterval) {
            lastAnalyzedTimestampMs = now
            return true
        }
        return false
    }

    private fun evaluateFromIntent(intent: Intent?) {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

        val rawPct = if (intent != null && intent.hasExtra(BatteryManager.EXTRA_LEVEL)) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            if (level >= 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else 100
        } else {
            val prop = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (prop in 0..100) prop else 100
        }

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0

        val isOsPowerSave = powerManager?.isPowerSaveMode == true
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 280) ?: 280
        val tempC = tempTenths / 10f

        // Enter Ultra Eco <= 12%, Eco Saver <= 25% (or OS Battery Saver ON), exit cleanly above 30% or when charging
        val prevTier = currentPolicy.tier
        val nextTier = when {
            isCharging -> if (isOsPowerSave) EcoTier.ECO_SAVER else EcoTier.NORMAL
            rawPct <= ULTRA_ECO_THRESHOLD_PCT -> EcoTier.ULTRA_ECO
            rawPct <= ECO_ENTER_THRESHOLD_PCT || isOsPowerSave -> EcoTier.ECO_SAVER
            prevTier != EcoTier.NORMAL && rawPct < ECO_EXIT_THRESHOLD_PCT -> prevTier
            else -> EcoTier.NORMAL
        }

        val nextPolicy = when (nextTier) {
            EcoTier.ULTRA_ECO -> EcoPolicy(
                tier = EcoTier.ULTRA_ECO,
                batteryPercent = rawPct,
                isCharging = isCharging,
                isPowerSaveMode = isOsPowerSave,
                temperatureC = tempC,
                targetAnalysisFps = 4,
                minAnalysisIntervalMs = 250L,
                disableHeavyAr = true,
                disableHeavyMeters = true,
                disableMultiCamPip = true,
                targetAnalysisResolution = Size(320, 240)
            )
            EcoTier.ECO_SAVER -> EcoPolicy(
                tier = EcoTier.ECO_SAVER,
                batteryPercent = rawPct,
                isCharging = isCharging,
                isPowerSaveMode = isOsPowerSave,
                temperatureC = tempC,
                targetAnalysisFps = 10,
                minAnalysisIntervalMs = 100L,
                disableHeavyAr = true,
                disableHeavyMeters = false,
                disableMultiCamPip = true,
                targetAnalysisResolution = Size(480, 360)
            )
            EcoTier.NORMAL -> EcoPolicy(
                tier = EcoTier.NORMAL,
                batteryPercent = rawPct,
                isCharging = isCharging,
                isPowerSaveMode = isOsPowerSave,
                temperatureC = tempC,
                targetAnalysisFps = 30,
                minAnalysisIntervalMs = 0L,
                disableHeavyAr = false,
                disableHeavyMeters = false,
                disableMultiCamPip = false,
                targetAnalysisResolution = Size(640, 480)
            )
        }

        val tierChanged = (nextPolicy.tier != currentPolicy.tier) ||
            (nextPolicy.batteryPercent != currentPolicy.batteryPercent)
        currentPolicy = nextPolicy
        if (tierChanged) {
            onPolicyChanged(nextPolicy)
        }
    }

    companion object {
        private const val ULTRA_ECO_THRESHOLD_PCT = 12
        private const val ECO_ENTER_THRESHOLD_PCT = 25
        private const val ECO_EXIT_THRESHOLD_PCT = 30
    }
}

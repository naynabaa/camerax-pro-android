package com.lumevra.camera.power

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.ImageCapture
import androidx.camera.video.Quality
import java.util.concurrent.Executor

class ThermalGuardManager(
    private val context: Context,
    private val onPolicyChanged: (ThermalPolicy) -> Unit
) {

    enum class ThermalTier(val severityRank: Int) {
        NOMINAL(0),
        MODERATE(1),
        SEVERE(2),
        CRITICAL(3),
        EMERGENCY(4)
    }

    data class ThermalPolicy(
        val tier: ThermalTier,
        val rawStatusCode: Int,
        val statusLabel: String,
        val thermalHeadroom: Float,
        val targetAnalysisFps: Int,
        val minAnalysisIntervalMs: Long,
        val targetAnalysisResolution: Size,
        val maxImageCaptureResolution: Size?,
        val forceMinimizeCaptureLatency: Boolean,
        val maxVideoQuality: Quality?,
        val reduceAnalysisComplexity: Boolean,
        val disableHeavyAr: Boolean,
        val disableMultiCamPip: Boolean
    )

    @Volatile
    var currentPolicy: ThermalPolicy = buildPolicy(
        tier = ThermalTier.NOMINAL,
        rawCode = 0,
        statusLabel = "NOMINAL",
        headroom = 0f
    )
        private set

    @Volatile
    private var lastAnalyzedTimestampMs: Long = 0L
    private var isRegistered: Boolean = false
    private val mainExecutor: Executor = context.mainExecutor
    private var thermalListener: Any? = null

    val isThrottled: Boolean
        get() = currentPolicy.tier.severityRank >= ThermalTier.MODERATE.severityRank

    val shouldReduceComplexity: Boolean
        get() = currentPolicy.reduceAnalysisComplexity

    val isArDisabled: Boolean
        get() = currentPolicy.disableHeavyAr

    val isMultiCamDisabled: Boolean
        get() = currentPolicy.disableMultiCamPip

    fun register() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            evaluateSystemThermalStatus(powerManager, powerManager.currentThermalStatus)
            if (!isRegistered) {
                val listener = PowerManager.OnThermalStatusChangedListener { status ->
                    evaluateSystemThermalStatus(powerManager, status)
                }
                thermalListener = listener
                try {
                    powerManager.addThermalStatusListener(mainExecutor, listener)
                    isRegistered = true
                } catch (_: SecurityException) {
                    thermalListener = null
                }
            }
        } else {
            isRegistered = true
        }
    }

    fun unregister() {
        if (!isRegistered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val listener = thermalListener as? PowerManager.OnThermalStatusChangedListener
            if (powerManager != null && listener != null) {
                try {
                    powerManager.removeThermalStatusListener(listener)
                } catch (_: IllegalArgumentException) {
                }
            }
        }
        thermalListener = null
        isRegistered = false
    }

    /**
     * Fallback signal for pre-API 29 devices using BatteryManager temperature readings (°C).
     */
    fun updateBatteryTemperatureFallback(tempC: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        val tier = when {
            tempC >= 46.5f -> ThermalTier.CRITICAL
            tempC >= 43.0f -> ThermalTier.SEVERE
            tempC >= 39.5f -> ThermalTier.MODERATE
            else -> ThermalTier.NOMINAL
        }
        val label = "${tier.name} (${tempC.toInt()}°C)"
        applyEvaluatedPolicy(buildPolicy(tier, tier.severityRank, label, 0f))
    }

    /**
     * Gate called at the entry of CameraX ImageAnalysis.Analyzer to throttle analysis CPU/NPU load
     * smoothly without rebinding the camera pipeline on minor fluctuations.
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

    fun effectiveCaptureMode(requestedMode: Int): Int {
        return if (currentPolicy.forceMinimizeCaptureLatency) {
            ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        } else {
            requestedMode
        }
    }

    fun constrainVideoQuality(requested: Quality): Quality {
        val cap = currentPolicy.maxVideoQuality ?: return requested
        val rankRequested = qualityRank(requested)
        val rankCap = qualityRank(cap)
        return if (rankRequested > rankCap) cap else requested
    }

    private fun qualityRank(q: Quality): Int = when (q) {
        Quality.UHD -> 3
        Quality.FHD -> 2
        Quality.HD -> 1
        else -> 0
    }

    private fun evaluateSystemThermalStatus(powerManager: PowerManager, status: Int) {
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                powerManager.getThermalHeadroom(10).takeIf { it.isFinite() && it >= 0f } ?: 0f
            } catch (_: Throwable) {
                0f
            }
        } else {
            0f
        }

        val (tier, label) = when {
            status >= PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalTier.EMERGENCY to "EMERGENCY"
            status == PowerManager.THERMAL_STATUS_CRITICAL -> ThermalTier.CRITICAL to "CRITICAL"
            status == PowerManager.THERMAL_STATUS_SEVERE || headroom >= 1.0f -> ThermalTier.SEVERE to "SEVERE"
            status == PowerManager.THERMAL_STATUS_MODERATE || headroom >= 0.85f -> ThermalTier.MODERATE to "MODERATE"
            status == PowerManager.THERMAL_STATUS_LIGHT -> ThermalTier.NOMINAL to "LIGHT"
            else -> ThermalTier.NOMINAL to "NOMINAL"
        }
        applyEvaluatedPolicy(buildPolicy(tier, status, label, headroom))
    }

    private fun applyEvaluatedPolicy(nextPolicy: ThermalPolicy) {
        val changed = nextPolicy.tier != currentPolicy.tier ||
            nextPolicy.rawStatusCode != currentPolicy.rawStatusCode
        currentPolicy = nextPolicy
        if (changed) {
            onPolicyChanged(nextPolicy)
        }
    }

    private fun buildPolicy(
        tier: ThermalTier,
        rawCode: Int,
        statusLabel: String,
        headroom: Float
    ): ThermalPolicy = when (tier) {
        ThermalTier.EMERGENCY -> ThermalPolicy(
            tier = tier,
            rawStatusCode = rawCode,
            statusLabel = statusLabel,
            thermalHeadroom = headroom,
            targetAnalysisFps = 2,
            minAnalysisIntervalMs = 500L,
            targetAnalysisResolution = Size(240, 180),
            maxImageCaptureResolution = Size(960, 720),
            forceMinimizeCaptureLatency = true,
            maxVideoQuality = Quality.SD,
            reduceAnalysisComplexity = true,
            disableHeavyAr = true,
            disableMultiCamPip = true
        )
        ThermalTier.CRITICAL -> ThermalPolicy(
            tier = tier,
            rawStatusCode = rawCode,
            statusLabel = statusLabel,
            thermalHeadroom = headroom,
            targetAnalysisFps = 3,
            minAnalysisIntervalMs = 333L,
            targetAnalysisResolution = Size(320, 240),
            maxImageCaptureResolution = Size(1280, 720),
            forceMinimizeCaptureLatency = true,
            maxVideoQuality = Quality.HD,
            reduceAnalysisComplexity = true,
            disableHeavyAr = true,
            disableMultiCamPip = true
        )
        ThermalTier.SEVERE -> ThermalPolicy(
            tier = tier,
            rawStatusCode = rawCode,
            statusLabel = statusLabel,
            thermalHeadroom = headroom,
            targetAnalysisFps = 8,
            minAnalysisIntervalMs = 125L,
            targetAnalysisResolution = Size(320, 240),
            maxImageCaptureResolution = Size(1920, 1080),
            forceMinimizeCaptureLatency = true,
            maxVideoQuality = Quality.FHD,
            reduceAnalysisComplexity = true,
            disableHeavyAr = true,
            disableMultiCamPip = true
        )
        ThermalTier.MODERATE -> ThermalPolicy(
            tier = tier,
            rawStatusCode = rawCode,
            statusLabel = statusLabel,
            thermalHeadroom = headroom,
            targetAnalysisFps = 15,
            minAnalysisIntervalMs = 66L,
            targetAnalysisResolution = Size(480, 360),
            maxImageCaptureResolution = null,
            forceMinimizeCaptureLatency = false,
            maxVideoQuality = null,
            reduceAnalysisComplexity = true,
            disableHeavyAr = false,
            disableMultiCamPip = false
        )
        ThermalTier.NOMINAL -> ThermalPolicy(
            tier = tier,
            rawStatusCode = rawCode,
            statusLabel = statusLabel,
            thermalHeadroom = headroom,
            targetAnalysisFps = 30,
            minAnalysisIntervalMs = 0L,
            targetAnalysisResolution = Size(640, 480),
            maxImageCaptureResolution = null,
            forceMinimizeCaptureLatency = false,
            maxVideoQuality = null,
            reduceAnalysisComplexity = false,
            disableHeavyAr = false,
            disableMultiCamPip = false
        )
    }
}

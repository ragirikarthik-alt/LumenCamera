package com.aicamera.photo.core.camera

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks the device thermal status and derives the runtime policy: analysis rate, whether
 * expensive AI may run, and whether model resources should be released.
 */
class ThermalMonitor(context: Context) {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val _status = MutableStateFlow(power.currentThermalStatus)
    val status: StateFlow<Int> = _status

    private val listener = PowerManager.OnThermalStatusChangedListener { _status.value = it }

    fun start() = power.addThermalStatusListener(listener)
    fun stop() = power.removeThermalStatusListener(listener)

    /** Forecast thermal headroom (1.0 = throttling threshold), or null if unavailable. */
    fun headroom(): Float? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        power.getThermalHeadroom(10).takeIf { !it.isNaN() }
    } else null

    companion object {
        fun label(status: Int) = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "None"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
            else -> "Unknown"
        }

        fun targetAnalysisFps(status: Int): Int = when {
            status <= PowerManager.THERMAL_STATUS_LIGHT -> 15
            status == PowerManager.THERMAL_STATUS_MODERATE -> 10
            status == PowerManager.THERMAL_STATUS_SEVERE -> 5
            else -> 3
        }

        fun aiAllowed(status: Int) = status < PowerManager.THERMAL_STATUS_SEVERE
        fun shouldReleaseModel(status: Int) = status >= PowerManager.THERMAL_STATUS_CRITICAL
    }
}

/**
 * Adaptive analysis rate: targets 5-15 FPS based on thermal state, and backs off further when
 * analysis itself is slow, so the analysis queue never builds up.
 */
class AdaptiveRateController(private val minFps: Int = 3) {
    @Volatile var thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE
    @Volatile var busy: Boolean = false
    private var avgProcessingMs = 40f
    private var lastProcessedAt = 0L

    fun recordProcessing(ms: Long) {
        avgProcessingMs += 0.2f * (ms - avgProcessingMs)
    }

    val currentIntervalMs: Long
        get() {
            var fps = ThermalMonitor.targetAnalysisFps(thermalStatus)
            if (busy) fps = (fps / 2).coerceAtLeast(minFps)
            val byThermal = 1000L / fps
            val byLoad = (avgProcessingMs * 1.25f).toLong()
            return maxOf(byThermal, byLoad, 1000L / 15)
        }

    /** Returns true when this frame should be analysed; otherwise the caller drops it immediately. */
    fun shouldProcess(nowMs: Long): Boolean {
        if (nowMs - lastProcessedAt >= currentIntervalMs) {
            lastProcessedAt = nowMs
            return true
        }
        return false
    }
}

package com.aicamera.photo.core.camera

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class DiagnosticsSnapshot(
    val cameraState: String = "Idle",
    val analysisFps: Float = 0f,
    val framesProcessed: Long = 0,
    val framesDropped: Long = 0,
    val analysisMs: Long = 0,
    val detectorMs: Long = 0,
    val compositionMs: Long = 0,
    val aiState: String = "Not installed",
    val aiLastLatencyMs: Long? = null,
    val aiColdStartMs: Long? = null,
    val thermalStatus: Int = 0,
    val analysisIntervalMs: Long = 0,
    val javaHeapMb: Int = 0,
    val nativeHeapMb: Int = 0,
)

/** Shared, lock-free diagnostics store (spec Appendix A). */
class Diagnostics {
    private val _state = MutableStateFlow(DiagnosticsSnapshot())
    val state: StateFlow<DiagnosticsSnapshot> = _state

    private var windowStart = 0L
    private var windowFrames = 0

    fun cameraState(s: String) = _state.update { it.copy(cameraState = s) }

    fun frameDropped() = _state.update { it.copy(framesDropped = it.framesDropped + 1) }

    fun frameProcessed(nowMs: Long, totalMs: Long, detectorMs: Long, intervalMs: Long) {
        windowFrames++
        var fps = _state.value.analysisFps
        if (windowStart == 0L) windowStart = nowMs
        val elapsed = nowMs - windowStart
        if (elapsed >= 1000) {
            fps = windowFrames * 1000f / elapsed
            windowFrames = 0
            windowStart = nowMs
        }
        val rt = Runtime.getRuntime()
        _state.update {
            it.copy(
                analysisFps = fps,
                framesProcessed = it.framesProcessed + 1,
                analysisMs = totalMs,
                detectorMs = detectorMs,
                analysisIntervalMs = intervalMs,
                javaHeapMb = ((rt.totalMemory() - rt.freeMemory()) / 1_048_576).toInt(),
                nativeHeapMb = (android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576).toInt(),
            )
        }
    }

    fun compositionTime(ms: Long) = _state.update { it.copy(compositionMs = ms) }
    fun thermal(status: Int) = _state.update { it.copy(thermalStatus = status) }
    fun ai(state: String, latencyMs: Long? = null, coldStartMs: Long? = null) = _state.update {
        it.copy(aiState = state, aiLastLatencyMs = latencyMs ?: it.aiLastLatencyMs, aiColdStartMs = coldStartMs ?: it.aiColdStartMs)
    }
}

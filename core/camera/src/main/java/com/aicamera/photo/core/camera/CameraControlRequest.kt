package com.aicamera.photo.core.camera

import android.hardware.camera2.CameraMetadata

/**
 * The only way to change camera hardware state. Every request is validated against the probed
 * [CameraCapabilities] before it reaches the camera. AI components never construct these directly;
 * they return recommendation data and the user decides.
 */
sealed interface CameraControlRequest {
    data class Zoom(val ratio: Float) : CameraControlRequest
    data class ExposureCompensation(val index: Int) : CameraControlRequest
    data class Flash(val mode: FlashSetting) : CameraControlRequest
    data class Torch(val enabled: Boolean) : CameraControlRequest
    data class FocusAt(val viewX: Float, val viewY: Float) : CameraControlRequest
    data class ManualIso(val iso: Int?) : CameraControlRequest
    data class ManualShutter(val exposureTimeNs: Long?) : CameraControlRequest
    data class ManualFocus(val diopters: Float?) : CameraControlRequest
    data class WhiteBalance(val preset: WhiteBalancePreset) : CameraControlRequest
    data object ResetManual : CameraControlRequest
}

enum class FlashSetting { OFF, AUTO, ON }

enum class WhiteBalancePreset(val label: String, val awbMode: Int) {
    AUTO("Auto", CameraMetadata.CONTROL_AWB_MODE_AUTO),
    DAYLIGHT("Daylight", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("Cloudy", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    SHADE("Shade", CameraMetadata.CONTROL_AWB_MODE_SHADE),
    TUNGSTEN("Tungsten", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT("Fluorescent", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
}

sealed interface ControlResult {
    data object Applied : ControlResult
    data class Clamped(val message: String) : ControlResult
    data class Rejected(val reason: String) : ControlResult
}

/** Current manual overrides; null fields mean "automatic". */
data class ManualSettings(
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val focusDiopters: Float? = null,
    val whiteBalance: WhiteBalancePreset = WhiteBalancePreset.AUTO,
) {
    val manualExposure get() = iso != null || exposureTimeNs != null
    val isAuto get() = !manualExposure && focusDiopters == null && whiteBalance == WhiteBalancePreset.AUTO
}

/** Live values reported by the camera (useful as starting points for manual mode and for diagnostics). */
data class LiveExposure(
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val focusDiopters: Float? = null,
    val aperture: Float? = null,
)

object ControlValidator {
    /** Validates and clamps a request. Returns the (possibly clamped) request, or a rejection. */
    fun validate(req: CameraControlRequest, caps: CameraCapabilities?): Pair<CameraControlRequest?, ControlResult> {
        if (caps == null) return null to ControlResult.Rejected("Camera not ready")
        return when (req) {
            is CameraControlRequest.Zoom -> {
                val c = req.ratio.coerceIn(caps.zoomRange)
                CameraControlRequest.Zoom(c) to if (c != req.ratio) ControlResult.Clamped("Zoom limited to ${caps.zoomRange}") else ControlResult.Applied
            }
            is CameraControlRequest.ExposureCompensation -> {
                if (!caps.supportsExposureCompensation) return null to ControlResult.Rejected("Exposure compensation not supported")
                val c = req.index.coerceIn(caps.exposureIndexRange)
                CameraControlRequest.ExposureCompensation(c) to ControlResult.Applied
            }
            is CameraControlRequest.Flash, is CameraControlRequest.Torch -> {
                if (!caps.hasFlash) null to ControlResult.Rejected("This camera has no flash") else req to ControlResult.Applied
            }
            is CameraControlRequest.FocusAt -> req to ControlResult.Applied
            is CameraControlRequest.ManualIso -> {
                if (req.iso == null) return req to ControlResult.Applied
                val range = caps.isoRange
                if (!caps.manualSensor || range == null) return null to ControlResult.Rejected("Manual ISO not supported on this camera")
                CameraControlRequest.ManualIso(req.iso.coerceIn(range)) to ControlResult.Applied
            }
            is CameraControlRequest.ManualShutter -> {
                if (req.exposureTimeNs == null) return req to ControlResult.Applied
                val range = caps.exposureTimeRangeNs
                if (!caps.manualSensor || range == null) return null to ControlResult.Rejected("Manual shutter not supported on this camera")
                CameraControlRequest.ManualShutter(req.exposureTimeNs.coerceIn(range)) to ControlResult.Applied
            }
            is CameraControlRequest.ManualFocus -> {
                if (req.diopters == null) return req to ControlResult.Applied
                if (!caps.manualFocus) return null to ControlResult.Rejected("Manual focus not supported on this camera")
                CameraControlRequest.ManualFocus(req.diopters.coerceIn(0f, caps.minFocusDistance)) to ControlResult.Applied
            }
            is CameraControlRequest.WhiteBalance -> {
                if (req.preset.awbMode !in caps.awbModes) null to ControlResult.Rejected("${req.preset.label} white balance not supported")
                else req to ControlResult.Applied
            }
            CameraControlRequest.ResetManual -> req to ControlResult.Applied
        }
    }
}

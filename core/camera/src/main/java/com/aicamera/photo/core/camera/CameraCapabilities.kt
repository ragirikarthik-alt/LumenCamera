package com.aicamera.photo.core.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.SizeF
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class LensRole(val label: String) { ULTRAWIDE("Ultrawide"), MAIN("Main"), TELEPHOTO("Telephoto"), FRONT("Front") }

/** Probed capabilities of one CameraX camera. Manual controls and RAW are only exposed when present here. */
data class CameraCapabilities(
    val cameraId: String,
    val lensFacing: Int,
    val focalLengthMm: Float?,
    val focalLength35mm: Int?,
    val role: LensRole,
    val isLogicalMultiCamera: Boolean,
    val physicalCameraCount: Int,
    val hasFlash: Boolean,
    val zoomRange: ClosedFloatingPointRange<Float>,
    val exposureIndexRange: IntRange,
    val exposureStep: Float,
    val manualSensor: Boolean,
    val isoRange: IntRange?,
    val exposureTimeRangeNs: LongRange?,
    val manualFocus: Boolean,
    /** Diopters; 0 means fixed focus. */
    val minFocusDistance: Float,
    val awbModes: List<Int>,
    val rawSupported: Boolean,
    val hardwareLevel: Int?,
) {
    val supportsExposureCompensation get() = exposureIndexRange.first < exposureIndexRange.last
}

/**
 * A choice in the lens picker. Either a distinct CameraX camera (optical switch) or a zoom ratio on a
 * logical multi-camera, where the device may switch physical lenses itself.
 */
data class LensOption(
    val id: String,
    val label: String,
    val detail: String,
    val role: LensRole,
    val cameraIndex: Int,
    val zoomRatio: Float?,
    /** True when choosing this option changes the physical lens rather than cropping digitally. */
    val optical: Boolean,
)

object CapabilityProbe {

    @OptIn(ExperimentalCamera2Interop::class)
    fun probe(context: Context, info: CameraInfo): CameraCapabilities {
        val c2 = Camera2CameraInfo.from(info)
        val id = c2.cameraId
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()

        val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
        val sensor: SizeF? = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val eq = if (focal != null && sensor != null && sensor.width > 0) {
            (focal * 43.27f / hypot(sensor.width, sensor.height)).roundToInt()
        } else null
        val caps = c2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
        val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        val iso: Range<Int>? = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp: Range<Long>? = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocus = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val awb = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: emptyList()
        val level = c2.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        val physicalCount = chars?.physicalCameraIds?.size ?: 0

        val rawByCameraX = runCatching {
            ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
        }.getOrDefault(false)
        val rawByCamera2 = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps

        val zoom = info.zoomState.value
        val expState = info.exposureState
        val facing = info.lensFacing
        val role = when {
            facing == CameraSelector.LENS_FACING_FRONT -> LensRole.FRONT
            eq != null && eq < 20 -> LensRole.ULTRAWIDE
            eq != null && eq > 40 -> LensRole.TELEPHOTO
            else -> LensRole.MAIN
        }
        return CameraCapabilities(
            cameraId = id,
            lensFacing = facing,
            focalLengthMm = focal,
            focalLength35mm = eq,
            role = role,
            isLogicalMultiCamera = runCatching { info.isLogicalMultiCameraSupported }.getOrDefault(false) || physicalCount > 1,
            physicalCameraCount = physicalCount,
            hasFlash = info.hasFlashUnit(),
            zoomRange = (zoom?.minZoomRatio ?: 1f)..(zoom?.maxZoomRatio ?: 1f),
            exposureIndexRange = if (expState.isExposureCompensationSupported) expState.exposureCompensationRange.lower..expState.exposureCompensationRange.upper else 0..0,
            exposureStep = expState.exposureCompensationStep.toFloat(),
            manualSensor = manualSensor && iso != null && exp != null,
            isoRange = iso?.let { it.lower..it.upper },
            exposureTimeRangeNs = exp?.let { it.lower..it.upper },
            manualFocus = manualSensor && minFocus > 0f,
            minFocusDistance = minFocus,
            awbModes = awb,
            rawSupported = rawByCameraX && rawByCamera2,
            hardwareLevel = level,
        )
    }

    /** Builds the lens picker: distinct back cameras first, then zoom presets on a logical multi-camera. */
    fun lensOptions(caps: List<CameraCapabilities>): List<LensOption> {
        val back = caps.withIndex().filter { it.value.lensFacing == CameraSelector.LENS_FACING_BACK }
        val front = caps.withIndex().firstOrNull { it.value.lensFacing == CameraSelector.LENS_FACING_FRONT }
        val options = mutableListOf<LensOption>()
        val main = back.firstOrNull { it.value.role == LensRole.MAIN } ?: back.firstOrNull()
        val mainEq = main?.value?.focalLength35mm ?: 24

        // Distinct back cameras with different focal lengths are true optical switches.
        val distinct = back.distinctBy { it.value.focalLength35mm }
        if (distinct.size > 1) {
            distinct.sortedBy { it.value.focalLength35mm ?: 0 }.forEach { (index, c) ->
                val factor = (c.focalLength35mm ?: mainEq).toFloat() / mainEq
                options += LensOption(
                    id = "cam_${c.cameraId}",
                    label = formatFactor(factor),
                    detail = "${c.role.label} ${c.focalLength35mm?.let { "${it}mm" } ?: ""}".trim(),
                    role = c.role,
                    cameraIndex = index,
                    zoomRatio = null,
                    optical = true,
                )
            }
            // A logical main camera may hide its telephoto behind zoom ratios (e.g. Galaxy S23 3x).
            val m = main?.value
            if (m != null && m.isLogicalMultiCamera && m.zoomRange.endInclusive >= 3f && distinct.none { it.value.role == LensRole.TELEPHOTO }) {
                options += LensOption("zoom_tele", "3×", "Telephoto (device lens switch)", LensRole.TELEPHOTO, main.index, 3f, optical = true)
            }
        } else if (main != null) {
            val c = main.value
            // Logical multi-camera: zoom ratios below 1x or at telephoto factors let the device switch lenses.
            if (c.isLogicalMultiCamera && c.zoomRange.start < 0.95f) {
                options += LensOption("zoom_uw", formatFactor(c.zoomRange.start), "Ultrawide (device lens switch)", LensRole.ULTRAWIDE, main.index, c.zoomRange.start, optical = true)
            }
            options += LensOption("zoom_main", "1×", "Main ${c.focalLength35mm?.let { "${it}mm" } ?: ""}".trim(), LensRole.MAIN, main.index, 1f, optical = true)
            if (c.isLogicalMultiCamera && c.zoomRange.endInclusive >= 3f) {
                options += LensOption("zoom_tele", "3×", "Telephoto (device lens switch)", LensRole.TELEPHOTO, main.index, 3f, optical = true)
            }
        }
        front?.let { (index, c) ->
            options += LensOption("cam_${c.cameraId}", "Selfie", "Front ${c.focalLength35mm?.let { "${it}mm" } ?: ""}".trim(), LensRole.FRONT, index, null, optical = true)
        }
        return options
    }

    private fun formatFactor(f: Float): String =
        if (kotlin.math.abs(f - f.roundToInt()) < 0.05f) "${f.roundToInt()}×" else String.format("%.1f×", f)
}

package com.aicamera.photo.core.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import com.aicamera.photo.domain.CaptureMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class CameraState(
    val ready: Boolean = false,
    val lensOptions: List<LensOption> = emptyList(),
    val selectedLensId: String? = null,
    val capabilities: CameraCapabilities? = null,
    val zoomRatio: Float = 1f,
    val exposureIndex: Int = 0,
    val flash: FlashSetting = FlashSetting.OFF,
    val torch: Boolean = false,
    val manual: ManualSettings = ManualSettings(),
    val live: LiveExposure = LiveExposure(),
    val rawRequested: Boolean = false,
    val rawActive: Boolean = false,
    val isFrontCamera: Boolean = false,
    val capturing: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

data class CaptureOutcome(val jpeg: File, val raw: File?, val metadata: CaptureMetadata)

/**
 * Owns the CameraX lifecycle and is the single gatekeeper for camera hardware changes.
 *
 * Preview, ImageAnalysis and ImageCapture are separate use cases. Analysis uses ~640x480 YUV with
 * KEEP_ONLY_LATEST so stale frames never queue. Full-resolution capture happens only on shutter.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraController(
    private val context: Context,
    private val diagnostics: Diagnostics,
) {
    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state

    private var provider: ProcessCameraProvider? = null
    private var infos: List<CameraInfo> = emptyList()
    private var allCaps: List<CameraCapabilities> = emptyList()
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var meteringFactory: MeteringPointFactory? = null
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    private var owner: LifecycleOwner? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var analyzer: ImageAnalysis.Analyzer? = null
    private var analyzerExecutor: Executor? = null
    private var currentCameraIndex = -1
    private var targetRotation: Int = android.view.Surface.ROTATION_0
    private var lastLiveUpdate = 0L

    suspend fun initialize() {
        if (provider != null) return
        diagnostics.cameraState("Initializing")
        val p = ProcessCameraProvider.awaitInstance(context)
        provider = p
        infos = p.availableCameraInfos
        allCaps = infos.map { CapabilityProbe.probe(context, it) }
        val options = CapabilityProbe.lensOptions(allCaps)
        val default = options.firstOrNull { it.role == LensRole.MAIN } ?: options.firstOrNull()
        _state.update { it.copy(lensOptions = options, selectedLensId = default?.id) }
        if (default != null) currentCameraIndex = default.cameraIndex
        Log.i(TAG, "Cameras: " + allCaps.joinToString { "${it.cameraId}:${it.role}:${it.focalLength35mm}mm raw=${it.rawSupported} manual=${it.manualSensor}" })
    }

    /** All probed capabilities, for the diagnostics/capability report. */
    fun capabilityReport(): List<CameraCapabilities> = allCaps

    fun setMeteringPointFactory(factory: MeteringPointFactory) {
        meteringFactory = factory
    }

    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewSurface: Preview.SurfaceProvider,
        analysisExecutor: Executor,
        frameAnalyzer: ImageAnalysis.Analyzer,
    ) {
        owner = lifecycleOwner
        surfaceProvider = previewSurface
        analyzer = frameAnalyzer
        analyzerExecutor = analysisExecutor
        rebind()
    }

    fun unbind() {
        provider?.unbindAll()
        camera = null
        imageCapture = null
        diagnostics.cameraState("Unbound")
        _state.update { it.copy(ready = false) }
    }

    fun setRawRequested(enabled: Boolean) {
        if (_state.value.rawRequested == enabled) return
        _state.update { it.copy(rawRequested = enabled) }
        if (camera != null) rebind()
    }

    fun setTargetRotation(surfaceRotation: Int) {
        targetRotation = surfaceRotation
        imageCapture?.targetRotation = surfaceRotation
    }

    fun selectLens(optionId: String) {
        val option = _state.value.lensOptions.firstOrNull { it.id == optionId } ?: return
        val cameraChanged = option.cameraIndex != currentCameraIndex
        _state.update { it.copy(selectedLensId = optionId, manual = ManualSettings()) }
        currentCameraIndex = option.cameraIndex
        if (cameraChanged) rebind()
        val zoom = option.zoomRatio ?: if (!cameraChanged) 1f else null
        zoom?.let { apply(CameraControlRequest.Zoom(it)) }
    }

    private fun rebind() {
        val p = provider ?: return
        val lifecycleOwner = owner ?: return
        val sp = surfaceProvider ?: return
        val info = infos.getOrNull(currentCameraIndex) ?: infos.firstOrNull() ?: run {
            _state.update { it.copy(error = "No camera available") }
            return
        }
        val caps = allCaps[infos.indexOf(info)]
        val wantRaw = _state.value.rawRequested && caps.rawSupported

        val aspect = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val previewBuilder = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(liveCallback)
        val preview = previewBuilder.build().also { it.surfaceProvider = sp }

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspect)
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        analyzerExecutor?.let { ex -> analyzer?.let { analysis.setAnalyzer(ex, it) } }

        fun buildCapture(raw: Boolean) = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspect)
                    .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                    .build()
            )
            .setJpegQuality(95)
            .setTargetRotation(targetRotation)
            .apply { if (raw) setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG) }
            .build()
            .also { it.flashMode = _state.value.flash.toCameraX() }

        p.unbindAll()
        var rawActive = wantRaw
        var capture = buildCapture(wantRaw)
        val bound = try {
            p.bindToLifecycle(lifecycleOwner, info.cameraSelector, preview, analysis, capture)
        } catch (e: IllegalArgumentException) {
            if (!wantRaw) throw e
            Log.w(TAG, "RAW+JPEG combination not supported with analysis; falling back to JPEG", e)
            rawActive = false
            capture = buildCapture(false)
            p.bindToLifecycle(lifecycleOwner, info.cameraSelector, preview, analysis, capture)
        }
        camera = bound
        imageCapture = capture
        diagnostics.cameraState("Bound ${caps.role.label}${if (rawActive) " + RAW" else ""}")
        _state.update {
            it.copy(
                ready = true,
                capabilities = caps,
                zoomRatio = bound.cameraInfo.zoomState.value?.zoomRatio ?: 1f,
                exposureIndex = 0,
                torch = false,
                rawActive = rawActive,
                isFrontCamera = caps.role == LensRole.FRONT,
                manual = ManualSettings(),
                error = null,
                message = if (wantRaw && !rawActive) "RAW isn't available together with live coaching on this lens" else null,
            )
        }
    }

    /** Applies a validated control request. */
    fun apply(request: CameraControlRequest): ControlResult {
        val cam = camera ?: return ControlResult.Rejected("Camera not ready")
        val (validated, result) = ControlValidator.validate(request, _state.value.capabilities)
        if (validated == null) {
            _state.update { it.copy(message = (result as? ControlResult.Rejected)?.reason) }
            return result
        }
        when (validated) {
            is CameraControlRequest.Zoom -> {
                cam.cameraControl.setZoomRatio(validated.ratio)
                _state.update { it.copy(zoomRatio = validated.ratio) }
            }
            is CameraControlRequest.ExposureCompensation -> {
                cam.cameraControl.setExposureCompensationIndex(validated.index)
                _state.update { it.copy(exposureIndex = validated.index) }
            }
            is CameraControlRequest.Flash -> {
                imageCapture?.flashMode = validated.mode.toCameraX()
                _state.update { it.copy(flash = validated.mode) }
            }
            is CameraControlRequest.Torch -> {
                cam.cameraControl.enableTorch(validated.enabled)
                _state.update { it.copy(torch = validated.enabled) }
            }
            is CameraControlRequest.FocusAt -> {
                val factory = meteringFactory ?: return ControlResult.Rejected("Preview not ready")
                if (_state.value.manual.focusDiopters != null) return ControlResult.Rejected("Manual focus is on")
                val point = factory.createPoint(validated.viewX, validated.viewY)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(5, TimeUnit.SECONDS)
                    .build()
                cam.cameraControl.startFocusAndMetering(action)
            }
            is CameraControlRequest.ManualIso -> updateManual { it.copy(iso = validated.iso) }
            is CameraControlRequest.ManualShutter -> updateManual { it.copy(exposureTimeNs = validated.exposureTimeNs) }
            is CameraControlRequest.ManualFocus -> updateManual { it.copy(focusDiopters = validated.diopters) }
            is CameraControlRequest.WhiteBalance -> updateManual { it.copy(whiteBalance = validated.preset) }
            CameraControlRequest.ResetManual -> updateManual { ManualSettings() }
        }
        return result
    }

    private fun updateManual(change: (ManualSettings) -> ManualSettings) {
        val cam = camera ?: return
        val manual = change(_state.value.manual)
        _state.update { it.copy(manual = manual) }
        val live = _state.value.live
        val builder = CaptureRequestOptions.Builder()
        if (manual.manualExposure) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, manual.iso ?: live.iso ?: 100)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, manual.exposureTimeNs ?: live.exposureTimeNs ?: 16_666_666L)
        }
        if (manual.focusDiopters != null) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, manual.focusDiopters)
        }
        if (manual.whiteBalance != WhiteBalancePreset.AUTO) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, manual.whiteBalance.awbMode)
        }
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(builder.build())
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /**
     * Captures a full-resolution still (and DNG when RAW is active). The user is the only trigger.
     */
    suspend fun capture(jpegFile: File, rawFile: File?): CaptureOutcome {
        val capture = imageCapture ?: error("Camera not ready")
        _state.update { it.copy(capturing = true) }
        try {
            val rawActive = _state.value.rawActive && rawFile != null
            val jpegDone = CompletableDeferred<Unit>()
            val rawDone = CompletableDeferred<Unit>()
            val callback = object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (output.imageFormat == ImageFormat.RAW_SENSOR) rawDone.complete(Unit) else jpegDone.complete(Unit)
                }

                override fun onError(exception: ImageCaptureException) {
                    jpegDone.completeExceptionally(exception)
                    rawDone.completeExceptionally(exception)
                }
            }
            val jpegOptions = ImageCapture.OutputFileOptions.Builder(jpegFile).build()
            if (rawActive) {
                val rawOptions = ImageCapture.OutputFileOptions.Builder(rawFile!!).build()
                capture.takePicture(rawOptions, jpegOptions, captureExecutor, callback)
            } else {
                capture.takePicture(jpegOptions, captureExecutor, callback)
            }
            jpegDone.await()
            val savedRaw = if (rawActive) {
                withTimeoutOrNull(4_000) { runCatching { rawDone.await() }.isSuccess }
                rawFile?.takeIf { it.exists() && it.length() > 0 }
            } else null
            val metadata = withContext(Dispatchers.IO) { readMetadata(jpegFile) }
            return CaptureOutcome(jpegFile, savedRaw, metadata)
        } finally {
            _state.update { it.copy(capturing = false) }
        }
    }

    private fun readMetadata(file: File): CaptureMetadata {
        val s = _state.value
        val lens = s.lensOptions.firstOrNull { it.id == s.selectedLensId }
        return runCatching {
            val exif = ExifInterface(file)
            val rotation = exif.rotationDegrees
            var w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            var h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            if (w == 0 || h == 0) {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, o)
                w = o.outWidth; h = o.outHeight
            }
            if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }
            CaptureMetadata(
                width = w,
                height = h,
                iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 } ?: s.live.iso,
                exposureTimeNs = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let { (it * 1e9).toLong() } ?: s.live.exposureTimeNs,
                aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.toFloat() ?: s.live.aperture,
                focalLengthMm = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.toFloat() ?: s.capabilities?.focalLengthMm,
                focalLength35mm = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0).takeIf { it > 0 }
                    ?: s.capabilities?.focalLength35mm?.let { (it * s.zoomRatio).toInt() },
                lensLabel = lens?.let { "${it.label} ${it.detail}" },
                exposureCompensation = s.capabilities?.let { s.exposureIndex * it.exposureStep },
                flash = exif.getAttributeInt(ExifInterface.TAG_FLASH, 0) and 1 == 1,
                whiteBalance = s.manual.whiteBalance.label,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            )
        }.getOrElse { CaptureMetadata(lensLabel = lens?.label, deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}") }
    }

    private val liveCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val now = System.currentTimeMillis()
            if (now - lastLiveUpdate < 250) return
            lastLiveUpdate = now
            val live = LiveExposure(
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                aperture = result.get(CaptureResult.LENS_APERTURE),
            )
            _state.update { it.copy(live = live) }
        }
    }

    fun shutdown() {
        unbind()
        captureExecutor.shutdown()
    }

    private fun FlashSetting.toCameraX() = when (this) {
        FlashSetting.OFF -> ImageCapture.FLASH_MODE_OFF
        FlashSetting.AUTO -> ImageCapture.FLASH_MODE_AUTO
        FlashSetting.ON -> ImageCapture.FLASH_MODE_ON
    }

    companion object {
        private const val TAG = "LumenCamera"
    }
}

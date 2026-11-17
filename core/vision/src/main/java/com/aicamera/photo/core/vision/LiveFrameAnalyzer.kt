package com.aicamera.photo.core.vision

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.aicamera.photo.core.camera.AdaptiveRateController
import com.aicamera.photo.core.camera.DeviceOrientation
import com.aicamera.photo.core.camera.Diagnostics
import com.aicamera.photo.core.camera.FrameConverter
import com.aicamera.photo.domain.AnalysisFrame
import com.aicamera.photo.domain.LumaImage
import com.aicamera.photo.domain.SceneSnapshot
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/** Geometry context of an analysed frame, needed to map world-upright results back to the screen. */
data class FrameContext(
    /** Clockwise rotation that was applied to the display-upright frame to make it world-upright. */
    val worldRotationCw: Int,
    val luma: LumaImage,
    val deviceTiltDegrees: Float?,
)

/**
 * CameraX analyzer (Loop A - fast vision).
 *
 * Runs on a dedicated executor with KEEP_ONLY_LATEST backpressure. Frames are skipped according to the
 * adaptive rate, the ImageProxy is always closed in `finally`, and no full-resolution Bitmap is created.
 */
class LiveFrameAnalyzer(
    private val analyzer: DefaultSceneAnalyzer,
    private val orientation: StateFlow<DeviceOrientation>,
    private val rate: AdaptiveRateController,
    private val diagnostics: Diagnostics,
    private val onSnapshot: (SceneSnapshot, FrameContext) -> Unit,
) : ImageAnalysis.Analyzer {

    @Volatile var enabled: Boolean = true
    private var lastLabelerAt = 0L

    /**
     * When set, the next processed frame is also encoded as a small upright JPEG for local AI.
     * Only requested on meaningful scene changes - never for every frame.
     */
    @Volatile var aiFrameRequest: kotlinx.coroutines.CompletableDeferred<ByteArray?>? = null

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        try {
            if (!enabled || !rate.shouldProcess(now)) {
                diagnostics.frameDropped()
                return
            }
            val o = orientation.value
            val rotation = (image.imageInfo.rotationDegrees + o.worldRotationCw) % 360
            val luma = FrameConverter.luma(image, rotation)
            val chroma = FrameConverter.chroma(image, rotation)
            val media = image.image
            val rotated = rotation == 90 || rotation == 270
            val input = media?.let {
                PlatformInput(
                    InputImage.fromMediaImage(it, rotation),
                    if (rotated) image.height else image.width,
                    if (rotated) image.width else image.height,
                )
            }
            // Scene labeling is slower and changes slowly: run it about once a second.
            val runLabeler = now - lastLabelerAt > 1_000
            if (runLabeler) lastLabelerAt = now
            val frame = AnalysisFrame(
                timestamp = System.currentTimeMillis(),
                luma = luma,
                chroma = chroma,
                worldRotation = o.worldRotationCw,
                deviceTiltDegrees = o.tiltDegrees,
                deviceStability = o.stability,
                platformImage = input,
                platformRotation = rotation,
                runSceneClassifier = runLabeler,
            )
            // The analysis executor is a dedicated thread; blocking here is the backpressure we want.
            val snapshot = runBlocking { analyzer.analyze(frame) }
            val total = SystemClock.elapsedRealtime() - now
            rate.recordProcessing(total)
            diagnostics.frameProcessed(SystemClock.elapsedRealtime(), total, analyzer.lastDetectorMs, rate.currentIntervalMs)
            onSnapshot(snapshot, FrameContext(o.worldRotationCw, luma, o.tiltDegrees))
            aiFrameRequest?.let { request ->
                aiFrameRequest = null
                request.complete(runCatching { encodeForAi(image, rotation) }.getOrNull())
            }
        } catch (t: Throwable) {
            Log.w("LumenVision", "Frame analysis failed", t)
        } finally {
            image.close()
        }
    }

    private fun encodeForAi(image: ImageProxy, rotation: Int): ByteArray {
        val full = image.toBitmap()
        val scale = 448f / maxOf(full.width, full.height)
        val m = android.graphics.Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        val small = android.graphics.Bitmap.createBitmap(full, 0, 0, full.width, full.height, m, true)
        if (small != full) full.recycle()
        val out = java.io.ByteArrayOutputStream()
        small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        small.recycle()
        return out.toByteArray()
    }
}

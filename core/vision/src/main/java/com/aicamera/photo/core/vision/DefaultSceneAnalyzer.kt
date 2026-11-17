package com.aicamera.photo.core.vision

import com.aicamera.photo.domain.AnalysisFrame
import com.aicamera.photo.domain.ConfidenceLevel
import com.aicamera.photo.domain.HorizonEstimate
import com.aicamera.photo.domain.HorizonSource
import com.aicamera.photo.domain.SceneAnalyzer
import com.aicamera.photo.domain.SceneSnapshot
import com.aicamera.photo.domain.SceneType
import kotlin.math.abs

/**
 * Combines ML Kit detections with custom CV into a [SceneSnapshot].
 * Not thread-safe; use one instance per analysis thread.
 */
class DefaultSceneAnalyzer(
    private val detectors: MlKitDetectors?,
    private val signals: ImageSignals = ImageSignals(),
    private val trackMotion: Boolean = true,
) : SceneAnalyzer {

    private var cachedLabels: List<SceneLabel> = emptyList()
    private var lastSceneType = SceneType.UNKNOWN

    /** Time spent in ML detectors during the last [analyze] call. */
    var lastDetectorMs: Long = 0
        private set

    override suspend fun analyze(frame: AnalysisFrame): SceneSnapshot {
        val input = frame.platformImage as? PlatformInput
        val t0 = System.nanoTime()
        val detections = if (detectors != null && input != null) {
            detectors.detect(input, frame.runSceneClassifier, lastSceneType == SceneType.ANIMAL)
        } else Detections(emptyList(), null)
        lastDetectorMs = (System.nanoTime() - t0) / 1_000_000
        detections.labels?.let { cachedLabels = it }

        val subjects = detections.subjects
        val primaryGuess = subjects
            .filter { it.confidence >= ConfidenceLevel.MEDIUM_THRESHOLD }
            .maxByOrNull { it.importance }
        val cv = signals.compute(frame.luma, frame.chroma, subjects, primaryGuess, frame.deviceStability, trackMotion)

        val horizon = fuseHorizon(frame.deviceTiltDegrees, cv.horizonLine)
        val (type, typeConfidence) = SceneClassifier.classify(cachedLabels, subjects, cv.signals, horizon?.isVisibleLine == true)
        lastSceneType = type
        val lighting = SceneClassifier.lighting(cv.signals, primaryGuess)

        return SceneSnapshot(
            sceneType = type,
            sceneConfidence = typeConfidence,
            lighting = lighting,
            subjects = subjects,
            horizon = horizon,
            visualSignals = cv.signals,
            timestamp = frame.timestamp,
            sceneLabels = cachedLabels.sortedByDescending { it.confidence }.take(5).map { it.text },
        )
    }

    fun reset() {
        signals.reset()
        cachedLabels = emptyList()
        lastSceneType = SceneType.UNKNOWN
    }

    companion object {
        /**
         * The device roll sensor is the most reliable measure of "level". The image line provides the
         * horizon position and a fallback tilt when no sensor value is available.
         */
        fun fuseHorizon(sensorTilt: Float?, line: HorizonLine?): HorizonEstimate? {
            val lineOk = line != null && line.confidence >= 0.35f
            return when {
                sensorTilt != null && lineOk -> HorizonEstimate(
                    tiltDegrees = sensorTilt,
                    positionY = line!!.positionY,
                    confidence = 0.9f,
                    lineConfidence = line.confidence,
                    source = HorizonSource.FUSED,
                )
                sensorTilt != null -> HorizonEstimate(sensorTilt, null, 0.85f, 0f, HorizonSource.DEVICE_SENSOR)
                lineOk && abs(line!!.angleDegrees) <= 20f -> HorizonEstimate(
                    tiltDegrees = line.angleDegrees,
                    positionY = line.positionY,
                    confidence = line.confidence * 0.8f,
                    lineConfidence = line.confidence,
                    source = HorizonSource.IMAGE,
                )
                else -> null
            }
        }
    }
}

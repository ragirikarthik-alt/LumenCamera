package com.aicamera.photo.domain

/** Grayscale analysis image in world-upright orientation, downsampled for fast CV. */
class LumaImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(pixels.size >= width * height) { "Luma buffer too small" }
    }

    fun at(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF
}

/** Coarse chroma averages (U/V centred on 0, range about -1..1) on a grid, world-upright. */
class ChromaGrid(val cols: Int, val rows: Int, val u: FloatArray, val v: FloatArray) {
    fun meanU(): Float = if (u.isEmpty()) 0f else u.average().toFloat()
    fun meanV(): Float = if (v.isEmpty()) 0f else v.average().toFloat()
}

/**
 * One frame handed to the [SceneAnalyzer].
 *
 * @param platformImage an opaque platform handle (e.g. a camera image) that platform detectors may use.
 *   It is only valid during [SceneAnalyzer.analyze].
 * @param platformRotation clockwise rotation a platform detector must apply to [platformImage] to
 *   make it world-upright.
 * @param worldRotation clockwise rotation (0/90/180/270) applied to the display-upright frame to make
 *   it world-upright (non-zero when the phone is held sideways).
 * @param deviceTiltDegrees residual device roll relative to the nearest quarter turn, if a sensor is available.
 */
class AnalysisFrame(
    val timestamp: Long,
    val luma: LumaImage,
    val chroma: ChromaGrid?,
    val worldRotation: Int,
    val deviceTiltDegrees: Float?,
    val deviceStability: Float,
    val platformImage: Any? = null,
    val platformRotation: Int = 0,
    /** Run the slower scene classifier on this frame. */
    val runSceneClassifier: Boolean = true,
)

interface SceneAnalyzer {
    suspend fun analyze(frame: AnalysisFrame): SceneSnapshot
}

interface CompositionEngine {
    suspend fun generateCandidates(scene: SceneSnapshot): List<CompositionCandidate>
    fun calculateAlignment(scene: SceneSnapshot, target: CompositionCandidate): AlignmentResult
}

interface PhotographyCoach {
    suspend fun generateRecommendations(
        scene: SceneSnapshot,
        candidates: List<CompositionCandidate>,
    ): List<CoachingRecommendation>
}

/** Injectable clock so deterministic logic is testable. */
fun interface Clock {
    fun now(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

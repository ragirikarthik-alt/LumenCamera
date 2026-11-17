package com.aicamera.photo.domain

import kotlinx.serialization.Serializable

@Serializable
enum class SceneType(val label: String) {
    PORTRAIT("Portrait"),
    GROUP("Group of people"),
    LANDSCAPE("Landscape"),
    ARCHITECTURE("Architecture / city"),
    STREET("Street"),
    FOOD("Food"),
    ANIMAL("Animal"),
    CLOSE_UP("Close-up / object"),
    INDOOR("Indoor"),
    NIGHT("Night / low light"),
    UNKNOWN("General scene"),
}

@Serializable
enum class LightingCondition(val label: String) {
    BALANCED("Balanced light"),
    LOW_LIGHT("Low light"),
    BRIGHT_HARSH("Bright, harsh light"),
    BACKLIT("Backlit"),
    HIGH_CONTRAST("High contrast"),
    WARM_SOFT("Warm, soft light"),
}

@Serializable
enum class SubjectKind { FACE, PERSON, ANIMAL, OBJECT }

/** How confident the system is. Used to choose wording; low-confidence detections are never stated as fact. */
@Serializable
enum class ConfidenceLevel {
    LOW, MEDIUM, HIGH;

    companion object {
        const val HIGH_THRESHOLD = 0.75f
        const val MEDIUM_THRESHOLD = 0.5f
        fun of(value: Float): ConfidenceLevel = when {
            value >= HIGH_THRESHOLD -> HIGH
            value >= MEDIUM_THRESHOLD -> MEDIUM
            else -> LOW
        }
    }
}

@Serializable
data class DetectedSubject(
    val trackingId: Int,
    val label: String,
    val confidence: Float,
    val boundingBox: NormalizedRect,
    val importance: Float,
    val kind: SubjectKind = SubjectKind.OBJECT,
) {
    val confidenceLevel: ConfidenceLevel get() = ConfidenceLevel.of(confidence)

    /**
     * The point of the subject that composition places on a target: the eyes of a face, the head of a
     * person, the centre of anything else. (Placing a person's box centre on the upper third would put
     * their stomach there and push the head out of the frame.)
     */
    val anchor: NormalizedPoint
        get() = when (kind) {
            SubjectKind.FACE -> NormalizedPoint(boundingBox.center.x, boundingBox.top + boundingBox.height * 0.4f)
            SubjectKind.PERSON -> NormalizedPoint(boundingBox.center.x, boundingBox.top + boundingBox.height * 0.12f)
            else -> boundingBox.center
        }

    /** Label wording that respects confidence: "person" vs "possible person" vs "subject". */
    fun describe(): String = when {
        label == "subject" -> "subject"
        else -> describeByConfidence()
    }

    private fun describeByConfidence(): String = when (confidenceLevel) {
        ConfidenceLevel.HIGH -> label
        ConfidenceLevel.MEDIUM -> "possible $label"
        ConfidenceLevel.LOW -> "subject"
    }
}

@Serializable
enum class HorizonSource { IMAGE, DEVICE_SENSOR, FUSED }

/**
 * @param tiltDegrees deviation from level. Positive means the horizon slopes down to the right;
 *   rotate the phone clockwise to correct it.
 * @param positionY where the horizon crosses the frame centre (normalized), if a horizon line is visible.
 * @param lineConfidence confidence that a horizon-like line is visible in the image.
 */
@Serializable
data class HorizonEstimate(
    val tiltDegrees: Float,
    val positionY: Float?,
    val confidence: Float,
    val lineConfidence: Float,
    val source: HorizonSource,
) {
    val isVisibleLine: Boolean get() = positionY != null && lineConfidence >= ConfidenceLevel.MEDIUM_THRESHOLD
}

@Serializable
enum class FrameEdge(val label: String) { LEFT("left"), RIGHT("right"), TOP("top"), BOTTOM("bottom") }

@Serializable
data class EdgeDistraction(
    val edge: FrameEdge,
    val region: NormalizedRect,
    val description: String,
    val strength: Float,
)

/** Background clutter merging with the primary subject (e.g. a pole "growing" out of a head). */
@Serializable
data class SeparationIssue(
    val subjectTrackingId: Int,
    val clutteredSide: FrameEdge,
    /** Direction the photographer should step to get a cleaner background, if determinable. */
    val stepDirection: FrameEdge?,
    val strength: Float,
    val verticalLineBehindSubject: Boolean,
)

@Serializable
data class VisualSignals(
    val meanLuma: Float = 0.5f,
    val highlightClipping: Float = 0f,
    val shadowClipping: Float = 0f,
    val contrast: Float = 0.2f,
    val sharpness: Float = 0.5f,
    val motion: Float = 0f,
    val deviceStability: Float = 1f,
    val clutter: Float = 0.3f,
    val symmetry: Float = 0f,
    val leadingLines: List<LineSegment> = emptyList(),
    val lineConvergence: NormalizedPoint? = null,
    val diagonalStrength: Float = 0f,
    val framingRegion: NormalizedRect? = null,
    val foregroundDetail: Float = 0f,
    val skyDetail: Float = 0f,
    val warmth: Float = 0f,
    val edgeDistractions: List<EdgeDistraction> = emptyList(),
    val separationIssue: SeparationIssue? = null,
    val subjectLuma: Float? = null,
    val backgroundLuma: Float? = null,
)

/** Timestamped structured interpretation of the latest analysed frame. */
@Serializable
data class SceneSnapshot(
    val sceneType: SceneType,
    val sceneConfidence: Float,
    val lighting: LightingCondition,
    val subjects: List<DetectedSubject>,
    val horizon: HorizonEstimate?,
    val visualSignals: VisualSignals,
    val timestamp: Long,
    val sceneLabels: List<String> = emptyList(),
) {
    val primarySubject: DetectedSubject?
        get() = subjects
            .filter { it.confidence >= ConfidenceLevel.MEDIUM_THRESHOLD || it.kind == SubjectKind.FACE }
            .maxByOrNull { it.importance }

    companion object {
        fun empty(timestamp: Long = 0L) = SceneSnapshot(
            sceneType = SceneType.UNKNOWN,
            sceneConfidence = 0f,
            lighting = LightingCondition.BALANCED,
            subjects = emptyList(),
            horizon = null,
            visualSignals = VisualSignals(),
            timestamp = timestamp,
        )
    }
}

package com.aicamera.photo.domain

import kotlinx.serialization.Serializable

@Serializable
enum class CompositionStrategy(val displayName: String, val summary: String, val lessonId: String) {
    RULE_OF_THIRDS("Off-centre", "Put the subject a little to one side - it looks more lively.", LessonIds.THIRDS),
    CENTERED_SYMMETRY("Centred & balanced", "Put the subject in the middle so both sides match.", LessonIds.SYMMETRY),
    LEADING_LINES("Follow the lines", "Use roads, edges or fences that point to the subject.", LessonIds.LEADING_LINES),
    NEGATIVE_SPACE("Lots of empty space", "Keep the subject small with plain space around it.", LessonIds.NEGATIVE_SPACE),
    FRAME_WITHIN_FRAME("Natural frame", "Shoot through a doorway, window or branches around the subject.", LessonIds.FRAMING),
    LAYERED_LANDSCAPE("Near and far", "Show something close, something far, and the sky.", LessonIds.FOREGROUND_DEPTH),
    DIAGONAL("Diagonal", "Let a line run corner to corner for energy.", LessonIds.DIAGONALS),
    FILL_THE_FRAME("Get closer", "Make the subject big so it fills the picture.", LessonIds.FILL_FRAME),
}

@Serializable
data class SubjectTarget(
    /** Tracking id of the subject this target is for, or null for "the primary subject". */
    val trackingId: Int?,
    val target: NormalizedPoint,
    /** Desired fraction of the frame area the subject should occupy, if size matters for the strategy. */
    val desiredArea: Float? = null,
)

@Serializable
data class CompositionCandidate(
    val id: String,
    val strategy: CompositionStrategy,
    val subjectTargets: List<SubjectTarget>,
    val horizonTarget: Float?,
    val explanation: String,
    /** Ranking heuristic only - not an objective measure of artistic quality. */
    val suitability: Float,
    /** Extra guide lines for the overlay (e.g. leading lines, symmetry axis). */
    val guides: List<LineSegment> = emptyList(),
    val targetRegion: NormalizedRect? = null,
)

@Serializable
enum class AlignmentState(val label: String) {
    NOT_ALIGNED("Move to the circle"),
    NEARLY_ALIGNED("Almost there"),
    READY("Ready - take the photo"),
}

@Serializable
data class AlignmentResult(
    val state: AlignmentState,
    val score: Float,
    val subjectCurrent: NormalizedPoint?,
    val subjectTarget: NormalizedPoint?,
    val horizonTiltError: Float?,
    val horizonPositionError: Float?,
    /** The single most useful movement hint, e.g. "Aim a little left". */
    val hint: String,
    /** Technical problems that should be fixed before capture (blur, motion). */
    val technicalBlockers: List<String> = emptyList(),
    val subjectLost: Boolean = false,
)

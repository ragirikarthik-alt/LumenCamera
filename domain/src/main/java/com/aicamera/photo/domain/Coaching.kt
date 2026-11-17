package com.aicamera.photo.domain

import kotlinx.serialization.Serializable

@Serializable
enum class RecommendationType(val label: String, val lessonId: String) {
    HORIZON("Horizon", LessonIds.HORIZON),
    SUBJECT_PLACEMENT("Placement", LessonIds.THIRDS),
    SEPARATION("Separation", LessonIds.SEPARATION),
    FOREGROUND_DEPTH("Depth", LessonIds.FOREGROUND_DEPTH),
    EXPOSURE("Exposure", LessonIds.EXPOSURE),
    EDGE_DISTRACTION("Edges", LessonIds.CLEAN_EDGES),
    STABILITY("Stability", LessonIds.MOTION_BLUR),
    VIEWPOINT("Viewpoint", LessonIds.CAMERA_HEIGHT),
    NEGATIVE_SPACE("Space", LessonIds.NEGATIVE_SPACE),
    LENS_CHOICE("Lens", LessonIds.FOCAL_LENGTH),
    LIGHT_DIRECTION("Light", LessonIds.LIGHT_DIRECTION),
    FOCUS("Focus", LessonIds.FOCUS),
    CLOSER("Get closer", LessonIds.FILL_FRAME),
}

@Serializable
enum class RecommendationSource { RULES, LOCAL_AI }

@Serializable
data class CoachingRecommendation(
    val id: String,
    val title: String,
    val instruction: String,
    val priority: Float,
    val expiresAt: Long,
    val type: RecommendationType,
    val confidence: Float = 0.8f,
    val source: RecommendationSource = RecommendationSource.RULES,
    val rationale: String = "",
    val lessonId: String? = type.lessonId,
    /** Where in the frame the suggestion refers to (e.g. the subject to tap for exposure). */
    val focusPoint: NormalizedPoint? = null,
)

@Serializable
enum class CoachingIntensity(val label: String, val description: String) {
    MINIMAL("Minimal", "Only important, high-confidence suggestions, rarely."),
    BALANCED("Balanced", "Timely suggestions with sensible pauses."),
    ACTIVE("Active", "More frequent ideas while you explore."),
}

/** Lifecycle of a continuous-coaching suggestion (spec section 12). */
@Serializable
enum class CoachingPhase { NEW, DISPLAYED, USER_RESPONDING, ACHIEVED, DISMISSED, EXPIRED, REPLACED }

@Serializable
data class CoachingEventRecord(
    val recommendation: CoachingRecommendation,
    val phase: CoachingPhase,
    val timestamp: Long,
)

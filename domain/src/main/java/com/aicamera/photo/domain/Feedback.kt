package com.aicamera.photo.domain

import kotlinx.serialization.Serializable

/** Measurable technical facts are kept separate from subjective artistic observations. */
@Serializable
enum class FindingCategory(val label: String) { TECHNICAL("Technical"), ARTISTIC("Artistic") }

@Serializable
enum class FindingSeverity { STRENGTH, INFO, SUGGESTION, ISSUE }

@Serializable
data class PhotoFinding(
    val id: String,
    val category: FindingCategory,
    val severity: FindingSeverity,
    val title: String,
    val detail: String,
    val confidence: Float,
    val lessonId: String? = null,
    /** For technical findings, the measured value in plain words (e.g. "2.4% of pixels clipped"). */
    val measurement: String? = null,
    val source: RecommendationSource = RecommendationSource.RULES,
)

@Serializable
data class PhotoFeedback(
    val summary: String,
    val findings: List<PhotoFinding>,
    val nextLessonId: String?,
    val aiUsed: Boolean,
    val createdAt: Long,
) {
    val technical: List<PhotoFinding> get() = findings.filter { it.category == FindingCategory.TECHNICAL }
    val artistic: List<PhotoFinding> get() = findings.filter { it.category == FindingCategory.ARTISTIC }
}

@Serializable
data class CaptureMetadata(
    val width: Int = 0,
    val height: Int = 0,
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val aperture: Float? = null,
    val focalLengthMm: Float? = null,
    val focalLength35mm: Int? = null,
    val lensLabel: String? = null,
    val exposureCompensation: Float? = null,
    val flash: Boolean? = null,
    val whiteBalance: String? = null,
    val deviceModel: String? = null,
) {
    fun shutterLabel(): String? = exposureTimeNs?.let { ns ->
        val seconds = ns / 1_000_000_000.0
        if (seconds >= 0.5) String.format("%.1fs", seconds) else "1/${(1.0 / seconds).toInt().coerceAtLeast(1)}s"
    }
}

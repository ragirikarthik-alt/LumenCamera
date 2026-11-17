package com.aicamera.photo.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room stores structured metadata only. Media lives in files; entities hold stable file references.
 * Complex values are stored as versioned JSON produced by kotlinx.serialization.
 */

@Entity(tableName = "capture_sessions")
data class CaptureSessionEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val endedAt: Long?,
    val mode: String,
    val cameraSettingsJson: String?,
)

@Entity(
    tableName = "photos",
    foreignKeys = [ForeignKey(
        entity = CaptureSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"],
        onDelete = ForeignKey.SET_NULL,
    )],
    indices = [Index("sessionId"), Index("capturedAt"), Index("lessonId")],
)
data class PhotoEntity(
    @PrimaryKey val id: String,
    val sessionId: String?,
    val originalPath: String,
    val rawPath: String?,
    val thumbnailPath: String?,
    val capturedAt: Long,
    val width: Int,
    val height: Int,
    val mode: String,
    val metadataJson: String?,
    /** Academy practice exercise this photo belongs to, if any. */
    val lessonId: String? = null,
    val exportedUri: String? = null,
)

@Entity(
    tableName = "scene_analyses",
    foreignKeys = [ForeignKey(
        entity = PhotoEntity::class, parentColumns = ["id"], childColumns = ["photoId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("photoId"), Index("sessionId")],
)
data class SceneAnalysisEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val photoId: String?,
    val sessionId: String?,
    val createdAt: Long,
    /** "preview" for the live frame at capture time, "final" for the full-resolution still. */
    val stage: String,
    val sceneType: String,
    val lighting: String,
    val snapshotJson: String,
)

@Entity(
    tableName = "compositions",
    foreignKeys = [ForeignKey(
        entity = PhotoEntity::class, parentColumns = ["id"], childColumns = ["photoId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("photoId")],
)
data class CompositionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val photoId: String,
    val strategy: String,
    val candidateJson: String,
    val suitability: Float,
    val alignmentState: String?,
    val alignmentScore: Float?,
)

@Entity(
    tableName = "coaching_events",
    foreignKeys = [
        ForeignKey(entity = CaptureSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PhotoEntity::class, parentColumns = ["id"], childColumns = ["photoId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("sessionId"), Index("photoId"), Index("type")],
)
data class CoachingEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val photoId: String?,
    val recommendationId: String,
    val type: String,
    val title: String,
    val instruction: String,
    val phase: String,
    val source: String,
    val timestamp: Long,
    val contextJson: String?,
)

@Entity(
    tableName = "photo_feedback",
    foreignKeys = [ForeignKey(
        entity = PhotoEntity::class, parentColumns = ["id"], childColumns = ["photoId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class PhotoFeedbackEntity(
    @PrimaryKey val photoId: String,
    val summary: String,
    val feedbackJson: String,
    val nextLessonId: String?,
    val aiUsed: Boolean,
    val createdAt: Long,
)

@Entity(
    tableName = "edits",
    foreignKeys = [ForeignKey(
        entity = PhotoEntity::class, parentColumns = ["id"], childColumns = ["photoId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("photoId")],
)
data class EditEntity(
    @PrimaryKey val id: String,
    val photoId: String,
    /** Always the untouched original; edits never overwrite it. */
    val originalPath: String,
    val recipeJson: String,
    val recipeVersion: Int,
    val profileId: String,
    val outputPath: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val exportedUri: String?,
)

@Entity(tableName = "lesson_progress")
data class LessonProgressEntity(
    @PrimaryKey val lessonId: String,
    val progress: Float,
    val completed: Boolean,
    val practicePhotoIdsJson: String,
    val startedAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
)

@Entity(tableName = "model_benchmarks")
data class ModelBenchmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelId: String,
    val backend: String,
    val deviceModel: String,
    val success: Boolean,
    val error: String?,
    val coldStartMs: Long?,
    val firstResponseMs: Long?,
    val warmResponseMs: Long?,
    val imageInputWorked: Boolean,
    val peakMemoryMb: Int?,
    val thermalBefore: Int,
    val thermalAfter: Int,
    val createdAt: Long,
)

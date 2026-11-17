package com.aicamera.photo.core.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class PhotoWithDetails(
    @Embedded val photo: PhotoEntity,
    @Relation(parentColumn = "id", entityColumn = "photoId") val analyses: List<SceneAnalysisEntity>,
    @Relation(parentColumn = "id", entityColumn = "photoId") val compositions: List<CompositionEntity>,
    @Relation(parentColumn = "id", entityColumn = "photoId") val feedback: PhotoFeedbackEntity?,
    @Relation(parentColumn = "id", entityColumn = "photoId") val edits: List<EditEntity>,
    @Relation(parentColumn = "id", entityColumn = "photoId") val coachingEvents: List<CoachingEventEntity>,
)

data class PhotoListItem(
    val id: String,
    val originalPath: String,
    val thumbnailPath: String?,
    val rawPath: String?,
    val capturedAt: Long,
    val mode: String,
    val lessonId: String?,
    val editCount: Int,
    val latestEditPath: String?,
    val hasFeedback: Boolean,
)

@Dao
interface PhotoDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(photo: PhotoEntity)

    @Query("UPDATE photos SET thumbnailPath = :path WHERE id = :id")
    suspend fun setThumbnail(id: String, path: String)

    @Query("UPDATE photos SET exportedUri = :uri WHERE id = :id")
    suspend fun setExportedUri(id: String, uri: String)

    @Query("UPDATE photos SET lessonId = :lessonId WHERE id = :id")
    suspend fun setLesson(id: String, lessonId: String?)

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun get(id: String): PhotoEntity?

    @Query("SELECT * FROM photos ORDER BY capturedAt DESC")
    suspend fun all(): List<PhotoEntity>

    @Query(
        """
        SELECT p.id, p.originalPath, p.thumbnailPath, p.rawPath, p.capturedAt, p.mode, p.lessonId,
          (SELECT COUNT(*) FROM edits e WHERE e.photoId = p.id) AS editCount,
          (SELECT e2.outputPath FROM edits e2 WHERE e2.photoId = p.id AND e2.outputPath IS NOT NULL ORDER BY e2.updatedAt DESC LIMIT 1) AS latestEditPath,
          EXISTS(SELECT 1 FROM photo_feedback f WHERE f.photoId = p.id) AS hasFeedback
        FROM photos p ORDER BY p.capturedAt DESC
        """
    )
    fun observeList(): Flow<List<PhotoListItem>>

    @Transaction
    @Query("SELECT * FROM photos WHERE id = :id")
    fun observeDetails(id: String): Flow<PhotoWithDetails?>

    @Query("SELECT * FROM photos WHERE thumbnailPath IS NULL")
    suspend fun missingThumbnails(): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE lessonId = :lessonId ORDER BY capturedAt DESC")
    fun observeForLesson(lessonId: String): Flow<List<PhotoEntity>>

    @Query("SELECT id FROM photos ORDER BY capturedAt DESC LIMIT 1")
    fun observeLatestId(): Flow<String?>

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM photos")
    suspend fun deleteAll()
}

@Dao
interface SessionDao {
    @Upsert
    suspend fun upsert(session: CaptureSessionEntity)

    @Query("UPDATE capture_sessions SET endedAt = :endedAt WHERE id = :id")
    suspend fun end(id: String, endedAt: Long)

    @Query("SELECT * FROM capture_sessions WHERE id = :id")
    suspend fun get(id: String): CaptureSessionEntity?
}

@Dao
interface AnalysisDao {
    @Insert
    suspend fun insertAnalysis(analysis: SceneAnalysisEntity): Long

    @Insert
    suspend fun insertComposition(composition: CompositionEntity): Long

    @Query("SELECT * FROM scene_analyses WHERE photoId = :photoId ORDER BY createdAt DESC")
    suspend fun analysesFor(photoId: String): List<SceneAnalysisEntity>

    @Query("SELECT * FROM compositions WHERE photoId = :photoId")
    suspend fun compositionsFor(photoId: String): List<CompositionEntity>
}

data class TypeCount(val type: String, val phase: String, val count: Int)

@Dao
interface CoachingDao {
    @Insert
    suspend fun insert(event: CoachingEventEntity)

    @Insert
    suspend fun insertAll(events: List<CoachingEventEntity>)

    @Query("UPDATE coaching_events SET photoId = :photoId WHERE sessionId = :sessionId AND photoId IS NULL AND timestamp >= :since")
    suspend fun attachToPhoto(sessionId: String, photoId: String, since: Long)

    @Query("SELECT type, phase, COUNT(*) AS count FROM coaching_events GROUP BY type, phase")
    fun observeTypeCounts(): Flow<List<TypeCount>>

    @Query("DELETE FROM coaching_events")
    suspend fun deleteAll()
}

data class LessonCount(val nextLessonId: String, val count: Int)

@Dao
interface FeedbackDao {
    @Upsert
    suspend fun upsert(feedback: PhotoFeedbackEntity)

    @Query("SELECT * FROM photo_feedback WHERE photoId = :photoId")
    fun observe(photoId: String): Flow<PhotoFeedbackEntity?>

    @Query("SELECT * FROM photo_feedback WHERE photoId = :photoId")
    suspend fun get(photoId: String): PhotoFeedbackEntity?

    @Query("SELECT * FROM photo_feedback ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PhotoFeedbackEntity>>

    @Query("SELECT nextLessonId, COUNT(*) AS count FROM photo_feedback WHERE nextLessonId IS NOT NULL GROUP BY nextLessonId ORDER BY count DESC")
    fun observeLessonCounts(): Flow<List<LessonCount>>
}

@Dao
interface EditDao {
    @Upsert
    suspend fun upsert(edit: EditEntity)

    @Query("SELECT * FROM edits WHERE id = :id")
    suspend fun get(id: String): EditEntity?

    @Query("SELECT * FROM edits WHERE photoId = :photoId ORDER BY updatedAt DESC")
    fun observeForPhoto(photoId: String): Flow<List<EditEntity>>

    @Query("SELECT * FROM edits WHERE photoId = :photoId ORDER BY updatedAt DESC")
    suspend fun forPhoto(photoId: String): List<EditEntity>

    @Query("SELECT * FROM edits")
    suspend fun all(): List<EditEntity>

    @Query("UPDATE edits SET exportedUri = :uri WHERE id = :id")
    suspend fun setExportedUri(id: String, uri: String)

    @Query("DELETE FROM edits WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface LessonDao {
    @Upsert
    suspend fun upsert(progress: LessonProgressEntity)

    @Query("SELECT * FROM lesson_progress WHERE lessonId = :lessonId")
    suspend fun get(lessonId: String): LessonProgressEntity?

    @Query("SELECT * FROM lesson_progress")
    fun observeAll(): Flow<List<LessonProgressEntity>>

    @Query("DELETE FROM lesson_progress")
    suspend fun deleteAll()
}

@Dao
interface BenchmarkDao {
    @Insert
    suspend fun insert(result: ModelBenchmarkEntity): Long

    @Query("SELECT * FROM model_benchmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ModelBenchmarkEntity>>

    @Query("SELECT * FROM model_benchmarks WHERE modelId = :modelId AND success = 1 ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestSuccessful(modelId: String): ModelBenchmarkEntity?
}

package com.aicamera.photo.core.data

import androidx.room.withTransaction
import com.aicamera.photo.core.data.db.CaptureSessionEntity
import com.aicamera.photo.core.data.db.CoachingEventEntity
import com.aicamera.photo.core.data.db.CompositionEntity
import com.aicamera.photo.core.data.db.EditEntity
import com.aicamera.photo.core.data.db.LessonProgressEntity
import com.aicamera.photo.core.data.db.LumenDatabase
import com.aicamera.photo.core.data.db.PhotoEntity
import com.aicamera.photo.core.data.db.PhotoFeedbackEntity
import com.aicamera.photo.core.data.db.SceneAnalysisEntity
import com.aicamera.photo.domain.AlignmentResult
import com.aicamera.photo.domain.CaptureMetadata
import com.aicamera.photo.domain.CoachingEventRecord
import com.aicamera.photo.domain.CompositionCandidate
import com.aicamera.photo.domain.FilmRecipe
import com.aicamera.photo.domain.PhotoFeedback
import com.aicamera.photo.domain.SceneSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.UUID

/** Everything known about one shutter press, saved atomically. */
data class CaptureRecord(
    val photoId: String,
    val sessionId: String,
    val originalFile: File,
    val rawFile: File?,
    val capturedAt: Long,
    val mode: CameraMode,
    val metadata: CaptureMetadata,
    val previewScene: SceneSnapshot?,
    val composition: CompositionCandidate?,
    val alignment: AlignmentResult?,
    val lessonId: String?,
)

class PhotoRepository(
    private val db: LumenDatabase,
    private val storage: MediaStorage,
) {
    private val photos = db.photoDao()

    fun observeList() = photos.observeList()
    fun observeDetails(id: String) = photos.observeDetails(id)
    fun observeLatestId() = photos.observeLatestId()
    fun observeForLesson(lessonId: String) = photos.observeForLesson(lessonId)
    suspend fun get(id: String) = photos.get(id)
    suspend fun missingThumbnails() = photos.missingThumbnails()
    suspend fun setThumbnail(id: String, path: String) = photos.setThumbnail(id, path)
    suspend fun setExportedUri(id: String, uri: String) = photos.setExportedUri(id, uri)
    suspend fun setLesson(id: String, lessonId: String?) = photos.setLesson(id, lessonId)

    suspend fun startSession(mode: CameraMode, settingsJson: String?): String {
        val id = UUID.randomUUID().toString()
        db.sessionDao().upsert(CaptureSessionEntity(id, System.currentTimeMillis(), null, mode.name, settingsJson))
        return id
    }

    suspend fun endSession(id: String) = db.sessionDao().end(id, System.currentTimeMillis())

    suspend fun saveCapture(record: CaptureRecord) = db.withTransaction {
        if (db.sessionDao().get(record.sessionId) == null) {
            db.sessionDao().upsert(CaptureSessionEntity(record.sessionId, record.capturedAt, null, record.mode.name, null))
        }
        photos.insert(
            PhotoEntity(
                id = record.photoId,
                sessionId = record.sessionId,
                originalPath = record.originalFile.absolutePath,
                rawPath = record.rawFile?.absolutePath,
                thumbnailPath = null,
                capturedAt = record.capturedAt,
                width = record.metadata.width,
                height = record.metadata.height,
                mode = record.mode.name,
                metadataJson = AppJson.encodeToString(CaptureMetadata.serializer(), record.metadata),
                lessonId = record.lessonId,
            )
        )
        record.previewScene?.let { scene ->
            db.analysisDao().insertAnalysis(scene.toEntity(record.photoId, record.sessionId, "preview"))
        }
        record.composition?.let { c ->
            db.analysisDao().insertComposition(
                CompositionEntity(
                    photoId = record.photoId,
                    strategy = c.strategy.name,
                    candidateJson = AppJson.encodeToString(CompositionCandidate.serializer(), c),
                    suitability = c.suitability,
                    alignmentState = record.alignment?.state?.name,
                    alignmentScore = record.alignment?.score,
                )
            )
        }
        // Link the coaching events from the last minute of the session to this photo.
        db.coachingDao().attachToPhoto(record.sessionId, record.photoId, record.capturedAt - 60_000)
    }

    suspend fun saveFinalAnalysis(photoId: String, scene: SceneSnapshot) {
        db.analysisDao().insertAnalysis(scene.toEntity(photoId, null, "final"))
    }

    suspend fun saveFeedback(photoId: String, feedback: PhotoFeedback) {
        db.feedbackDao().upsert(
            PhotoFeedbackEntity(
                photoId = photoId,
                summary = feedback.summary,
                feedbackJson = AppJson.encodeToString(PhotoFeedback.serializer(), feedback),
                nextLessonId = feedback.nextLessonId,
                aiUsed = feedback.aiUsed,
                createdAt = feedback.createdAt,
            )
        )
    }

    fun observeFeedback(photoId: String) = db.feedbackDao().observe(photoId)
    fun observeRecentFeedback(limit: Int = 50) = db.feedbackDao().observeRecent(limit)
    fun observeFeedbackLessonCounts() = db.feedbackDao().observeLessonCounts()

    /** Deletes the photo, its RAW, developed versions, thumbnail and all linked records. */
    suspend fun deletePhoto(id: String) {
        val photo = photos.get(id) ?: return
        val edits = db.editDao().forPhoto(id)
        photos.delete(id) // cascades to analyses, compositions, feedback, edits
        storage.deleteManagedFile(photo.originalPath)
        storage.deleteManagedFile(photo.rawPath)
        storage.deleteManagedFile(photo.thumbnailPath)
        edits.forEach { storage.deleteManagedFile(it.outputPath) }
    }

    suspend fun deleteAll() {
        photos.deleteAll()
        db.coachingDao().deleteAll()
        storage.deleteAllMedia()
    }

    companion object {
        fun decodeMetadata(json: String?): CaptureMetadata? =
            json?.let { runCatching { AppJson.decodeFromString(CaptureMetadata.serializer(), it) }.getOrNull() }

        fun decodeScene(json: String): SceneSnapshot? =
            runCatching { AppJson.decodeFromString(SceneSnapshot.serializer(), json) }.getOrNull()

        fun decodeCandidate(json: String): CompositionCandidate? =
            runCatching { AppJson.decodeFromString(CompositionCandidate.serializer(), json) }.getOrNull()

        fun decodeFeedback(json: String): PhotoFeedback? =
            runCatching { AppJson.decodeFromString(PhotoFeedback.serializer(), json) }.getOrNull()
    }
}

private fun SceneSnapshot.toEntity(photoId: String?, sessionId: String?, stage: String) = SceneAnalysisEntity(
    photoId = photoId,
    sessionId = sessionId,
    createdAt = System.currentTimeMillis(),
    stage = stage,
    sceneType = sceneType.name,
    lighting = lighting.name,
    snapshotJson = AppJson.encodeToString(SceneSnapshot.serializer(), this),
)

class CoachingRepository(private val db: LumenDatabase) {
    suspend fun record(sessionId: String, event: CoachingEventRecord) {
        val r = event.recommendation
        db.coachingDao().insert(
            CoachingEventEntity(
                sessionId = sessionId,
                photoId = null,
                recommendationId = r.id,
                type = r.type.name,
                title = r.title,
                instruction = r.instruction,
                phase = event.phase.name,
                source = r.source.name,
                timestamp = event.timestamp,
                contextJson = null,
            )
        )
    }

    fun observeTypeCounts() = db.coachingDao().observeTypeCounts()
}

class EditRepository(
    private val db: LumenDatabase,
    private val storage: MediaStorage,
) {
    fun observeForPhoto(photoId: String) = db.editDao().observeForPhoto(photoId)
    suspend fun get(id: String) = db.editDao().get(id)

    suspend fun save(editId: String?, photoId: String, originalPath: String, recipe: FilmRecipe, outputPath: String?): EditEntity {
        val now = System.currentTimeMillis()
        val existing = editId?.let { db.editDao().get(it) }
        if (existing?.outputPath != null && existing.outputPath != outputPath) storage.deleteManagedFile(existing.outputPath)
        val entity = EditEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            photoId = photoId,
            originalPath = originalPath,
            recipeJson = AppJson.encodeToString(FilmRecipe.serializer(), recipe),
            recipeVersion = FilmRecipe.VERSION,
            profileId = recipe.profileId,
            outputPath = outputPath,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            exportedUri = existing?.exportedUri,
        )
        db.editDao().upsert(entity)
        return entity
    }

    suspend fun setExportedUri(id: String, uri: String) = db.editDao().setExportedUri(id, uri)

    suspend fun delete(id: String) {
        val e = db.editDao().get(id) ?: return
        db.editDao().delete(id)
        storage.deleteManagedFile(e.outputPath)
    }

    companion object {
        fun decodeRecipe(json: String): FilmRecipe =
            runCatching { AppJson.decodeFromString(FilmRecipe.serializer(), json) }.getOrDefault(FilmRecipe())
    }
}

class LessonProgressRepository(private val db: LumenDatabase) {
    private val dao = db.lessonDao()
    private val idList = ListSerializer(String.serializer())

    fun observeAll(): Flow<List<LessonProgressEntity>> = dao.observeAll()

    fun practicePhotos(entity: LessonProgressEntity): List<String> =
        runCatching { AppJson.decodeFromString(idList, entity.practicePhotoIdsJson) }.getOrDefault(emptyList())

    suspend fun markOpened(lessonId: String) {
        val now = System.currentTimeMillis()
        val e = dao.get(lessonId)
        dao.upsert(
            e?.copy(progress = maxOf(e.progress, 0.34f), updatedAt = now)
                ?: LessonProgressEntity(lessonId, 0.34f, false, "[]", now, now, null)
        )
    }

    suspend fun addPracticePhoto(lessonId: String, photoId: String) {
        val now = System.currentTimeMillis()
        val e = dao.get(lessonId) ?: LessonProgressEntity(lessonId, 0.34f, false, "[]", now, now, null)
        val ids = (practicePhotos(e) + photoId).distinct()
        dao.upsert(e.copy(practicePhotoIdsJson = AppJson.encodeToString(idList, ids), progress = maxOf(e.progress, 0.67f), updatedAt = now))
    }

    suspend fun setCompleted(lessonId: String, completed: Boolean) {
        val now = System.currentTimeMillis()
        val e = dao.get(lessonId) ?: LessonProgressEntity(lessonId, 0f, false, "[]", now, now, null)
        dao.upsert(
            e.copy(
                completed = completed,
                progress = if (completed) 1f else minOf(e.progress, 0.67f),
                completedAt = if (completed) now else null,
                updatedAt = now,
            )
        )
    }

    suspend fun resetAll() = dao.deleteAll()
}

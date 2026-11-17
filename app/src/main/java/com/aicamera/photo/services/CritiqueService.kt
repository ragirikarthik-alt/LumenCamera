package com.aicamera.photo.services

import android.graphics.Bitmap
import com.aicamera.photo.AppContainer
import com.aicamera.photo.core.ai.PhotoCritic
import com.aicamera.photo.core.data.PhotoRepository
import com.aicamera.photo.core.image.ImageIo
import com.aicamera.photo.core.vision.StillImageAnalyzer
import com.aicamera.photo.domain.AlignmentState
import com.aicamera.photo.domain.FindingCategory
import com.aicamera.photo.domain.PhotoFeedback
import com.aicamera.photo.domain.RecommendationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Post-capture review: always analyses the final full-resolution file, never the preview. */
class CritiqueService(private val c: AppContainer) {

    /** Deterministic critique (fast, offline, no model needed). */
    suspend fun runDeterministic(photoId: String, captureTilt: Float?): PhotoFeedback? = withContext(Dispatchers.Default) {
        val photo = c.photos.get(photoId) ?: return@withContext null
        val file = File(photo.originalPath)
        if (!file.exists()) return@withContext null
        val result = StillImageAnalyzer().use { it.analyze(file, captureTilt) }
        result.preview.recycle()
        val compositionRow = c.database.analysisDao().compositionsFor(photoId).firstOrNull()
        val candidate = compositionRow?.let { PhotoRepository.decodeCandidate(it.candidateJson) }
        val alignment = compositionRow?.alignmentState?.let { runCatching { AlignmentState.valueOf(it) }.getOrNull() }
        val meta = PhotoRepository.decodeMetadata(photo.metadataJson)
        val technical = PhotoCritic.technical(result.snapshot, meta, captureTilt)
        val artistic = PhotoCritic.artistic(result.snapshot, candidate, alignment)
        val feedback = PhotoCritic.assemble(technical, artistic, null, false, System.currentTimeMillis())
        c.photos.saveFinalAnalysis(photoId, result.snapshot)
        c.photos.saveFeedback(photoId, feedback)
        feedback
    }

    /**
     * Adds on-device AI artistic observations to an existing critique. Requires an installed model;
     * returns null (and leaves the deterministic critique untouched) if AI is unavailable.
     */
    suspend fun addAiObservations(photoId: String): PhotoFeedback? = withContext(Dispatchers.Default) {
        val photo = c.photos.get(photoId) ?: return@withContext null
        val existingRow = c.photos.observeFeedback(photoId).first()
        val existing = existingRow?.let { PhotoRepository.decodeFeedback(it.feedbackJson) }
            ?: runDeterministic(photoId, null) ?: return@withContext null
        val finalScene = c.database.analysisDao().analysesFor(photoId).firstOrNull { it.stage == "final" }
            ?.let { PhotoRepository.decodeScene(it.snapshotJson) } ?: return@withContext null
        val strategy = c.database.analysisDao().compositionsFor(photoId).firstOrNull()
            ?.let { PhotoRepository.decodeCandidate(it.candidateJson)?.strategy?.displayName }
        val jpeg = runCatching {
            val bmp = ImageIo.decodeUpright(File(photo.originalPath), 768)
            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                bmp.recycle()
                out.toByteArray()
            }
        }.getOrNull()
        val (summary, aiFindings) = c.ai.critique(finalScene, existing.technical, strategy, jpeg)
        if (aiFindings.isEmpty()) return@withContext null
        val kept = existing.findings.filterNot { it.source == RecommendationSource.LOCAL_AI }
        val merged = existing.copy(
            findings = kept + aiFindings.filter { it.category == FindingCategory.ARTISTIC },
            summary = summary ?: existing.summary,
            aiUsed = true,
            createdAt = System.currentTimeMillis(),
        )
        c.photos.saveFeedback(photoId, merged)
        merged
    }
}

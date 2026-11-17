package com.aicamera.photo.services

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aicamera.photo.container
import java.io.File

/** Generates library thumbnails in the background (deferrable work). */
class ThumbnailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val ids = inputData.getString(KEY_PHOTO_ID)?.let { listOf(it) } ?: c.photos.missingThumbnails().map { it.id }
        for (id in ids) {
            val photo = c.photos.get(id) ?: continue
            val out = c.storage.thumbnailFor(id)
            runCatching { c.developer.thumbnail(File(photo.originalPath), out) }
                .onSuccess { c.photos.setThumbnail(id, out.absolutePath) }
        }
        return Result.success()
    }

    companion object {
        const val KEY_PHOTO_ID = "photo_id"
    }
}

/** Runs the deterministic post-capture critique on the saved full-resolution photo. */
class CritiqueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_PHOTO_ID) ?: return Result.failure()
        val tilt = inputData.getFloat(KEY_TILT, Float.NaN).takeUnless { it.isNaN() }
        return runCatching { applicationContext.container.critique.runDeterministic(id, tilt) }
            .fold({ Result.success() }, { if (runAttemptCount < 2) Result.retry() else Result.failure() })
    }

    companion object {
        const val KEY_PHOTO_ID = "photo_id"
        const val KEY_TILT = "tilt"
    }
}

object BackgroundJobs {
    fun afterCapture(context: Context, photoId: String, tilt: Float?, critique: Boolean) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniqueWork(
            "thumb_$photoId", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ThumbnailWorker>().setInputData(workDataOf(ThumbnailWorker.KEY_PHOTO_ID to photoId)).build(),
        )
        if (critique) {
            wm.enqueueUniqueWork(
                "critique_$photoId", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CritiqueWorker>()
                    .setInputData(workDataOf(CritiqueWorker.KEY_PHOTO_ID to photoId, CritiqueWorker.KEY_TILT to (tilt ?: Float.NaN)))
                    .build(),
            )
        }
    }

    fun rebuildThumbnails(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "thumb_all", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<ThumbnailWorker>().build(),
        )
    }
}

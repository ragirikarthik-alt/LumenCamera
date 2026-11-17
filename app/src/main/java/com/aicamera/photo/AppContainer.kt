package com.aicamera.photo

import android.content.Context
import com.aicamera.photo.core.ai.AiCoordinator
import com.aicamera.photo.core.ai.LiteRtLmEngine
import com.aicamera.photo.core.ai.MemoryGuard
import com.aicamera.photo.core.ai.ModelBenchmark
import com.aicamera.photo.core.ai.ModelManager
import com.aicamera.photo.core.camera.Diagnostics
import com.aicamera.photo.core.camera.ThermalMonitor
import com.aicamera.photo.core.data.CoachingRepository
import com.aicamera.photo.core.data.EditRepository
import com.aicamera.photo.core.data.LessonProgressRepository
import com.aicamera.photo.core.data.MediaStorage
import com.aicamera.photo.core.data.PhotoRepository
import com.aicamera.photo.core.data.SettingsRepository
import com.aicamera.photo.core.data.db.LumenDatabase
import com.aicamera.photo.core.image.Developer
import com.aicamera.photo.core.image.MediaExporter
import com.aicamera.photo.services.CritiqueService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container; one instance per process. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: LumenDatabase by lazy { LumenDatabase.build(appContext) }
    val storage: MediaStorage by lazy { MediaStorage(appContext) }
    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }
    val photos: PhotoRepository by lazy { PhotoRepository(database, storage) }
    val coaching: CoachingRepository by lazy { CoachingRepository(database) }
    val edits: EditRepository by lazy { EditRepository(database, storage) }
    val lessons: LessonProgressRepository by lazy { LessonProgressRepository(database) }

    val diagnostics = Diagnostics()
    val thermal: ThermalMonitor by lazy { ThermalMonitor(appContext) }

    val models: ModelManager by lazy { ModelManager(appContext, appScope) }
    val memory: MemoryGuard by lazy { MemoryGuard(appContext) }
    val llm by lazy { LiteRtLmEngine() }
    val ai: AiCoordinator by lazy {
        AiCoordinator(llm, models, memory, appContext.cacheDir.absolutePath) { state, latency, cold ->
            diagnostics.ai(state, latency, cold)
        }
    }
    val benchmark: ModelBenchmark by lazy { ModelBenchmark(appContext, llm, models, memory, database.benchmarkDao()) }

    val developer = Developer()
    val exporter: MediaExporter by lazy { MediaExporter(appContext) }
    val critique: CritiqueService by lazy { CritiqueService(this) }
}

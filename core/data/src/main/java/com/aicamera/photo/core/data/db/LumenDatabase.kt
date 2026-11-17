package com.aicamera.photo.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        CaptureSessionEntity::class,
        PhotoEntity::class,
        SceneAnalysisEntity::class,
        CompositionEntity::class,
        CoachingEventEntity::class,
        PhotoFeedbackEntity::class,
        EditEntity::class,
        LessonProgressEntity::class,
        ModelBenchmarkEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LumenDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun sessionDao(): SessionDao
    abstract fun analysisDao(): AnalysisDao
    abstract fun coachingDao(): CoachingDao
    abstract fun feedbackDao(): FeedbackDao
    abstract fun editDao(): EditDao
    abstract fun lessonDao(): LessonDao
    abstract fun benchmarkDao(): BenchmarkDao

    companion object {
        const val NAME = "lumen.db"

        /**
         * Schema migrations, starting from version 1. Never use destructive migration: the database
         * links originals, edits and feedback, and losing it would orphan user media.
         * Add `Migration(1, 2) { ... }` entries here as the schema evolves (schemas are exported to
         * core/data/schemas for migration testing).
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun build(context: Context): LumenDatabase =
            Room.databaseBuilder(context.applicationContext, LumenDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}

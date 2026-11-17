package com.aicamera.photo

import com.aicamera.photo.domain.CompositionStrategy
import com.aicamera.photo.domain.LessonIds
import com.aicamera.photo.domain.RecommendationType
import com.aicamera.photo.features.academy.AcademyContent
import com.aicamera.photo.features.academy.Track
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademyCoverageTest {
    private fun allLessonIds(): List<String> =
        LessonIds::class.java.declaredFields.filter { it.type == String::class.java }.map { it.isAccessible = true; it.get(null) as String }

    @Test
    fun everyLessonIdHasALesson() {
        val ids = allLessonIds()
        assertTrue(ids.size >= 20)
        ids.forEach { assertNotNull("Missing lesson for $it", AcademyContent.lesson(it)) }
    }

    @Test
    fun strategiesAndRecommendationsLinkToLessons() {
        CompositionStrategy.entries.forEach { assertNotNull(it.name, AcademyContent.lesson(it.lessonId)) }
        RecommendationType.entries.forEach { assertNotNull(it.name, AcademyContent.lesson(it.lessonId)) }
    }

    @Test
    fun everyTrackHasLessonsWithPractice() {
        Track.entries.forEach { t ->
            val lessons = AcademyContent.forTrack(t)
            assertTrue("${t.name} empty", lessons.isNotEmpty())
            lessons.forEach { assertTrue(it.id, it.practice.steps.isNotEmpty() && it.sections.isNotEmpty()) }
        }
    }
}

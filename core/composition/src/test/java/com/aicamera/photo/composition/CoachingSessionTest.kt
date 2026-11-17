package com.aicamera.photo.composition

import com.aicamera.photo.composition.TestScenes.horizon
import com.aicamera.photo.composition.TestScenes.scene
import com.aicamera.photo.composition.TestScenes.subjectAt
import com.aicamera.photo.domain.Clock
import com.aicamera.photo.domain.CoachingEventRecord
import com.aicamera.photo.domain.CoachingIntensity
import com.aicamera.photo.domain.CoachingPhase
import com.aicamera.photo.domain.CoachingRecommendation
import com.aicamera.photo.domain.RecommendationType
import com.aicamera.photo.domain.SceneType
import com.aicamera.photo.domain.VisualSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachingSessionTest {
    private var now = 100_000L
    private val clock = Clock { now }
    private val events = mutableListOf<CoachingEventRecord>()
    private val session = CoachingSession(clock, CoachingIntensity.BALANCED) { events += it }
    private val sig = SceneSignature.of(scene(subjects = listOf(subjectAt(0.5f, 0.5f))))

    private fun rec(type: RecommendationType, priority: Float, id: String = type.name) = CoachingRecommendation(
        id = id, title = type.label, instruction = "do ${type.label}", priority = priority,
        expiresAt = now + 8_000, type = type, confidence = 0.9f,
    )

    @Test
    fun showsOnlyOneSuggestionAtATime() {
        val shown = session.offer(listOf(rec(RecommendationType.HORIZON, 0.8f), rec(RecommendationType.EXPOSURE, 0.7f)), sig)
        assertEquals(RecommendationType.HORIZON, shown?.type)
        assertEquals(1, events.count { it.phase == CoachingPhase.DISPLAYED })
    }

    @Test
    fun lowPriorityIsFilteredByIntensity() {
        assertNull(session.offer(listOf(rec(RecommendationType.HORIZON, 0.2f)), sig))
    }

    @Test
    fun achievedWhenConditionDisappears() {
        session.offer(listOf(rec(RecommendationType.HORIZON, 0.8f)), sig)
        now += 500
        session.offer(emptyList(), sig)
        assertNotNull(session.active)
        now += 1_500
        session.offer(emptyList(), sig)
        assertNull(session.active)
        assertTrue(events.any { it.phase == CoachingPhase.ACHIEVED })
    }

    @Test
    fun dismissedSuggestionNotRepeatedInSameScene() {
        session.offer(listOf(rec(RecommendationType.HORIZON, 0.8f)), sig)
        session.dismiss()
        now += 200_000 // well past every cooldown
        val again = session.offer(listOf(rec(RecommendationType.HORIZON, 0.8f)), sig)
        assertNull(again)
        // A materially different scene allows it again.
        val other = SceneSignature.of(scene(type = SceneType.LANDSCAPE, horizon = horizon(3f), signals = VisualSignals(meanLuma = 0.9f)))
        val later = session.offer(listOf(rec(RecommendationType.HORIZON, 0.8f)), other)
        assertEquals(RecommendationType.HORIZON, later?.type)
    }

    @Test
    fun cooldownPreventsImmediateRepeat() {
        session.offer(listOf(rec(RecommendationType.HORIZON, 0.8f)), sig)
        now += 2_000
        session.offer(emptyList(), sig) // achieved
        now += 6_000 // past min gap, inside category cooldown
        assertNull(session.offer(listOf(rec(RecommendationType.HORIZON, 0.8f)), sig))
        assertEquals(RecommendationType.EXPOSURE, session.offer(listOf(rec(RecommendationType.EXPOSURE, 0.8f)), sig)?.type)
    }

    @Test
    fun replacedOnlyByClearlyHigherPriorityAfterStablePeriod() {
        session.offer(listOf(rec(RecommendationType.EXPOSURE, 0.5f)), sig)
        now += 500
        // Higher priority, but the current suggestion is still in its stable period.
        var shown = session.offer(listOf(rec(RecommendationType.EXPOSURE, 0.5f), rec(RecommendationType.STABILITY, 0.95f)), sig)
        assertEquals(RecommendationType.EXPOSURE, shown?.type)
        now += 5_000
        // Only slightly higher: no replacement.
        shown = session.offer(listOf(rec(RecommendationType.EXPOSURE, 0.5f), rec(RecommendationType.HORIZON, 0.6f)), sig)
        assertEquals(RecommendationType.EXPOSURE, shown?.type)
        shown = session.offer(listOf(rec(RecommendationType.EXPOSURE, 0.5f), rec(RecommendationType.STABILITY, 0.95f)), sig)
        assertEquals(RecommendationType.STABILITY, shown?.type)
        assertTrue(events.any { it.phase == CoachingPhase.REPLACED })
    }

    @Test
    fun expiresWhenNotResolved() {
        session.offer(listOf(rec(RecommendationType.EXPOSURE, 0.7f)), sig)
        var t = 0L
        while (t < 13_000) {
            now += 1_000; t += 1_000
            session.offer(listOf(rec(RecommendationType.EXPOSURE, 0.7f)), sig)
        }
        assertTrue(events.any { it.phase == CoachingPhase.EXPIRED })
    }

    @Test
    fun anotherIdeaSwitchesType() {
        session.offer(listOf(rec(RecommendationType.EXPOSURE, 0.7f), rec(RecommendationType.HORIZON, 0.5f)), sig)
        val next = session.anotherIdea()
        assertEquals(RecommendationType.HORIZON, next?.type)
    }

    @Test
    fun opportunityDetectorRespectsIntentionalTilt() {
        val detector = OpportunityDetector()
        val strongTilt = detector.detect(scene(type = SceneType.LANDSCAPE, horizon = horizon(25f)), now)
        assertFalse(strongTilt.any { it.type == RecommendationType.HORIZON })
        val smallTilt = detector.detect(scene(type = SceneType.LANDSCAPE, horizon = horizon(4f)), now)
        assertTrue(smallTilt.any { it.type == RecommendationType.HORIZON })
    }

    @Test
    fun opportunityDetectorHedgesLowConfidenceSubjects() {
        val detector = OpportunityDetector()
        val s = scene(subjects = listOf(subjectAt(0.5f, 0.5f, size = 0.1f, confidence = 0.6f)), signals = VisualSignals(clutter = 0.6f, sharpness = 0.6f))
        val recs = detector.detect(s, now)
        assertTrue(recs.isNotEmpty())
        assertTrue(recs.all { !it.instruction.contains("the person") })
    }
}

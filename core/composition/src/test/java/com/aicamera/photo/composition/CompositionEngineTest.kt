package com.aicamera.photo.composition

import com.aicamera.photo.composition.TestScenes.horizon
import com.aicamera.photo.composition.TestScenes.scene
import com.aicamera.photo.composition.TestScenes.subjectAt
import com.aicamera.photo.domain.AlignmentState
import com.aicamera.photo.domain.CompositionStrategy
import com.aicamera.photo.domain.FrameRotation
import com.aicamera.photo.domain.NormalizedPoint
import com.aicamera.photo.domain.SceneType
import com.aicamera.photo.domain.VisualSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionEngineTest {
    private val engine = DefaultCompositionEngine()

    @Test
    fun emptySceneProducesNoCandidates() {
        assertTrue(engine.generate(scene()).isEmpty())
    }

    @Test
    fun atMostThreeCandidatesSortedBySuitability() {
        val s = scene(
            subjects = listOf(subjectAt(0.45f, 0.5f, size = 0.15f)),
            type = SceneType.LANDSCAPE,
            horizon = horizon(0f),
            signals = VisualSignals(symmetry = 0.8f, clutter = 0.1f, foregroundDetail = 0.5f, sharpness = 0.6f),
        )
        val candidates = engine.generate(s)
        assertTrue(candidates.size in 1..3)
        assertEquals(candidates.sortedByDescending { it.suitability }, candidates)
        assertTrue(candidates.all { it.explanation.isNotBlank() })
    }

    @Test
    fun symmetryOnlyOfferedWhenSceneIsSymmetrical() {
        val subject = listOf(subjectAt(0.5f, 0.5f))
        val asym = engine.generate(scene(subjects = subject, signals = VisualSignals(symmetry = 0.2f)))
        assertFalse(asym.any { it.strategy == CompositionStrategy.CENTERED_SYMMETRY })
        val sym = engine.generate(scene(subjects = subject, signals = VisualSignals(symmetry = 0.9f)))
        assertTrue(sym.any { it.strategy == CompositionStrategy.CENTERED_SYMMETRY })
    }

    @Test
    fun thirdsTargetIsOnThirdLineOnSubjectSide() {
        val s = scene(subjects = listOf(subjectAt(0.7f, 0.6f)))
        val thirds = engine.generate(s).first { it.strategy == CompositionStrategy.RULE_OF_THIRDS }
        assertEquals(2f / 3f, thirds.subjectTargets.single().target.x, 1e-4f)
    }

    @Test
    fun alignmentReadyWhenSubjectOnTargetAndLevel() {
        val target = engine.generate(scene(subjects = listOf(subjectAt(0.6f, 0.4f)))).first { it.strategy == CompositionStrategy.RULE_OF_THIRDS }
        val aligned = scene(subjects = listOf(subjectAt(target.subjectTargets[0].target.x, target.subjectTargets[0].target.y)), horizon = horizon(0.5f, y = null))
        val raw = AlignmentMath.evaluate(aligned, target)
        assertEquals(AlignmentState.READY, raw.state)
    }

    @Test
    fun alignmentRecalculatedFromLatestScene() {
        val target = engine.generate(scene(subjects = listOf(subjectAt(0.6f, 0.4f)))).first()
        val far = AlignmentMath.evaluate(scene(subjects = listOf(subjectAt(0.1f, 0.9f))), target)
        assertEquals(AlignmentState.NOT_ALIGNED, far.state)
        assertTrue(far.hint.isNotBlank())
    }

    @Test
    fun lostSubjectIsReportedNotGuessed() {
        val target = engine.generate(scene(subjects = listOf(subjectAt(0.6f, 0.4f)))).first { it.strategy == CompositionStrategy.RULE_OF_THIRDS }
        val lost = AlignmentMath.evaluate(scene(), target)
        assertTrue(lost.subjectLost)
        assertEquals(AlignmentState.NOT_ALIGNED, lost.state)
    }

    @Test
    fun tiltBlocksReady() {
        val target = engine.generate(scene(subjects = listOf(subjectAt(0.6f, 0.4f)))).first { it.strategy == CompositionStrategy.RULE_OF_THIRDS }
        val t = target.subjectTargets[0].target
        val tilted = AlignmentMath.evaluate(scene(subjects = listOf(subjectAt(t.x, t.y)), horizon = horizon(5f, y = null)), target)
        assertTrue(tilted.state != AlignmentState.READY)
        assertTrue(tilted.hint.contains("lower the right side"))
    }

    @Test
    fun hysteresisPreventsFlicker() {
        val tracker = AlignmentTracker()
        val target = engine.generate(scene(subjects = listOf(subjectAt(0.6f, 0.4f)))).first { it.strategy == CompositionStrategy.RULE_OF_THIRDS }
        val t = target.subjectTargets[0].target
        val on = scene(subjects = listOf(subjectAt(t.x, t.y)))
        repeat(10) { tracker.update(target.id, AlignmentMath.evaluate(on, target)) }
        // Small jitter just below the ready threshold must not drop out of READY.
        val jitter = scene(subjects = listOf(subjectAt(t.x + 0.05f, t.y)))
        val r = tracker.update(target.id, AlignmentMath.evaluate(jitter, target))
        assertEquals(AlignmentState.READY, r.state)
    }

    @Test
    fun aimHintDirectionIsCameraMovement() {
        // Subject should move right in frame -> aim camera left.
        val hint = MovementHints.aimHint(NormalizedPoint(0.4f, 0.5f), NormalizedPoint(0.66f, 0.5f))
        assertTrue(hint.startsWith("Move the phone left"))
    }

    @Test
    fun quarterRotationsRoundTrip() {
        val p = NormalizedPoint(0.2f, 0.7f)
        for (d in listOf(0, 90, 180, 270)) {
            val back = FrameRotation.rotatePointCw(FrameRotation.rotatePointCw(p, d), 360 - d)
            assertEquals(p.x, back.x, 1e-5f)
            assertEquals(p.y, back.y, 1e-5f)
        }
        // World "up" with the phone rotated 90 degrees counter-clockwise points to the screen's right edge.
        val worldTop = FrameRotation.rotatePointCw(NormalizedPoint(0.5f, 0f), 90)
        assertEquals(1f, worldTop.x, 1e-5f)
        assertEquals(0.5f, worldTop.y, 1e-5f)
    }
}

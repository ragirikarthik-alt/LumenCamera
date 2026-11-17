package com.aicamera.photo.composition

import com.aicamera.photo.composition.TestScenes.scene
import com.aicamera.photo.composition.TestScenes.subjectAt
import com.aicamera.photo.domain.AlignmentState
import com.aicamera.photo.domain.CompositionStrategy
import com.aicamera.photo.domain.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedAccuracyTest {
    private val engine = DefaultCompositionEngine()

    @Test
    fun personIsPlacedByTheHeadNotTheBodyCentre() {
        // A standing person: head on the upper third, body below it - not the body centre on the line.
        val person = subjectAt(0.62f, 0.55f, size = 0.4f, kind = SubjectKind.PERSON)
        val thirds = engine.generate(scene(subjects = listOf(person))).first { it.strategy == CompositionStrategy.RULE_OF_THIRDS }
        val t = thirds.subjectTargets.single().target
        assertEquals(1f / 3f, t.y, 0.02f)
        assertTrue("explanation should talk about the head: ${thirds.explanation}", thirds.explanation.contains("head"))
    }

    @Test
    fun targetKeepsTheWholeSubjectInsideTheFrame() {
        // A tall subject whose head on the top third would push its feet below the frame.
        val tall = subjectAt(0.6f, 0.55f, size = 0.8f, kind = SubjectKind.PERSON)
        val thirds = engine.generate(scene(subjects = listOf(tall))).first { it.strategy == CompositionStrategy.RULE_OF_THIRDS }
        val t = thirds.subjectTargets.single().target
        val box = tall.boundingBox
        val shift = t.y - tall.anchor.y
        assertTrue("bottom would leave the frame: ${box.bottom + shift}", box.bottom + shift <= 1f)
        assertTrue(box.top + shift >= 0f)
    }

    @Test
    fun alignmentStaysOnTheChosenSubjectWhenTrackingIdChanges() {
        val chosen = subjectAt(0.4f, 0.5f, size = 0.15f, id = 1)
        val candidate = engine.generate(scene(subjects = listOf(chosen))).first { it.strategy == CompositionStrategy.RULE_OF_THIRDS }
        engine.calculateAlignment(scene(subjects = listOf(chosen)), candidate)
        // Next frame: the detector renamed our subject (id 7), and a bigger object (the bed) appeared far away.
        val renamed = subjectAt(0.42f, 0.5f, size = 0.15f, id = 7)
        val bed = subjectAt(0.8f, 0.85f, size = 0.35f, id = 9)
        val r = engine.calculateAlignment(scene(subjects = listOf(bed, renamed)), candidate)
        val current = r.subjectCurrent!!
        assertTrue("jumped to another object: $current", current.distanceTo(renamed.anchor) < 0.05f)
    }

    @Test
    fun briefDetectionGapIsBridgedNotReportedAsLost() {
        val chosen = subjectAt(0.4f, 0.5f, size = 0.15f, id = 1)
        val candidate = engine.generate(scene(subjects = listOf(chosen))).first { it.strategy == CompositionStrategy.RULE_OF_THIRDS }
        engine.calculateAlignment(scene(subjects = listOf(chosen)), candidate)
        val gap = engine.calculateAlignment(scene(), candidate)
        assertTrue(!gap.subjectLost)
    }

    @Test
    fun targetDoesNotMoveWhileAligning() {
        val chosen = subjectAt(0.4f, 0.5f, size = 0.15f, id = 1)
        val candidate = engine.generate(scene(subjects = listOf(chosen))).first { it.strategy == CompositionStrategy.RULE_OF_THIRDS }
        val target = candidate.subjectTargets.single().target
        var last = engine.calculateAlignment(scene(subjects = listOf(chosen)), candidate)
        // Walk the subject onto the target; the target point must stay put the whole time.
        for (i in 1..12) {
            val x = 0.4f + (target.x - 0.4f) * i / 12f
            val y = 0.5f + (target.y - chosen.anchor.y) * i / 12f
            last = engine.calculateAlignment(scene(subjects = listOf(subjectAt(x, y, size = 0.15f, id = 1))), candidate)
            assertEquals(target, last.subjectTarget)
        }
        assertEquals(AlignmentState.READY, last.state)
    }
}

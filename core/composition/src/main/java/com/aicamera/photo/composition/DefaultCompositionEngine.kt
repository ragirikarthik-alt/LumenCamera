package com.aicamera.photo.composition

import com.aicamera.photo.domain.AlignmentResult
import com.aicamera.photo.domain.CompositionCandidate
import com.aicamera.photo.domain.CompositionEngine
import com.aicamera.photo.domain.CompositionStrategy
import com.aicamera.photo.domain.DetectedSubject
import com.aicamera.photo.domain.LineSegment
import com.aicamera.photo.domain.NormalizedPoint
import com.aicamera.photo.domain.SceneSnapshot
import com.aicamera.photo.domain.SceneType
import com.aicamera.photo.domain.SubjectKind
import com.aicamera.photo.domain.SubjectTarget
import kotlin.math.abs

/**
 * Deterministic composition engine.
 *
 * Generates only strategies that fit the scene, ranks them with explainable signals, and computes
 * alignment against the latest scene (never against a stale pixel target).
 * The highest suitability is a ranking heuristic, not an objective "best" photo.
 */
class DefaultCompositionEngine(
    private val maxCandidates: Int = 3,
    private val alignmentTracker: AlignmentTracker = AlignmentTracker(),
) : CompositionEngine {

    override suspend fun generateCandidates(scene: SceneSnapshot): List<CompositionCandidate> = generate(scene)

    fun generate(scene: SceneSnapshot): List<CompositionCandidate> {
        val feasibility = technicalFeasibility(scene)
        val all = listOfNotNull(
            thirds(scene),
            symmetry(scene),
            leadingLines(scene),
            negativeSpace(scene),
            frameWithinFrame(scene),
            layeredLandscape(scene),
            diagonal(scene),
            fillTheFrame(scene),
        )
        return all
            .map { it.copy(suitability = (it.suitability * feasibility).coerceIn(0f, 1f)) }
            .sortedByDescending { it.suitability }
            .take(maxCandidates)
    }

    private val subjectLock = SubjectLock()
    private var lockedCandidateId: String? = null

    /**
     * Smoothed, hysteresis-protected alignment that stays locked on the subject the composition was
     * chosen for. The target point itself is fixed in the frame; only the subject position is live.
     */
    override fun calculateAlignment(scene: SceneSnapshot, target: CompositionCandidate): AlignmentResult {
        if (target.id != lockedCandidateId) {
            subjectLock.reset()
            lockedCandidateId = target.id
        }
        val subjectTarget = target.subjectTargets.firstOrNull()
        val locked = if (subjectTarget?.trackingId != null) subjectLock.resolve(scene, subjectTarget.trackingId) else null
        return alignmentTracker.update(target.id, AlignmentMath.evaluate(scene, target, locked))
    }

    fun resetAlignment() {
        alignmentTracker.reset()
        subjectLock.reset()
        lockedCandidateId = null
    }

    // --- Strategies -----------------------------------------------------------------------------

    private fun thirds(scene: SceneSnapshot): CompositionCandidate? {
        val subject = scene.primarySubject
        val horizon = scene.horizon?.takeIf { it.isVisibleLine }
        if (subject == null && horizon == null) return null

        val subjectTarget = subject?.let {
            val a = it.anchor
            val x = if (a.x <= 0.5f) THIRD else TWO_THIRDS
            // Eyes / head go on the upper line; other subjects on the line nearest to them.
            val y = when {
                it.kind == SubjectKind.FACE || it.kind == SubjectKind.PERSON -> THIRD
                a.y <= 0.5f -> THIRD
                else -> TWO_THIRDS
            }
            SubjectTarget(it.trackingId, fitted(it, NormalizedPoint(x, y)))
        }
        val horizonTarget = horizon?.let { horizonThird(scene) }

        var score = 0.5f
        if (subject != null) {
            score += 0.2f * subject.confidence
            if (subject.boundingBox.area > 0.35f) score -= 0.25f
        }
        if (horizon != null) score += 0.1f
        if (scene.sceneType in setOf(SceneType.PORTRAIT, SceneType.LANDSCAPE, SceneType.STREET, SceneType.ANIMAL)) score += 0.05f

        val text = buildString {
            if (subject != null && subjectTarget != null) {
                append("Move the ${partOf(subject)} onto the circle on the ${if (subjectTarget.target.x < 0.5f) "left" else "right"} line. ")
                append("A subject a little to the side looks more lively.")
            }
            if (horizonTarget != null) {
                if (isNotEmpty()) append(" ")
                append("Keep the horizon on the ${if (horizonTarget < 0.5f) "top" else "bottom"} dashed line - ")
                append(if (horizonTarget > 0.5f) "more sky looks dramatic." else "more ground shows depth.")
            }
        }
        return CompositionCandidate(
            id = "thirds",
            strategy = CompositionStrategy.RULE_OF_THIRDS,
            subjectTargets = listOfNotNull(subjectTarget),
            horizonTarget = horizonTarget,
            explanation = text,
            suitability = score,
            guides = thirdsGuides(),
        )
    }

    private fun symmetry(scene: SceneSnapshot): CompositionCandidate? {
        val sym = scene.visualSignals.symmetry
        val architecture = scene.sceneType == SceneType.ARCHITECTURE
        if (sym < 0.55f && !(architecture && sym >= 0.4f)) return null
        val subject = scene.primarySubject
        val target = subject?.let { SubjectTarget(it.trackingId, fitted(it, NormalizedPoint(0.5f, it.anchor.y.coerceIn(0.3f, 0.7f)))) }
        val score = 0.35f + 0.6f * sym + if (architecture) 0.1f else 0f
        return CompositionCandidate(
            id = "symmetry",
            strategy = CompositionStrategy.CENTERED_SYMMETRY,
            subjectTargets = listOfNotNull(target),
            horizonTarget = null,
            explanation = "This scene looks the same on both sides. Put ${subject?.let { "the ${nameOf(it)}" } ?: "the middle of the scene"} on the centre line and keep both sides even.",
            suitability = score,
            guides = listOf(LineSegment(NormalizedPoint(0.5f, 0f), NormalizedPoint(0.5f, 1f))),
        )
    }

    private fun leadingLines(scene: SceneSnapshot): CompositionCandidate? {
        val vs = scene.visualSignals
        val convergence = vs.lineConvergence ?: return null
        val strong = vs.leadingLines.sortedByDescending { it.strength }.take(3)
        if (strong.isEmpty()) return null
        val avg = strong.map { it.strength }.average().toFloat()
        if (avg < 0.35f) return null
        val subject = scene.primarySubject
        // The target is fixed when chosen; the lines should lead to the subject placed there.
        val point = nearestPowerPoint(convergence, includeCentre = true)
        val target = subject?.let { SubjectTarget(it.trackingId, fitted(it, point)) } ?: SubjectTarget(null, point)
        var score = 0.4f + 0.4f * avg
        if (scene.sceneType in setOf(SceneType.STREET, SceneType.ARCHITECTURE, SceneType.LANDSCAPE)) score += 0.1f
        return CompositionCandidate(
            id = "leading_lines",
            strategy = CompositionStrategy.LEADING_LINES,
            subjectTargets = listOf(target),
            horizonTarget = null,
            explanation = if (subject != null) {
                "Lines in this scene point inwards. Put the ${nameOf(subject)} on the circle so the lines lead to it."
            } else {
                "Lines in this scene meet at one point. Move that meeting point onto the circle so the lines pull the eye in."
            },
            suitability = score,
            guides = strong,
        )
    }

    private fun negativeSpace(scene: SceneSnapshot): CompositionCandidate? {
        val subject = scene.primarySubject ?: return null
        val area = subject.boundingBox.area
        val clutter = scene.visualSignals.clutter
        if (area > 0.12f || clutter > 0.35f) return null
        val a = subject.anchor
        val x = if (a.x <= 0.5f) 0.28f else 0.72f
        val y = if (a.y <= 0.5f) THIRD else TWO_THIRDS
        val side = if (x < 0.5f) "left" else "right"
        return CompositionCandidate(
            id = "negative_space",
            strategy = CompositionStrategy.NEGATIVE_SPACE,
            subjectTargets = listOf(SubjectTarget(subject.trackingId, fitted(subject, NormalizedPoint(x, y)), desiredArea = area.coerceIn(0.01f, 0.08f))),
            horizonTarget = null,
            explanation = "The area around the ${nameOf(subject)} is plain. Keep it small on the $side and leave the empty space around it - it makes the subject stand out.",
            suitability = 0.4f + 0.4f * (1f - clutter) + 0.1f * subject.confidence,
        )
    }

    private fun frameWithinFrame(scene: SceneSnapshot): CompositionCandidate? {
        val region = scene.visualSignals.framingRegion ?: return null
        if (region.area !in 0.12f..0.75f) return null
        val subject = scene.primarySubject
        val point = region.center.clamped()
        val target = subject?.let { SubjectTarget(it.trackingId, fitted(it, point)) } ?: SubjectTarget(null, point)
        val areaFit = 1f - abs(region.area - 0.4f) / 0.4f
        return CompositionCandidate(
            id = "frame_within_frame",
            strategy = CompositionStrategy.FRAME_WITHIN_FRAME,
            subjectTargets = listOf(target),
            horizonTarget = null,
            explanation = "There may be a natural frame here, like a doorway, window or branches. Keep ${subject?.let { "the ${nameOf(it)}" } ?: "your subject"} inside it.",
            suitability = 0.45f + 0.3f * areaFit.coerceIn(0f, 1f),
            targetRegion = region,
        )
    }

    private fun layeredLandscape(scene: SceneSnapshot): CompositionCandidate? {
        val horizon = scene.horizon
        val landscape = scene.sceneType == SceneType.LANDSCAPE
        if (!landscape && horizon?.isVisibleLine != true) return null
        val primary = scene.primarySubject
        if (primary?.kind == SubjectKind.FACE && primary.boundingBox.area > 0.08f) return null
        val fg = scene.visualSignals.foregroundDetail
        val subjectTarget = primary?.let {
            SubjectTarget(it.trackingId, fitted(it, NormalizedPoint(if (it.anchor.x < 0.5f) THIRD else TWO_THIRDS, 0.75f)))
        }
        return CompositionCandidate(
            id = "layered",
            strategy = CompositionStrategy.LAYERED_LANDSCAPE,
            subjectTargets = listOfNotNull(subjectTarget),
            horizonTarget = THIRD,
            explanation = if (fg >= 0.3f) {
                "There is something interesting close to you. Put the horizon on the top dashed line to show near, middle and far."
            } else {
                "Put the horizon on the top dashed line and add something close at the bottom, like a rock, path or flowers."
            },
            suitability = 0.4f + 0.3f * fg.coerceIn(0f, 1f) + 0.2f * (horizon?.lineConfidence ?: 0.3f),
            guides = listOf(LineSegment(NormalizedPoint(0f, THIRD), NormalizedPoint(1f, THIRD))),
        )
    }

    private fun diagonal(scene: SceneSnapshot): CompositionCandidate? {
        val vs = scene.visualSignals
        if (vs.diagonalStrength < 0.5f) return null
        val dominant = vs.leadingLines.filter { abs(it.angleDegrees) in 20f..70f || abs(it.angleDegrees) in 110f..160f }
            .maxByOrNull { it.strength * it.length } ?: return null
        val slopesDown = (dominant.angleDegrees > 0f && dominant.angleDegrees < 90f) || dominant.angleDegrees < -90f
        val subject = scene.primarySubject
        val options = if (slopesDown) {
            listOf(NormalizedPoint(THIRD, THIRD), NormalizedPoint(TWO_THIRDS, TWO_THIRDS))
        } else {
            listOf(NormalizedPoint(TWO_THIRDS, THIRD), NormalizedPoint(THIRD, TWO_THIRDS))
        }
        val from = subject?.anchor ?: NormalizedPoint.CENTER
        val point = options.minBy { it.distanceTo(from) }
        val diagGuide = if (slopesDown) LineSegment(NormalizedPoint(0f, 0f), NormalizedPoint(1f, 1f))
        else LineSegment(NormalizedPoint(1f, 0f), NormalizedPoint(0f, 1f))
        return CompositionCandidate(
            id = "diagonal",
            strategy = CompositionStrategy.DIAGONAL,
            subjectTargets = if (subject != null) listOf(SubjectTarget(subject.trackingId, fitted(subject, point))) else emptyList(),
            horizonTarget = null,
            explanation = "A strong line crosses the scene at an angle. Let it run from corner to corner${subject?.let { ", with the ${nameOf(it)} on the circle" } ?: ""}. Angled lines add energy.",
            suitability = 0.35f + 0.45f * vs.diagonalStrength,
            guides = listOf(diagGuide, dominant),
        )
    }

    private fun fillTheFrame(scene: SceneSnapshot): CompositionCandidate? {
        val subject = scene.primarySubject ?: return null
        val area = subject.boundingBox.area
        if (area >= 0.25f) return null
        val closeUpScene = scene.sceneType in setOf(SceneType.FOOD, SceneType.CLOSE_UP, SceneType.ANIMAL, SceneType.PORTRAIT)
        val clutter = scene.visualSignals.clutter
        if (!closeUpScene && clutter < 0.5f) return null
        return CompositionCandidate(
            id = "fill_frame",
            strategy = CompositionStrategy.FILL_THE_FRAME,
            subjectTargets = listOf(SubjectTarget(subject.trackingId, NormalizedPoint(0.5f, 0.5f), desiredArea = 0.45f)),
            horizonTarget = null,
            explanation = "Move closer so the ${nameOf(subject)} fills most of the picture${if (clutter >= 0.5f) " and the messy background disappears" else ""}.",
            suitability = 0.4f + 0.3f * clutter + if (closeUpScene) 0.15f else 0f,
        )
    }

    /**
     * Adjusts a target so that, with the subject's anchor on it, the whole subject still fits inside the
     * frame. Targets the subject can't physically reach without being cut off are never proposed.
     */
    private fun fitted(subject: DetectedSubject, target: NormalizedPoint, margin: Float = 0.03f): NormalizedPoint {
        val b = subject.boundingBox
        val a = subject.anchor
        fun axis(t: Float, anchor: Float, lo: Float, hi: Float, center: Float): Float {
            val size = hi - lo
            if (size >= 1f - 2 * margin) return 0.5f + (anchor - center)
            val d = t - anchor
            val newLo = lo + d
            val newHi = hi + d
            return when {
                newLo < margin -> t + (margin - newLo)
                newHi > 1f - margin -> t - (newHi - (1f - margin))
                else -> t
            }
        }
        return NormalizedPoint(
            axis(target.x, a.x, b.left, b.right, b.center.x),
            axis(target.y, a.y, b.top, b.bottom, b.center.y),
        ).clamped()
    }

    private fun nameOf(s: DetectedSubject): String = s.describe()

    /** What to put on the target, in everyday words. */
    private fun partOf(s: DetectedSubject): String = when (s.kind) {
        SubjectKind.FACE -> "eyes"
        SubjectKind.PERSON -> "person's head"
        else -> nameOf(s)
    }

    // --- Helpers --------------------------------------------------------------------------------

    private fun horizonThird(scene: SceneSnapshot): Float {
        val vs = scene.visualSignals
        // More interest in the sky -> lower horizon (more sky). Otherwise favour the foreground.
        return if (vs.skyDetail > vs.foregroundDetail * 1.2f) TWO_THIRDS else THIRD
    }

    private fun technicalFeasibility(scene: SceneSnapshot): Float {
        val vs = scene.visualSignals
        val motionPenalty = 0.4f * vs.motion.coerceIn(0f, 1f)
        val stabilityPenalty = 0.2f * (1f - vs.deviceStability.coerceIn(0f, 1f))
        return (1f - motionPenalty - stabilityPenalty).coerceIn(0.5f, 1f)
    }

    companion object {
        const val THIRD = 1f / 3f
        const val TWO_THIRDS = 2f / 3f

        val POWER_POINTS = listOf(
            NormalizedPoint(THIRD, THIRD), NormalizedPoint(TWO_THIRDS, THIRD),
            NormalizedPoint(THIRD, TWO_THIRDS), NormalizedPoint(TWO_THIRDS, TWO_THIRDS),
        )

        fun nearestPowerPoint(p: NormalizedPoint, includeCentre: Boolean = false): NormalizedPoint {
            val pts = if (includeCentre) POWER_POINTS + NormalizedPoint.CENTER else POWER_POINTS
            return pts.minBy { it.distanceTo(p) }
        }

        fun thirdsGuides(): List<LineSegment> = listOf(
            LineSegment(NormalizedPoint(THIRD, 0f), NormalizedPoint(THIRD, 1f)),
            LineSegment(NormalizedPoint(TWO_THIRDS, 0f), NormalizedPoint(TWO_THIRDS, 1f)),
            LineSegment(NormalizedPoint(0f, THIRD), NormalizedPoint(1f, THIRD)),
            LineSegment(NormalizedPoint(0f, TWO_THIRDS), NormalizedPoint(1f, TWO_THIRDS)),
        )

        internal fun subjectFor(scene: SceneSnapshot, trackingId: Int?): DetectedSubject? =
            trackingId?.let { id -> scene.subjects.firstOrNull { it.trackingId == id } } ?: scene.primarySubject
    }
}

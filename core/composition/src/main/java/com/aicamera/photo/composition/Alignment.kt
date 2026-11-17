package com.aicamera.photo.composition

import com.aicamera.photo.domain.AlignmentResult
import com.aicamera.photo.domain.AlignmentState
import com.aicamera.photo.domain.CompositionCandidate
import com.aicamera.photo.domain.CompositionStrategy
import com.aicamera.photo.domain.ConfidenceLevel
import com.aicamera.photo.domain.DetectedSubject
import com.aicamera.photo.domain.NormalizedRect
import com.aicamera.photo.domain.SubjectKind
import com.aicamera.photo.domain.NormalizedPoint
import com.aicamera.photo.domain.SceneSnapshot
import kotlin.math.abs
import kotlin.math.ln

/** Stateless alignment evaluation against the latest scene. */
object AlignmentMath {
    const val READY_SCORE = 0.85f
    const val NEARLY_SCORE = 0.6f
    const val MAX_SUBJECT_DISTANCE = 0.30f
    const val MAX_TILT_DEGREES = 6f
    const val READY_MAX_TILT = 2.5f

    /**
     * @param locked the subject resolved by a [SubjectLock]. When given, alignment only ever follows that
     *   subject; without it, the subject is looked up by tracking id or falls back to the primary subject.
     */
    fun evaluate(scene: SceneSnapshot, candidate: CompositionCandidate, locked: LockedSubject? = null): AlignmentResult {
        val vs = scene.visualSignals
        val blockers = buildList {
            if (vs.motion > 0.5f || vs.deviceStability < 0.45f) add("Hold the phone still")
            if (vs.sharpness < 0.15f && vs.motion < 0.3f) add("Looks blurry - tap the subject to focus")
        }

        val weighted = mutableListOf<Pair<Float, Float>>() // score to weight
        var hint: Pair<Float, String>? = null // (error weight, hint)
        fun offerHint(severity: Float, text: String) {
            if (hint == null || severity > hint!!.first) hint = severity to text
        }

        // Subject placement.
        val target = candidate.subjectTargets.firstOrNull()
        var current: NormalizedPoint? = null
        var subjectLost = false
        if (target != null) {
            val subject = if (locked != null) locked.subject else DefaultCompositionEngine.subjectFor(scene, target.trackingId)
            current = when {
                locked != null && !locked.lost && locked.anchor != null -> locked.anchor
                locked == null && subject != null -> subject.anchor
                target.trackingId == null && candidate.strategy == CompositionStrategy.LEADING_LINES -> vs.lineConvergence
                target.trackingId == null && candidate.strategy == CompositionStrategy.FRAME_WITHIN_FRAME -> vs.framingRegion?.center
                else -> null
            }
            if (current == null) {
                subjectLost = true
            } else {
                val dist = current.distanceTo(target.target)
                val s = (1f - dist / MAX_SUBJECT_DISTANCE).coerceIn(0f, 1f)
                weighted += s to 0.6f
                offerHint(1f - s, MovementHints.aimHint(current, target.target, subject?.describe() ?: "subject"))
                val desiredArea = target.desiredArea
                if (subject != null && desiredArea != null) {
                    val ratio = (subject.boundingBox.area / desiredArea).coerceAtLeast(0.0001f)
                    val sizeScore = (1f - abs(ln(ratio)) / ln(2.5f)).coerceIn(0f, 1f)
                    weighted += sizeScore to 0.15f
                    offerHint((1f - sizeScore) * 0.9f, MovementHints.sizeHint(subject.boundingBox.area, desiredArea))
                }
            }
        } else if (candidate.strategy == CompositionStrategy.FRAME_WITHIN_FRAME || candidate.strategy == CompositionStrategy.LEADING_LINES) {
            current = vs.framingRegion?.center ?: vs.lineConvergence
        }

        // Horizon level and placement.
        var tiltError: Float? = null
        var positionError: Float? = null
        val horizon = scene.horizon
        if (horizon != null && horizon.confidence >= ConfidenceLevel.MEDIUM_THRESHOLD) {
            tiltError = horizon.tiltDegrees
            val tiltScore = (1f - abs(horizon.tiltDegrees) / MAX_TILT_DEGREES).coerceIn(0f, 1f)
            weighted += tiltScore to if (candidate.horizonTarget != null) 0.25f else 0.15f
            if (abs(horizon.tiltDegrees) > 1.5f) offerHint((1f - tiltScore) * 1.1f, MovementHints.levelHint(horizon.tiltDegrees))
        }
        val horizonTarget = candidate.horizonTarget
        val horizonY = horizon?.positionY
        if (horizonTarget != null && horizonY != null && horizon.isVisibleLine) {
            val err = horizonY - horizonTarget
            positionError = err
            val s = (1f - abs(err) / 0.2f).coerceIn(0f, 1f)
            weighted += s to 0.3f
            offerHint(1f - s, MovementHints.horizonPositionHint(horizonY, horizonTarget))
        }

        if (subjectLost) {
            return AlignmentResult(
                state = AlignmentState.NOT_ALIGNED,
                score = 0f,
                subjectCurrent = null,
                subjectTarget = target?.target,
                horizonTiltError = tiltError,
                horizonPositionError = positionError,
                hint = "Point the phone back at your subject",
                technicalBlockers = blockers,
                subjectLost = true,
            )
        }

        val totalWeight = weighted.sumOf { it.second.toDouble() }.toFloat()
        val score = if (totalWeight <= 0f) 0f else weighted.sumOf { (it.first * it.second).toDouble() }.toFloat() / totalWeight
        val tiltOk = tiltError == null || abs(tiltError) <= READY_MAX_TILT
        val state = when {
            score >= READY_SCORE && tiltOk && blockers.isEmpty() -> AlignmentState.READY
            score >= NEARLY_SCORE -> AlignmentState.NEARLY_ALIGNED
            else -> AlignmentState.NOT_ALIGNED
        }
        val finalHint = when {
            state == AlignmentState.READY -> "Perfect - take the photo"
            blockers.isNotEmpty() && score >= NEARLY_SCORE -> blockers.first()
            else -> hint?.second ?: "Adjust the framing"
        }
        return AlignmentResult(
            state = state,
            score = score,
            subjectCurrent = current,
            subjectTarget = target?.target,
            horizonTiltError = tiltError,
            horizonPositionError = positionError,
            hint = finalHint,
            technicalBlockers = blockers,
        )
    }
}

/**
 * Smooths alignment scores and applies hysteresis so the state does not flicker between frames.
 */
class AlignmentTracker(
    private val smoothing: Float = 0.35f,
    private val readyEnter: Float = 0.85f,
    private val readyExit: Float = 0.75f,
    private val nearlyEnter: Float = 0.6f,
    private val nearlyExit: Float = 0.5f,
) {
    private var candidateId: String? = null
    private var smoothed = 0f
    private var state = AlignmentState.NOT_ALIGNED

    fun reset() {
        candidateId = null
        smoothed = 0f
        state = AlignmentState.NOT_ALIGNED
    }

    fun update(id: String, raw: AlignmentResult): AlignmentResult {
        if (id != candidateId) {
            reset()
            candidateId = id
            smoothed = raw.score
        } else {
            smoothed += smoothing * (raw.score - smoothed)
        }
        if (raw.subjectLost) {
            state = AlignmentState.NOT_ALIGNED
            smoothed = 0f
            return raw
        }
        val blocked = raw.technicalBlockers.isNotEmpty()
        val tiltBlocked = raw.horizonTiltError?.let { abs(it) > AlignmentMath.READY_MAX_TILT } ?: false
        state = when (state) {
            AlignmentState.READY -> when {
                smoothed < nearlyExit -> AlignmentState.NOT_ALIGNED
                smoothed < readyExit || blocked || tiltBlocked -> AlignmentState.NEARLY_ALIGNED
                else -> AlignmentState.READY
            }
            AlignmentState.NEARLY_ALIGNED -> when {
                smoothed >= readyEnter && !blocked && !tiltBlocked -> AlignmentState.READY
                smoothed < nearlyExit -> AlignmentState.NOT_ALIGNED
                else -> AlignmentState.NEARLY_ALIGNED
            }
            AlignmentState.NOT_ALIGNED -> when {
                smoothed >= readyEnter && !blocked && !tiltBlocked -> AlignmentState.READY
                smoothed >= nearlyEnter -> AlignmentState.NEARLY_ALIGNED
                else -> AlignmentState.NOT_ALIGNED
            }
        }
        val hint = if (state == AlignmentState.READY) "Perfect - take the photo"
        else if (raw.state == AlignmentState.READY) "Almost there - hold still" else raw.hint
        return raw.copy(state = state, score = smoothed, hint = hint)
    }
}

/** Result of following the locked subject in the latest scene. */
data class LockedSubject(
    val subject: DetectedSubject?,
    /** Smoothed placement point (eyes / head / centre) of the locked subject. */
    val anchor: NormalizedPoint?,
    val lost: Boolean,
)

/**
 * Keeps guided alignment on the subject the composition was chosen for.
 *
 * Detector tracking ids are not stable on every frame, so the subject is matched by id first, then by
 * the nearest detection of the same kind close to where it was last seen. It never jumps to a different,
 * far-away object, and short detection gaps are bridged instead of reported as "lost".
 */
class SubjectLock(
    private val smoothing: Float = 0.5f,
    private val maxJump: Float = 0.22f,
    private val missesBeforeLost: Int = 8,
) {
    private var trackingId: Int? = null
    private var kind: SubjectKind? = null
    private var lastBox: NormalizedRect? = null
    private var anchor: NormalizedPoint? = null
    private var misses = 0

    fun reset() {
        trackingId = null; kind = null; lastBox = null; anchor = null; misses = 0
    }

    fun resolve(scene: SceneSnapshot, preferredId: Int?): LockedSubject {
        val box = lastBox
        if (box == null) {
            val s = preferredId?.let { id -> scene.subjects.firstOrNull { it.trackingId == id } } ?: scene.primarySubject
            if (s != null) adopt(s, smooth = false)
            return LockedSubject(s, anchor, lost = s == null)
        }
        val byId = scene.subjects.firstOrNull { it.trackingId == trackingId }
            ?.takeIf { it.boundingBox.center.distanceTo(box.center) < maxJump * 1.5f }
        val match = byId ?: scene.subjects
            .filter { compatible(it.kind) }
            .filter { (it.boundingBox.area / box.area.coerceAtLeast(1e-4f)) in 0.4f..2.5f }
            .minByOrNull { it.boundingBox.center.distanceTo(box.center) }
            ?.takeIf { it.boundingBox.center.distanceTo(box.center) < maxJump }
        if (match != null) {
            adopt(match, smooth = true)
            misses = 0
            return LockedSubject(match, anchor, lost = false)
        }
        misses++
        return LockedSubject(null, anchor, lost = misses > missesBeforeLost)
    }

    private fun compatible(k: SubjectKind): Boolean {
        val mine = kind ?: return true
        val people = setOf(SubjectKind.FACE, SubjectKind.PERSON)
        return if (mine in people) k == mine else k !in people
    }

    private fun adopt(s: DetectedSubject, smooth: Boolean) {
        trackingId = s.trackingId
        kind = s.kind
        lastBox = s.boundingBox
        val a = s.anchor
        val prev = anchor
        anchor = if (smooth && prev != null) {
            NormalizedPoint(prev.x + smoothing * (a.x - prev.x), prev.y + smoothing * (a.y - prev.y))
        } else a
    }
}

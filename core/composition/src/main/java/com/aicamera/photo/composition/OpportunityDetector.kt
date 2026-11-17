package com.aicamera.photo.composition

import com.aicamera.photo.domain.CoachingRecommendation
import com.aicamera.photo.domain.ConfidenceLevel
import com.aicamera.photo.domain.FrameEdge
import com.aicamera.photo.domain.LightingCondition
import com.aicamera.photo.domain.RecommendationType
import com.aicamera.photo.domain.SceneSnapshot
import com.aicamera.photo.domain.SceneType
import com.aicamera.photo.domain.SubjectKind
import kotlin.math.abs
import kotlin.math.min

/**
 * Deterministic detection of coaching opportunities from structured scene data.
 *
 * Rules respect artistic intent: strongly tilted frames (likely intentional) and centred subjects in
 * symmetrical scenes are not flagged as mistakes, and uncertain detections use hedged wording.
 */
class OpportunityDetector(private val ttlMs: Long = 8_000L) {

    fun detect(scene: SceneSnapshot, now: Long): List<CoachingRecommendation> {
        val out = mutableListOf<CoachingRecommendation>()
        val vs = scene.visualSignals
        val subject = scene.primarySubject
        val name = subject?.describe() ?: "subject"
        fun rec(
            variant: String, type: RecommendationType, title: String, instruction: String,
            priority: Float, confidence: Float, rationale: String,
            focus: com.aicamera.photo.domain.NormalizedPoint? = null,
        ) {
            out += CoachingRecommendation(
                id = "${type.name}:$variant",
                title = title,
                instruction = instruction,
                priority = priority.coerceIn(0f, 1f),
                expiresAt = now + ttlMs,
                type = type,
                confidence = confidence.coerceIn(0f, 1f),
                rationale = rationale,
                focusPoint = focus,
            )
        }

        // Stability first: a blurred photo cannot be saved by composition.
        val lowLight = scene.lighting == LightingCondition.LOW_LIGHT || scene.sceneType == SceneType.NIGHT
        if (vs.motion > 0.45f || vs.deviceStability < 0.45f) {
            rec(
                "steady", RecommendationType.STABILITY, "Hold still",
                if (lowLight) "It's dark. Keep your elbows close or lean on something, and hold still." else "The picture is moving. Hold the phone still for a moment.",
                0.55f + if (lowLight) 0.2f else 0f, 0.85f, "Moving the phone makes photos blurry.",
            )
        }

        // Horizon: only small tilts are likely unintentional (>12 degrees is treated as a deliberate angle).
        scene.horizon?.let { h ->
            val tilt = abs(h.tiltDegrees)
            val relevant = h.isVisibleLine || scene.sceneType in setOf(SceneType.LANDSCAPE, SceneType.ARCHITECTURE)
            if (relevant && tilt in 1.5f..12f && h.confidence >= ConfidenceLevel.MEDIUM_THRESHOLD) {
                val title = if (scene.sceneType == SceneType.ARCHITECTURE) "Straighten the photo" else "Horizon is tilted"
                rec(
                    "tilt", RecommendationType.HORIZON, title, MovementHints.levelHint(h.tiltDegrees) + ".",
                    0.5f + 0.4f * (min(tilt, 8f) / 8f), h.confidence, "A straight horizon looks calm and intentional.",
                )
            }
        }

        // Subject separation.
        vs.separationIssue?.let { issue ->
            // Only for confidently identified subjects: a false "merge" warning damages trust.
            if (subject != null && issue.strength >= 0.4f && subject.confidence >= 0.7f) {
                val dir = issue.stepDirection?.let { MovementHints.stepDirection(it) }
                val instruction = when {
                    issue.verticalLineBehindSubject && dir != null -> "Take a step $dir so the pole or line behind the $name moves away from it."
                    dir != null -> "Take a small step $dir so the $name stands out from the busy background."
                    else -> "Try a slightly different angle so the $name stands out from the background."
                }
                rec(
                    "merge", RecommendationType.SEPARATION, "Busy background", instruction,
                    0.55f + 0.35f * issue.strength, min(subject.confidence, issue.strength + 0.3f),
                    "A clean background makes the subject stand out.", subject.boundingBox.center,
                )
            }
        }

        // Exposure.
        val subjectLuma = vs.subjectLuma
        val bgLuma = vs.backgroundLuma
        if (subject != null && subjectLuma != null && bgLuma != null && bgLuma - subjectLuma > 0.25f &&
            (scene.lighting == LightingCondition.BACKLIT || vs.highlightClipping > 0.03f)
        ) {
            rec(
                "backlit", RecommendationType.EXPOSURE, "Subject is too dark",
                "Tap the $name on the screen to make it brighter. The sky may get lighter.",
                0.7f, subject.confidence, "The bright background is making the subject dark.",
                subject.boundingBox.center,
            )
        } else if (vs.highlightClipping > 0.08f) {
            rec(
                "highlights", RecommendationType.EXPOSURE, "Too bright",
                "Some parts are pure white. Tap the screen and slide the ☀ slider down a little.",
                0.55f, 0.85f, "Pure white areas lose all detail.",
            )
        } else if (vs.shadowClipping > 0.25f && !lowLight) {
            rec(
                "shadows", RecommendationType.EXPOSURE, "Too dark",
                "Big areas are almost black. Tap the screen and slide the ☀ slider up, or find more light.",
                0.4f, 0.75f, "Very dark areas hide detail.",
            )
        }

        // Harsh light on faces.
        if (subject?.kind == SubjectKind.FACE && scene.lighting == LightingCondition.BRIGHT_HARSH) {
            rec(
                "harsh", RecommendationType.LIGHT_DIRECTION, "Strong sun on the face",
                "Move into the shade, or turn so the sun is at the side, not in front.",
                0.45f, subject.confidence, "Soft light looks nicer on faces.",
            )
        }

        // Edge distractions.
        vs.edgeDistractions.maxByOrNull { it.strength }?.let { d ->
            if (d.strength >= 0.45f) {
                rec(
                    "edge_${d.edge.name}", RecommendationType.EDGE_DISTRACTION, "Something at the edge",
                    "There is ${aOrAn(d.description)} at the ${d.edge.label} edge - ${MovementHints.aimAwayFrom(d.edge)} to leave it out.",
                    0.45f + 0.3f * d.strength, d.strength, "Things at the edge pull the eye away.",
                )
            }
        }

        if (subject != null) {
            val box = subject.boundingBox
            // Subject cut off at the frame edge (only for prominent subjects).
            val cut = when {
                box.left < 0.01f && box.width < 0.8f -> FrameEdge.LEFT
                box.right > 0.99f && box.width < 0.8f -> FrameEdge.RIGHT
                box.top < 0.01f && box.height < 0.8f && subject.kind == SubjectKind.FACE -> FrameEdge.TOP
                else -> null
            }
            if (cut != null && box.area > 0.03f) {
                rec(
                    "cut_${cut.name}", RecommendationType.SUBJECT_PLACEMENT, "Subject is cut off",
                    "Part of the $name is outside the picture on the ${cut.label}. ${aimToward(cut)}.",
                    0.6f, subject.confidence, "Cutting off part of the subject looks like a mistake.",
                )
            }

            // Centred small subject in a non-symmetrical scene: gentle suggestion only.
            val c = box.center
            if (abs(c.x - 0.5f) < 0.06f && box.area < 0.2f && vs.symmetry < 0.5f &&
                scene.sceneType != SceneType.PORTRAIT && subject.confidence >= ConfidenceLevel.MEDIUM_THRESHOLD
            ) {
                rec(
                    "thirds", RecommendationType.SUBJECT_PLACEMENT, "Try it to one side",
                    "Try putting the $name a little to the left or right of the middle. Keep it centred if you like it that way.",
                    0.35f, 0.6f, "A subject to one side often looks more lively.",
                )
            }

            // Small subject in a busy frame.
            if (box.area < 0.04f && vs.clutter > 0.45f) {
                rec(
                    "closer", RecommendationType.CLOSER, "Get closer",
                    "The $name looks small and there is a lot going on. Step closer or zoom in.",
                    0.45f, subject.confidence, "A bigger subject hides the clutter.",
                )
            }
        }

        // Landscape depth.
        val horizon = scene.horizon
        if (scene.sceneType == SceneType.LANDSCAPE && horizon?.isVisibleLine == true && vs.foregroundDetail < 0.15f && subject == null) {
            rec(
                "foreground", RecommendationType.FOREGROUND_DEPTH, "Add something close",
                "Include something close to you at the bottom, like a rock, path or flowers.",
                0.4f, 0.7f, "Near and far things together give a sense of depth.",
            )
        }

        // Viewpoint for flat, low-detail scenes (e.g. food or small subjects shot from eye level).
        if (scene.sceneType == SceneType.FOOD && subject != null && box(subject).center.y < 0.4f) {
            rec(
                "food_top", RecommendationType.VIEWPOINT, "Try another height",
                "Try shooting from straight above, or down at table level.",
                0.35f, 0.6f, "Changing height makes food look more interesting.",
            )
        }

        // Soft focus when the camera is steady.
        if (vs.sharpness < 0.12f && vs.motion < 0.25f && vs.deviceStability > 0.7f && !lowLight) {
            rec(
                "soft", RecommendationType.FOCUS, "Looks blurry",
                if (subject != null) "Tap the $name on the screen to make it sharp." else "Tap your subject on the screen to make it sharp.",
                0.5f, 0.55f, "The subject should be sharp.", subject?.boundingBox?.center,
            )
        }

        return out.sortedByDescending { it.priority }
    }

    private fun box(s: com.aicamera.photo.domain.DetectedSubject) = s.boundingBox

    /** A subject cut at the left edge needs the camera aimed further left to include it. */
    private fun aimToward(edge: FrameEdge) = when (edge) {
        FrameEdge.LEFT -> "Move the phone a little left or step back"
        FrameEdge.RIGHT -> "Move the phone a little right or step back"
        FrameEdge.TOP -> "Point the phone a little up or step back"
        FrameEdge.BOTTOM -> "Point the phone a little down or step back"
    }

    private fun aOrAn(noun: String) = if (noun.firstOrNull()?.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u')) "an $noun" else "a $noun"
}

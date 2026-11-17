package com.aicamera.photo.core.ai

import com.aicamera.photo.domain.AlignmentState
import com.aicamera.photo.domain.CaptureMetadata
import com.aicamera.photo.domain.CompositionCandidate
import com.aicamera.photo.domain.CompositionStrategy
import com.aicamera.photo.domain.FindingCategory
import com.aicamera.photo.domain.FindingSeverity
import com.aicamera.photo.domain.LessonIds
import com.aicamera.photo.domain.LightingCondition
import com.aicamera.photo.domain.PhotoFeedback
import com.aicamera.photo.domain.PhotoFinding
import com.aicamera.photo.domain.SceneSnapshot
import com.aicamera.photo.domain.SceneType
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Post-capture critique of the final still. Technical findings are measurements with thresholds;
 * artistic observations are hedged opinions that respect deliberate choices.
 */
object PhotoCritic {

    fun technical(scene: SceneSnapshot, meta: CaptureMetadata?, captureTilt: Float?): List<PhotoFinding> {
        val vs = scene.visualSignals
        val out = mutableListOf<PhotoFinding>()
        fun add(id: String, sev: FindingSeverity, title: String, detail: String, conf: Float, lesson: String?, measurement: String?) {
            out += PhotoFinding(id, FindingCategory.TECHNICAL, sev, title, detail, conf, lesson, measurement)
        }

        // Exposure
        val clipped = vs.highlightClipping * 100
        when {
            clipped > 2f -> add("hl", FindingSeverity.ISSUE, "Some parts too bright",
                "Some bright areas came out pure white with no detail. Next time, tap the bright area or slide the ☀ slider down a little.",
                0.9f, LessonIds.EXPOSURE, "%.1f%% of the photo is pure white".format(clipped))
            clipped > 0.5f -> add("hl", FindingSeverity.INFO, "A few spots pure white",
                "A few small spots are pure white. That is normal for lamps, sun or reflections.", 0.85f, LessonIds.EXPOSURE, "%.1f%% pure white".format(clipped))
        }
        val crushed = vs.shadowClipping * 100
        if (crushed > 15f && scene.sceneType != SceneType.NIGHT) {
            add("sh", FindingSeverity.SUGGESTION, "Very dark areas",
                "Big areas are almost black. Slide the ☀ slider up or find more light if you want to see detail there.",
                0.85f, LessonIds.EXPOSURE_COMP, "%.0f%% near black".format(crushed))
        }
        val brightness = vs.meanLuma
        when {
            brightness < 0.2f && scene.sceneType != SceneType.NIGHT -> add("dark", FindingSeverity.SUGGESTION, "Photo is too dark",
                "The photo came out darker than usual. Tap your subject, or slide the ☀ slider up.", 0.75f, LessonIds.EXPOSURE_COMP,
                "average brightness ${(brightness * 100).roundToInt()}%")
            brightness > 0.78f -> add("bright", FindingSeverity.SUGGESTION, "Photo is too bright",
                "The photo came out brighter than usual. Slide the ☀ slider down a little to keep colour and detail.", 0.75f, LessonIds.EXPOSURE_COMP,
                "average brightness ${(brightness * 100).roundToInt()}%")
            clipped <= 0.5f && crushed <= 5f -> add("exp_ok", FindingSeverity.STRENGTH, "Well exposed",
                "Both the bright and dark parts show detail.", 0.8f, null, "nothing pure white or pure black")
        }

        // Sharpness and shutter speed
        val shutterNs = meta?.exposureTimeNs
        val focal35 = meta?.focalLength35mm
        val shakeRisk = shutterNs != null && focal35 != null && shutterNs > 1_000_000_000L / (2 * focal35.coerceAtLeast(12))
        when {
            vs.sharpness < 0.12f -> add("soft", FindingSeverity.ISSUE, "Looks blurry",
                if (shakeRisk) "The photo is blurry, probably because the phone moved while it was dark. Hold the phone still or find more light."
                else "The photo is blurry - the focus may have missed or something moved. Tap the subject before you shoot.",
                if (shakeRisk) 0.75f else 0.6f, if (shakeRisk) LessonIds.MOTION_BLUR else LessonIds.FOCUS,
                "sharpness ${(vs.sharpness * 100).roundToInt()}/100")
            vs.sharpness > 0.35f -> add("sharp", FindingSeverity.STRENGTH, "Crisp detail",
                "Small details are clear and sharp.", 0.7f, null, "sharpness ${(vs.sharpness * 100).roundToInt()}/100")
        }
        if (shakeRisk && vs.sharpness >= 0.12f) {
            add("slow", FindingSeverity.INFO, "Low light",
                "It was quite dark, so the camera took longer to take the photo. You held still this time - in dim light, keep the phone steady.",
                0.8f, LessonIds.SHUTTER_ISO, meta?.shutterLabel())
        }
        meta?.iso?.let { iso ->
            if (iso >= 1600) add("iso", FindingSeverity.INFO, "Grainy (low light)",
                "In low light photos can look grainy. More light gives cleaner photos.", 0.85f, LessonIds.SHUTTER_ISO, "ISO $iso")
        }

        // Level
        val tilt = captureTilt ?: scene.horizon?.tiltDegrees
        val horizonRelevant = scene.horizon?.isVisibleLine == true || scene.sceneType in setOf(SceneType.LANDSCAPE, SceneType.ARCHITECTURE)
        if (tilt != null && horizonRelevant) {
            when {
                abs(tilt) in 1.5f..12f -> add("tilt", FindingSeverity.ISSUE, "Photo is tilted",
                    "The photo leans by about %.1f°. If you didn't mean it, wait for the level line to turn green before shooting.".format(abs(tilt)),
                    0.85f, LessonIds.HORIZON, "%.1f°".format(tilt))
                abs(tilt) < 1f -> add("level", FindingSeverity.STRENGTH, "Straight photo", "The phone was held straight.", 0.85f, null, "%.1f°".format(tilt))
            }
        }
        return out
    }

    fun artistic(scene: SceneSnapshot, composition: CompositionCandidate?, alignment: AlignmentState?): List<PhotoFinding> {
        val vs = scene.visualSignals
        val out = mutableListOf<PhotoFinding>()
        fun add(id: String, sev: FindingSeverity, title: String, detail: String, conf: Float, lesson: String?) {
            out += PhotoFinding(id, FindingCategory.ARTISTIC, sev, title, detail, conf, lesson)
        }
        val subject = scene.primarySubject

        if (composition != null) {
            if (alignment == AlignmentState.READY) {
                add("comp_followed", FindingSeverity.STRENGTH, "You matched the guide",
                    "You lined the subject up with the \"${composition.strategy.displayName}\" guide.", 0.8f, composition.strategy.lessonId)
            } else if (alignment == AlignmentState.NOT_ALIGNED) {
                add("comp_missed", FindingSeverity.SUGGESTION, "Moved off the guide",
                    "The subject wasn't on the circle when you took the photo - that's fine if you changed your mind.", 0.6f, composition.strategy.lessonId)
            }
        }

        if (subject != null) {
            val c = subject.boundingBox.center
            val centered = abs(c.x - 0.5f) < 0.07f
            val onThird = abs(c.x - 1f / 3f) < 0.07f || abs(c.x - 2f / 3f) < 0.07f
            when {
                centered && (vs.symmetry > 0.5f || composition?.strategy == CompositionStrategy.CENTERED_SYMMETRY) ->
                    add("centred_sym", FindingSeverity.STRENGTH, "Nicely balanced", "The subject in the middle matches the even scene.", 0.65f, LessonIds.SYMMETRY)
                centered && composition == null ->
                    add("centred", FindingSeverity.SUGGESTION, "Subject in the middle",
                        "A centred subject feels calm. Putting it a little to one side could make it more lively - both are fine.", 0.55f, LessonIds.THIRDS)
                onThird -> add("thirds", FindingSeverity.STRENGTH, "Good placement", "The subject sits a little to one side, which looks lively.", 0.65f, LessonIds.THIRDS)
            }
            if (subject.boundingBox.area < 0.03f && vs.clutter > 0.45f) {
                add("small", FindingSeverity.SUGGESTION, "Subject looks small",
                    "The subject is small and there is a lot around it. Stepping closer would make it clearer.", 0.6f, LessonIds.FILL_FRAME)
            }
        }

        vs.separationIssue?.let {
            add("merge", FindingSeverity.SUGGESTION, "Busy background",
                if (it.verticalLineBehindSubject) "Something in the background seems to stick out from behind the subject. A small step to the side would fix it."
                else "The background next to the subject is busy. A slightly different angle would help it stand out.", 0.55f, LessonIds.SEPARATION)
        }
        vs.edgeDistractions.maxByOrNull { it.strength }?.takeIf { it.strength > 0.5f }?.let {
            add("edge", FindingSeverity.SUGGESTION, "Something at the edge",
                "The ${it.description} at the ${it.edge.label} edge may pull the eye away. Moving the phone a little would leave it out.", 0.55f, LessonIds.CLEAN_EDGES)
        }
        if (scene.sceneType == SceneType.LANDSCAPE) {
            if (vs.foregroundDetail > 0.35f) add("fg", FindingSeverity.STRENGTH, "Nice depth", "Things close to you lead the eye into the scene.", 0.6f, LessonIds.FOREGROUND_DEPTH)
            else add("fg_none", FindingSeverity.SUGGESTION, "Add something close", "A rock, path or plant close to you would add depth.", 0.5f, LessonIds.FOREGROUND_DEPTH)
        }
        when (scene.lighting) {
            LightingCondition.WARM_SOFT -> add("light_warm", FindingSeverity.STRENGTH, "Lovely warm light", "The soft, warm light makes the scene look nice.", 0.6f, LessonIds.GOLDEN_HOUR)
            LightingCondition.BACKLIT -> add("light_back", FindingSeverity.SUGGESTION, "Light behind the subject",
                "The subject is darker than the background. Tap the subject to brighten it, or keep it dark as a silhouette.", 0.6f, LessonIds.BACKLIGHT)
            LightingCondition.BRIGHT_HARSH -> add("light_harsh", FindingSeverity.SUGGESTION, "Strong sunlight",
                "Strong sun from above makes hard shadows. Shade, or light from the side, is softer.", 0.55f, LessonIds.HARSH_LIGHT)
            else -> Unit
        }
        if (vs.leadingLines.isNotEmpty() && vs.lineConvergence != null) {
            add("lines", FindingSeverity.STRENGTH, "Lines lead the eye", "Lines in the scene guide the eye through the photo.", 0.55f, LessonIds.LEADING_LINES)
        }
        return out
    }

    fun assemble(technical: List<PhotoFinding>, artistic: List<PhotoFinding>, aiSummary: String?, aiUsed: Boolean, now: Long): PhotoFeedback {
        val all = technical + artistic
        val priority = all.filter { it.severity == FindingSeverity.ISSUE }.maxByOrNull { it.confidence }
            ?: all.filter { it.severity == FindingSeverity.SUGGESTION }.maxByOrNull { it.confidence }
        val strengths = all.filter { it.severity == FindingSeverity.STRENGTH }
        val summary = aiSummary ?: buildString {
            if (strengths.isNotEmpty()) append(strengths.take(2).joinToString(" and ") { it.title.lowercase() }.replaceFirstChar { it.uppercase() }).append(". ")
            if (priority != null) append("One thing to try: ${priority.title.lowercase()}.") else append("Nothing important to fix.")
        }.trim()
        val nextLesson = priority?.lessonId ?: strengths.firstNotNullOfOrNull { it.lessonId }
        return PhotoFeedback(summary, all, nextLesson, aiUsed, now)
    }
}

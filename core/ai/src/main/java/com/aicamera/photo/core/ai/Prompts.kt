package com.aicamera.photo.core.ai

import com.aicamera.photo.domain.CoachingRecommendation
import com.aicamera.photo.domain.CompositionCandidate
import com.aicamera.photo.domain.ConfidenceLevel
import com.aicamera.photo.domain.NormalizedPoint
import com.aicamera.photo.domain.PhotoFinding
import com.aicamera.photo.domain.RecommendationType
import com.aicamera.photo.domain.SceneSnapshot
import kotlin.math.abs
import kotlin.math.roundToInt

/** Builds structured prompts. Structured vision data is always provided so the model reasons over facts. */
object Prompts {

    val COACH_SYSTEM = """
        You are a concise photography coach inside a phone camera viewfinder.
        You see one preview frame and structured measurements from the camera's vision system.
        Give practical, specific advice a casual photographer can act on in seconds.
        Write like you are talking to a friend: simple everyday English, short common words, no
        photography jargon (never say composition, exposure, negative space, leading lines, rule of
        thirds, depth of field, bokeh, framing, foreground). Example: "Step to the left so the tree
        isn't behind her head." Instruction: one sentence, at most 14 words.
        Rules:
        - Suggest physical actions (move, step, crouch, aim, tilt, wait, include, get closer) - never camera settings commands.
        - Never claim to take photos or change settings yourself.
        - Respect artistic intent: centred subjects and deliberate tilts are valid choices.
        - Call the main subject "the subject" unless a subject line below names it. Scene labels are
          uncertain guesses from a classifier - never name an object from them.
        - Do not repeat the suggestions already shown.
        Reply with ONLY a JSON object, no other text:
        {"scene":"<max 8 words>","suggestions":[{"type":"<TYPE>","title":"<max 4 words>","instruction":"<one imperative sentence, max 90 characters>","rationale":"<max 70 characters>","priority":0.0,"confidence":0.0}]}
        TYPE is one of: ${RecommendationType.entries.joinToString(",") { it.name }}.
        Give exactly 1 suggestion - the single most useful one - or an empty list if the frame already works well.
    """.trimIndent()

    val CRITIQUE_SYSTEM = """
        You are a kind, honest photography teacher reviewing one finished photo.
        Measured technical facts are given to you; do not contradict them and do not repeat them.
        Comment only on artistic aspects: composition, light, mood, storytelling, viewpoint.
        Treat everything as an opinion, using words like "could" or "may". Respect deliberate choices.
        Use simple everyday English with short common words and no photography jargon.
        Reply with ONLY a JSON object, no other text:
        {"summary":"<one sentence, max 120 characters>","observations":[{"kind":"strength|suggestion","title":"<max 5 words>","detail":"<max 140 characters>"}]}
        Give 1 to 3 observations, including at least one strength.
    """.trimIndent()

    fun coachPrompt(scene: SceneSnapshot, candidates: List<CompositionCandidate>, shown: List<CoachingRecommendation>): String = buildString {
        appendLine("Measurements:")
        appendLine("- scene: ${scene.sceneType.label} (confidence ${pct(scene.sceneConfidence)})")
        if (scene.sceneLabels.isNotEmpty()) appendLine("- uncertain classifier guesses (do not name objects from these): ${scene.sceneLabels.take(3).joinToString()}")
        appendLine("- light: ${scene.lighting.label}; brightness ${pct(scene.visualSignals.meanLuma)}; clipped highlights ${pct(scene.visualSignals.highlightClipping)}")
        scene.subjects.sortedByDescending { it.importance }.take(4).forEach {
            appendLine("- ${if (it.confidenceLevel == ConfidenceLevel.LOW) "unidentified subject" else it.describe()} at ${where(it.boundingBox.center)}, ${pct(it.boundingBox.area)} of frame")
        }
        if (scene.subjects.isEmpty()) appendLine("- no clear subject detected")
        scene.horizon?.let { appendLine("- camera tilt ${"%.1f".format(it.tiltDegrees)} degrees${if (it.isVisibleLine) ", horizon line visible at ${pct(it.positionY ?: 0.5f)} height" else ""}") }
        val vs = scene.visualSignals
        appendLine("- background clutter ${pct(vs.clutter)}, symmetry ${pct(vs.symmetry)}, foreground detail ${pct(vs.foregroundDetail)}")
        if (candidates.isNotEmpty()) appendLine("Compositions that fit: ${candidates.joinToString { it.strategy.displayName }}")
        if (shown.isNotEmpty()) appendLine("Already suggested (do not repeat): ${shown.joinToString { it.instruction }}")
        append("Return the JSON now.")
    }

    fun critiquePrompt(scene: SceneSnapshot, technical: List<PhotoFinding>, strategyName: String?): String = buildString {
        appendLine("Photo facts:")
        appendLine("- scene: ${scene.sceneType.label}; light: ${scene.lighting.label}")
        scene.primarySubject?.let { appendLine("- main subject: ${it.describe()} at ${where(it.boundingBox.center)}, ${pct(it.boundingBox.area)} of frame") }
        strategyName?.let { appendLine("- the photographer chose the \"$it\" composition") }
        technical.forEach { appendLine("- measured: ${it.title}${it.measurement?.let { m -> " ($m)" } ?: ""}") }
        append("Return the JSON now.")
    }

    private fun pct(v: Float) = "${(v * 100).roundToInt()}%"

    fun where(p: NormalizedPoint): String {
        val h = when {
            p.x < 0.38f -> "left"
            p.x > 0.62f -> "right"
            else -> "centre"
        }
        val v = when {
            p.y < 0.38f -> "top"
            p.y > 0.62f -> "bottom"
            else -> "middle"
        }
        return if (h == "centre" && v == "middle") "the centre" else "$v $h"
    }

    fun angleWords(deg: Float) = if (abs(deg) < 1f) "level" else "%.1f degrees".format(deg)
}

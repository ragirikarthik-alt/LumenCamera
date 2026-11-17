package com.aicamera.photo.core.ai

import com.aicamera.photo.domain.CoachingRecommendation
import com.aicamera.photo.domain.FindingCategory
import com.aicamera.photo.domain.FindingSeverity
import com.aicamera.photo.domain.PhotoFinding
import com.aicamera.photo.domain.RecommendationSource
import com.aicamera.photo.domain.RecommendationType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AiSuggestionDto(
    val type: String = "",
    val title: String = "",
    val instruction: String = "",
    val rationale: String = "",
    val priority: Float = 0.5f,
    val confidence: Float = 0.5f,
)

@Serializable
data class AiCoachResponseDto(val scene: String = "", val suggestions: List<AiSuggestionDto> = emptyList())

@Serializable
data class AiObservationDto(val kind: String = "", val title: String = "", val detail: String = "")

@Serializable
data class AiCritiqueResponseDto(val summary: String = "", val observations: List<AiObservationDto> = emptyList())

/**
 * The safety boundary between the model and the app (spec 8.2): model output is untrusted text.
 * Only well-formed, short, actionable recommendation data passes; anything resembling a command,
 * a setting change or an automatic action is dropped. Nothing here can reach camera hardware.
 */
object ResponseValidator {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    const val MAX_TITLE = 32
    const val MAX_INSTRUCTION = 110
    const val MAX_RATIONALE = 90
    /** AI suggestions are interpretations; cap their confidence below deterministic measurements. */
    const val MAX_AI_CONFIDENCE = 0.75f

    private val actionVerbs = setOf(
        "move", "step", "tilt", "aim", "crouch", "kneel", "get", "include", "wait", "try", "place", "shoot",
        "turn", "lower", "raise", "tap", "hold", "frame", "use", "switch", "rotate", "level", "reframe",
        "fill", "back", "go", "look", "find", "position", "keep", "shift", "walk", "angle", "center", "centre",
        "put", "let", "bring", "exclude", "remove", "avoid", "compose", "consider", "focus", "expose", "zoom",
    )
    private val forbidden = listOf(
        Regex("""\b(set|change|adjust)\s+(the\s+)?(iso|shutter|aperture|white balance|exposure time)\s+to\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(i will|i'll|i have|i've|automatically|auto-?capture|taking the (photo|picture))\b""", RegexOption.IGNORE_CASE),
        Regex("""https?://""", RegexOption.IGNORE_CASE),
        Regex("""[{}<>`]"""),
    )
    private val vague = listOf("improve composition", "make it better", "improve the photo", "better photo")

    /** Extracts the first JSON object from a model reply (tolerates code fences and preambles). */
    fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    fun parseCoach(raw: String, now: Long, ttlMs: Long = 15_000, alreadyShown: Set<RecommendationType> = emptySet()): List<CoachingRecommendation> {
        val body = extractJson(raw) ?: return emptyList()
        val dto = runCatching { json.decodeFromString(AiCoachResponseDto.serializer(), body) }.getOrNull() ?: return emptyList()
        return dto.suggestions.take(2).mapIndexedNotNull { i, s ->
            val type = RecommendationType.entries.firstOrNull { it.name.equals(s.type.trim(), ignoreCase = true) } ?: return@mapIndexedNotNull null
            if (type in alreadyShown) return@mapIndexedNotNull null
            val instruction = clean(s.instruction, MAX_INSTRUCTION) ?: return@mapIndexedNotNull null
            if (!isActionable(instruction)) return@mapIndexedNotNull null
            val title = clean(s.title, MAX_TITLE) ?: type.label
            CoachingRecommendation(
                id = "AI:${type.name}:${now / 1000}:$i",
                title = title,
                instruction = instruction,
                priority = (s.priority.takeIf { !it.isNaN() } ?: 0.5f).coerceIn(0.2f, 0.85f),
                expiresAt = now + ttlMs,
                type = type,
                confidence = (s.confidence.takeIf { !it.isNaN() } ?: 0.5f).coerceIn(0f, MAX_AI_CONFIDENCE),
                source = RecommendationSource.LOCAL_AI,
                rationale = clean(s.rationale, MAX_RATIONALE) ?: "",
            )
        }
    }

    fun parseCritique(raw: String): Pair<String?, List<PhotoFinding>> {
        val body = extractJson(raw) ?: return null to emptyList()
        val dto = runCatching { json.decodeFromString(AiCritiqueResponseDto.serializer(), body) }.getOrNull() ?: return null to emptyList()
        val findings = dto.observations.take(3).mapIndexedNotNull { i, o ->
            val title = clean(o.title, 40) ?: return@mapIndexedNotNull null
            val detail = clean(o.detail, 160) ?: return@mapIndexedNotNull null
            PhotoFinding(
                id = "ai_$i",
                category = FindingCategory.ARTISTIC,
                severity = if (o.kind.trim().equals("strength", true)) FindingSeverity.STRENGTH else FindingSeverity.SUGGESTION,
                title = title,
                detail = detail,
                confidence = 0.6f,
                source = RecommendationSource.LOCAL_AI,
            )
        }
        return clean(dto.summary, 140) to findings
    }

    /** Photography jargon the model sometimes uses anyway, mapped to everyday words. */
    private val plainWords = listOf(
        "negative space" to "empty space",
        "leading lines" to "lines",
        "leading line" to "line",
        "rule of thirds" to "off-centre placement",
        "depth of field" to "background blur",
        "bokeh" to "background blur",
        "foreground" to "front",
        "backlit" to "lit from behind",
        "overexposed" to "too bright",
        "underexposed" to "too dark",
        "exposure" to "brightness",
        "composition" to "framing",
        "juxtaposition" to "contrast",
        "vantage point" to "spot",
    )

    fun plain(text: String): String {
        var t = text
        for ((jargon, simple) in plainWords) t = t.replace(Regex("\\b" + Regex.escape(jargon) + "\\b", RegexOption.IGNORE_CASE), simple)
        return t
    }

    /** Trims, removes control characters, rejects forbidden content and truncates at a word boundary. */
    fun clean(text: String, max: Int): String? {
        var t = plain(text).replace(Regex("""[\p{Cntrl}]"""), " ").replace(Regex("""\s+"""), " ").trim().trim('"')
        if (t.isEmpty()) return null
        if (forbidden.any { it.containsMatchIn(t) }) return null
        if (t.length > max) {
            val cut = t.lastIndexOf(' ', max - 1).takeIf { it > max / 2 } ?: (max - 1)
            t = t.substring(0, cut).trimEnd(',', ';', ' ') + "…"
        }
        return t
    }

    fun isActionable(instruction: String): Boolean {
        val lower = instruction.lowercase()
        if (vague.any { lower.contains(it) }) return false
        val first = lower.split(' ', ',', '-').firstOrNull { it.isNotBlank() } ?: return false
        return first.trimEnd('.', '!') in actionVerbs
    }
}

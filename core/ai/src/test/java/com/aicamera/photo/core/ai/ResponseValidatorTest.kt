package com.aicamera.photo.core.ai

import com.aicamera.photo.domain.FindingCategory
import com.aicamera.photo.domain.RecommendationSource
import com.aicamera.photo.domain.RecommendationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseValidatorTest {
    private val now = 1_000_000L

    @Test
    fun parsesFencedJsonAndClampsValues() {
        val raw = """
            Sure! Here you go:
            ```json
            {"scene":"beach","suggestions":[{"type":"foreground_depth","title":"Add depth","instruction":"Include the rocks at your feet in the bottom third.","rationale":"Layers","priority":1.7,"confidence":0.99}]}
            ```
        """.trimIndent()
        val recs = ResponseValidator.parseCoach(raw, now)
        assertEquals(1, recs.size)
        val r = recs[0]
        assertEquals(RecommendationType.FOREGROUND_DEPTH, r.type)
        assertEquals(RecommendationSource.LOCAL_AI, r.source)
        assertTrue(r.priority <= 0.85f)
        assertTrue(r.confidence <= ResponseValidator.MAX_AI_CONFIDENCE)
        assertTrue(r.expiresAt > now)
    }

    @Test
    fun rejectsUnknownTypesCommandsAndVagueAdvice() {
        val raw = """{"suggestions":[
            {"type":"ZOOM_HACK","instruction":"Move left"},
            {"type":"EXPOSURE","instruction":"Set ISO to 3200 now"},
            {"type":"VIEWPOINT","instruction":"Improve composition"},
            {"type":"HORIZON","instruction":"I will take the photo automatically"}
        ]}"""
        assertTrue(ResponseValidator.parseCoach(raw, now).isEmpty())
    }

    @Test
    fun garbageProducesNothing() {
        assertTrue(ResponseValidator.parseCoach("no json here", now).isEmpty())
        assertTrue(ResponseValidator.parseCoach("{broken", now).isEmpty())
    }

    @Test
    fun skipsTypesAlreadyShown() {
        val raw = """{"suggestions":[{"type":"HORIZON","instruction":"Level the horizon before shooting."}]}"""
        assertTrue(ResponseValidator.parseCoach(raw, now, alreadyShown = setOf(RecommendationType.HORIZON)).isEmpty())
    }

    @Test
    fun longTextIsTruncatedAtWordBoundary() {
        val long = "Step " + "slowly ".repeat(40)
        val t = ResponseValidator.clean(long, ResponseValidator.MAX_INSTRUCTION)!!
        assertTrue(t.length <= ResponseValidator.MAX_INSTRUCTION + 1)
        assertTrue(t.endsWith("…"))
    }

    @Test
    fun critiqueIsArtisticOnly() {
        val raw = """{"summary":"A calm scene.","observations":[{"kind":"strength","title":"Soft light","detail":"The light flatters the subject."},{"kind":"suggestion","title":"Try lower","detail":"A lower angle could add drama."}]}"""
        val (summary, findings) = ResponseValidator.parseCritique(raw)
        assertEquals("A calm scene.", summary)
        assertEquals(2, findings.size)
        assertTrue(findings.all { it.category == FindingCategory.ARTISTIC && it.source == RecommendationSource.LOCAL_AI })
    }

    @Test
    fun extractJsonHandlesBracesInStrings() {
        assertEquals("""{"a":"x}y"}""", ResponseValidator.extractJson("""prefix {"a":"x}y"} suffix"""))
        assertNull(ResponseValidator.extractJson("nothing"))
    }
}

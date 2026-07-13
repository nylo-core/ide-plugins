package dev.nylo.plugin.logs.render

import dev.nylo.plugin.logs.NdjsonFixtures
import dev.nylo.plugin.logs.filter.LogFilter
import dev.nylo.plugin.logs.filter.LogQuery
import dev.nylo.plugin.logs.filter.SortOrder
import dev.nylo.plugin.logs.parse.LogParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRendererTest {

    @Test
    fun `reconstructs banner and log text and folds detailed network entries`() {
        val doc = LogParser.parse(NdjsonFixtures.TWO_SESSIONS)
        val result = LogRenderer.render(LogFilter.apply(doc, LogQuery(sort = SortOrder.OLDEST_FIRST)))

        // Banner + log lines are rebuilt as readable text (not JSON).
        assertTrue(result.text.contains("SESSION  2026-06-17T11-52-55-4lfu9t"))
        assertTrue(result.text.contains("[AppProvider] setup in 217ms"))
        // The single-line request is rendered inline (no fold).
        assertTrue(result.text.contains("→ [GET]"))

        // The response (body) and the error (errorType/message) carry detail, so each gets a fold.
        assertEquals(2, result.folds.size)
        assertTrue(result.folds.any { it.placeholder.contains("200 OK") })
        // The folded response body is still part of the rendered text.
        assertTrue(result.text.contains("Ann"))
        result.folds.forEach {
            assertTrue(it.startOffset in 0..it.endOffset && it.endOffset <= result.text.length)
        }
    }

    @Test
    fun `renders empty document as empty text with no folds`() {
        val result = LogRenderer.render(LogParser.parse(""))
        assertEquals("", result.text)
        assertTrue(result.folds.isEmpty())
    }
}

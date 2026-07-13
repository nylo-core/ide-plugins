package dev.nylo.plugin.logs.filter

import dev.nylo.plugin.logs.NdjsonFixtures
import dev.nylo.plugin.logs.model.LogCategorizer
import dev.nylo.plugin.logs.model.LogCategory
import dev.nylo.plugin.logs.parse.LogParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogQueryTest {

    private val doc = LogParser.parse(NdjsonFixtures.TWO_SESSIONS)

    @Test
    fun `filters by short session tag`() {
        val result = LogFilter.apply(doc, LogQuery(sessionTag = "9qbhab"))
        assertEquals("9qbhab", result.sessions.single().tag)
    }

    @Test
    fun `filters by full session id`() {
        val result = LogFilter.apply(doc, LogQuery(sessionTag = "2026-06-17T11-52-55-4lfu9t"))
        assertEquals("4lfu9t", result.sessions.single().tag)
    }

    @Test
    fun `text filter keeps only matching sessions and entries`() {
        val result = LogFilter.apply(doc, LogQuery(text = "user"))
        val session = result.sessions.single()
        assertEquals("9qbhab", session.tag)
        assertTrue(session.entries.isNotEmpty())
        assertTrue(session.entries.all { it.raw.contains("user", ignoreCase = true) })
    }

    @Test
    fun `newest-first orders sessions by started descending`() {
        val result = LogFilter.apply(doc, LogQuery(sort = SortOrder.NEWEST_FIRST))
        assertEquals(listOf("9qbhab", "4lfu9t"), result.sessions.map { it.tag })
    }

    @Test
    fun `oldest-first orders sessions by started ascending`() {
        val result = LogFilter.apply(doc, LogQuery(sort = SortOrder.OLDEST_FIRST))
        assertEquals(listOf("4lfu9t", "9qbhab"), result.sessions.map { it.tag })
    }

    @Test
    fun `networking tab keeps only network entries and drops network-free sessions`() {
        val result = LogFilter.apply(doc, LogQuery(category = LogCategory.NETWORKING))
        assertEquals(listOf("9qbhab"), result.sessions.map { it.tag }) // 4lfu9t has no network
        assertTrue(result.sessions.flatMap { it.entries }.all { LogCategorizer.isNetwork(it) })
    }

    @Test
    fun `console tab excludes network but keeps everything else`() {
        val result = LogFilter.apply(doc, LogQuery(category = LogCategory.CONSOLE))
        assertEquals(listOf("4lfu9t", "9qbhab"), result.sessions.map { it.tag }.sorted())
        assertTrue(result.sessions.flatMap { it.entries }.none { LogCategorizer.isNetwork(it) })
        // 9qbhab keeps only its non-network entry (the "hello" debug line).
        assertEquals(1, result.sessions.single { it.tag == "9qbhab" }.entries.size)
    }

    @Test
    fun `errors tab keeps error lines and network errors across sessions`() {
        val result = LogFilter.apply(doc, LogQuery(category = LogCategory.ERRORS))
        assertEquals(listOf("4lfu9t", "9qbhab"), result.sessions.map { it.tag }.sorted())
        val entries = result.sessions.flatMap { it.entries }
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.all { LogCategorizer.isError(it) })
    }

    @Test
    fun `all tab is identity`() {
        val result = LogFilter.apply(doc, LogQuery(category = LogCategory.ALL))
        assertEquals(doc.sessions.map { it.tag }.toSet(), result.sessions.map { it.tag }.toSet())
        assertEquals(doc.sessions.sumOf { it.entries.size }, result.sessions.sumOf { it.entries.size })
    }

    @Test
    fun `category and text intersect`() {
        val result = LogFilter.apply(doc, LogQuery(text = "PUT", category = LogCategory.NETWORKING))
        val entries = result.sessions.single().entries
        assertEquals(1, entries.size) // only the PUT (error) request matches; GET request/response don't
        assertTrue(entries.all { LogCategorizer.isError(it) })
        assertFalse(entries.any { it.raw.contains("[GET]") })
    }
}

package dev.nylo.plugin.logs.parse

import dev.nylo.plugin.logs.NdjsonFixtures
import dev.nylo.plugin.logs.model.NetworkEntry
import dev.nylo.plugin.logs.model.RawLine
import dev.nylo.plugin.logs.model.StandardLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class LogParserTest {

    @Test
    fun `parses session fields, log lines and console lines`() {
        val doc = LogParser.parse(NdjsonFixtures.TWO_SESSIONS)
        assertEquals(2, doc.sessions.size)

        val s0 = doc.sessions[0]
        assertEquals("4lfu9t", s0.tag)
        assertEquals("2026-06-17T11-52-55-4lfu9t", s0.fullId)
        assertEquals(LocalDateTime.of(2026, 6, 17, 11, 52, 55), s0.started)
        assertEquals("ios Version 26.5 (Build 23F77)", s0.platform)
        assertEquals("Pretalk 1.0.0", s0.app)
        assertEquals("nylo_framework v7.1.24", s0.version)
        assertEquals("developing", s0.env)
        // The banner block is reconstructed from the session record for display.
        assertTrue(s0.bannerLines.any { it.contains("SESSION  2026-06-17T11-52-55-4lfu9t") })

        val stds = s0.entries.filterIsInstance<StandardLine>()
        assertEquals(3, stds.size)
        assertEquals("debug", stds[0].level)
        assertEquals("4lfu9t", stds[0].sessionTag)
        assertEquals("[AppProvider] setup in 217ms", stds[0].message)
        assertEquals("error", stds[1].level)
        assertEquals("boom", stds[1].message)
        assertTrue(stds[1].stack!!.contains("#0      foo"))
        assertTrue(stds[1].raw.contains("\n#0      foo")) // stack is appended to the displayed line
        // console record -> a StandardLine with no level.
        assertNull(stds[2].level)
        assertEquals("flutter: console output", stds[2].message)
    }

    @Test
    fun `parses network records with kind and structured fields`() {
        val s1 = LogParser.parse(NdjsonFixtures.TWO_SESSIONS).sessions[1]
        assertEquals("9qbhab", s1.tag)

        val nets = s1.entries.filterIsInstance<NetworkEntry>()
        assertEquals(3, nets.size)
        assertEquals(NetworkEntry.Kind.REQUEST, nets[0].kind)
        assertEquals("GET", nets[0].method)
        assertEquals("096232f7", nets[0].requestId)
        assertTrue(nets[0].summary.contains("GET"))
        assertEquals(NetworkEntry.Kind.RESPONSE, nets[1].kind)
        assertEquals(200, nets[1].statusCode)
        assertEquals(NetworkEntry.Kind.ERROR, nets[2].kind)
        assertEquals(500, nets[2].statusCode)
        // Verbose response detail (the body) is folded into the entry's expanded text.
        assertTrue(nets[1].raw.contains("Ann"))
    }

    @Test
    fun `log record without session or level`() {
        val doc = LogParser.parse("""{"t":"log","ts":"2026-06-17T11:52:55","msg":"hello world"}""")
        val line = doc.preamble.filterIsInstance<StandardLine>().single()
        assertNull(line.sessionTag)
        assertNull(line.level)
        assertEquals("hello world", line.message)
        assertEquals(LocalDateTime.of(2026, 6, 17, 11, 52, 55), line.timestamp)
    }

    @Test
    fun `records before the first session are preamble`() {
        val doc = LogParser.parse("""{"t":"console","msg":"boot starting"}""" + "\n" + NdjsonFixtures.TWO_SESSIONS)
        assertEquals(2, doc.sessions.size)
        assertTrue(doc.preamble.any { it.raw.contains("boot starting") })
    }

    @Test
    fun `non-json and unknown records become raw lines`() {
        val doc = LogParser.parse("not json at all\n" + """{"t":"mystery","msg":"x"}""")
        val raws = doc.preamble.filterIsInstance<RawLine>()
        assertTrue(raws.any { it.raw == "not json at all" })
        assertTrue(raws.any { it.raw.contains("mystery") })
    }

    @Test
    fun `carriage returns are normalized out`() {
        val one = """{"t":"console","msg":"line one"}"""
        val two = """{"t":"console","msg":"line two"}"""
        val doc = LogParser.parse(one + "\r\n" + two + "\r\n")
        assertEquals(listOf("line one", "line two"), doc.preamble.filterIsInstance<StandardLine>().map { it.message })
        assertTrue(doc.preamble.all { '\r' !in it.raw })
    }

    @Test
    fun `json-escaped carriage returns inside fields never reach the raw text`() {
        // A \r inside a JSON string survives whole-file normalization (it exists in the file only
        // as the two-char escape); a literal CR in `raw` would crash the editor Document.
        val record = """{"t":"log","ts":"2026-06-26 10:00:00","session":"s1","level":"error",""" +
            """"msg":"line1\r\nline2","stack":"#0 a\r#1 b"}"""
        val line = LogParser.parse(record).preamble.single() as StandardLine
        assertTrue('\r' !in line.raw)
        assertTrue(line.raw.contains("line1\nline2"))
        assertTrue(line.stack!!.contains("#0 a\n#1 b"))
    }

    @Test
    fun `parses iso timestamps with negative utc offsets`() {
        val doc = LogParser.parse("""{"t":"log","ts":"2026-06-26T10:32:59-05:00","msg":"hi"}""")
        val line = doc.preamble.single() as StandardLine
        assertEquals(LocalDateTime.of(2026, 6, 26, 10, 32, 59), line.timestamp)
    }
}

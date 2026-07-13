package dev.nylo.plugin.logs.parse

import dev.nylo.plugin.logs.model.LogCategorizer
import dev.nylo.plugin.logs.model.NetworkEntry
import dev.nylo.plugin.logs.model.StandardLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Contract guard: parses the *exact* NDJSON lines emitted by `nylo_support`'s `NyFileLogger`
 * (captured from its Phase A test run) so a future change on either side that breaks the shared
 * schema fails here. Verifies microsecond timestamps and superset network fields are tolerated.
 */
class LogParserFrameworkSampleTest {

    private val sample = listOf(
        """{"t":"session","id":"2026-06-26T14-17-24-idrxkz","started":"2026-06-26T14:17:24.223296","platform":"macos Version 26.5.1 (Build 25F80)","app":"Pretalk 1.0.0","version":"nylo_framework v7.1.24","env":"developing"}""",
        """{"t":"log","ts":"2026-06-26T10:32:59.123","session":"idrxkz","level":"debug","msg":"[AppProvider] Booting"}""",
        """{"t":"log","ts":"2026-06-26T10:33:00.001","session":"idrxkz","level":"error","msg":"Failed to load user","context":{"id":123,"retry":true},"stack":"#0 main (file.dart:1:1)\n#1 x (y:2:2)"}""",
        """{"t":"console","ts":"2026-06-26T14:17:24.229440","session":"idrxkz","msg":"flutter: raw console line"}""",
        """{"t":"net","session":"idrxkz","ts":"2026-06-26T14:17:24.238011","kind":"request","requestId":"236f0833","method":"PUT","uri":"https://api.example.com/users/5","headers":{},"contentType":null,"responseType":"ResponseType.json","body":{"name":"Ada"}}""",
        """{"t":"net","session":"idrxkz","ts":"2026-06-26T14:17:24.300000","kind":"response","requestId":"236f0833","method":"PUT","uri":"https://api.example.com/users/5","statusCode":200,"statusMessage":"OK","responseTimeMs":5,"payloadSizeBytes":18,"payloadSize":"18 B","data":{"id":5,"name":"Ada"}}""",
        """{"t":"net","session":"idrxkz","ts":"2026-06-26T14:17:24.400000","kind":"error","requestId":"236f0833","errorType":"DioExceptionType.badResponse","method":"PUT","uri":"https://api.example.com/users/5","responseTimeMs":7,"payloadSizeBytes":15,"payloadSize":"15 B","statusCode":500,"statusMessage":"Server Error","data":{"error":"kaboom"},"message":"Http status error [500]"}""",
    ).joinToString("\n")

    @Test
    fun `parses the real framework NDJSON output`() {
        val s = LogParser.parse(sample).sessions.single()

        assertEquals("idrxkz", s.tag)
        assertEquals("2026-06-26T14-17-24-idrxkz", s.fullId)
        assertEquals("nylo_framework v7.1.24", s.version)
        // Microsecond fraction (.223296) parses; display drops it.
        assertEquals(LocalDateTime.of(2026, 6, 26, 14, 17, 24, 223_296_000), s.started)
        assertEquals("2026-06-26 14:17:24", s.startedRaw)

        val stds = s.entries.filterIsInstance<StandardLine>()
        assertEquals(3, stds.size) // debug, error(+context+stack), console
        val errorLine = stds.single { it.level == "error" }
        assertTrue(errorLine.raw.contains("Failed to load user"))
        assertTrue(errorLine.raw.contains("\"retry\":true")) // context rendered inline
        assertTrue(errorLine.raw.contains("\n#0 main")) // stack appended
        assertNull(stds.single { it.message.contains("raw console line") }.level) // console has no level

        val nets = s.entries.filterIsInstance<NetworkEntry>()
        assertEquals(3, nets.size)
        assertEquals(NetworkEntry.Kind.REQUEST, nets[0].kind)
        assertEquals("236f0833", nets[0].requestId)
        assertTrue(nets[0].summary.contains("[PUT]"))
        assertTrue(nets[0].raw.contains("Ada")) // body folded in; empty headers{} skipped
        assertTrue(nets[0].raw.contains("headers").not())
        assertEquals(200, nets[1].statusCode)
        assertTrue(nets[1].raw.contains("\"name\": \"Ada\"")) // response body pretty-printed
        assertEquals(NetworkEntry.Kind.ERROR, nets[2].kind)
        assertEquals(500, nets[2].statusCode)

        // Categorization: the error log line and the net error both land in ERRORS.
        assertTrue(LogCategorizer.isError(errorLine))
        assertTrue(LogCategorizer.isError(nets[2]))
        assertTrue(LogCategorizer.isNetwork(nets[0]))
    }
}

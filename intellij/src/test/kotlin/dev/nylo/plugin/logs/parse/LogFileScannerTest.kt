package dev.nylo.plugin.logs.parse

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

class LogFileScannerTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("nylo-logs-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `returns empty when dir does not exist`() {
        assertTrue(LogFileScanner.scan(File(dir, "missing")).isEmpty())
    }

    @Test
    fun `discovers log files newest-first and ignores others`() {
        File(dir, "2026-06-17.log").writeText("x")
        File(dir, "2026-06-19.log").writeText("x")
        File(dir, "2026-06-18.log").writeText("x")
        File(dir, "readme.md").writeText("x")          // not a .log
        File(dir, "2026-99-99.log").writeText("x")     // matches the name shape but is not a real date

        val dates = LogFileScanner.scan(dir).map { it.date }
        assertEquals(
            listOf(LocalDate.parse("2026-06-19"), LocalDate.parse("2026-06-18"), LocalDate.parse("2026-06-17")),
            dates,
        )
    }

    @Test
    fun `defaultDate prefers today when present`() {
        val dates = listOf(LocalDate.parse("2026-06-19"), LocalDate.parse("2026-06-17"))
        assertEquals(LocalDate.parse("2026-06-17"), LogFileScanner.defaultDate(dates, LocalDate.parse("2026-06-17")))
    }

    @Test
    fun `defaultDate falls back to newest when today is missing`() {
        val dates = listOf(LocalDate.parse("2026-06-19"), LocalDate.parse("2026-06-17"))
        assertEquals(LocalDate.parse("2026-06-19"), LogFileScanner.defaultDate(dates, LocalDate.parse("2026-06-20")))
    }

    @Test
    fun `defaultDate is null when there are no logs`() {
        assertNull(LogFileScanner.defaultDate(emptyList(), LocalDate.parse("2026-06-20")))
    }
}

package dev.nylo.plugin.logs.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogCategoryTest {

    private val debug = std("debug", "ok")
    private val info = std("info", "fyi")
    private val error = std("error", "boom")
    private val warning = std("warning", "careful")
    private val console = std(null, "raw print")
    private val request = net(NetworkEntry.Kind.REQUEST)
    private val response = net(NetworkEntry.Kind.RESPONSE)
    private val netError = net(NetworkEntry.Kind.ERROR)
    private val raw = RawLine("#0 frame", 1..1)

    @Test
    fun `console and plain log entries are CONSOLE only`() {
        assertMembership(debug, console = true, networking = false, errors = false)
        assertMembership(info, console = true, networking = false, errors = false)
        assertMembership(console, console = true, networking = false, errors = false)
        assertMembership(raw, console = true, networking = false, errors = false)
    }

    @Test
    fun `error-level lines are both CONSOLE and ERRORS`() {
        assertMembership(error, console = true, networking = false, errors = true)
        assertMembership(warning, console = true, networking = false, errors = true)
    }

    @Test
    fun `network entries are NETWORKING and only error-kind is also ERRORS`() {
        assertMembership(request, console = false, networking = true, errors = false)
        assertMembership(response, console = false, networking = true, errors = false)
        assertMembership(netError, console = false, networking = true, errors = true)
    }

    @Test
    fun `ALL matches everything`() {
        listOf(debug, error, console, request, response, netError, raw).forEach {
            assertTrue(LogCategorizer.matches(it, LogCategory.ALL))
        }
    }

    @Test
    fun `isError covers error and warning lines and network errors only`() {
        assertTrue(LogCategorizer.isError(error))
        assertTrue(LogCategorizer.isError(warning))
        assertTrue(LogCategorizer.isError(netError))
        assertFalse(LogCategorizer.isError(debug))
        assertFalse(LogCategorizer.isError(request))
        assertFalse(LogCategorizer.isError(raw))
    }

    private fun assertMembership(entry: LogEntry, console: Boolean, networking: Boolean, errors: Boolean) {
        assertEquals(console, LogCategorizer.matches(entry, LogCategory.CONSOLE))
        assertEquals(networking, LogCategorizer.matches(entry, LogCategory.NETWORKING))
        assertEquals(errors, LogCategorizer.matches(entry, LogCategory.ERRORS))
    }

    private fun std(level: String?, message: String) =
        StandardLine(null, "-", "tag", level, message, null, null, message, 1..1)

    private fun net(kind: NetworkEntry.Kind) =
        NetworkEntry(kind, "id", "GET", "http://x", null, null, null, null, "s", "s", 1..1)
}

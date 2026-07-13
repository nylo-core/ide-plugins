package dev.nylo.plugin.screenshots.run

import org.junit.Assert.assertEquals
import org.junit.Test

class LineBufferTest {

    private fun collect(vararg chunks: String, flush: Boolean = true): List<String> {
        val out = mutableListOf<String>()
        val buffer = LineBuffer { out.add(it) }
        chunks.forEach(buffer::append)
        if (flush) buffer.flush()
        return out
    }

    @Test
    fun `emits complete lines and holds a partial line until the next chunk`() {
        val out = mutableListOf<String>()
        val buffer = LineBuffer { out.add(it) }
        buffer.append("hello\nwor")
        assertEquals(listOf("hello"), out)
        buffer.append("ld\n")
        assertEquals(listOf("hello", "world"), out)
    }

    @Test
    fun `splits several lines in a single chunk`() {
        assertEquals(listOf("a", "b", "c"), collect("a\nb\nc\n"))
    }

    @Test
    fun `trims a trailing carriage return including a split CRLF`() {
        assertEquals(listOf("a", "b"), collect("a\r\nb\r", "\n"))
    }

    @Test
    fun `flush emits a trailing line with no newline`() {
        assertEquals(listOf("only line"), collect("only line"))
    }

    @Test
    fun `flush is a no-op when everything ended on a newline`() {
        assertEquals(listOf("done"), collect("done\n"))
    }
}

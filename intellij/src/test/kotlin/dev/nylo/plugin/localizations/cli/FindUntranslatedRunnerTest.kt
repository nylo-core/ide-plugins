package dev.nylo.plugin.localizations.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FindUntranslatedRunnerTest {

    @Test
    fun `parses the findings array`() {
        val json = """
            {"project":"app","generated_at":"now","count":2,"findings":[
              {"file":"lib/resources/pages/home.dart","line":15,"column":10,"value":"Welcome","context":"Text(...)"},
              {"file":"lib/resources/widgets/badge.dart","line":8,"column":4,"value":"New","context":"Text(...)"}
            ]}
        """.trimIndent()
        val findings = FindUntranslatedRunner.parseFindings(json)
        assertEquals(2, findings.size)
        assertEquals("lib/resources/pages/home.dart", findings[0].file)
        assertEquals(15, findings[0].line)
        assertEquals("Welcome", findings[0].value)
        assertEquals("Text(...)", findings[0].context)
    }

    @Test
    fun `tolerates missing optional fields`() {
        val findings = FindUntranslatedRunner.parseFindings("""{"findings":[{"file":"a.dart"}]}""")
        assertEquals(1, findings.size)
        assertEquals(0, findings[0].line)
        assertEquals("", findings[0].value)
    }

    @Test
    fun `empty, absent, or non-object input yields an empty list`() {
        assertTrue(FindUntranslatedRunner.parseFindings("""{"findings":[]}""").isEmpty())
        assertTrue(FindUntranslatedRunner.parseFindings("""{"count":0}""").isEmpty())
        assertTrue(FindUntranslatedRunner.parseFindings("").isEmpty())
        assertTrue(FindUntranslatedRunner.parseFindings("""[1,2]""").isEmpty())
    }
}

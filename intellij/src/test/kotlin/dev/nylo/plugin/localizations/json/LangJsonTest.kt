package dev.nylo.plugin.localizations.json

import com.google.gson.JsonParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LangJsonTest {

    @Test
    fun `flattens nested objects to dot notation`() {
        val map = LangJson.parseFlattened(
            """{"common":{"ok":"OK","nested":{"deep":"D"}},"buttons":{"login":"Login"}}""",
        )
        assertEquals("OK", map["common.ok"])
        assertEquals("D", map["common.nested.deep"])
        assertEquals("Login", map["buttons.login"])
        assertEquals(3, map.size)
    }

    @Test
    fun `preserves insertion order`() {
        val map = LangJson.parseFlattened("""{"b":{"z":"1","a":"2"},"a":"3"}""")
        assertEquals(listOf("b.z", "b.a", "a"), map.keys.toList())
    }

    @Test
    fun `reads raw utf8 characters`() {
        val map = LangJson.parseFlattened("""{"a":"café","b":"💔"}""")
        assertEquals("café", map["a"])
        assertEquals("💔", map["b"])
    }

    @Test
    fun `decodes json unicode escapes`() {
        val bs = 0x5C.toChar() // backslash, kept out of source as a literal escape
        val json = "{\"a\":\"" + bs + "u0041" + bs + "u00e9\"}" // {"a":"Aé"}
        assertEquals("Aé", LangJson.parseFlattened(json)["a"])
    }

    @Test
    fun `keeps escaped quotes verbatim`() {
        // {"a":"say \"hi\""}
        val map = LangJson.parseFlattened("{\"a\":\"say \\\"hi\\\"\"}")
        assertEquals("say \"hi\"", map["a"])
    }

    @Test
    fun `keeps placeholder tokens verbatim`() {
        val map = LangJson.parseFlattened(
            """{"a":"{{xp}} XP","b":"{{terms:terms and conditions}}"}""",
        )
        assertEquals("{{xp}} XP", map["a"])
        assertEquals("{{terms:terms and conditions}}", map["b"])
    }

    @Test
    fun `stringifies arrays and maps json null to empty`() {
        val map = LangJson.parseFlattened("""{"arr":[1,2,"x"],"nul":null,"num":5,"bool":true}""")
        assertEquals("[1,2,\"x\"]", map["arr"])
        assertEquals("", map["nul"])
        assertEquals("5", map["num"])
        assertEquals("true", map["bool"])
    }

    @Test
    fun `strips a leading BOM`() {
        val bom = 0xFEFF.toChar()
        val map = LangJson.parseFlattened(bom + """{"a":"1"}""")
        assertEquals("1", map["a"])
    }

    @Test
    fun `duplicate keys keep the last value`() {
        val map = LangJson.parseFlattened("""{"a":"1","a":"2"}""")
        assertEquals("2", map["a"])
        assertEquals(1, map.size)
    }

    @Test(expected = JsonParseException::class)
    fun `throws on malformed json`() {
        LangJson.parseFlattened("{")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `throws when top level is not an object`() {
        LangJson.parseFlattened("""[1,2,3]""")
    }

    @Test
    fun `withValue updates an existing key`() {
        val updated = LangJson.withValue("""{"common":{"ok":"OK"}}""", "common.ok", "Okay")
        assertEquals("Okay", LangJson.parseFlattened(updated)["common.ok"])
    }

    @Test
    fun `withValue inserts a missing nested key`() {
        val updated = LangJson.withValue("""{"common":{"ok":"OK"}}""", "common.deep.new", "Hi")
        val map = LangJson.parseFlattened(updated)
        assertEquals("Hi", map["common.deep.new"])
        assertEquals("OK", map["common.ok"])
    }

    @Test
    fun `withValue preserves a single trailing newline`() {
        val updated = LangJson.withValue("{}\n", "a", "1")
        assertTrue(updated.endsWith("\n"))
        assertFalse(updated.removeSuffix("\n").endsWith("\n"))
    }

    @Test
    fun `withValue omits a trailing newline when the source has none`() {
        assertFalse(LangJson.withValue("""{"a":"x"}""", "a", "y").endsWith("\n"))
    }

    @Test
    fun `withValue keeps placeholders and emoji unescaped`() {
        val updated = LangJson.withValue("""{"a":"x"}""", "b", "{{xp}} 💔")
        assertTrue(updated.contains("{{xp}} 💔"))
    }

    @Test
    fun `withValue writes apostrophes and html chars literally`() {
        val apos = LangJson.withValue("""{"a":"x"}""", "score", "Score're")
        assertTrue(apos.contains("Score're"))
        assertFalse(apos.contains("\\u0027"))   // literal backslash-u-0027 must be absent

        val html = LangJson.withValue("""{"a":"x"}""", "b", "a < b & c > d = e")
        assertTrue(html.contains("a < b & c > d = e"))
    }

    @Test
    fun `withValue updates a flat dotted key in place instead of nesting a duplicate`() {
        val updated = LangJson.withValue("""{"login.email":"E-mail"}""", "login.email", "Email")
        val map = LangJson.parseFlattened(updated)
        assertEquals("Email", map["login.email"])
        assertEquals(1, map.size)
        assertTrue(updated.contains("\"login.email\"")) // still stored flat, not exploded to nested
    }

    @Test
    fun `withValue updates through an object name that itself contains a dot`() {
        val updated = LangJson.withValue("""{"a.b":{"c":"old"}}""", "a.b.c", "new")
        val map = LangJson.parseFlattened(updated)
        assertEquals("new", map["a.b.c"])
        assertEquals(1, map.size)
    }

    @Test
    fun `withValue never replaces a scalar intermediate - the new key is written flat`() {
        val updated = LangJson.withValue("""{"login":"Anmelden"}""", "login.email", "E-Mail")
        val map = LangJson.parseFlattened(updated)
        assertEquals("Anmelden", map["login"]) // the existing translation survives
        assertEquals("E-Mail", map["login.email"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `withValue refuses to overwrite a nested object with a string`() {
        LangJson.withValue("""{"login":{"email":"x"}}""", "login", "y")
    }
}

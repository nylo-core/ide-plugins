package dev.nylo.plugin.localizations.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BaselineResolverTest {

    private val locales = listOf("de", "en", "es")

    @Test
    fun `override wins when valid`() {
        assertEquals("es", BaselineResolver.resolve(locales, "es", "de"))
    }

    @Test
    fun `ignores invalid override and uses env default`() {
        assertEquals("de", BaselineResolver.resolve(locales, "zz", "de"))
    }

    @Test
    fun `falls back to en when no override or env default`() {
        assertEquals("en", BaselineResolver.resolve(locales, null, null))
    }

    @Test
    fun `falls back to the first locale when there is no en`() {
        assertEquals("de", BaselineResolver.resolve(listOf("de", "fr"), null, null))
    }

    @Test
    fun `returns null when there are no locales`() {
        assertNull(BaselineResolver.resolve(emptyList(), "en", "en"))
    }

    @Test
    fun `reads DEFAULT_LOCALE from env text`() {
        assertEquals("fr", BaselineResolver.readEnvDefaultLocale("APP_NAME=foo\nDEFAULT_LOCALE=\"fr\"\n"))
        assertEquals("ja", BaselineResolver.readEnvDefaultLocale("DEFAULT_LOCALE=ja"))
        assertNull(BaselineResolver.readEnvDefaultLocale("APP_NAME=foo\n"))
    }
}

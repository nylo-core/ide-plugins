package dev.nylo.plugin.localizations.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LangFileWatcherTest {

    private val root = "/Users/me/project"

    @Test
    fun `matches json files directly in lang`() {
        assertTrue(LangFileWatcher.isLangFileChange(root, "$root/lang/en.json"))
        assertTrue(LangFileWatcher.isLangFileChange(root, "$root/lang/pt_BR.json"))
    }

    @Test
    fun `rejects nested, non-json, and outside-lang paths`() {
        assertFalse(LangFileWatcher.isLangFileChange(root, "$root/lang/sub/en.json"))
        assertFalse(LangFileWatcher.isLangFileChange(root, "$root/lang/readme.md"))
        assertFalse(LangFileWatcher.isLangFileChange(root, "$root/lib/en.json"))
        assertFalse(LangFileWatcher.isLangFileChange(root, "$root/langx/en.json"))
    }

    @Test
    fun `rejects the lang directory itself`() {
        assertFalse(LangFileWatcher.isLangFileChange(root, "$root/lang"))
        assertFalse(LangFileWatcher.isLangFileChange(root, "$root/lang/"))
    }

    @Test
    fun `tolerates a trailing slash on the base path`() {
        assertTrue(LangFileWatcher.isLangFileChange("$root/", "$root/lang/de.json"))
    }
}

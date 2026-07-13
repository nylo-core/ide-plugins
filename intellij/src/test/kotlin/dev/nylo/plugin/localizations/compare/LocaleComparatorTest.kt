package dev.nylo.plugin.localizations.compare

import dev.nylo.plugin.localizations.model.KeyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocaleComparatorTest {

    private fun base() = linkedMapOf("a" to "A", "b" to "B", "c" to "C")

    @Test
    fun `classifies missing empty extra and translated`() {
        val values = mapOf(
            "en" to base(),
            // a translated, b empty (blank), c missing, d extra
            "es" to linkedMapOf("a" to "Ä", "b" to "  ", "d" to "D"),
        )
        val report = LocaleComparator.compare("en", values)
        val es = report.summaries.single { it.locale == "es" }
        assertEquals(1, es.translated)
        assertEquals(1, es.missing)
        assertEquals(1, es.empty)
        assertEquals(1, es.extra)

        val byStatus = report.issues.filter { it.locale == "es" }.groupBy { it.status }
        assertEquals("c", byStatus.getValue(KeyStatus.MISSING).single().key)
        assertEquals("b", byStatus.getValue(KeyStatus.EMPTY).single().key)
        assertEquals("d", byStatus.getValue(KeyStatus.EXTRA).single().key)
    }

    @Test
    fun `same as base flagged only when enabled and still counts as translated`() {
        val values = mapOf("en" to linkedMapOf("a" to "A"), "es" to linkedMapOf("a" to "A"))
        assertTrue(LocaleComparator.compare("en", values, flagSameAsBase = false).issues.isEmpty())

        val flagged = LocaleComparator.compare("en", values, flagSameAsBase = true)
        assertEquals(KeyStatus.UNTRANSLATED_SAME_AS_BASE, flagged.issues.single().status)
        assertEquals(100, flagged.summaries.single { it.locale == "es" }.percentComplete)
    }

    @Test
    fun `percent complete reflects translated over baseline keys`() {
        val values = mapOf("en" to base(), "es" to linkedMapOf("a" to "A")) // 1 of 3 present
        val es = LocaleComparator.compare("en", values).summaries.single { it.locale == "es" }
        assertEquals(33, es.percentComplete)
        assertEquals(2, es.missing)
    }

    @Test
    fun `zero key locale is all missing`() {
        val values = mapOf("en" to base(), "es" to linkedMapOf<String, String>())
        val es = LocaleComparator.compare("en", values).summaries.single { it.locale == "es" }
        assertEquals(0, es.percentComplete)
        assertEquals(3, es.missing)
    }

    @Test
    fun `empty baseline avoids division error and reports extras only`() {
        val values = mapOf("en" to linkedMapOf<String, String>(), "es" to linkedMapOf("a" to "A"))
        val es = LocaleComparator.compare("en", values).summaries.single { it.locale == "es" }
        assertEquals(100, es.percentComplete)
        assertEquals(1, es.extra)
    }

    @Test
    fun `baseline switch changes results`() {
        val values = mapOf("en" to linkedMapOf("a" to "A"), "es" to linkedMapOf("a" to "A", "b" to "B"))
        assertEquals(1, LocaleComparator.compare("en", values).issues.count { it.status == KeyStatus.EXTRA })
        assertEquals(1, LocaleComparator.compare("es", values).issues.count { it.status == KeyStatus.MISSING })
    }

    @Test
    fun `parse error produces an error summary and no phantom issues`() {
        val report = LocaleComparator.compare("en", mapOf("en" to base()), parseErrors = mapOf("es" to "bad json"))
        val es = report.summaries.single { it.locale == "es" }
        assertEquals("bad json", es.parseError)
        assertEquals(0, es.missing)
        assertTrue(report.issues.none { it.locale == "es" })
    }

    @Test
    fun `issues follow baseline key order`() {
        val values = mapOf(
            "en" to linkedMapOf("z" to "Z", "y" to "Y", "x" to "X"),
            "es" to linkedMapOf<String, String>(),
        )
        val keys = LocaleComparator.compare("en", values).issues.filter { it.locale == "es" }.map { it.key }
        assertEquals(listOf("z", "y", "x"), keys)
    }

    @Test
    fun `baseline summary comes first and is marked`() {
        val report = LocaleComparator.compare("en", mapOf("en" to base(), "es" to base()))
        assertTrue(report.summaries.first().isBaseline)
        assertEquals("en", report.summaries.first().locale)
    }

    @Test
    fun `broken baseline produces no issues instead of flagging every key extra`() {
        // An unparseable baseline must not degrade to an empty map: that would misclassify every
        // key of every locale as EXTRA (and "Remove extra keys" would then ask to delete them all).
        val report = LocaleComparator.compare(
            "en",
            localeValues = mapOf("es" to linkedMapOf("a" to "A", "b" to "B")),
            parseErrors = mapOf("en" to "Invalid JSON"),
        )
        assertTrue(report.issues.isEmpty())
        assertEquals("Invalid JSON", report.summaries.single { it.locale == "en" }.parseError)
        assertEquals(0, report.summaries.single { it.locale == "es" }.extra)
    }

    @Test
    fun `absent baseline produces no issues`() {
        val report = LocaleComparator.compare("en", mapOf("es" to linkedMapOf("a" to "A")))
        assertTrue(report.issues.isEmpty())
    }
}

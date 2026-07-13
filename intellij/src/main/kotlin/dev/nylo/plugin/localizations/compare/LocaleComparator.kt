package dev.nylo.plugin.localizations.compare

import dev.nylo.plugin.localizations.model.KeyStatus
import dev.nylo.plugin.localizations.model.LocaleIssue
import dev.nylo.plugin.localizations.model.LocaleSummary
import dev.nylo.plugin.localizations.model.LocalizationReport

/**
 * Compares each locale's flattened key->value map against the [baseline]'s, producing a
 * [LocalizationReport]. Pure and IDE-free so it can be unit-tested directly.
 *
 * Mirrors Nylo's `locale:check-missing-keys`: MISSING = baseline key absent here, EMPTY = present but
 * blank, EXTRA = key here but not in baseline. [flagSameAsBase] additionally flags values identical to
 * a non-blank baseline value ([KeyStatus.UNTRANSLATED_SAME_AS_BASE]) — a heuristic, off by default.
 */
object LocaleComparator {

    fun compare(
        baseline: String,
        localeValues: Map<String, Map<String, String>>,
        parseErrors: Map<String, String> = emptyMap(),
        flagSameAsBase: Boolean = false,
    ): LocalizationReport {
        // Without a readable baseline there is nothing to compare against. Falling back to an empty
        // map would misclassify every key of every locale as EXTRA, flooding the report with bogus
        // issues. Emit summaries (the baseline row carries its parse error) but no issues at all.
        val baselineReadable = baseline in localeValues && baseline !in parseErrors
        val baseMap = if (baselineReadable) localeValues[baseline].orEmpty() else emptyMap()
        val baseKeyCount = baseMap.size

        val summaries = ArrayList<LocaleSummary>()
        val issues = ArrayList<LocaleIssue>()

        summaries += LocaleSummary(
            locale = baseline,
            isBaseline = true,
            baselineKeyCount = baseKeyCount,
            translated = baseMap.count { it.value.isNotBlank() },
            missing = 0, empty = 0, extra = 0, sameAsBase = 0,
            parseError = parseErrors[baseline],
        )

        val otherLocales = (localeValues.keys + parseErrors.keys)
            .asSequence()
            .filter { it != baseline }
            .distinct()
            .sorted()
            .toList()

        for (locale in otherLocales) {
            val parseError = parseErrors[locale]
            if (parseError != null) {
                summaries += LocaleSummary(
                    locale = locale, isBaseline = false, baselineKeyCount = baseKeyCount,
                    translated = 0, missing = 0, empty = 0, extra = 0, sameAsBase = 0, parseError = parseError,
                )
                continue
            }

            val map = localeValues[locale].orEmpty()

            if (!baselineReadable) {
                summaries += LocaleSummary(
                    locale = locale, isBaseline = false, baselineKeyCount = 0,
                    translated = map.count { it.value.isNotBlank() },
                    missing = 0, empty = 0, extra = 0, sameAsBase = 0,
                )
                continue
            }

            var translated = 0
            var missing = 0
            var empty = 0
            var sameAsBase = 0

            // Walk baseline keys in baseline (insertion) order so issues come out file-ordered.
            for ((key, baseValue) in baseMap) {
                val current = map[key]
                when {
                    current == null -> {
                        missing++
                        issues += LocaleIssue(locale, key, KeyStatus.MISSING, baseValue, null)
                    }
                    current.isBlank() -> {
                        empty++
                        issues += LocaleIssue(locale, key, KeyStatus.EMPTY, baseValue, current)
                    }
                    flagSameAsBase && baseValue.isNotBlank() && current == baseValue -> {
                        translated++ // present + non-blank still counts toward completeness
                        sameAsBase++
                        issues += LocaleIssue(locale, key, KeyStatus.UNTRANSLATED_SAME_AS_BASE, baseValue, current)
                    }
                    else -> translated++
                }
            }

            // Extra keys: present here, absent from the baseline.
            var extra = 0
            for ((key, current) in map) {
                if (!baseMap.containsKey(key)) {
                    extra++
                    issues += LocaleIssue(locale, key, KeyStatus.EXTRA, null, current)
                }
            }

            summaries += LocaleSummary(
                locale = locale, isBaseline = false, baselineKeyCount = baseKeyCount,
                translated = translated, missing = missing, empty = empty, extra = extra, sameAsBase = sameAsBase,
            )
        }

        return LocalizationReport(baseline, summaries, issues)
    }
}

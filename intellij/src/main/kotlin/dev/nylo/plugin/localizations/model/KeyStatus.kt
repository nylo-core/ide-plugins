package dev.nylo.plugin.localizations.model

/**
 * How a translation key fares in a given locale relative to the baseline.
 *
 * [MISSING], [EMPTY] and [EXTRA] mirror Nylo's `locale:check-missing-keys` exactly.
 * [UNTRANSLATED_SAME_AS_BASE] is an extra heuristic (value identical to a non-blank baseline value)
 * the CLI does not emit; it is surfaced only when explicitly enabled because brand names / tokens
 * ("OK", "XP", "%") are false positives.
 */
enum class KeyStatus {
    TRANSLATED,
    MISSING,
    EMPTY,
    UNTRANSLATED_SAME_AS_BASE,
    EXTRA,
}

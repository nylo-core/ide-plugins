package dev.nylo.plugin.localizations.model

/**
 * One actionable problem: [key] in [locale] is [status] relative to the baseline.
 *
 * [baseValue] is the baseline's value for the key — null only for [KeyStatus.EXTRA] (the key isn't in
 * the baseline). [currentValue] is the locale's own value — null for [KeyStatus.MISSING].
 */
data class LocaleIssue(
    val locale: String,
    val key: String,
    val status: KeyStatus,
    val baseValue: String?,
    val currentValue: String?,
)

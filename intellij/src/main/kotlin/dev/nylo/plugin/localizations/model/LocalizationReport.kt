package dev.nylo.plugin.localizations.model

/**
 * The result of comparing every locale against [baseline]: a per-locale [summaries] roll-up (the
 * baseline first, then locales sorted by code) and a flat [issues] list (every non-baseline problem),
 * suitable for the filterable issue table and the matrix.
 */
data class LocalizationReport(
    val baseline: String,
    val summaries: List<LocaleSummary>,
    val issues: List<LocaleIssue>,
) {
    companion object {
        val EMPTY = LocalizationReport("", emptyList(), emptyList())
    }
}

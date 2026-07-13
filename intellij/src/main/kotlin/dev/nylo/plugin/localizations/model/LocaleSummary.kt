package dev.nylo.plugin.localizations.model

/**
 * Per-locale health roll-up shown in the Summary tab's top table. When [parseError] is non-null the
 * locale's JSON failed to parse: its counts are all zero and it contributes no issues to the report.
 */
data class LocaleSummary(
    val locale: String,
    val isBaseline: Boolean,
    val baselineKeyCount: Int,
    val translated: Int,
    val missing: Int,
    val empty: Int,
    val extra: Int,
    val sameAsBase: Int,
    val parseError: String? = null,
) {
    /** Baseline keys present and non-blank, as a percentage. 100 when the baseline itself has no keys. */
    val percentComplete: Int
        get() = if (baselineKeyCount == 0) 100 else (translated * 100) / baselineKeyCount

    /** Problems counting against sync health. Same-as-base is heuristic and intentionally excluded. */
    val issueCount: Int get() = missing + empty + extra
}

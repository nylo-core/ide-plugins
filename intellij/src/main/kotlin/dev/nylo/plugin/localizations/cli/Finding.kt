package dev.nylo.plugin.localizations.cli

/**
 * One hardcoded, user-facing string reported by `nylo locale:find-untranslated`: the source [file]
 * (project-relative), the 1-based [line], the literal [value], and the [context] (e.g. `Text(...)`).
 */
data class Finding(
    val file: String,
    val line: Int,
    val value: String,
    val context: String,
)

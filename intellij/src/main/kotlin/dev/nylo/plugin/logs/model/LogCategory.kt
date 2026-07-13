package dev.nylo.plugin.logs.model

/** The log-type tabs shown above the viewer. [ALL] is the unfiltered default. */
enum class LogCategory { ALL, CONSOLE, NETWORKING, ERRORS }

/**
 * Classifies [LogEntry]s into [LogCategory]s. The single source of truth for what counts as an
 * "error" or "networking" entry for the category tabs.
 *
 * Membership is multi-category: a network error is both [LogCategory.NETWORKING] and
 * [LogCategory.ERRORS]; an error-level line is both [LogCategory.CONSOLE] and [LogCategory.ERRORS].
 * [LogCategory.CONSOLE] is the catch-all (everything that isn't networking), so nothing is ever
 * hidden from every tab.
 */
object LogCategorizer {

    val ERROR_LEVELS = setOf("error", "err", "warn", "warning", "severe", "fatal", "alert", "emergency")

    fun isNetwork(entry: LogEntry): Boolean = entry is NetworkEntry

    fun isError(entry: LogEntry): Boolean = when (entry) {
        is NetworkEntry -> entry.kind == NetworkEntry.Kind.ERROR
        is StandardLine -> entry.level?.lowercase() in ERROR_LEVELS
        else -> false
    }

    fun matches(entry: LogEntry, category: LogCategory): Boolean = when (category) {
        LogCategory.ALL -> true
        LogCategory.NETWORKING -> isNetwork(entry)
        LogCategory.ERRORS -> isError(entry)
        LogCategory.CONSOLE -> !isNetwork(entry)
    }
}

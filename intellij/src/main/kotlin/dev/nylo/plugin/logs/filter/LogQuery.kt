package dev.nylo.plugin.logs.filter

import dev.nylo.plugin.logs.model.LogCategorizer
import dev.nylo.plugin.logs.model.LogCategory
import dev.nylo.plugin.logs.model.LogDocument
import dev.nylo.plugin.logs.model.LogSession
import java.time.LocalDateTime

enum class SortOrder { NEWEST_FIRST, OLDEST_FIRST }

/** The user-driven view state applied to a parsed [LogDocument]. */
data class LogQuery(
    val sessionTag: String? = null,
    val text: String? = null,
    val sort: SortOrder = SortOrder.NEWEST_FIRST,
    val category: LogCategory = LogCategory.ALL,
)

/** Applies session/category/text filtering and session-block sorting to a [LogDocument]. */
object LogFilter {

    fun apply(doc: LogDocument, query: LogQuery): LogDocument {
        var sessions = doc.sessions
        var preamble = doc.preamble

        // Session filter — accepts the short tag or a full banner id (suffix after the last '-').
        val tag = query.sessionTag?.trim()?.takeIf { it.isNotEmpty() }?.substringAfterLast('-')
        if (tag != null) {
            sessions = sessions.filter { it.tag.equals(tag, ignoreCase = true) }
            preamble = emptyList()
        }

        // Category (tab) filter — keep the entries in the active tab; drop sessions left with none
        // (no bare banners under Console/Networking/Errors).
        if (query.category != LogCategory.ALL) {
            sessions = sessions.mapNotNull { session ->
                val kept = session.entries.filter { LogCategorizer.matches(it, query.category) }
                if (kept.isEmpty()) null else session.copy(entries = kept)
            }
            preamble = preamble.filter { LogCategorizer.matches(it, query.category) }
        }

        // Text filter — keep sessions with a match; narrow them to the matching entries.
        val text = query.text?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null) {
            sessions = sessions.mapNotNull { session -> narrowToText(session, text) }
            preamble = preamble.filter { it.raw.contains(text, ignoreCase = true) }
        }

        sessions = when (query.sort) {
            SortOrder.NEWEST_FIRST ->
                sessions.sortedWith(compareByDescending<LogSession> { it.started ?: LocalDateTime.MIN })
            SortOrder.OLDEST_FIRST ->
                sessions.sortedWith(compareBy<LogSession> { it.started ?: LocalDateTime.MAX })
        }
        return LogDocument(preamble, sessions)
    }

    private fun narrowToText(session: LogSession, text: String): LogSession? {
        val matched = session.entries.filter { it.raw.contains(text, ignoreCase = true) }
        return when {
            matched.isNotEmpty() -> session.copy(entries = matched)
            session.bannerLines.any { it.contains(text, ignoreCase = true) } -> session.copy(entries = emptyList())
            else -> null
        }
    }
}

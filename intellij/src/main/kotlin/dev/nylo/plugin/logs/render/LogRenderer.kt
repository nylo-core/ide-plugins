package dev.nylo.plugin.logs.render

import dev.nylo.plugin.logs.model.LogDocument
import dev.nylo.plugin.logs.model.LogEntry
import dev.nylo.plugin.logs.model.NetworkEntry

/** A fold region in the rendered text: `[startOffset, endOffset)` collapses to [placeholder]. */
data class RenderFold(val startOffset: Int, val endOffset: Int, val placeholder: String)

/**
 * The flattened text for the editor plus the fold regions to collapse (the network entries).
 * [firstSessionEnd] is the offset just past the first rendered session block — under Newest-First
 * that block is the growing one, so it's where Follow should scroll to (new lines append there,
 * not at the document end).
 */
data class RenderResult(val text: String, val folds: List<RenderFold>, val firstSessionEnd: Int? = null)

/**
 * Flattens a (already filtered/sorted) [LogDocument] into the single string shown in the editor
 * viewer, recording a [RenderFold] for every [NetworkEntry] that carries expandable detail so the
 * panel can collapse it to its one-line summary.
 *
 * Each entry's [LogEntry.raw] is already human-readable display text (the parser reconstructs it
 * from JSON), so rendering is a straight concatenation. Fold offsets are computed against the
 * rendered text, so they stay correct regardless of how filtering/sorting reordered content.
 */
object LogRenderer {

    fun render(doc: LogDocument): RenderResult {
        val sb = StringBuilder()
        val folds = mutableListOf<RenderFold>()

        fun appendEntry(entry: LogEntry) {
            if (entry is NetworkEntry && entry.raw.contains('\n')) {
                val start = sb.length
                sb.append(entry.raw)
                folds += RenderFold(start, sb.length, placeholderFor(entry))
            } else {
                sb.append(entry.raw)
            }
            sb.append('\n')
        }

        doc.preamble.forEach(::appendEntry)
        var firstSessionEnd: Int? = null
        for (session in doc.sessions) {
            session.bannerLines.forEach { sb.append(it).append('\n') }
            session.entries.forEach(::appendEntry)
            if (firstSessionEnd == null) firstSessionEnd = sb.length
        }

        val text = if (sb.isNotEmpty() && sb.last() == '\n') sb.substring(0, sb.length - 1) else sb.toString()
        return RenderResult(text, folds, firstSessionEnd?.coerceAtMost(text.length))
    }

    private fun placeholderFor(entry: NetworkEntry): String = "  ${entry.summary}  ▾"
}

package dev.nylo.plugin.logs.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.nylo.plugin.NyloBundle
import dev.nylo.plugin.logs.filter.LogFilter
import dev.nylo.plugin.logs.filter.LogQuery
import dev.nylo.plugin.logs.filter.SortOrder
import dev.nylo.plugin.logs.model.LogCategory
import dev.nylo.plugin.logs.model.LogDocument
import dev.nylo.plugin.logs.parse.LogFileRef
import dev.nylo.plugin.logs.parse.LogFileScanner
import dev.nylo.plugin.logs.parse.LogParser
import dev.nylo.plugin.logs.render.LogRenderer
import dev.nylo.plugin.logs.render.RenderResult
import dev.nylo.plugin.logs.tail.LogTailer
import dev.nylo.plugin.state.NyloPluginState
import java.awt.BorderLayout
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel

/**
 * Root of the Nylo Logs tool window: the [LogToolbar] over a [LogEditorViewer]. Holds the view state
 * (date, session/text filters, sort, follow) and re-renders through [LogFilter] + [LogRenderer].
 *
 * File reads + parsing happen off the EDT; a [generation] guard ensures only the most recent request
 * paints, so fast typing in the filters never applies a stale render. When Follow is on and today is
 * selected, a [LogTailer] polls the file and re-renders (scrolling to the end) as it grows.
 */
class LogInspectorPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()), Disposable {

    private val viewer = LogEditorViewer(project)
    private val pluginState = NyloPluginState.getInstance(project)
    private var sort: SortOrder = if (pluginState.logSortNewestFirst) SortOrder.NEWEST_FIRST else SortOrder.OLDEST_FIRST
    private var follow: Boolean = pluginState.logFollow
    private var category: LogCategory = pluginState.logCategory
    private val toolbar = LogToolbar(
        parentDisposable = this,
        initialSort = sort,
        initialFollow = follow,
        onDateSelected = { selectedDate = it; updateTailer(); reloadContent() },
        onSessionChanged = { sessionTag = it; reloadContent() },
        onTextChanged = { textQuery = it; reloadContent() },
        onSortChanged = { sort = it; pluginState.logSortNewestFirst = (it == SortOrder.NEWEST_FIRST); reloadContent() },
        onFollowChanged = { follow = it; pluginState.logFollow = it; updateTailer(); if (it) reloadContent(scrollToEnd = true) },
        onRefresh = { refreshFileList(selectDefault = false) },
    )
    private val tailer = LogTailer(this) { onTailTick() }
    private val categoryTabs = LogCategoryTabs(category, viewer.component) { selected ->
        category = selected
        pluginState.logCategory = selected
        reloadContent()
    }

    private var refs: List<LogFileRef> = emptyList()
    private var selectedDate: LocalDate? = null
    private var sessionTag: String = ""
    private var textQuery: String = ""

    private val generation = AtomicInteger(0)

    init {
        Disposer.register(parent, this)
        Disposer.register(this, viewer)
        add(toolbar.component, BorderLayout.NORTH)
        add(categoryTabs.component, BorderLayout.CENTER)
        refreshFileList(selectDefault = true)
    }

    /** Rescans `logs/`, repopulates the date dropdown, refreshes the tailer target, then re-renders. */
    private fun refreshFileList(selectDefault: Boolean, scrollToEnd: Boolean = false) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val scanned = LogFileScanner.scan(project)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                refs = scanned
                val dates = scanned.map { it.date }
                val keepCurrent = !selectDefault && selectedDate != null && selectedDate in dates
                selectedDate = if (keepCurrent) selectedDate else LogFileScanner.defaultDate(dates, LocalDate.now())
                toolbar.setDates(dates, selectedDate)
                updateTailer()
                reloadContent(scrollToEnd)
            }
        }
    }

    /**
     * A tail tick means today's file grew, appeared for the first time, or the day rolled over.
     * When the current view already shows today, just re-render; otherwise the date list is stale
     * (the tailer noticed a file the last scan didn't) — rescan and jump to today, which is what
     * Follow promises to show.
     */
    private fun onTailTick() {
        val today = LocalDate.now()
        if (selectedDate == today && refs.any { it.date == today }) {
            reloadContent(scrollToEnd = true)
        } else {
            refreshFileList(selectDefault = true, scrollToEnd = true)
        }
    }

    /** Re-reads the selected file and repaints; parsing/rendering run off the EDT. */
    private fun reloadContent(scrollToEnd: Boolean = false) {
        val gen = generation.incrementAndGet()
        // Resolve the target file on the EDT so the background job never reads the `refs` field off-thread.
        val file = selectedDate?.let { d -> refs.firstOrNull { it.date == d }?.file }
        val query = LogQuery(sessionTag.ifBlank { null }, textQuery.ifBlank { null }, sort, category)
        ApplicationManager.getApplication().executeOnPooledThread {
            val filtered = file?.let {
                LogFilter.apply(LogParser.parse(runCatching { it.readText() }.getOrDefault("")), query)
            }
            val result = renderResultFor(file, query, filtered)
            // Follow means "show new lines as they arrive". Under Newest-First the growing session
            // renders first, so new lines land at the end of the FIRST session block — scrolling to
            // the document end would show the oldest session instead.
            val scrollTo = when {
                !scrollToEnd -> null
                query.sort == SortOrder.NEWEST_FIRST -> result.firstSessionEnd ?: result.text.length
                else -> result.text.length
            }
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && gen == generation.get()) {
                    viewer.setContent(result, scrollTo)
                }
            }
        }
    }

    private fun renderResultFor(file: File?, query: LogQuery, filtered: LogDocument?): RenderResult {
        if (file == null || filtered == null) return RenderResult(NyloBundle.message("logs.empty"), emptyList())
        return when {
            !filtered.isEmpty -> LogRenderer.render(filtered)
            hasActiveFilter(query) -> RenderResult(emptyMessageFor(query), emptyList())
            else -> RenderResult(NyloBundle.message("logs.file.empty"), emptyList())
        }
    }

    private fun hasActiveFilter(query: LogQuery): Boolean =
        !query.sessionTag.isNullOrBlank() || !query.text.isNullOrBlank() || query.category != LogCategory.ALL

    /** A category-specific empty message when only a tab is active; the generic one otherwise. */
    private fun emptyMessageFor(query: LogQuery): String {
        val onlyCategory = query.sessionTag.isNullOrBlank() && query.text.isNullOrBlank()
        if (!onlyCategory) return NyloBundle.message("logs.no.match")
        return when (query.category) {
            LogCategory.CONSOLE -> NyloBundle.message("logs.no.match.console")
            LogCategory.NETWORKING -> NyloBundle.message("logs.no.match.networking")
            LogCategory.ERRORS -> NyloBundle.message("logs.no.match.errors")
            LogCategory.ALL -> NyloBundle.message("logs.no.match")
        }
    }

    /**
     * Tails when Follow is on and the view is "current": today is selected, or the selection is
     * still the default (newest available / nothing) because today's file doesn't exist yet. In the
     * latter cases the tailer watches the *expected* today path so the first app run of the day is
     * discovered without a manual refresh. Explicitly selected older dates never tail.
     */
    private fun updateTailer() {
        val logsDir = LogFileScanner.logsDir(project)
        val today = LocalDate.now()
        val newestKnown = refs.firstOrNull()?.date // refs are sorted newest first
        val followEligible = selectedDate == null || selectedDate == today || selectedDate == newestKnown
        // Resolve inside the supplier (called from the poll thread) so the midnight rollover to a
        // new daily filename is picked up without touching panel state off the EDT.
        val resolver = logsDir?.let { dir -> { File(dir, "${LocalDate.now()}.log") } }
        tailer.update(resolver, follow && followEligible && resolver != null)
    }

    override fun dispose() {}
}

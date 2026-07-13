package dev.nylo.plugin.logs.ui

import com.intellij.ui.components.JBTabbedPane
import dev.nylo.plugin.NyloBundle
import dev.nylo.plugin.logs.model.LogCategory
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The log-type tab strip shown above the viewer: All · Console · Networking · Errors.
 *
 * It is a *selector only* — there is a single shared [content] editor, not one per tab. Each tab holds
 * an empty host panel; selecting a tab re-parents the shared editor into that tab (so it sits directly
 * under the tab headers) and reports the new [LogCategory] through [onCategoryChanged], which re-filters
 * the one view. No per-tab editors or tailers.
 */
class LogCategoryTabs(
    initialCategory: LogCategory,
    private val content: JComponent,
    private val onCategoryChanged: (LogCategory) -> Unit,
) {
    private val order = listOf(LogCategory.ALL, LogCategory.CONSOLE, LogCategory.NETWORKING, LogCategory.ERRORS)
    private val tabbedPane = JBTabbedPane()
    private var suppress = false

    val component: JComponent get() = tabbedPane

    init {
        order.forEach { category ->
            tabbedPane.addTab(NyloBundle.message("logs.category.${category.name.lowercase()}"), JPanel(BorderLayout()))
        }
        select(initialCategory)
        installContentInSelectedTab()
        tabbedPane.addChangeListener {
            if (suppress) return@addChangeListener
            installContentInSelectedTab()
            onCategoryChanged(order[tabbedPane.selectedIndex.coerceIn(order.indices)])
        }
    }

    /** Selects [category] without firing [onCategoryChanged]. */
    fun select(category: LogCategory) {
        suppress = true
        try {
            tabbedPane.selectedIndex = order.indexOf(category).coerceAtLeast(0)
        } finally {
            suppress = false
        }
    }

    private fun installContentInSelectedTab() {
        val host = tabbedPane.selectedComponent as? JPanel ?: return
        if (content.parent === host) return
        (content.parent as? JComponent)?.remove(content)
        host.add(content, BorderLayout.CENTER)
        host.revalidate()
        host.repaint()
    }
}

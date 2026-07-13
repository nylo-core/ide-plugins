package dev.nylo.plugin.logs.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import dev.nylo.plugin.logs.filter.SortOrder
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import java.time.LocalDate
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * The Git-Log-style toolbar: date dropdown, session-id field, free-text search, a Newest/Oldest sort
 * toggle, a Follow toggle, and Refresh. Changes are reported through the constructor callbacks; the
 * text fields are debounced so typing doesn't re-render on every keystroke.
 */
class LogToolbar(
    parentDisposable: Disposable,
    initialSort: SortOrder,
    initialFollow: Boolean,
    private val onDateSelected: (LocalDate) -> Unit,
    private val onSessionChanged: (String) -> Unit,
    private val onTextChanged: (String) -> Unit,
    private val onSortChanged: (SortOrder) -> Unit,
    private val onFollowChanged: (Boolean) -> Unit,
    private val onRefresh: () -> Unit,
) {
    private val dateCombo = ComboBox<LocalDate>()
    private val sessionField = JBTextField(10)
    private val searchField = JBTextField(16)
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)

    private var sort = initialSort
    private var follow = initialFollow
    private var suppressDateEvents = false

    val component: JComponent

    init {
        dateCombo.renderer = SimpleListCellRenderer.create("") { value: LocalDate -> formatDate(value) }
        // Use the item from the event (reliable) rather than getSelectedItem(), which on some L&Fs
        // still returns the previous value when the event fires — that desyncs the date from the content.
        dateCombo.addItemListener { event ->
            if (!suppressDateEvents && event.stateChange == ItemEvent.SELECTED) {
                (event.item as? LocalDate)?.let(onDateSelected)
            }
        }

        sessionField.emptyText.text = "Session id"
        sessionField.toolTipText = "Show only the pasted session (short tag or full id)"
        sessionField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = debounce { onSessionChanged(sessionField.text.trim()) }
        })

        searchField.emptyText.text = "Filter logs"
        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = debounce { onTextChanged(searchField.text.trim()) }
        })

        val group = DefaultActionGroup(
            SortAction(), FollowAction(), Separator.getInstance(), RefreshAction(),
        )
        val actionToolbar = ActionManager.getInstance().createActionToolbar("NyloLogsToolbar", group, true)

        val root = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(6), JBUI.scale(3)))
        root.add(JBLabel("Logs:"))
        root.add(dateCombo)
        root.add(sessionField)
        root.add(searchField)
        root.add(actionToolbar.component)
        actionToolbar.targetComponent = root
        component = root
    }

    /** Populates the date dropdown without firing the selection callback. */
    fun setDates(dates: List<LocalDate>, selected: LocalDate?) {
        suppressDateEvents = true
        try {
            dateCombo.removeAllItems()
            dates.forEach(dateCombo::addItem)
            dateCombo.selectedItem = selected
            dateCombo.isEnabled = dates.isNotEmpty()
        } finally {
            suppressDateEvents = false
        }
    }

    private fun debounce(run: () -> Unit) {
        alarm.cancelAllRequests()
        alarm.addRequest(run, DEBOUNCE_MS)
    }

    private fun formatDate(date: LocalDate): String {
        val today = LocalDate.now()
        return when (date) {
            today -> "$date  (today)"
            today.minusDays(1) -> "$date  (yesterday)"
            else -> date.toString()
        }
    }

    private inner class SortAction : ToggleAction(
        "Newest First",
        "Sort sessions newest or oldest first",
        AllIcons.ObjectBrowser.Sorted,
    ), DumbAware {
        override fun isSelected(e: AnActionEvent) = sort == SortOrder.NEWEST_FIRST
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            sort = if (state) SortOrder.NEWEST_FIRST else SortOrder.OLDEST_FIRST
            onSortChanged(sort)
        }
        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.text = if (sort == SortOrder.NEWEST_FIRST) "Newest First" else "Oldest First"
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class FollowAction : ToggleAction(
        "Follow",
        "Scroll to the end as today's log grows",
        AllIcons.RunConfigurations.Scroll_down,
    ), DumbAware {
        override fun isSelected(e: AnActionEvent) = follow
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            follow = state
            onFollowChanged(state)
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class RefreshAction : AnAction(
        "Refresh",
        "Reload log files",
        AllIcons.Actions.Refresh,
    ), DumbAware {
        override fun actionPerformed(e: AnActionEvent) = onRefresh()
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    companion object {
        private const val DEBOUNCE_MS = 250
    }
}

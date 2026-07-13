package dev.nylo.plugin.localizations.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.TableView
import com.intellij.util.Alarm
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import dev.nylo.plugin.NyloBundle
import dev.nylo.plugin.localizations.model.KeyStatus
import dev.nylo.plugin.localizations.model.LocaleIssue
import dev.nylo.plugin.localizations.model.LocaleSummary
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent

/**
 * The "Summary" tab: a per-locale health table on top and a filterable, multi-select issue table below.
 * Selecting a health row scopes the issue table to that locale; the issue table's Current column is
 * inline-editable (writes straight back to the locale file via the service).
 */
class SummaryPanel(project: Project, parent: Disposable) : LocalizationTabPanel(project, parent) {

    private val allLocales = NyloBundle.message("localizations.locale.all")

    private val summaryModel = ListTableModel<LocaleSummary>(*summaryColumns())
    private val summaryTable = TableView(summaryModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
    }

    private val issueModel = ListTableModel<LocaleIssue>(*issueColumns())
    private val issueTable = TableView(issueModel).apply {
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
    }

    private val missingBox = JBCheckBox(NyloBundle.message("localizations.filter.missing"), pluginState.localizationsShowMissing)
    private val emptyBox = JBCheckBox(NyloBundle.message("localizations.filter.empty"), pluginState.localizationsShowEmpty)
    private val extraBox = JBCheckBox(NyloBundle.message("localizations.filter.extra"), pluginState.localizationsShowExtra)
    private val sameBox = JBCheckBox(NyloBundle.message("localizations.filter.sameAsBase"), pluginState.localizationsShowSameAsBase)
    private val localeFilterCombo = com.intellij.openapi.ui.ComboBox<String>()
    private val searchField = JBTextField(16)
    private val searchAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private val splitter = OnePixelSplitter(true, 0.32f)
    private val emptyLabel = JBLabel("", SwingConstants.CENTER)
    private var centerComponent: JComponent? = null

    private var suppressLocaleEvents = false

    init {
        add(buildToolbar(), BorderLayout.NORTH)
        splitter.firstComponent = com.intellij.ui.components.JBScrollPane(summaryTable)
        splitter.secondComponent = com.intellij.ui.components.JBScrollPane(issueTable)

        missingBox.addActionListener { pluginState.localizationsShowMissing = missingBox.isSelected; refilterIssues() }
        emptyBox.addActionListener { pluginState.localizationsShowEmpty = emptyBox.isSelected; refilterIssues() }
        extraBox.addActionListener { pluginState.localizationsShowExtra = extraBox.isSelected; refilterIssues() }
        // Same-as-base changes which issues the comparator produces, so recompute via the service.
        sameBox.addActionListener {
            pluginState.localizationsShowSameAsBase = sameBox.isSelected
            service.setFlagSameAsBase(sameBox.isSelected)
        }
        localeFilterCombo.addItemListener { e ->
            if (!suppressLocaleEvents && e.stateChange == ItemEvent.SELECTED) {
                val sel = e.item as? String
                pluginState.localizationsLocaleFilter = if (sel == null || sel == allLocales) null else sel
                refilterIssues()
            }
        }
        searchField.emptyText.text = NyloBundle.message("localizations.search.placeholder")
        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                searchAlarm.cancelAllRequests()
                searchAlarm.addRequest({ refilterIssues() }, SEARCH_DEBOUNCE_MS)
            }
        })
        summaryTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) onSummaryRowSelected()
        }

        onDataChanged()
    }

    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup(RefreshAction())
        val actionToolbar = ActionManager.getInstance().createActionToolbar("NyloLocalizationsSummaryToolbar", group, true)
        val root = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(6), JBUI.scale(3)))
        root.add(JBLabel(NyloBundle.message("localizations.baseline.label")))
        root.add(baselineCombo)
        root.add(missingBox)
        root.add(emptyBox)
        root.add(extraBox)
        root.add(sameBox)
        root.add(localeFilterCombo)
        root.add(searchField)
        root.add(actionToolbar.component)
        actionToolbar.targetComponent = root
        return root
    }

    override fun onDataChanged() {
        refreshBaselineCombo()
        sameBox.isSelected = pluginState.localizationsShowSameAsBase
        repopulateLocaleFilter()
        summaryModel.items = service.report.summaries
        refilterIssues()
        updateBody()
    }

    private fun repopulateLocaleFilter() {
        suppressLocaleEvents = true
        try {
            val previous = pluginState.localizationsLocaleFilter
            localeFilterCombo.removeAllItems()
            localeFilterCombo.addItem(allLocales)
            service.localeCodes.filter { it != service.report.baseline }.forEach { localeFilterCombo.addItem(it) }
            // A persisted filter that no longer maps to a combo item (locale deleted, or it became
            // the baseline) must also be cleared from state — refilterIssues() reads the persisted
            // value, so leaving it stale would filter the table by a locale the combo doesn't show.
            val effective = previous?.takeIf { it in service.localeCodes && it != service.report.baseline }
            if (effective == null) pluginState.localizationsLocaleFilter = null
            localeFilterCombo.selectedItem = effective ?: allLocales
        } finally {
            suppressLocaleEvents = false
        }
    }

    private fun onSummaryRowSelected() {
        val selected = summaryTable.selectedObject ?: return
        suppressLocaleEvents = true
        try {
            val target = if (selected.isBaseline) allLocales else selected.locale
            localeFilterCombo.selectedItem = target
            pluginState.localizationsLocaleFilter = if (selected.isBaseline) null else selected.locale
        } finally {
            suppressLocaleEvents = false
        }
        refilterIssues()
    }

    private fun refilterIssues() {
        val localeFilter = pluginState.localizationsLocaleFilter
        val query = searchField.text.trim().lowercase()
        val filtered = service.report.issues.filter { issue ->
            statusEnabled(issue.status) &&
                (localeFilter == null || issue.locale == localeFilter) &&
                (query.isEmpty() || issue.matchesQuery(query))
        }
        issueModel.items = filtered
    }

    private fun statusEnabled(status: KeyStatus): Boolean = when (status) {
        KeyStatus.MISSING -> missingBox.isSelected
        KeyStatus.EMPTY -> emptyBox.isSelected
        KeyStatus.EXTRA -> extraBox.isSelected
        KeyStatus.UNTRANSLATED_SAME_AS_BASE -> sameBox.isSelected
        KeyStatus.TRANSLATED -> false
    }

    private fun LocaleIssue.matchesQuery(query: String): Boolean =
        key.lowercase().contains(query) ||
            baseValue?.lowercase()?.contains(query) == true ||
            currentValue?.lowercase()?.contains(query) == true

    private fun onInlineEdit(locale: String, key: String, value: String) {
        val error = service.setValue(locale, key, value)
        if (error != null) Messages.showErrorDialog(project, error, NyloBundle.message("localizations.edit.error.title"))
    }

    private fun updateBody() {
        val target: JComponent = when {
            !service.hasLangDir() -> emptyLabel.apply { text = NyloBundle.message("localizations.empty.noLangDir") }
            service.localeCodes.isEmpty() -> emptyLabel.apply { text = NyloBundle.message("localizations.empty.noLocales") }
            else -> splitter
        }
        if (centerComponent !== target) {
            centerComponent?.let { remove(it) }
            add(target, BorderLayout.CENTER)
            centerComponent = target
            revalidate()
            repaint()
        }
    }

    // --- columns ---------------------------------------------------------------------------------

    private fun summaryColumns(): Array<ColumnInfo<LocaleSummary, *>> = arrayOf(
        summaryCol("localizations.column.locale", compareBy { it.locale }) {
            it.locale + if (it.isBaseline) " (base)" else ""
        },
        summaryCol("localizations.column.percent", compareBy { it.percentComplete }) {
            if (it.parseError != null) NyloBundle.message("localizations.parseError") else "${it.percentComplete}%"
        },
        summaryCol("localizations.column.missing", compareBy { it.missing }) { dashOr(it, it.missing) },
        summaryCol("localizations.column.empty", compareBy { it.empty }) { dashOr(it, it.empty) },
        summaryCol("localizations.column.extra", compareBy { it.extra }) { dashOr(it, it.extra) },
    )

    private fun dashOr(summary: LocaleSummary, count: Int): String = if (summary.parseError != null) "—" else count.toString()

    private fun summaryCol(
        titleKey: String,
        comparator: Comparator<LocaleSummary>,
        get: (LocaleSummary) -> String,
    ) = object : ColumnInfo<LocaleSummary, String>(NyloBundle.message(titleKey)) {
        override fun valueOf(item: LocaleSummary): String = get(item)
        override fun getComparator(): Comparator<LocaleSummary> = comparator
    }

    private fun issueColumns(): Array<ColumnInfo<LocaleIssue, *>> = arrayOf(
        issueStr("localizations.column.locale", compareBy { it.locale }) { it.locale },
        issueStr("localizations.column.key", compareBy { it.key }) { it.key },
        object : ColumnInfo<LocaleIssue, LocaleIssue>(NyloBundle.message("localizations.column.issue")) {
            override fun valueOf(item: LocaleIssue): LocaleIssue = item
            override fun getRenderer(item: LocaleIssue?) = statusRenderer
            override fun getComparator(): Comparator<LocaleIssue> = compareBy { it.status.ordinal }
        },
        object : ColumnInfo<LocaleIssue, String>(NyloBundle.message("localizations.column.base")) {
            override fun valueOf(item: LocaleIssue): String = item.baseValue ?: ""
            override fun getRenderer(item: LocaleIssue?) = valueRenderer
            override fun getComparator(): Comparator<LocaleIssue> = compareBy { it.baseValue ?: "" }
        },
        object : ColumnInfo<LocaleIssue, String>(NyloBundle.message("localizations.column.current")) {
            override fun valueOf(item: LocaleIssue): String = item.currentValue ?: ""
            override fun getRenderer(item: LocaleIssue?) = valueRenderer
            override fun isCellEditable(item: LocaleIssue?): Boolean = true
            override fun setValue(item: LocaleIssue, value: String) = onInlineEdit(item.locale, item.key, value)
            override fun getComparator(): Comparator<LocaleIssue> = compareBy { it.currentValue ?: "" }
        },
    )

    private fun issueStr(titleKey: String, comparator: Comparator<LocaleIssue>, get: (LocaleIssue) -> String) =
        object : ColumnInfo<LocaleIssue, String>(NyloBundle.message(titleKey)) {
            override fun valueOf(item: LocaleIssue): String = get(item)
            override fun getComparator(): Comparator<LocaleIssue> = comparator
        }

    private val statusRenderer = object : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val issue = value as? LocaleIssue ?: return
            when (issue.status) {
                KeyStatus.MISSING -> { icon = AllIcons.General.Error; append(NyloBundle.message("localizations.status.missing"), SimpleTextAttributes.ERROR_ATTRIBUTES) }
                KeyStatus.EMPTY -> { icon = AllIcons.General.Warning; append(NyloBundle.message("localizations.status.empty"), ORANGE) }
                KeyStatus.EXTRA -> { icon = AllIcons.General.Information; append(NyloBundle.message("localizations.status.extra"), SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                KeyStatus.UNTRANSLATED_SAME_AS_BASE -> append(NyloBundle.message("localizations.status.sameAsBase"), BLUE)
                KeyStatus.TRANSLATED -> append(NyloBundle.message("localizations.status.translated"))
            }
        }
    }

    private val valueRenderer = object : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val text = value?.toString().orEmpty()
            append(text)
            toolTipText = text.ifEmpty { null }
        }
    }

    private inner class RefreshAction : AnAction(
        NyloBundle.message("localizations.refresh.text"),
        NyloBundle.message("localizations.refresh.description"),
        AllIcons.Actions.Refresh,
    ), DumbAware {
        override fun actionPerformed(e: AnActionEvent) = service.refreshAsync()
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 250
        private val ORANGE = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.ORANGE)
        private val BLUE = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.BLUE)
    }
}

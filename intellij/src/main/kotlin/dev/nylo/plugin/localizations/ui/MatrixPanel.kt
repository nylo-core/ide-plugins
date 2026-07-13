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
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import dev.nylo.plugin.NyloBundle
import dev.nylo.plugin.localizations.model.KeyStatus
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel

/**
 * The "Matrix" tab: a spreadsheet-style key × locale grid. Rows are baseline keys (optionally narrowed
 * to "problems only" and the search box), columns are the baseline followed by the other locales. Every
 * locale cell is inline-editable and writes straight back to that locale's JSON via the service; missing
 * and empty cells are colored so gaps are obvious at a glance.
 */
class MatrixPanel(project: Project, parent: Disposable) : LocalizationTabPanel(project, parent) {

    private val model = MatrixModel { code, key, value -> onInlineEdit(code, key, value) }
    private val table = JBTable(model).apply {
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        setDefaultRenderer(Any::class.java, MatrixCellRenderer())
        tableHeader.reorderingAllowed = false
    }

    private val problemsOnlyBox = JBCheckBox(
        NyloBundle.message("localizations.matrix.problemsOnly"),
        pluginState.localizationsProblemsOnly,
    )
    private val searchField = JBTextField(16)
    private val searchAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private val scrollPane = JBScrollPane(table)
    private val emptyLabel = JBLabel("", SwingConstants.CENTER)
    private var centerComponent: JComponent? = null

    init {
        add(buildToolbar(), BorderLayout.NORTH)
        problemsOnlyBox.toolTipText = NyloBundle.message("localizations.matrix.problemsOnly.description")
        problemsOnlyBox.addActionListener {
            pluginState.localizationsProblemsOnly = problemsOnlyBox.isSelected
            rebuild()
        }
        searchField.emptyText.text = NyloBundle.message("localizations.search.placeholder")
        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                searchAlarm.cancelAllRequests()
                searchAlarm.addRequest({ rebuild() }, SEARCH_DEBOUNCE_MS)
            }
        })
        onDataChanged()
    }

    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup(RefreshAction())
        val actionToolbar = ActionManager.getInstance().createActionToolbar("NyloLocalizationsMatrixToolbar", group, true)
        val root = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(6), JBUI.scale(3)))
        root.add(JBLabel(NyloBundle.message("localizations.baseline.label")))
        root.add(baselineCombo)
        root.add(problemsOnlyBox)
        root.add(searchField)
        root.add(actionToolbar.component)
        actionToolbar.targetComponent = root
        return root
    }

    override fun onDataChanged() {
        refreshBaselineCombo()
        rebuild()
        updateBody()
    }

    private fun rebuild() {
        val report = service.report
        val baseline = report.baseline
        val codes = buildList {
            if (baseline.isNotEmpty()) add(baseline)
            addAll(service.localeCodes.filter { it != baseline })
        }
        val problemKeys: Set<String>? = if (problemsOnlyBox.isSelected) {
            report.issues.asSequence()
                .filter { it.status == KeyStatus.MISSING || it.status == KeyStatus.EMPTY }
                .map { it.key }
                .toSet()
        } else {
            null
        }
        val query = searchField.text.trim().lowercase()
        val keys = service.valuesFor(baseline).keys.filter { key ->
            (problemKeys == null || key in problemKeys) &&
                (query.isEmpty() || key.lowercase().contains(query))
        }
        val snapshot = codes.associateWith { service.valuesFor(it) }
        model.setData(keys, codes, snapshot)
        applyColumnWidths()
    }

    private fun applyColumnWidths() {
        val columnModel = table.columnModel
        if (columnModel.columnCount == 0) return
        columnModel.getColumn(0).preferredWidth = JBUI.scale(260)
        for (i in 1 until columnModel.columnCount) columnModel.getColumn(i).preferredWidth = JBUI.scale(150)
    }

    private fun onInlineEdit(locale: String, key: String, value: String) {
        val error = service.setValue(locale, key, value)
        if (error != null) Messages.showErrorDialog(project, error, NyloBundle.message("localizations.edit.error.title"))
    }

    private fun updateBody() {
        val target: JComponent = when {
            !service.hasLangDir() -> emptyLabel.apply { text = NyloBundle.message("localizations.empty.noLangDir") }
            service.localeCodes.isEmpty() -> emptyLabel.apply { text = NyloBundle.message("localizations.empty.noLocales") }
            else -> scrollPane
        }
        if (centerComponent !== target) {
            centerComponent?.let { remove(it) }
            add(target, BorderLayout.CENTER)
            centerComponent = target
            revalidate()
            repaint()
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

    /** Backing model for the grid. Column 0 is the key; the rest are locale values (null = missing). */
    private class MatrixModel(
        private val onEdit: (locale: String, key: String, value: String) -> Unit,
    ) : AbstractTableModel() {

        private var keys: List<String> = emptyList()
        private var codes: List<String> = emptyList()
        private var snapshot: Map<String, Map<String, String>> = emptyMap()

        fun setData(keys: List<String>, codes: List<String>, snapshot: Map<String, Map<String, String>>) {
            this.keys = keys
            this.codes = codes
            this.snapshot = snapshot
            fireTableStructureChanged()
        }

        override fun getRowCount(): Int = keys.size

        override fun getColumnCount(): Int = codes.size + 1

        override fun getColumnName(column: Int): String =
            if (column == 0) NyloBundle.message("localizations.column.key") else codes[column - 1]

        override fun getValueAt(row: Int, column: Int): Any? {
            val key = keys[row]
            if (column == 0) return key
            return snapshot[codes[column - 1]]?.get(key) // null = key missing in this locale
        }

        override fun isCellEditable(row: Int, column: Int): Boolean = column > 0

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            if (column == 0) return
            onEdit(codes[column - 1], keys[row], value?.toString().orEmpty())
        }
    }

    private class MatrixCellRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            if (column == 0) {
                append(value?.toString().orEmpty())
                return
            }
            when {
                value == null -> append(NyloBundle.message("localizations.status.missing"), SimpleTextAttributes.ERROR_ATTRIBUTES)
                (value as String).isBlank() -> append(NyloBundle.message("localizations.status.empty"), ORANGE)
                else -> {
                    append(value)
                    toolTipText = value
                }
            }
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 250
        private val ORANGE = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.ORANGE)
    }
}

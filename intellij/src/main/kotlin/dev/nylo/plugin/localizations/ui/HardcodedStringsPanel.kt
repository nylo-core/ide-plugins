package dev.nylo.plugin.localizations.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import dev.nylo.plugin.NyloBundle
import dev.nylo.plugin.localizations.cli.Finding
import dev.nylo.plugin.localizations.cli.FindUntranslatedRunner
import java.awt.BorderLayout
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

/**
 * The "Hardcoded strings" tab: runs `nylo locale:find-untranslated` (the CLI-backed concern that needs
 * the Dart analyzer) and lists user-facing strings not wrapped in `.tr()`. Degrades to a hint panel
 * when the Nylo CLI isn't installed.
 */
class HardcodedStringsPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()), Disposable {

    private val model = ListTableModel<Finding>(*columns())
    private val table = TableView(model).apply { setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION) }
    private val scrollPane = JBScrollPane(table)
    private val statusLabel = JBLabel("", SwingConstants.CENTER)
    private var centerComponent: JComponent? = null

    init {
        Disposer.register(parent, this)
        add(buildToolbar(), BorderLayout.NORTH)
        showStatus(NyloBundle.message("localizations.hardcoded.intro"))
    }

    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup(RunAction())
        val actionToolbar = ActionManager.getInstance().createActionToolbar("NyloLocalizationsHardcodedToolbar", group, true)
        val root = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEADING, JBUI.scale(6), JBUI.scale(3)))
        root.add(actionToolbar.component)
        actionToolbar.targetComponent = root
        return root
    }

    private fun runScan() {
        showStatus(NyloBundle.message("localizations.cli.running"))
        val basePath = project.basePath
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = if (basePath == null) {
                FindUntranslatedRunner.ScanOutcome(null, NyloBundle.message("localizations.cli.failed", "no project path"))
            } else {
                FindUntranslatedRunner.run(File(basePath))
            }
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) applyOutcome(outcome)
            }, ModalityState.nonModal())
        }
    }

    private fun applyOutcome(outcome: FindUntranslatedRunner.ScanOutcome) {
        val findings = outcome.findings
        when {
            outcome.error != null -> showStatus(outcome.error)
            findings.isNullOrEmpty() -> showStatus(NyloBundle.message("localizations.hardcoded.empty"))
            else -> {
                model.items = findings
                showCenter(scrollPane)
            }
        }
    }

    private fun showStatus(text: String) {
        statusLabel.text = text
        showCenter(statusLabel)
    }

    private fun showCenter(component: JComponent) {
        if (centerComponent === component) return
        centerComponent?.let { remove(it) }
        add(component, BorderLayout.CENTER)
        centerComponent = component
        revalidate()
        repaint()
    }

    private fun columns(): Array<ColumnInfo<Finding, *>> = arrayOf(
        object : ColumnInfo<Finding, String>(NyloBundle.message("localizations.hardcoded.column.file")) {
            override fun valueOf(item: Finding): String = item.file
            override fun getComparator(): Comparator<Finding> = compareBy { it.file }
        },
        object : ColumnInfo<Finding, Int>(NyloBundle.message("localizations.hardcoded.column.line")) {
            override fun valueOf(item: Finding): Int = item.line
            override fun getComparator(): Comparator<Finding> = compareBy { it.line }
        },
        object : ColumnInfo<Finding, String>(NyloBundle.message("localizations.hardcoded.column.string")) {
            override fun valueOf(item: Finding): String = item.value
            override fun getComparator(): Comparator<Finding> = compareBy { it.value }
        },
        object : ColumnInfo<Finding, String>(NyloBundle.message("localizations.hardcoded.column.context")) {
            override fun valueOf(item: Finding): String = item.context
            override fun getComparator(): Comparator<Finding> = compareBy { it.context }
        },
    )

    private inner class RunAction : AnAction(
        NyloBundle.message("localizations.hardcoded.run"),
        NyloBundle.message("localizations.hardcoded.intro"),
        AllIcons.Actions.Execute,
    ), DumbAware {
        override fun actionPerformed(e: AnActionEvent) = runScan()
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    override fun dispose() {}
}

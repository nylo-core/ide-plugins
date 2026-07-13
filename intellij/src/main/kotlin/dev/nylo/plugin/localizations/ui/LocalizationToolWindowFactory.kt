package dev.nylo.plugin.localizations.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.nylo.plugin.NyloBundle
import dev.nylo.plugin.localizations.service.LocalizationService
import dev.nylo.plugin.project.NyloProjectDetector

/**
 * Registers the "Nylo Localizations" tool window (Nylo projects only, like the Logs/Screenshots ones).
 * Hosts tabs as separate contents: Summary, Matrix, and Hardcoded strings. Kicks off the first data load
 * after the panels subscribe so they paint as soon as the scan completes.
 */
class LocalizationToolWindowFactory : ToolWindowFactory, DumbAware {

    override suspend fun isApplicableAsync(project: Project): Boolean =
        NyloProjectDetector.isNyloProject(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()
        val parent = toolWindow.disposable

        val summary = SummaryPanel(project, parent)
        val matrix = MatrixPanel(project, parent)
        val hardcoded = HardcodedStringsPanel(project, parent)

        toolWindow.contentManager.addContent(
            contentFactory.createContent(summary, NyloBundle.message("localizations.tab.summary"), false),
        )
        toolWindow.contentManager.addContent(
            contentFactory.createContent(matrix, NyloBundle.message("localizations.tab.matrix"), false),
        )
        toolWindow.contentManager.addContent(
            contentFactory.createContent(hardcoded, NyloBundle.message("localizations.tab.hardcoded"), false),
        )

        LocalizationService.getInstance(project).refreshAsync()
    }
}

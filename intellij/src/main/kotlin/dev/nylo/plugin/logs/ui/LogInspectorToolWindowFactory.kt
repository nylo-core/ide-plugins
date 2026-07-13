package dev.nylo.plugin.logs.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.nylo.plugin.project.NyloProjectDetector

/**
 * Registers the "Nylo Logs" tool window, available only in Nylo projects (gated by
 * [NyloProjectDetector] the same way [dev.nylo.plugin.startup.NyloProjectActivity] gates its work).
 */
class LogInspectorToolWindowFactory : ToolWindowFactory, DumbAware {

    override suspend fun isApplicableAsync(project: Project): Boolean =
        NyloProjectDetector.isNyloProject(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = LogInspectorPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }
}

package dev.nylo.plugin.screenshots.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.nylo.plugin.project.NyloProjectDetector

/**
 * Registers the "Nylo Screenshots" tool window, available only in Nylo projects
 * (gated the same way as the Logs tool window).
 */
class ScreenshotToolWindowFactory : ToolWindowFactory, DumbAware {

    override suspend fun isApplicableAsync(project: Project): Boolean =
        NyloProjectDetector.isNyloProject(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ScreenshotStudioPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }
}

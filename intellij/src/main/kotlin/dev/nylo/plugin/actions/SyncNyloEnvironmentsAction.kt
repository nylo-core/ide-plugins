package dev.nylo.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import dev.nylo.plugin.env.EnvSyncService
import dev.nylo.plugin.project.NyloProjectDetector
import dev.nylo.plugin.ui.NyloNotifications

class SyncNyloEnvironmentsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && NyloProjectDetector.isNyloProject(project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Scan off the EDT, mutate on the EDT (handled by the service), then notify.
        EnvSyncService.getInstance(project).computeAndApplyOnEdt { result ->
            NyloNotifications.notifySyncResult(project, result)
        }
    }
}

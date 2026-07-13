package dev.nylo.plugin.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import dev.nylo.plugin.NyloBundle
import dev.nylo.plugin.env.EnvSyncService

object NyloNotifications {
    private const val GROUP_ID = "Nylo"

    fun notifySyncResult(project: Project, result: EnvSyncService.Result) {
        when (result) {
            is EnvSyncService.Result.Synced -> notifySynced(project, result)
            is EnvSyncService.Result.Skipped -> Unit
        }
    }

    private fun notifySynced(project: Project, result: EnvSyncService.Result.Synced) {
        if (result.isEmpty) return
        val message = when {
            result.added.isNotEmpty() && result.removed.isNotEmpty() ->
                NyloBundle.message(
                    "notification.sync.added.removed",
                    result.added.joinToString(", "),
                    result.removed.joinToString(", "),
                )
            result.added.isNotEmpty() ->
                NyloBundle.message("notification.sync.added", result.added.joinToString(", "))
            else ->
                NyloBundle.message("notification.sync.removed", result.removed.joinToString(", "))
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                NyloBundle.message("notification.sync.title"),
                message,
                NotificationType.INFORMATION,
            )
            .notify(project)
    }
}

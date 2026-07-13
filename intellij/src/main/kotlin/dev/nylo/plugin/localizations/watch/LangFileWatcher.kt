package dev.nylo.plugin.localizations.watch

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.util.SingleAlarm
import dev.nylo.plugin.localizations.service.LocalizationService

/**
 * Watches the project's `lang/` folder for `.json` changes and refreshes the localizations tool window
 * so its tables stay live as translators edit files. Mirrors
 * [dev.nylo.plugin.env.EnvFileWatcher]: a cheap path check on every VFS event, then a debounced EDT
 * refresh. To avoid doing work when the pane is closed, it only refreshes while the tool window is
 * visible (which also avoids instantiating the service prematurely).
 *
 * Registered per-project via `<projectListeners>` on the VFS_CHANGES topic.
 */
class LangFileWatcher(private val project: Project) : BulkFileListener {

    private val refreshAlarm = SingleAlarm.singleEdtAlarm(REFRESH_DEBOUNCE_MS, project) { runRefresh() }

    /** Set when a lang change arrived while the pane was hidden; consumed on the next show. */
    private var pendingWhileHidden = false

    init {
        // Refreshes skipped while hidden must not be lost — re-check when tool-window state changes
        // so reopening the pane shows current data instead of the pre-edit snapshot.
        project.messageBus.connect().subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun stateChanged(toolWindowManager: ToolWindowManager) {
                if (!pendingWhileHidden || project.isDisposed) return
                val toolWindow = toolWindowManager.getToolWindow(LocalizationService.TOOL_WINDOW_ID)
                if (toolWindow != null && toolWindow.isVisible) {
                    pendingWhileHidden = false
                    LocalizationService.getInstance(project).refreshAsync()
                }
            }
        })
    }

    override fun after(events: MutableList<out VFileEvent>) {
        val basePath = project.basePath ?: return
        if (events.any { isLangFileChange(basePath, it.path) }) {
            refreshAlarm.cancelAndRequest()
        }
    }

    private fun runRefresh() {
        if (project.isDisposed) return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(LocalizationService.TOOL_WINDOW_ID)
        if (toolWindow == null || !toolWindow.isVisible) {
            pendingWhileHidden = true
            return
        }
        LocalizationService.getInstance(project).refreshAsync()
    }

    companion object {
        private const val REFRESH_DEBOUNCE_MS = 300

        /**
         * Whether [changedPath] (a `/`-normalized VFS path) is a `.json` file directly in the project's
         * `lang/` folder. Pure and `VFileEvent`-free so it can be unit-tested.
         */
        fun isLangFileChange(basePath: String, changedPath: String): Boolean {
            val langDir = basePath.trimEnd('/') + "/lang/"
            if (!changedPath.startsWith(langDir)) return false
            val rest = changedPath.substring(langDir.length)
            return rest.isNotEmpty() && !rest.contains('/') && rest.endsWith(".json")
        }
    }
}

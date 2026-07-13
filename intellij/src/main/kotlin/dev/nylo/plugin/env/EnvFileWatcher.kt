package dev.nylo.plugin.env

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.SingleAlarm

/**
 * Watches the project root for `.env*` file creation/deletion/rename and re-runs [EnvSyncService]
 * so Flutter run configurations and Metro external tools stay in sync without a restart or the
 * manual *Sync Nylo Environments* action.
 *
 * Registered per-project via `<projectListeners>` on the VFS_CHANGES topic. [after] fires for every
 * VFS event in the IDE, so it does only a cheap path check and defers the real work to a debounced
 * EDT alarm — the sync mutates RunManager/ToolManager and re-scans the real filesystem.
 */
class EnvFileWatcher(private val project: Project) : BulkFileListener {

    private val syncAlarm = SingleAlarm.singleEdtAlarm(SYNC_DEBOUNCE_MS, project) { runSync() }

    override fun after(events: MutableList<out VFileEvent>) {
        val basePath = project.basePath ?: return
        if (events.any { isRelevant(basePath, it) }) {
            syncAlarm.cancelAndRequest()
        }
    }

    /**
     * True when the event touches an `.env*` file in the project root. For renames and moves
     * [VFileEvent.getPath] is the file's NEW path, so an env file renamed/moved *away* from an
     * env name would go unnoticed — check the old path too.
     */
    private fun isRelevant(basePath: String, event: VFileEvent): Boolean {
        if (isEnvFileChangeInRoot(basePath, event.path)) return true
        val oldPath = when (event) {
            is VFilePropertyChangeEvent -> event.oldPath
            is VFileMoveEvent -> event.oldPath
            else -> return false
        }
        return isEnvFileChangeInRoot(basePath, oldPath)
    }

    private fun runSync() {
        if (project.isDisposed) return
        // Scan happens off the EDT (the service offloads it); mutations come back to the EDT.
        // The watcher syncs silently: `.env*` files come and go as a side effect of the user's own
        // edits, so a result popup would just be noise. The manual *Sync Nylo Environments* action
        // and project-open sync still notify.
        EnvSyncService.getInstance(project).computeAndApplyOnEdt { /* sync silently */ }
    }

    companion object {
        private const val SYNC_DEBOUNCE_MS = 250

        /**
         * Whether [changedPath] (a `/`-normalized VFS path) points at an `.env*` file directly in
         * the project root [basePath]. Pure and free of `VFileEvent` so it can be unit-tested.
         */
        fun isEnvFileChangeInRoot(basePath: String, changedPath: String): Boolean {
            val parent = changedPath.substringBeforeLast('/', "")
            val name = changedPath.substringAfterLast('/')
            return parent == basePath.trimEnd('/') && EnvFileScanner.isEnvFileName(name)
        }
    }
}

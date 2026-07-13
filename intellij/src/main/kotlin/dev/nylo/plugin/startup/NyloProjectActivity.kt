package dev.nylo.plugin.startup

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.nylo.plugin.env.EnvSyncService
import dev.nylo.plugin.project.NyloProjectDetector
import dev.nylo.plugin.runconfig.FlutterRunConfigSync
import dev.nylo.plugin.ui.NyloNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class NyloProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!NyloProjectDetector.isNyloProject(project)) return
        // `execute` runs off the EDT: do the readiness wait + scan here, then hop to the EDT to
        // mutate RunManager/ToolManager. Without the readiness wait, a startup race with the Flutter
        // plugin would make the sync silently skip (FlutterPluginMissing).
        if (!awaitFlutterReady()) return

        val service = EnvSyncService.getInstance(project)
        val plan = service.computePlan()
        val result = withContext(Dispatchers.EDT) {
            if (project.isDisposed) null else service.applyPlan(plan)
        } ?: return
        NyloNotifications.notifySyncResult(project, result)
    }

    /**
     * The Flutter plugin registers its run-configuration type during its own (sometimes late)
     * project startup. Poll briefly so a startup race doesn't drop the sync.
     */
    private suspend fun awaitFlutterReady(): Boolean {
        repeat(FLUTTER_READY_ATTEMPTS) {
            if (FlutterRunConfigSync.isFlutterAvailable()) return true
            delay(FLUTTER_READY_POLL_MS)
        }
        return FlutterRunConfigSync.isFlutterAvailable()
    }

    private companion object {
        const val FLUTTER_READY_ATTEMPTS = 20
        const val FLUTTER_READY_POLL_MS = 500L
    }
}

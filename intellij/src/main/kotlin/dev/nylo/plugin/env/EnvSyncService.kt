package dev.nylo.plugin.env

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Computable
import dev.nylo.plugin.externaltools.MetroExternalToolSync
import dev.nylo.plugin.project.NyloProjectDetector
import dev.nylo.plugin.runconfig.FlutterRunConfigSync
import dev.nylo.plugin.state.NyloPluginState

/**
 * Keeps Flutter run configurations and Metro external tools in sync with the project's `.env*` files.
 *
 * Work is split into two phases so each can run on the correct thread:
 *  - [computePlan] does filesystem work (project detection, scanning) and is safe on a background
 *    thread — running it off the EDT avoids "slow operations on EDT" warnings.
 *  - [applyPlan] mutates `RunManager`/`ToolManager` and **must run on the EDT**.
 *
 * [computeAndApplyOnEdt] wires the two together for EDT-bound callers (the action, the file watcher).
 */
@Service(Service.Level.PROJECT)
class EnvSyncService(private val project: Project) {

    enum class SkipReason { NotANyloProject, FlutterPluginMissing }

    sealed interface Result {
        val isEmpty: Boolean
        data class Synced(val added: List<String>, val removed: List<String>) : Result {
            override val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()
        }
        data class Skipped(val reason: SkipReason) : Result {
            override val isEmpty: Boolean get() = true
        }
    }

    sealed interface Plan {
        data class Ready(val envFiles: List<EnvFile>) : Plan
        data class Skip(val reason: SkipReason) : Plan
    }

    /** Background-safe: detect the project type and scan `.env*` files. Does no IDE mutation. */
    fun computePlan(): Plan {
        if (!NyloProjectDetector.isNyloProject(project)) return Plan.Skip(SkipReason.NotANyloProject)
        if (!FlutterRunConfigSync.isFlutterAvailable()) return Plan.Skip(SkipReason.FlutterPluginMissing)
        return Plan.Ready(EnvFileScanner.scan(project))
    }

    /** EDT-only: apply [plan] to RunManager/ToolManager. */
    fun applyPlan(plan: Plan): Result = when (plan) {
        is Plan.Skip -> Result.Skipped(plan.reason)
        is Plan.Ready -> doApply(plan.envFiles)
    }

    private fun doApply(envFiles: List<EnvFile>): Result {
        val state = NyloPluginState.getInstance(project)

        // External tools (IDE-global). Add missing, then remove orphans this project owns —
        // but only those no other open project still manages (tools are shared by name).
        MetroExternalToolSync.addMissing(envFiles)
        val currentToolNames = envFiles.map { MetroToolNaming.toolName(it.displayName) }.toSet()
        // Record every tool this project's env files use, not just the ones just created: another
        // project's removableTools() consults this set, so a project that found its tools already
        // existing must still declare them or a sibling's orphan cleanup could delete them.
        currentToolNames.forEach(state::rememberExternalTool)
        val toolOrphans = state.managedExternalTools.toSet() - currentToolNames
        val toolsRemoved = MetroExternalToolSync.removeOrphans(removableTools(state, toolOrphans))
        toolsRemoved.forEach(state::forgetExternalTool)

        // Run configurations (ownership-based, additive). State is updated inside reconcile.
        // RunManager.addConfiguration silently no-ops without write access on modern platforms
        // (it runs on the EDT but needs the write lock), so do the mutations in a write action.
        val (added, removed) = ApplicationManager.getApplication().runWriteAction(
            Computable { FlutterRunConfigSync.reconcile(project, envFiles, state) }
        )
        return Result.Synced(added, removed)
    }

    /**
     * Convenience for EDT-bound callers: computes the plan on a pooled thread (off the EDT) and
     * applies it back on the EDT, delivering the [Result] to [onResult] on the EDT.
     */
    fun computeAndApplyOnEdt(onResult: (Result) -> Unit) {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val plan = computePlan()
            app.invokeLater({
                if (!project.isDisposed) onResult(applyPlan(plan))
            }, ModalityState.nonModal())
        }
    }

    /**
     * Narrows [toolOrphans] to tools no other open project still manages. External tools are
     * IDE-global, so a tool named identically (e.g. "Metro make:env Prod") may be shared by
     * another open project that still has the matching `.env` file; removing it would break that
     * project's before-run task.
     */
    private fun removableTools(state: NyloPluginState, toolOrphans: Set<String>): List<String> {
        if (toolOrphans.isEmpty()) return emptyList()
        val others = ProjectManager.getInstance().openProjects.filter { it != project && !it.isDisposed }
        if (others.isEmpty()) return toolOrphans.toList()
        return toolOrphans.filter { name ->
            others.none { NyloPluginState.getInstance(it).managedExternalTools.contains(name) }
        }
    }

    companion object {
        fun getInstance(project: Project): EnvSyncService = project.service()
    }
}

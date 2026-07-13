package dev.nylo.plugin.externaltools

import com.intellij.tools.Tool
import com.intellij.tools.ToolManager
import com.intellij.tools.ToolsGroup
import dev.nylo.plugin.env.EnvFile
import dev.nylo.plugin.env.MetroToolNaming

/**
 * Adds and removes `Metro make:env <Name>` external tools in the IDE-global External Tools list.
 *
 * External tools are global to the IDE installation. [addMissing] only adds entries that are
 * missing; [removeOrphans] removes named tools the caller has determined are no longer needed.
 * Callers are responsible for not removing tools still in use by other open projects.
 */
object MetroExternalToolSync {
    private const val GROUP_NAME = "External Tools"

    fun addMissing(envFiles: List<EnvFile>): List<String> {
        if (envFiles.isEmpty()) return emptyList()
        val toolManager = ToolManager.getInstance()
        val wantedNames = envFiles.map { MetroToolNaming.toolName(it.displayName) }.toSet()
        val existingNames = toolManager.tools.map { it.name }.toSet()
        val missing = envFiles.filter { MetroToolNaming.toolName(it.displayName) !in existingNames }

        val groups: MutableList<ToolsGroup<Tool>> = toolManager.groups.toMutableList()

        // Older builds created tools disabled, so a disabled before-run task would never run. Enable
        // any managed tool that already exists but is disabled. Mutate the instances *inside* the
        // groups (what setTools persists) — the list from ToolManager.tools may be separate copies.
        var enabledAny = false
        for (group in groups) {
            for (tool in group.elements) {
                if (tool.name in wantedNames && !tool.isEnabled) {
                    tool.isEnabled = true
                    enabledAny = true
                }
            }
        }
        if (missing.isEmpty() && !enabledAny) return emptyList()

        val targetGroup: ToolsGroup<Tool> = groups.firstOrNull { it.name == GROUP_NAME }
            ?: ToolsGroup<Tool>(GROUP_NAME).also { groups.add(it) }

        val added = mutableListOf<String>()
        for (env in missing) {
            val tool = createTool(env)
            targetGroup.addElement(tool)
            added.add(tool.name!!)
        }
        toolManager.setTools(groups)
        return added
    }

    /**
     * Removes external tools whose name is in [orphanToolNames] from the External Tools group.
     *
     * `Tool.equals` is a deep field-by-field comparison, so we cannot remove by rebuilding a
     * `Tool` — we filter the group's stored instances by name and remove those. Returns the
     * names actually removed.
     */
    fun removeOrphans(orphanToolNames: Collection<String>): List<String> {
        if (orphanToolNames.isEmpty()) return emptyList()
        val orphanSet = orphanToolNames.toSet()
        val toolManager = ToolManager.getInstance()
        val groups = toolManager.groups.toMutableList()
        val group = groups.firstOrNull { it.name == GROUP_NAME } ?: return emptyList()

        val toRemove = group.elements.filter { it.name in orphanSet }
        if (toRemove.isEmpty()) return emptyList()
        toRemove.forEach { group.removeElement(it) }
        toolManager.setTools(groups)
        return toRemove.mapNotNull { it.name }
    }

    private fun createTool(env: EnvFile): Tool = Tool().apply {
        name = MetroToolNaming.toolName(env.displayName)
        isEnabled = true // Tool() defaults to disabled; the before-run task needs it enabled to run.
        program = "dart"
        parameters = """run nylo_framework:main make:env --file="${env.fileName}""""
        workingDirectory = "\$ProjectFileDir\$"
        isUseConsole = false
        isShowConsoleOnStdOut = false
        isShowConsoleOnStdErr = false
        setFilesSynchronizedAfterRun(true)
    }
}

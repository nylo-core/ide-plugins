package dev.nylo.plugin.env

import com.intellij.openapi.project.Project
import java.io.File

object EnvFileScanner {
    private const val EXAMPLE_FILENAME = ".env-example"
    private val ENV_PATTERN = Regex("""^\.env(?:\.(.+))?$""")

    fun scan(project: Project): List<EnvFile> {
        val basePath = project.basePath ?: return emptyList()
        return scan(File(basePath))
    }

    fun scan(projectDir: File): List<EnvFile> {
        if (!projectDir.isDirectory) return emptyList()
        val files = projectDir.listFiles { _, name -> isEnvFileName(name) }
            ?: return emptyList()
        return files.mapNotNull { toEnvFile(it) }.sortedBy { it.displayName }
    }

    /**
     * Whether [name] (a bare file name, not a path) is an `.env*` file the plugin manages.
     * Single source of truth shared by [scan] and [EnvFileWatcher]; `.env-example` is excluded.
     */
    fun isEnvFileName(name: String): Boolean = name != EXAMPLE_FILENAME && ENV_PATTERN.matches(name)

    private fun toEnvFile(file: File): EnvFile? {
        val match = ENV_PATTERN.matchEntire(file.name) ?: return null
        val suffix = match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
        return EnvFile(file = file, suffix = suffix, displayName = EnvFileNaming.displayName(suffix))
    }
}

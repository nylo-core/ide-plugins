package dev.nylo.plugin.project

import com.intellij.openapi.project.Project
import java.io.File

object NyloProjectDetector {
    private val NYLO_FRAMEWORK_LINE = Regex("""^\s*nylo_framework\s*:""", RegexOption.MULTILINE)

    fun isNyloProject(project: Project): Boolean {
        val basePath = project.basePath ?: return false
        return isNyloProject(File(basePath))
    }

    fun isNyloProject(projectDir: File): Boolean {
        val pubspec = File(projectDir, "pubspec.yaml")
        if (!pubspec.isFile) return false
        return runCatching { pubspec.readText().contains(NYLO_FRAMEWORK_LINE) }.getOrDefault(false)
    }
}

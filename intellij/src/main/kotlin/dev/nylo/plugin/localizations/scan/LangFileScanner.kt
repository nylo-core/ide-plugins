package dev.nylo.plugin.localizations.scan

import com.intellij.openapi.project.Project
import java.io.File

/**
 * Discovers Nylo locale files under the project's `lang/` folder — one `<code>.json` per language.
 *
 * Mirrors [dev.nylo.plugin.screenshots.project.LocaleScanner] but returns the backing files (not just
 * codes), since the localizations feature needs to read their contents. The `File` overload keeps it
 * unit-testable without a `Project`.
 */
object LangFileScanner {
    fun scan(project: Project): List<LangFile> {
        val basePath = project.basePath ?: return emptyList()
        return scan(File(basePath))
    }

    fun scan(projectDir: File): List<LangFile> {
        val langDir = File(projectDir, "lang")
        if (!langDir.isDirectory) return emptyList()
        val files = langDir.listFiles { f -> f.isFile && f.extension == "json" } ?: return emptyList()
        return files.map { LangFile(it.nameWithoutExtension, it) }.sortedBy { it.code }
    }

    /** The project's `lang/` directory (whether or not it exists). */
    fun langDir(project: Project): File? = project.basePath?.let { File(File(it), "lang") }
}

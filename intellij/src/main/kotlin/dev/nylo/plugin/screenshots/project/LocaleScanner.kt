package dev.nylo.plugin.screenshots.project

import com.intellij.openapi.project.Project
import java.io.File

/**
 * Discovers the app's supported locales from its `lang/` folder. Nylo stores one
 * `<code>.json` per language there (e.g. `en.json`, `es.json`).
 */
object LocaleScanner {
    fun scan(project: Project): List<String> {
        val base = project.basePath?.let(::File) ?: return emptyList()
        val langDir = File(base, "lang")
        if (!langDir.isDirectory) return emptyList()
        return langDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            .orEmpty()
    }
}

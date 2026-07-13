package dev.nylo.plugin.localizations.cli

import java.io.File

/**
 * Decides how to invoke the Nylo CLI for `locale:find-untranslated` in a given project.
 *
 * Prefers `dart run nylo_installer:nylo` when the project actually depends on `nylo_installer` (works
 * without a global activation); otherwise assumes a globally-activated `nylo` on the PATH. Either way it
 * returns a best guess — actual availability is only known once the process runs, so the caller
 * ([FindUntranslatedRunner]) degrades gracefully if it can't start. Pure for testability.
 */
object NyloCliLocator {

    private val INSTALLER_DEP = Regex("""(?m)^\s*nylo_installer\s*:""")

    fun resolve(projectDir: File): List<String> {
        if (referencesInstaller(projectDir)) return listOf("dart", "run", "nylo_installer:nylo")
        return listOf("nylo")
    }

    private fun referencesInstaller(projectDir: File): Boolean {
        val pubspec = File(projectDir, "pubspec.yaml")
        if (pubspec.isFile && runCatching { pubspec.readText().contains(INSTALLER_DEP) }.getOrDefault(false)) return true
        val lock = File(projectDir, "pubspec.lock")
        return lock.isFile && runCatching { lock.readText().contains("nylo_installer") }.getOrDefault(false)
    }
}

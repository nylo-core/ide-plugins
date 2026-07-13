package dev.nylo.plugin.localizations.scan

import java.io.File

/**
 * Picks the baseline locale that other locales are compared against. Preference order: a persisted
 * user [override], then `DEFAULT_LOCALE` from the project's `.env`, then `en` if present, then the
 * first locale. Returns null only when there are no locale files. Pure (no IDE deps) for testability.
 */
object BaselineResolver {
    fun resolve(localeCodes: List<String>, override: String?, envDefaultLocale: String?): String? {
        if (localeCodes.isEmpty()) return null
        override?.takeIf { it in localeCodes }?.let { return it }
        envDefaultLocale?.takeIf { it in localeCodes }?.let { return it }
        if ("en" in localeCodes) return "en"
        return localeCodes.first()
    }

    /** Extracts `DEFAULT_LOCALE` from a `.env` file's contents (e.g. `DEFAULT_LOCALE="en"`). Null if absent. */
    fun readEnvDefaultLocale(envText: String): String? {
        val regex = Regex("""^\s*DEFAULT_LOCALE\s*=\s*["']?([A-Za-z0-9_\-]+)["']?\s*$""", RegexOption.MULTILINE)
        return regex.find(envText)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    /** Reads `DEFAULT_LOCALE` from a `.env` [envFile], or null when absent/unreadable. */
    fun readEnvDefaultLocale(envFile: File): String? =
        if (envFile.isFile) runCatching { readEnvDefaultLocale(envFile.readText()) }.getOrNull() else null
}

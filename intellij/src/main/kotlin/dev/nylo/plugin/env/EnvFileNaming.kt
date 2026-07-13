package dev.nylo.plugin.env

object EnvFileNaming {
    fun displayName(suffix: String?): String {
        if (suffix.isNullOrBlank()) return "Default"
        return suffix.split('.')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { segment -> segment.replaceFirstChar { it.uppercaseChar() } }
            .ifEmpty { "Default" }
    }
}

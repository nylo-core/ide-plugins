package dev.nylo.plugin.env

import java.io.File

data class EnvFile(
    val file: File,
    val suffix: String?,
    val displayName: String,
) {
    val fileName: String = file.name

    val toolActionId: String = "Tool_External Tools_${MetroToolNaming.toolName(displayName)}"
}

object MetroToolNaming {
    fun toolName(displayName: String): String = "Metro make:env $displayName"
}

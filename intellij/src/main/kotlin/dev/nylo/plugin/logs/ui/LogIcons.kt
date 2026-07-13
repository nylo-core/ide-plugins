package dev.nylo.plugin.logs.ui

import com.intellij.openapi.util.IconLoader

object LogIcons {
    /**
     * Tool-window stripe icon. Ships 16×16 (compact) + `@20x20` (new UI) variants, each with a
     * `_dark` counterpart; the platform auto-selects by size/theme and recolors to white when the
     * stripe button is selected (relies on the mandated content colors #6C707E / #CED0D6).
     */
    @JvmField
    val TOOL_WINDOW = IconLoader.getIcon("/icons/nyloLogs.svg", LogIcons::class.java)
}

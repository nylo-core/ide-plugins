package dev.nylo.plugin.state

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.nylo.plugin.logs.model.LogCategory

@Service(Service.Level.PROJECT)
@State(name = "NyloPluginState", storages = [Storage("nylo.xml")])
class NyloPluginState : SimplePersistentStateComponent<NyloPluginState.Data>(Data()) {

    class Data : BaseState() {
        /**
         * Stable ownership map: env file name (e.g. `.env.staging`) -> the display name of the run
         * configuration the plugin generated for it. Keyed by file, not by display name, so renames
         * and naming-scheme changes don't lose track of which config belongs to which `.env*` file.
         */
        var managedConfigs by map<String, String>()
        var managedExternalTools by stringSet()

        /** Legacy (pre-ownership-map) set of generated config names. Read once for migration, then cleared. */
        var managedRunConfigs by stringSet()
        var logSortNewestFirst by property(true)
        var logFollow by property(true)
        var logCategory by string()

        // Localizations tool window — last view state.
        var localizationsBaseline by string()
        var localizationsShowMissing by property(true)
        var localizationsShowEmpty by property(true)
        var localizationsShowExtra by property(true)
        var localizationsShowSameAsBase by property(false)
        var localizationsLocaleFilter by string()
        var localizationsProblemsOnly by property(true)

        // Screenshot Studio — last selection.
        var screenshotRoutes by stringSet()
        var screenshotLocales by stringSet()
        var screenshotDevices by stringSet()
        var screenshotOutputDir by string()
        var screenshotSettleMs by property(1200)
        var screenshotWindowMs by property(1200)
        var screenshotCleanStatusBar by property(true)
    }

    /** Env file name -> generated run-config display name. See [Data.managedConfigs]. */
    val managedConfigs: MutableMap<String, String> get() = state.managedConfigs
    val managedExternalTools: MutableSet<String> get() = state.managedExternalTools

    /** Names recorded by older plugin builds before ownership tracking existed; used only for migration. */
    val legacyRunConfigNames: Set<String> get() = state.managedRunConfigs.toSet()

    var logSortNewestFirst: Boolean
        get() = state.logSortNewestFirst
        set(value) { state.logSortNewestFirst = value }

    var logFollow: Boolean
        get() = state.logFollow
        set(value) { state.logFollow = value }

    var logCategory: LogCategory
        get() = state.logCategory?.let { runCatching { LogCategory.valueOf(it) }.getOrNull() } ?: LogCategory.ALL
        set(value) { state.logCategory = value.name }

    /** Baseline locale override for the Localizations pane; null = use the resolved default (.env / en). */
    var localizationsBaseline: String?
        get() = state.localizationsBaseline?.takeIf { it.isNotBlank() }
        set(value) { state.localizationsBaseline = value?.takeIf { it.isNotBlank() } }

    var localizationsShowMissing: Boolean
        get() = state.localizationsShowMissing
        set(value) { state.localizationsShowMissing = value }

    var localizationsShowEmpty: Boolean
        get() = state.localizationsShowEmpty
        set(value) { state.localizationsShowEmpty = value }

    var localizationsShowExtra: Boolean
        get() = state.localizationsShowExtra
        set(value) { state.localizationsShowExtra = value }

    var localizationsShowSameAsBase: Boolean
        get() = state.localizationsShowSameAsBase
        set(value) { state.localizationsShowSameAsBase = value }

    /** Locale filter for the issue table; null/blank = all locales. */
    var localizationsLocaleFilter: String?
        get() = state.localizationsLocaleFilter?.takeIf { it.isNotBlank() }
        set(value) { state.localizationsLocaleFilter = value?.takeIf { it.isNotBlank() } }

    var localizationsProblemsOnly: Boolean
        get() = state.localizationsProblemsOnly
        set(value) { state.localizationsProblemsOnly = value }

    val screenshotRoutes: MutableSet<String> get() = state.screenshotRoutes
    val screenshotLocales: MutableSet<String> get() = state.screenshotLocales
    val screenshotDevices: MutableSet<String> get() = state.screenshotDevices

    var screenshotOutputDir: String
        get() = state.screenshotOutputDir?.takeIf { it.isNotBlank() } ?: "screenshots"
        set(value) { state.screenshotOutputDir = value }

    var screenshotSettleMs: Int
        get() = state.screenshotSettleMs
        set(value) { state.screenshotSettleMs = value }

    var screenshotWindowMs: Int
        get() = state.screenshotWindowMs
        set(value) { state.screenshotWindowMs = value }

    var screenshotCleanStatusBar: Boolean
        get() = state.screenshotCleanStatusBar
        set(value) { state.screenshotCleanStatusBar = value }

    fun setScreenshotSelection(routes: Collection<String>, locales: Collection<String>, devices: Collection<String>) {
        state.screenshotRoutes.apply { clear(); addAll(routes) }
        state.screenshotLocales.apply { clear(); addAll(locales) }
        state.screenshotDevices.apply { clear(); addAll(devices) }
    }

    fun setScreenshotRoutes(routes: Collection<String>) {
        state.screenshotRoutes.apply { clear(); addAll(routes) }
    }

    fun rememberConfig(envFileName: String, configName: String) { state.managedConfigs[envFileName] = configName }
    fun forgetConfig(envFileName: String) { state.managedConfigs.remove(envFileName) }
    fun clearLegacyRunConfigs() { state.managedRunConfigs.clear() }
    fun rememberExternalTool(name: String) { state.managedExternalTools.add(name) }
    fun forgetExternalTool(name: String) { state.managedExternalTools.remove(name) }

    companion object {
        fun getInstance(project: Project): NyloPluginState = project.service()
    }
}

package dev.nylo.plugin.localizations.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.nylo.plugin.localizations.compare.LocaleComparator
import dev.nylo.plugin.localizations.edit.LangFileWriter
import dev.nylo.plugin.localizations.json.LangJson
import dev.nylo.plugin.localizations.model.LocalizationReport
import dev.nylo.plugin.localizations.scan.BaselineResolver
import dev.nylo.plugin.localizations.scan.LangFileScanner
import dev.nylo.plugin.state.NyloPluginState
import java.io.File

/**
 * Owns the localization data for a project so the tool window's tabs stay consistent.
 *
 * Reads + flattens every `lang/<code>.json` off the EDT, compares them with [LocaleComparator] (honoring the
 * persisted baseline + same-as-base toggle from [NyloPluginState]), caches the [report] and the
 * in-memory value maps, and publishes [LocalizationDataListener] on the EDT. Baseline/filter changes and
 * inline edits recompute from the in-memory maps without re-reading disk.
 */
@Service(Service.Level.PROJECT)
class LocalizationService(private val project: Project) {

    @Volatile
    var report: LocalizationReport = LocalizationReport.EMPTY
        private set

    @Volatile
    private var values: Map<String, LinkedHashMap<String, String>> = emptyMap()

    @Volatile
    private var files: Map<String, File> = emptyMap()

    @Volatile
    private var parseErrors: Map<String, String> = emptyMap()

    val localeCodes: List<String> get() = (values.keys + parseErrors.keys).sorted()

    fun valuesFor(locale: String): Map<String, String> = values[locale].orEmpty()

    fun fileFor(locale: String): File? = files[locale]

    /** Whether the project actually has a `lang/` directory (vs. just not being scanned yet). */
    fun hasLangDir(): Boolean = LangFileScanner.langDir(project)?.isDirectory == true

    /** Reloads every lang file off the EDT, recomputes the report, then publishes on the EDT. */
    fun refreshAsync() {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val newValues = LinkedHashMap<String, LinkedHashMap<String, String>>()
            val newFiles = LinkedHashMap<String, File>()
            val newErrors = LinkedHashMap<String, String>()
            for (lf in LangFileScanner.scan(project)) {
                newFiles[lf.code] = lf.file
                try {
                    newValues[lf.code] = LangJson.readFlattened(lf.file)
                } catch (e: Exception) {
                    newErrors[lf.code] = e.message ?: "Invalid JSON"
                }
            }
            values = newValues
            files = newFiles
            parseErrors = newErrors
            publish(compute())
        }
    }

    /** Recompares the in-memory maps with the current baseline/flag settings (no disk I/O). */
    fun recomputeAsync() {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!project.isDisposed) publish(compute())
        }
    }

    private fun compute(): LocalizationReport {
        val state = NyloPluginState.getInstance(project)
        val codes = (values.keys + parseErrors.keys).sorted()
        val baseline = BaselineResolver.resolve(codes, state.localizationsBaseline, envDefaultLocale()) ?: ""
        return LocaleComparator.compare(
            baseline = baseline,
            localeValues = values,
            parseErrors = parseErrors,
            flagSameAsBase = state.localizationsShowSameAsBase,
        )
    }

    private fun publish(newReport: LocalizationReport) {
        report = newReport
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) {
                project.messageBus.syncPublisher(LocalizationDataListener.TOPIC).localizationDataChanged()
            }
        }, ModalityState.nonModal())
    }

    private fun envDefaultLocale(): String? {
        val basePath = project.basePath ?: return null
        return BaselineResolver.readEnvDefaultLocale(File(basePath, ".env"))
    }

    /** Persists a new baseline override (null clears it back to the resolved default) and recomputes. */
    fun setBaselineOverride(code: String?) {
        NyloPluginState.getInstance(project).localizationsBaseline = code
        recomputeAsync()
    }

    /** Persists the same-as-base toggle and recomputes (it changes which issues are produced). */
    fun setFlagSameAsBase(enabled: Boolean) {
        NyloPluginState.getInstance(project).localizationsShowSameAsBase = enabled
        recomputeAsync()
    }

    /**
     * EDT-only inline edit: writes [value] for [key] into [locale]'s file, updates the in-memory map so
     * the UI reflects it immediately, then recomputes. Returns null on success, else an error message.
     */
    fun setValue(locale: String, key: String, value: String): String? {
        val file = files[locale] ?: return "No lang file for $locale"
        // No-change commits (double-click a cell, press Enter) must not rewrite the file: the write
        // re-serializes the whole JSON (reformatting hand-formatted files) and triggers a refresh cycle.
        if (values[locale]?.get(key) == value) return null
        val error = LangFileWriter.setValue(project, file, key, value)
        if (error != null) return error
        values[locale]?.let { existing ->
            val copy = LinkedHashMap(existing).apply { put(key, value) }
            values = HashMap(values).apply { put(locale, copy) }
        }
        recomputeAsync()
        return null
    }

    companion object {
        const val TOOL_WINDOW_ID = "Nylo Localizations"

        fun getInstance(project: Project): LocalizationService = project.service()
    }
}

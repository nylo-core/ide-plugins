package dev.nylo.plugin.localizations.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import dev.nylo.plugin.localizations.service.LocalizationDataListener
import dev.nylo.plugin.localizations.service.LocalizationService
import dev.nylo.plugin.state.NyloPluginState
import java.awt.BorderLayout
import java.awt.event.ItemEvent
import javax.swing.JPanel

/**
 * Shared scaffolding for the localization tool-window tabs: registers disposal, subscribes to the
 * [LocalizationService]'s data topic (the service publishes on the EDT, so [onDataChanged] runs there),
 * and provides the common baseline combo. Subclasses lay out their own tables/toolbars and repaint
 * in [onDataChanged].
 */
abstract class LocalizationTabPanel(
    protected val project: Project,
    parent: Disposable,
) : JPanel(BorderLayout()), Disposable {

    protected val service: LocalizationService = LocalizationService.getInstance(project)
    protected val pluginState: NyloPluginState = NyloPluginState.getInstance(project)

    private var suppressBaselineEvents = false
    protected val baselineCombo: ComboBox<String> = ComboBox<String>().apply {
        addItemListener { e ->
            if (!suppressBaselineEvents && e.stateChange == ItemEvent.SELECTED) {
                (e.item as? String)?.let { service.setBaselineOverride(it) }
            }
        }
    }

    init {
        Disposer.register(parent, this)
        project.messageBus.connect(this).subscribe(
            LocalizationDataListener.TOPIC,
            LocalizationDataListener { if (!project.isDisposed) onDataChanged() },
        )
    }

    /** Reflects the current locale list + resolved baseline in [baselineCombo] without firing a reselect. */
    protected fun refreshBaselineCombo() {
        suppressBaselineEvents = true
        try {
            baselineCombo.removeAllItems()
            service.localeCodes.forEach { baselineCombo.addItem(it) }
            baselineCombo.selectedItem = service.report.baseline.takeIf { it.isNotEmpty() }
            baselineCombo.isEnabled = service.localeCodes.isNotEmpty()
        } finally {
            suppressBaselineEvents = false
        }
    }

    /** Called on the EDT whenever the service publishes new data. */
    protected abstract fun onDataChanged()

    override fun dispose() {}
}

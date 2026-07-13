package dev.nylo.plugin.localizations.service

import com.intellij.util.messages.Topic

/**
 * Project message-bus topic the [LocalizationService] publishes on whenever its cached report changes
 * (a reload, a baseline/filter change, or an inline edit). The tool-window tabs subscribe and repaint;
 * the connection is disposed with each panel.
 */
fun interface LocalizationDataListener {
    fun localizationDataChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<LocalizationDataListener> =
            Topic.create("Nylo localization data", LocalizationDataListener::class.java)
    }
}

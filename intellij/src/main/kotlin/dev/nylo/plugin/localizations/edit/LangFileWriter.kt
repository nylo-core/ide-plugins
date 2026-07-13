package dev.nylo.plugin.localizations.edit

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import dev.nylo.plugin.localizations.json.LangJson
import java.io.File

/**
 * Writes a single translation value back into a `lang/<code>.json` file for inline edits.
 *
 * Goes through the IDE document (when one is loaded) so an open editor refreshes and the change joins
 * the undo stack; falls back to a direct VFS write otherwise. Must be called on the EDT — it mutates
 * inside a [WriteCommandAction]. Returns null on success, else a user-facing error message.
 */
object LangFileWriter {
    fun setValue(project: Project, file: File, key: String, value: String): String? {
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            ?: return "Could not locate ${file.name}"
        var error: String? = null
        WriteCommandAction.runWriteCommandAction(project, "Edit Translation", null, Runnable {
            error = runCatching {
                val fdm = FileDocumentManager.getInstance()
                val document = fdm.getDocument(vFile)
                if (document != null) {
                    document.setText(LangJson.withValue(document.text, key, value))
                    fdm.saveDocument(document)
                } else {
                    VfsUtil.saveText(vFile, LangJson.withValue(VfsUtil.loadText(vFile), key, value))
                }
                // A null message (e.g. an NPE) must still surface as a failure, not read as success.
            }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        })
        return error
    }
}

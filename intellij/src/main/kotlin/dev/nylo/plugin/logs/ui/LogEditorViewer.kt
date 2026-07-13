package dev.nylo.plugin.logs.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.FoldingModelEx
import com.intellij.openapi.project.Project
import dev.nylo.plugin.logs.render.RenderFold
import dev.nylo.plugin.logs.render.RenderResult
import javax.swing.JComponent

/**
 * A read-only editor viewer (Option B) over a synthetic [com.intellij.openapi.editor.Document].
 * Holds the entire log (no console cycle-buffer truncation) and collapses Dio boxes via manual fold
 * regions. When new content merely extends the current content (the common live-tail case), it
 * appends the delta — preserving existing fold/scroll/selection state — instead of replacing the
 * whole document. Released through [dispose].
 */
class LogEditorViewer(project: Project) : Disposable {

    private val factory = EditorFactory.getInstance()
    private val document = factory.createDocument("")
    private val editor: EditorEx = factory.createViewer(document, project, EditorKind.CONSOLE) as EditorEx

    /** Mirror of the document text, so we can detect a pure append without re-reading the document. */
    private var lastText: String = ""

    init {
        editor.settings.apply {
            isLineNumbersShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = true
            isRightMarginShown = false
            isCaretRowShown = false
            additionalLinesCount = 0
            additionalColumnsCount = 0
            isUseSoftWraps = false
        }
    }

    val component: JComponent get() = editor.component

    /** Replaces (or appends to) the content and re-applies fold regions; scrolls to [scrollTo] when tailing. */
    fun setContent(result: RenderResult, scrollTo: Int? = null) {
        val folding = editor.foldingModel as FoldingModelEx
        if (lastText.isNotEmpty() && result.text.length > lastText.length && result.text.startsWith(lastText)) {
            val base = lastText.length
            val suffix = result.text.substring(base)
            ApplicationManager.getApplication().runWriteAction {
                document.insertString(document.textLength, suffix)
            }
            folding.runBatchFoldingOperation {
                result.folds.forEach { if (it.startOffset >= base) addFold(folding, it) }
            }
        } else {
            ApplicationManager.getApplication().runWriteAction {
                document.setText(result.text)
            }
            folding.runBatchFoldingOperation {
                folding.allFoldRegions.forEach(folding::removeFoldRegion)
                result.folds.forEach { addFold(folding, it) }
            }
        }
        lastText = result.text
        if (scrollTo != null) {
            editor.caretModel.moveToOffset(scrollTo.coerceIn(0, document.textLength))
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        }
    }

    private fun addFold(folding: FoldingModelEx, fold: RenderFold) {
        if (fold.startOffset in 0 until fold.endOffset && fold.endOffset <= document.textLength) {
            folding.addFoldRegion(fold.startOffset, fold.endOffset, fold.placeholder)?.isExpanded = false
        }
    }

    override fun dispose() {
        factory.releaseEditor(editor)
    }
}

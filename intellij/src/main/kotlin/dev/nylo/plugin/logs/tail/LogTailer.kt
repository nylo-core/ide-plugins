package dev.nylo.plugin.logs.tail

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import java.io.File

/**
 * Polls a log file for changes and invokes [onChanged] on the EDT when it grows (or is
 * truncated/rotated), appears, or when [update]'s resolver starts pointing at a different path
 * (e.g. the midnight rollover to a new daily file). Used to live-tail today's file. Polling — not
 * VFS — is primary because the Flutter process writes outside the IDE's virtual file system.
 * Owned by the tool window via [Disposable].
 *
 * The file is a resolver, not a fixed [File], so "today's file" is re-evaluated on every poll —
 * a file that doesn't exist yet (first app run of the day) is watched into existence.
 * [update] runs on the EDT while [poll] runs on a pooled alarm thread, so all mutable state is
 * guarded by [lock].
 */
class LogTailer(
    parentDisposable: Disposable,
    private val onChanged: () -> Unit,
) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val lock = Any()
    private var resolveFile: (() -> File?)? = null
    private var lastPath: String? = null
    private var lastLength = -1L
    private var active = false

    init {
        Disposer.register(parentDisposable, this)
    }

    /** Points the tailer at [resolveFile] (resetting the baseline when the path changes) and toggles polling. */
    fun update(resolveFile: (() -> File?)?, active: Boolean) {
        synchronized(lock) {
            this.resolveFile = resolveFile
            val current = resolveFile?.invoke()
            if (current?.path != lastPath) {
                lastPath = current?.path
                lastLength = current?.takeIf { it.isFile }?.length() ?: -1L
            }
            this.active = active
            alarm.cancelAllRequests()
            if (active && resolveFile != null) alarm.addRequest({ poll() }, POLL_MS)
        }
    }

    private fun poll() {
        val changed: Boolean
        synchronized(lock) {
            if (!active) return
            val target = resolveFile?.invoke() ?: return
            val length = if (target.isFile) target.length() else -1L
            changed = target.path != lastPath || length != lastLength
            lastPath = target.path
            lastLength = length
            alarm.addRequest({ poll() }, POLL_MS)
        }
        if (changed) ApplicationManager.getApplication().invokeLater { onChanged() }
    }

    override fun dispose() {
        synchronized(lock) {
            active = false
            alarm.cancelAllRequests()
        }
    }

    companion object {
        private const val POLL_MS = 1200
    }
}

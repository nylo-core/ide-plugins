package dev.nylo.plugin.screenshots.run

/**
 * Accumulates streamed process text and emits complete lines. A chunk from `onTextAvailable` may contain
 * a partial line, several lines, or split a `\r\n`, so we buffer until a `\n` is seen and trim a trailing
 * `\r`. Pure (no IDE deps) so the splitting logic is unit-testable.
 */
class LineBuffer(private val onLine: (String) -> Unit) {

    private val buffer = StringBuilder()

    /** Feeds a chunk of streamed text, emitting each newly-completed line. */
    fun append(text: String) {
        buffer.append(text)
        var nl = buffer.indexOf("\n")
        while (nl >= 0) {
            onLine(buffer.substring(0, nl).trimEnd('\r'))
            buffer.delete(0, nl + 1)
            nl = buffer.indexOf("\n")
        }
    }

    /** Emits any remaining partial line (call once the stream ends). */
    fun flush() {
        if (buffer.isNotEmpty()) {
            onLine(buffer.toString().trimEnd('\r'))
            buffer.setLength(0)
        }
    }
}

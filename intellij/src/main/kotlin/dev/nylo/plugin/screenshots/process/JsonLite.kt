package dev.nylo.plugin.screenshots.process

/**
 * A dependency-free reader for the flat JSON that CLI tools emit (e.g.
 * `flutter devices --machine`). Splits a JSON array into its top-level object
 * substrings and pulls simple string/bool fields — enough for our needs without
 * pulling in a JSON library or risking a classpath assumption.
 */
object JsonLite {
    /** Returns each top-level `{...}` object substring within a JSON array. */
    fun objects(arrayJson: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in arrayJson.indices) {
            val c = arrayJson[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        result.add(arrayJson.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
        return result
    }

    fun string(obj: String, key: String): String? =
        Regex(""""${Regex.escape(key)}"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(obj)?.groupValues?.get(1)?.let(::unescape)

    fun bool(obj: String, key: String): Boolean? =
        Regex(""""${Regex.escape(key)}"\s*:\s*(true|false)""").find(obj)?.groupValues?.get(1)?.toBoolean()

    /** Decodes JSON string escapes — the regex above captures them raw (`\"`, `\\`, `\uXXXX`, …). */
    private fun unescape(raw: String): String {
        if ('\\' !in raw) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i == raw.length - 1) {
                sb.append(c)
                i++
                continue
            }
            val next = raw[i + 1]
            i += 2
            when (next) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    val hex = if (i + 4 <= raw.length) raw.substring(i, i + 4) else null
                    val code = hex?.toIntOrNull(16)
                    if (code != null) {
                        sb.append(code.toChar())
                        i += 4
                    } else {
                        sb.append("\\u") // malformed escape: keep it visible rather than drop it
                    }
                }
                else -> sb.append('\\').append(next)
            }
        }
        return sb.toString()
    }
}

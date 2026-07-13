package dev.nylo.plugin.localizations.json

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonWriter
import java.io.File
import java.io.StringWriter
import java.nio.charset.StandardCharsets

/**
 * Reads Nylo's nested `lang/<code>.json` files and flattens them to the dot-notation keys the
 * framework looks up at runtime (`{"common":{"ok":"OK"}}` -> `common.ok` = `OK`), and writes a single
 * value back ([withValue]) for inline edits.
 *
 * Uses Gson (already on the plugin's classpath via [dev.nylo.plugin.logs.parse.LogParser]) so unicode
 * escapes, emojis and escaped quotes are handled correctly. Pure and IDE-independent, so it can be
 * unit-tested directly. Insertion order is preserved (Gson's `JsonObject` is order-preserving) so
 * issues and matrix rows come out in file order.
 */
object LangJson {

    // html-unsafe so `'`, `<`, `>`, `&`, `=` serialize literally, not as `\uXXXX`. This is the
    // load-bearing flag: GSON.toJson(obj, jw) resets the writer's html-safe flag from this instance,
    // overriding any jw.isHtmlSafe set at the call site.
    private val GSON = GsonBuilder().disableHtmlEscaping().create()

    /** UTF-8 BOM (U+FEFF), built from its code point to keep this source file pure ASCII. */
    private val BOM: String = 0xFEFF.toChar().toString()

    /** Reads [file] as UTF-8 and returns its flattened key->value map. Throws on invalid JSON. */
    fun readFlattened(file: File): LinkedHashMap<String, String> =
        parseFlattened(file.readText(StandardCharsets.UTF_8))

    /** Parses one lang file's [text] into a flattened key->value map. Throws on invalid JSON. */
    fun parseFlattened(text: String): LinkedHashMap<String, String> {
        val root = JsonParser.parseString(text.removePrefix(BOM))
        require(root.isJsonObject) { "Expected a top-level JSON object in the lang file" }
        val out = LinkedHashMap<String, String>()
        flatten(root.asJsonObject, "", out)
        return out
    }

    private fun flatten(obj: JsonObject, prefix: String, out: LinkedHashMap<String, String>) {
        for ((name, element) in obj.entrySet()) {
            val key = if (prefix.isEmpty()) name else "$prefix.$name"
            when {
                element.isJsonObject -> flatten(element.asJsonObject, key, out)
                element is JsonNull -> out[key] = ""              // present-but-unset -> surfaces as EMPTY
                element is JsonArray -> out[key] = element.toString()  // arrays aren't translatable; stringify
                element is JsonPrimitive -> out[key] = element.asString // string/number/bool all safe
                else -> out[key] = element.toString()
            }
        }
    }

    /**
     * Returns [text] with the dot-notation [key] set to [value], re-serialized as pretty JSON.
     * The key is written back the way [flatten] reads it: an existing binding is updated in place —
     * whether the file stores it flat (`{"login.email": …}`) or nested (`{"login":{"email": …}}`) —
     * and nested objects are only inserted for genuinely new keys. Preserves the file's existing
     * indent (detected) and a trailing newline if present, so a single edit produces a minimal diff.
     * Throws on invalid JSON, or when [key] names a nested object rather than a value.
     */
    fun withValue(text: String, key: String, value: String): String {
        val hadBom = text.startsWith(BOM)
        val body = if (hadBom) text.substring(BOM.length) else text
        val root = JsonParser.parseString(body)
        require(root.isJsonObject) { "Expected a top-level JSON object in the lang file" }
        val obj = root.asJsonObject
        if (!updateExisting(obj, key, value)) insertNew(obj, key, value)

        val sw = StringWriter()
        JsonWriter(sw).use { jw ->
            jw.isHtmlSafe = false                 // redundant guard; GSON is already html-unsafe (that config is what counts)
            jw.setIndent(detectIndent(body))
            GSON.toJson(obj, jw)
        }
        val trailing = if (body.endsWith("\n")) "\n" else ""
        return (if (hadBom) BOM else "") + sw.toString() + trailing
    }

    /**
     * Updates the flattened [key] where it already lives, mirroring how [flatten] built it: either as
     * a literal (possibly dotted) leaf property at this level, or through any existing child object
     * whose name is a dot-delimited prefix of the key (which also handles object names that contain
     * dots themselves, e.g. `{"a.b":{"c": …}}` for key `a.b.c`). Returns false when the key has no
     * existing binding.
     */
    private fun updateExisting(obj: JsonObject, key: String, value: String): Boolean {
        val leaf = obj.get(key)
        if (leaf != null && !leaf.isJsonObject) {
            obj.addProperty(key, value)
            return true
        }
        var dot = key.indexOf('.')
        while (dot >= 0) {
            val child = obj.get(key.substring(0, dot))
            if (child != null && child.isJsonObject &&
                updateExisting(child.asJsonObject, key.substring(dot + 1), value)
            ) {
                return true
            }
            dot = key.indexOf('.', dot + 1)
        }
        return false
    }

    /**
     * Inserts a genuinely new [key], nesting along the dot segments. Existing non-object values are
     * never replaced with objects: when a segment is blocked by a scalar, the remainder is written as
     * a flat dotted property at that level — [flatten] reads both spellings as the same logical key.
     */
    private fun insertNew(obj: JsonObject, key: String, value: String) {
        var current = obj
        var rest = key
        while (true) {
            val dot = rest.indexOf('.')
            if (dot < 0) break
            val seg = rest.substring(0, dot)
            val existing = current.get(seg)
            current = when {
                existing == null -> JsonObject().also { current.add(seg, it) }
                existing.isJsonObject -> existing.asJsonObject
                else -> break // a scalar sits on this segment; keep it and write the rest flat here
            }
            rest = rest.substring(dot + 1)
        }
        require(current.get(rest)?.isJsonObject != true) {
            "Key '$key' resolves to a nested object in this file; edit its child keys instead"
        }
        current.addProperty(rest, value)
    }

    /** The indent unit used by the first nested member line (e.g. `"  "`, `"    "`, `"\t"`); 2 spaces by default. */
    private fun detectIndent(text: String): String {
        for (line in text.lineSequence()) {
            val ws = line.takeWhile { it == ' ' || it == '\t' }
            if (ws.isNotEmpty() && line.length > ws.length && line[ws.length] == '"') return ws
        }
        return "  "
    }
}

package dev.nylo.plugin.logs.parse

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.nylo.plugin.logs.model.LogDocument
import dev.nylo.plugin.logs.model.LogEntry
import dev.nylo.plugin.logs.model.LogSession
import dev.nylo.plugin.logs.model.NetworkEntry
import dev.nylo.plugin.logs.model.RawLine
import dev.nylo.plugin.logs.model.StandardLine
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Parses the NDJSON on-disk format written by Nylo's `NyFileLogger` (one JSON object per line) into
 * a [LogDocument]. Pure and IDE-independent so it can be unit-tested directly.
 *
 * Record shapes (authoritative source: `support/lib/helpers/src/ny_file_logger.dart`):
 *  - `{"t":"session", id, started, platform, app, version, env}` — starts a [LogSession]; the parser
 *    reconstructs the familiar `====` banner into [LogSession.bannerLines] for display.
 *  - `{"t":"log", ts, session, level?, msg, context?, stack?}` — a [StandardLine].
 *  - `{"t":"console", ts, session, msg}` — a [StandardLine] with a null level.
 *  - `{"t":"net", kind, ts, session, requestId, method, uri, statusCode?, ...}` — a [NetworkEntry].
 *
 * Each record occupies exactly one physical line. Display text is reconstructed here into each
 * entry's [LogEntry.raw], so downstream filtering/rendering work over readable text, never JSON.
 */
object LogParser {

    private val GSON = GsonBuilder().disableHtmlEscaping().create()
    private val PRETTY = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val TS_DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val TRAILING_UTC_OFFSET = Regex("""[+-]\d{2}(:?\d{2})?$""")
    private const val BANNER_RULE_LEN = 60

    fun parse(text: CharSequence): LogDocument {
        // Normalize line endings: IntelliJ Documents reject \r\n / \r, and pasted bodies sometimes carry
        // Windows endings. Doing it here keeps the model and render consistent.
        val normalized = text.toString().replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        val n = if (lines.size > 1 && lines.last().isEmpty()) lines.size - 1 else lines.size

        val preamble = mutableListOf<LogEntry>()
        val sessions = mutableListOf<LogSession>()
        var current: SessionBuilder? = null

        for (i in 0 until n) {
            val line = lines[i]
            if (line.isBlank()) continue // separator written between sessions

            val obj = runCatching { JsonParser.parseString(line) }.getOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject
            val lineNo = i + 1

            if (obj == null) {
                (current?.entries ?: preamble).add(RawLine(line, lineNo..lineNo))
                continue
            }

            when (obj.str("t")) {
                "session" -> {
                    current?.let { sessions.add(it.build()) }
                    current = sessionBuilder(obj, lineNo)
                }
                "net" -> (current?.entries ?: preamble).add(networkEntry(obj, lineNo))
                "console" -> (current?.entries ?: preamble).add(standardLine(obj, lineNo, console = true))
                "log" -> (current?.entries ?: preamble).add(standardLine(obj, lineNo, console = false))
                else -> (current?.entries ?: preamble).add(RawLine(line, lineNo..lineNo))
            }
        }
        current?.let { sessions.add(it.build()) }
        return LogDocument(preamble, sessions)
    }

    private fun sessionBuilder(obj: JsonObject, lineNo: Int): SessionBuilder {
        val fullId = obj.str("id") ?: "-"
        val startedRaw = obj.str("started")?.let { displayTimestamp(it) } ?: "-"
        val platform = obj.str("platform") ?: "-"
        val app = obj.str("app") ?: "-"
        val version = obj.str("version") ?: "-"
        val env = obj.str("env") ?: "-"
        return SessionBuilder(
            tag = fullId.substringAfterLast('-'),
            fullId = fullId,
            started = parseTimestamp(obj.str("started")),
            startedRaw = startedRaw,
            platform = platform,
            app = app,
            version = version,
            env = env,
            bannerLines = bannerLines(fullId, startedRaw, platform, app, version, env),
            startLine = lineNo,
        )
    }

    private fun standardLine(obj: JsonObject, lineNo: Int, console: Boolean): StandardLine {
        val tsRaw = obj.str("ts") ?: obj.str("timestamp")
        val tsDisplay = tsRaw?.let { displayTimestamp(it) } ?: ""
        val session = obj.str("session")
        val level = if (console) null else obj.str("level")
        val message = (obj.str("msg") ?: obj.str("message")).orEmpty()
        val contextJson = obj.obj("context")?.let { GSON.toJson(it) }
        val stack = obj.str("stack")

        val sb = StringBuilder()
        if (tsDisplay.isNotEmpty()) sb.append('[').append(tsDisplay).append("] ")
        if (!console && session != null) sb.append("[session:").append(session).append("] ")
        if (level != null) sb.append('[').append(level).append("] ")
        sb.append(message)
        if (contextJson != null) sb.append(' ').append(contextJson)
        if (stack != null) sb.append('\n').append(stack)

        return StandardLine(
            timestamp = parseTimestamp(tsRaw),
            timestampRaw = tsDisplay,
            sessionTag = session,
            level = level,
            message = message,
            context = contextJson,
            stack = stack,
            raw = sb.toString(),
            lineRange = lineNo..lineNo,
        )
    }

    private fun networkEntry(obj: JsonObject, lineNo: Int): NetworkEntry {
        val kind = when ((obj.str("kind") ?: obj.str("type"))?.lowercase()) {
            "request" -> NetworkEntry.Kind.REQUEST
            "response" -> NetworkEntry.Kind.RESPONSE
            "error" -> NetworkEntry.Kind.ERROR
            else -> NetworkEntry.Kind.REQUEST
        }
        val requestId = obj.str("requestId")
        val method = obj.str("method")
        val uri = obj.str("uri")
        val statusCode = obj.int("statusCode")
        val statusMessage = obj.str("statusMessage")
        val responseTimeMs = obj.long("responseTimeMs")
        val payloadSize = obj.str("payloadSize")

        val summary = networkSummary(kind, method, uri, statusCode, statusMessage, responseTimeMs, payloadSize, requestId)

        // Expanded detail: the summary followed by any structured headers/body the record carried.
        val sb = StringBuilder(summary)
        obj.str("errorType")?.let { sb.append("\n  errorType: ").append(it) }
        obj.str("message")?.let { sb.append("\n  message: ").append(it) }
        appendJsonBlock(sb, "headers", obj.obj("headers"))
        appendJsonBlock(sb, "body", obj.obj("body"))
        appendJsonBlock(sb, "data", obj.obj("data"))

        return NetworkEntry(
            kind = kind,
            requestId = requestId,
            method = method,
            uri = uri,
            statusCode = statusCode,
            statusMessage = statusMessage,
            responseTimeMs = responseTimeMs,
            payloadSize = payloadSize,
            summary = summary,
            raw = sb.toString(),
            lineRange = lineNo..lineNo,
        )
    }

    private fun networkSummary(
        kind: NetworkEntry.Kind,
        method: String?,
        uri: String?,
        statusCode: Int?,
        statusMessage: String?,
        responseTimeMs: Long?,
        payloadSize: String?,
        requestId: String?,
    ): String {
        val symbol = when {
            kind == NetworkEntry.Kind.REQUEST -> "→"
            kind == NetworkEntry.Kind.ERROR -> "✗"
            statusCode != null && statusCode in 200..299 -> "✓"
            else -> "⚠"
        }
        val parts = mutableListOf<String>()
        parts += "$symbol [${method ?: "?"}] ${uri ?: "-"}"
        val status = listOfNotNull(statusCode?.toString(), statusMessage).joinToString(" ").trim()
        if (status.isNotEmpty()) parts += status
        responseTimeMs?.let { parts += "${it}ms" }
        payloadSize?.let { parts += it }
        requestId?.let { parts += "ID $it" }
        return parts.joinToString("  ·  ")
    }

    private fun appendJsonBlock(sb: StringBuilder, label: String, element: JsonElement?) {
        if (element == null) return
        if (element.isJsonObject && element.asJsonObject.size() == 0) return
        if (element.isJsonArray && element.asJsonArray.size() == 0) return
        val pretty = PRETTY.toJson(element).prependIndent("  ")
        sb.append("\n  ").append(label).append(':').append('\n').append(pretty)
    }

    private fun bannerLines(
        id: String,
        started: String,
        platform: String,
        app: String,
        version: String,
        env: String,
    ): List<String> {
        val rule = "=".repeat(BANNER_RULE_LEN)
        return listOf(
            rule,
            " SESSION  $id",
            " started   $started",
            " platform  $platform",
            " app       $app",
            " version   $version",
            " env       $env",
            rule,
        )
    }

    private fun displayTimestamp(raw: String): String =
        parseTimestamp(raw)?.format(TS_DISPLAY) ?: raw

    private fun parseTimestamp(raw: String?): LocalDateTime? {
        if (raw == null) return null
        // Accept ISO-8601 ("2026-06-26T10:32:59[.sss][Z|±HH:mm]") and the legacy
        // "yyyy-MM-dd HH:mm:ss" form. Offsets are dropped, not converted — the wall-clock time as
        // logged is what should display and sort. A plain substringBefore('+') would miss negative
        // offsets (the '-' also appears in the date), so strip a trailing zone suffix by pattern.
        val core = raw.trim().replace(' ', 'T')
        return runCatching { LocalDateTime.parse(core) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(core.removeSuffix("Z").replace(TRAILING_UTC_OFFSET, "")) }.getOrNull()
    }

    // --- gson field accessors that tolerate missing / null / wrong-typed values -------------------

    private fun JsonObject.str(key: String): String? {
        val el = get(key) ?: return null
        if (el.isJsonNull) return null
        val s = if (el.isJsonPrimitive) el.asString else el.toString()
        // A JSON-escaped \r survives the whole-file normalization above (it exists in the file only
        // as the two-char escape) and IntelliJ Documents assert on literal CRs. This accessor is the
        // one choke point every decoded string passes through, so normalize here.
        return if ('\r' in s) s.replace("\r\n", "\n").replace('\r', '\n') else s
    }

    private fun JsonObject.int(key: String): Int? = number(key)?.toInt()

    private fun JsonObject.long(key: String): Long? = number(key)?.toLong()

    private fun JsonObject.number(key: String): Double? {
        val el = get(key) ?: return null
        if (!el.isJsonPrimitive || !el.asJsonPrimitive.isNumber) return null
        return runCatching { el.asDouble }.getOrNull()
    }

    private fun JsonObject.obj(key: String): JsonElement? = get(key)?.takeIf { !it.isJsonNull }

    private class SessionBuilder(
        val tag: String,
        val fullId: String,
        val started: LocalDateTime?,
        val startedRaw: String,
        val platform: String,
        val app: String,
        val version: String,
        val env: String,
        val bannerLines: List<String>,
        val startLine: Int,
    ) {
        val entries = mutableListOf<LogEntry>()

        fun build(): LogSession {
            val end = entries.lastOrNull()?.lineRange?.last ?: startLine
            return LogSession(
                tag, fullId, started, startedRaw, platform, app, version, env,
                bannerLines, entries.toList(), startLine..end,
            )
        }
    }
}

package dev.nylo.plugin.logs.model

import java.time.LocalDateTime

/**
 * A parsed Nylo log file: any records that appear before the first `session` record
 * (the [preamble]) followed by the [sessions] discovered in the file.
 */
data class LogDocument(
    val preamble: List<LogEntry>,
    val sessions: List<LogSession>,
) {
    val isEmpty: Boolean get() = preamble.isEmpty() && sessions.isEmpty()

    companion object {
        val EMPTY = LogDocument(emptyList(), emptyList())
    }
}

/**
 * One application run, as written by `NyFileLogger`. A session is introduced by a
 * `session` record and owns every record written under it in this file.
 *
 * [bannerLines] are the human-readable header lines the parser reconstructs from the
 * `session` record (the `====` / `SESSION` / `started` / ... block) — the on-disk form
 * is JSON, but the viewer shows the familiar banner.
 */
data class LogSession(
    /** Short 6-char tag matching the inline `session` field (banner id suffix). `-` if unknown. */
    val tag: String,
    /** Full session id, e.g. `2026-06-17T13-46-18-ni2dsc`. `-` if unknown. */
    val fullId: String,
    /** Parsed `started` timestamp — the sort key. Null when unparseable. */
    val started: LocalDateTime?,
    val startedRaw: String,
    val platform: String,
    val app: String,
    val version: String,
    val env: String,
    /** Reconstructed display header lines for this session (see class docs). */
    val bannerLines: List<String>,
    val entries: List<LogEntry>,
    /** 1-based, inclusive line span in the source file (session record .. last entry). */
    val lineRange: IntRange,
)

/** A single renderable unit within a file or session. */
sealed interface LogEntry {
    /** 1-based, inclusive line span in the source file. */
    val lineRange: IntRange

    /** Human-readable display text reconstructed from the JSON record (multi-line for [NetworkEntry]). */
    val raw: String
}

/**
 * A `log` or `console` record rendered as a `[ts] [session:tag] [level] message` line.
 * `console` records carry a null [level]; [context] and [stack] are present only when the
 * original entry had them.
 */
data class StandardLine(
    val timestamp: LocalDateTime?,
    val timestampRaw: String,
    val sessionTag: String?,
    val level: String?,
    val message: String,
    /** Encoded `context` object, when present (already a JSON string). */
    val context: String?,
    /** Stack-trace text, when present (multi-line). */
    val stack: String?,
    override val raw: String,
    override val lineRange: IntRange,
) : LogEntry

/**
 * A `net` record: a single Dio request / response / error with its structured fields.
 * [summary] is the one-line collapsed placeholder; [raw] is the full expanded text
 * (summary plus pretty-printed headers/body).
 */
data class NetworkEntry(
    val kind: Kind,
    val requestId: String?,
    val method: String?,
    val uri: String?,
    val statusCode: Int?,
    val statusMessage: String?,
    val responseTimeMs: Long?,
    val payloadSize: String?,
    /** One-line summary used as the collapsed fold placeholder. */
    val summary: String,
    override val raw: String,
    override val lineRange: IntRange,
) : LogEntry {
    enum class Kind { REQUEST, RESPONSE, ERROR }
}

/** Any line that isn't a recognized record (blank separators, malformed JSON). */
data class RawLine(
    override val raw: String,
    override val lineRange: IntRange,
) : LogEntry

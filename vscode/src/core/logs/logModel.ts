/**
 * Port of `dev.nylo.plugin.logs.model.LogModel`.
 *
 * Timestamps are stored as epoch-millis (UTC interpretation) for ordering, alongside the raw
 * display string. Line ranges are 1-based inclusive [lineStart, lineEnd].
 */

export type NetworkKind = 'request' | 'response' | 'error';

/** A `log` or `console` record rendered as `[ts] [session:tag] [level] message`. */
export interface StandardLine {
  entryType: 'standard';
  timestamp: number | null;
  timestampRaw: string;
  sessionTag: string | null;
  level: string | null;
  message: string;
  /** Encoded `context` object as a JSON string, when present. */
  context: string | null;
  /** Stack-trace text (multi-line), when present. */
  stack: string | null;
  raw: string;
  lineStart: number;
  lineEnd: number;
}

/** A `net` record: a single Dio request / response / error with its structured fields. */
export interface NetworkEntry {
  entryType: 'network';
  netKind: NetworkKind;
  requestId: string | null;
  method: string | null;
  uri: string | null;
  statusCode: number | null;
  statusMessage: string | null;
  responseTimeMs: number | null;
  payloadSize: string | null;
  /** One-line summary used as the collapsed placeholder. */
  summary: string;
  /** Full expanded text (summary plus pretty-printed headers/body). */
  raw: string;
  lineStart: number;
  lineEnd: number;
}

/** Any line that isn't a recognized record (blank separators, malformed JSON). */
export interface RawLine {
  entryType: 'raw';
  raw: string;
  lineStart: number;
  lineEnd: number;
}

export type LogEntry = StandardLine | NetworkEntry | RawLine;

/** One application run, as written by `NyFileLogger`. */
export interface LogSession {
  /** Short tag matching the inline `session` field (banner id suffix). `-` if unknown. */
  tag: string;
  /** Full session id, e.g. `2026-06-17T13-46-18-ni2dsc`. `-` if unknown. */
  fullId: string;
  /** Parsed `started` timestamp (epoch ms) — the sort key. Null when unparseable. */
  started: number | null;
  startedRaw: string;
  platform: string;
  app: string;
  version: string;
  env: string;
  /** Reconstructed display header lines for this session. */
  bannerLines: string[];
  entries: LogEntry[];
  lineStart: number;
  lineEnd: number;
}

/** A parsed Nylo log file: [preamble] (records before the first session) plus [sessions]. */
export interface LogDocument {
  preamble: LogEntry[];
  sessions: LogSession[];
}

export const EMPTY_DOCUMENT: LogDocument = { preamble: [], sessions: [] };

export function isDocumentEmpty(doc: LogDocument): boolean {
  return doc.preamble.length === 0 && doc.sessions.length === 0;
}

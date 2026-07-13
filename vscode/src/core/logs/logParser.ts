import {
  LogDocument,
  LogEntry,
  LogSession,
  NetworkEntry,
  NetworkKind,
  RawLine,
  StandardLine,
} from './logModel';
import { displayTimestamp, parseTimestampMs } from './timestamps';

/**
 * Port of `dev.nylo.plugin.logs.parse.LogParser`.
 *
 * Parses the NDJSON written by Nylo's `NyFileLogger` (one JSON object per line) into a
 * {@link LogDocument}. Pure and IDE-independent. Display text is reconstructed into each entry's
 * `raw`, so downstream filtering/rendering work over readable text, never JSON.
 */

const BANNER_RULE_LEN = 60;

export function parseLog(text: string): LogDocument {
  // Normalize line endings so the model/render stay consistent with pasted Windows content.
  const normalized = text.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  const lines = normalized.split('\n');
  const n = lines.length > 1 && lines[lines.length - 1] === '' ? lines.length - 1 : lines.length;

  const preamble: LogEntry[] = [];
  const sessions: LogSession[] = [];
  let current: SessionBuilder | null = null;

  for (let i = 0; i < n; i++) {
    const line = lines[i];
    if (line.trim().length === 0) {
      continue; // separator written between sessions
    }

    const obj = parseObject(line);
    const lineNo = i + 1;
    const sink = current ? current.entries : preamble;

    if (obj == null) {
      sink.push(rawLine(line, lineNo));
      continue;
    }

    switch (str(obj, 't')) {
      case 'session':
        if (current) {
          sessions.push(current.build());
        }
        current = new SessionBuilder(obj, lineNo);
        break;
      case 'net':
        (current ? current.entries : preamble).push(networkEntry(obj, lineNo));
        break;
      case 'console':
        (current ? current.entries : preamble).push(standardLine(obj, lineNo, true));
        break;
      case 'log':
        (current ? current.entries : preamble).push(standardLine(obj, lineNo, false));
        break;
      default:
        (current ? current.entries : preamble).push(rawLine(line, lineNo));
        break;
    }
  }
  if (current) {
    sessions.push(current.build());
  }
  return { preamble, sessions };
}

function rawLine(line: string, lineNo: number): RawLine {
  return { entryType: 'raw', raw: line, lineStart: lineNo, lineEnd: lineNo };
}

function standardLine(obj: JsonObject, lineNo: number, console: boolean): StandardLine {
  const tsRaw = str(obj, 'ts') ?? str(obj, 'timestamp');
  const tsDisplay = tsRaw != null ? displayTimestamp(tsRaw) : '';
  const session = str(obj, 'session');
  const level = console ? null : str(obj, 'level');
  const message = str(obj, 'msg') ?? str(obj, 'message') ?? '';
  const contextJson = obj['context'] != null ? JSON.stringify(obj['context']) : null;
  const stack = str(obj, 'stack');

  let sb = '';
  if (tsDisplay.length > 0) {
    sb += `[${tsDisplay}] `;
  }
  if (!console && session != null) {
    sb += `[session:${session}] `;
  }
  if (level != null) {
    sb += `[${level}] `;
  }
  sb += message;
  if (contextJson != null) {
    sb += ` ${contextJson}`;
  }
  if (stack != null) {
    sb += `\n${stack}`;
  }

  return {
    entryType: 'standard',
    timestamp: parseTimestampMs(tsRaw),
    timestampRaw: tsDisplay,
    sessionTag: session,
    level,
    message,
    context: contextJson,
    stack,
    raw: sb,
    lineStart: lineNo,
    lineEnd: lineNo,
  };
}

function networkEntry(obj: JsonObject, lineNo: number): NetworkEntry {
  const kindRaw = (str(obj, 'kind') ?? str(obj, 'type'))?.toLowerCase();
  const netKind: NetworkKind = kindRaw === 'response' ? 'response' : kindRaw === 'error' ? 'error' : 'request';

  const requestId = str(obj, 'requestId');
  const method = str(obj, 'method');
  const uri = str(obj, 'uri');
  const statusCode = int(obj, 'statusCode');
  const statusMessage = str(obj, 'statusMessage');
  const responseTimeMs = int(obj, 'responseTimeMs');
  const payloadSize = str(obj, 'payloadSize');

  const summary = networkSummary(netKind, method, uri, statusCode, statusMessage, responseTimeMs, payloadSize, requestId);

  let sb = summary;
  const errorType = str(obj, 'errorType');
  if (errorType != null) {
    sb += `\n  errorType: ${errorType}`;
  }
  const message = str(obj, 'message');
  if (message != null) {
    sb += `\n  message: ${message}`;
  }
  sb += jsonBlock('headers', obj['headers']);
  sb += jsonBlock('body', obj['body']);
  sb += jsonBlock('data', obj['data']);

  return {
    entryType: 'network',
    netKind,
    requestId,
    method,
    uri,
    statusCode,
    statusMessage,
    responseTimeMs,
    payloadSize,
    summary,
    raw: sb,
    lineStart: lineNo,
    lineEnd: lineNo,
  };
}

function networkSummary(
  kind: NetworkKind,
  method: string | null,
  uri: string | null,
  statusCode: number | null,
  statusMessage: string | null,
  responseTimeMs: number | null,
  payloadSize: string | null,
  requestId: string | null,
): string {
  let symbol: string;
  if (kind === 'request') {
    symbol = '→';
  } else if (kind === 'error') {
    symbol = '✗';
  } else if (statusCode != null && statusCode >= 200 && statusCode <= 299) {
    symbol = '✓';
  } else {
    symbol = '⚠';
  }
  const parts: string[] = [`${symbol} [${method ?? '?'}] ${uri ?? '-'}`];
  const status = [statusCode != null ? String(statusCode) : null, statusMessage].filter((x) => x != null).join(' ').trim();
  if (status.length > 0) {
    parts.push(status);
  }
  if (responseTimeMs != null) {
    parts.push(`${responseTimeMs}ms`);
  }
  if (payloadSize != null) {
    parts.push(payloadSize);
  }
  if (requestId != null) {
    parts.push(`ID ${requestId}`);
  }
  return parts.join('  ·  ');
}

function jsonBlock(label: string, value: unknown): string {
  if (value === undefined || value === null) {
    return '';
  }
  if (isPlainObject(value) && Object.keys(value).length === 0) {
    return '';
  }
  if (Array.isArray(value) && value.length === 0) {
    return '';
  }
  const pretty = JSON.stringify(value, null, 2)
    .split('\n')
    .map((l) => `  ${l}`)
    .join('\n');
  return `\n  ${label}:\n${pretty}`;
}

function bannerLines(id: string, started: string, platform: string, app: string, version: string, env: string): string[] {
  const rule = '='.repeat(BANNER_RULE_LEN);
  return [
    rule,
    ` SESSION  ${id}`,
    ` started   ${started}`,
    ` platform  ${platform}`,
    ` app       ${app}`,
    ` version   ${version}`,
    ` env       ${env}`,
    rule,
  ];
}

class SessionBuilder {
  readonly tag: string;
  readonly fullId: string;
  readonly started: number | null;
  readonly startedRaw: string;
  readonly platform: string;
  readonly app: string;
  readonly version: string;
  readonly env: string;
  readonly bannerLines: string[];
  readonly startLine: number;
  readonly entries: LogEntry[] = [];

  constructor(obj: JsonObject, lineNo: number) {
    this.fullId = str(obj, 'id') ?? '-';
    const startedRaw = str(obj, 'started');
    this.startedRaw = startedRaw != null ? displayTimestamp(startedRaw) : '-';
    this.platform = str(obj, 'platform') ?? '-';
    this.app = str(obj, 'app') ?? '-';
    this.version = str(obj, 'version') ?? '-';
    this.env = str(obj, 'env') ?? '-';
    this.tag = substringAfterLast(this.fullId, '-');
    this.started = parseTimestampMs(startedRaw);
    this.bannerLines = bannerLines(this.fullId, this.startedRaw, this.platform, this.app, this.version, this.env);
    this.startLine = lineNo;
  }

  build(): LogSession {
    const last = this.entries[this.entries.length - 1];
    const end = last ? last.lineEnd : this.startLine;
    return {
      tag: this.tag,
      fullId: this.fullId,
      started: this.started,
      startedRaw: this.startedRaw,
      platform: this.platform,
      app: this.app,
      version: this.version,
      env: this.env,
      bannerLines: this.bannerLines,
      entries: this.entries.slice(),
      lineStart: this.startLine,
      lineEnd: end,
    };
  }
}

// --- JSON helpers tolerant of missing / null / wrong-typed values -------------------------------

type JsonObject = Record<string, unknown>;

function parseObject(line: string): JsonObject | null {
  try {
    const parsed: unknown = JSON.parse(line);
    return isPlainObject(parsed) ? (parsed as JsonObject) : null;
  } catch {
    return null;
  }
}

function isPlainObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function str(obj: JsonObject, key: string): string | null {
  const el = obj[key];
  if (el === undefined || el === null) {
    return null;
  }
  let s: string;
  if (typeof el === 'string') {
    s = el;
  } else if (typeof el === 'number' || typeof el === 'boolean') {
    s = String(el);
  } else {
    s = JSON.stringify(el);
  }
  // A JSON-escaped \r survives the whole-file normalization above (in the file it's only the
  // two-char escape). This accessor is the one choke point every decoded string passes through,
  // so normalize here — `raw` must never carry CRs.
  return s.includes('\r') ? s.replace(/\r\n/g, '\n').replace(/\r/g, '\n') : s;
}

function int(obj: JsonObject, key: string): number | null {
  const el = obj[key];
  if (typeof el !== 'number' || !Number.isFinite(el)) {
    return null;
  }
  return Math.trunc(el);
}

function substringAfterLast(value: string, delimiter: string): string {
  const idx = value.lastIndexOf(delimiter);
  return idx < 0 ? value : value.slice(idx + delimiter.length);
}

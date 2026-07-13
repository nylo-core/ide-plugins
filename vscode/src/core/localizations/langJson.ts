/**
 * Port of `dev.nylo.plugin.localizations.json.LangJson`.
 *
 * Reads Nylo's nested `lang/<code>.json` files and flattens them to dot-notation keys, and writes a
 * single value back ({@link withValue}) for inline edits — preserving key order, the detected indent,
 * a leading BOM and a trailing newline so a single edit yields a minimal diff.
 *
 * `JSON.parse`/`JSON.stringify` match Gson's `isHtmlSafe = false` behaviour for our cases: `<`, `>`,
 * `&`, `=`, `'`, `{{...}}` placeholders and emoji are written verbatim (only `"`, `\` and control
 * chars are escaped).
 */

const BOM = '﻿';

/** Parses one lang file's [text] into a flattened key→value map (insertion-ordered). Throws on invalid JSON. */
export function parseFlattened(text: string): Map<string, string> {
  const body = text.startsWith(BOM) ? text.slice(BOM.length) : text;
  const root = JSON.parse(body);
  if (!isPlainObject(root)) {
    throw new Error('Expected a top-level JSON object in the lang file');
  }
  const out = new Map<string, string>();
  flatten(root, '', out);
  return out;
}

function flatten(obj: Record<string, unknown>, prefix: string, out: Map<string, string>): void {
  for (const [name, element] of Object.entries(obj)) {
    const key = prefix.length === 0 ? name : `${prefix}.${name}`;
    if (element === null) {
      out.set(key, ''); // present-but-unset -> surfaces as EMPTY
    } else if (Array.isArray(element)) {
      out.set(key, JSON.stringify(element)); // arrays aren't translatable; stringify
    } else if (typeof element === 'object') {
      flatten(element as Record<string, unknown>, key, out);
    } else if (typeof element === 'string') {
      out.set(key, element);
    } else {
      out.set(key, String(element)); // number / boolean
    }
  }
}

/**
 * Returns [text] with the dot-notation [key] set to [value], re-serialised as pretty JSON. The key is
 * written back the way {@link flatten} reads it: an existing binding is updated in place — whether the
 * file stores it flat (`{"login.email": …}`) or nested (`{"login":{"email": …}}`) — and nested objects
 * are only inserted for genuinely new keys. Preserves the file's existing indent, leading BOM and a
 * trailing newline. Throws on invalid JSON, or when [key] names a nested object rather than a value.
 */
export function withValue(text: string, key: string, value: string): string {
  const hadBom = text.startsWith(BOM);
  const body = hadBom ? text.slice(BOM.length) : text;
  const root = JSON.parse(body);
  if (!isPlainObject(root)) {
    throw new Error('Expected a top-level JSON object in the lang file');
  }
  if (!updateExisting(root, key, value)) {
    insertNew(root, key, value);
  }

  const json = JSON.stringify(root, null, detectIndent(body));
  const trailing = body.endsWith('\n') ? '\n' : '';
  return (hadBom ? BOM : '') + json + trailing;
}

/**
 * Updates the flattened [key] where it already lives, mirroring how {@link flatten} built it: either as
 * a literal (possibly dotted) leaf property at this level, or through any existing child object whose
 * name is a dot-delimited prefix of the key (which also handles object names that contain dots, e.g.
 * `{"a.b":{"c": …}}` for key `a.b.c`). Returns false when the key has no existing binding.
 */
function updateExisting(obj: Record<string, unknown>, key: string, value: string): boolean {
  const leaf = ownValue(obj, key);
  if (leaf !== undefined && !isPlainObject(leaf)) {
    obj[key] = value;
    return true;
  }
  let dot = key.indexOf('.');
  while (dot >= 0) {
    const child = ownValue(obj, key.slice(0, dot));
    if (isPlainObject(child) && updateExisting(child, key.slice(dot + 1), value)) {
      return true;
    }
    dot = key.indexOf('.', dot + 1);
  }
  return false;
}

/**
 * Inserts a genuinely new [key], nesting along the dot segments. Existing non-object values are never
 * replaced with objects: when a segment is blocked by a scalar, the remainder is written as a flat
 * dotted property at that level — {@link flatten} reads both spellings as the same logical key. Throws
 * when [key] resolves to an existing nested object.
 */
function insertNew(obj: Record<string, unknown>, key: string, value: string): void {
  let current = obj;
  let rest = key;
  for (;;) {
    const dot = rest.indexOf('.');
    if (dot < 0) {
      break;
    }
    const seg = rest.slice(0, dot);
    const existing = ownValue(current, seg);
    if (existing === undefined) {
      const created: Record<string, unknown> = {};
      current[seg] = created;
      current = created;
    } else if (isPlainObject(existing)) {
      current = existing;
    } else {
      break; // a scalar sits on this segment; keep it and write the rest flat here
    }
    rest = rest.slice(dot + 1);
  }
  if (isPlainObject(ownValue(current, rest))) {
    throw new Error(`Key '${key}' resolves to a nested object in this file; edit its child keys instead`);
  }
  current[rest] = value;
}

/** Returns [obj]'s own property [key] (never a prototype member, matching Gson's `JsonObject.get`), else undefined. */
function ownValue(obj: Record<string, unknown>, key: string): unknown {
  return Object.hasOwn(obj, key) ? obj[key] : undefined;
}

/** The indent unit of the first nested member line (`"  "`, `"    "`, `"\t"`); 2 spaces by default. */
function detectIndent(text: string): string {
  for (const line of text.split('\n')) {
    let i = 0;
    while (i < line.length && (line[i] === ' ' || line[i] === '\t')) {
      i++;
    }
    if (i > 0 && line.length > i && line[i] === '"') {
      return line.slice(0, i);
    }
  }
  return '  ';
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * Timestamp parsing shared by the log parser. Accepts ISO-8601 (`2026-06-26T10:32:59[.sss][Z|±hh:mm]`)
 * and the legacy `yyyy-MM-dd HH:mm:ss` form. Trailing zone/offset is ignored (the prefix is matched).
 * Fractional seconds are kept at full precision (the framework logs microseconds) so the session
 * sort key matches the Kotlin parser; the display form drops them.
 */

interface DateParts {
  y: number;
  mo: number;
  d: number;
  h: number;
  mi: number;
  s: number;
  /** Fractional milliseconds — may be non-integer (e.g. `.223296` → 223.296). */
  fracMs: number;
}

const TS_PREFIX = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?/;

function parseParts(raw: string | null | undefined): DateParts | null {
  if (raw == null) {
    return null;
  }
  const core = raw.trim().replace(' ', 'T');
  const m = TS_PREFIX.exec(core);
  if (!m) {
    return null;
  }
  return { y: +m[1], mo: +m[2], d: +m[3], h: +m[4], mi: +m[5], s: +m[6], fracMs: fracToMs(m[7]) };
}

/** Epoch millis (UTC interpretation, sub-ms fraction preserved) for ordering, or null when unparseable. */
export function parseTimestampMs(raw: string | null | undefined): number | null {
  const p = parseParts(raw);
  return p ? Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi, p.s) + p.fracMs : null;
}

/**
 * Seconds-fraction digits → milliseconds. The whole-millisecond part stays an exact integer
 * (`.100` → 100, never a float artifact); only digits beyond ms contribute a fraction
 * (`.223296` → 223.296).
 */
function fracToMs(digits: string | undefined): number {
  if (!digits) {
    return 0;
  }
  const wholeMs = Number(`${digits}000`.slice(0, 3));
  const subMs = digits.slice(3);
  return subMs ? wholeMs + Number(`0.${subMs}`) : wholeMs;
}

/** `yyyy-MM-dd HH:mm:ss` display form; falls back to the raw string when unparseable. */
export function displayTimestamp(raw: string | null | undefined): string {
  const p = parseParts(raw);
  if (!p) {
    return raw ?? '';
  }
  const z = (n: number) => String(n).padStart(2, '0');
  return `${p.y}-${z(p.mo)}-${z(p.d)} ${z(p.h)}:${z(p.mi)}:${z(p.s)}`;
}

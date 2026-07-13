import * as path from 'path';

/**
 * Pure helpers for the screenshot driver's stdout/stderr protocol, extracted from the host
 * orchestrator so they can be unit-tested. Ports the `kv()` parser and target-path builder from
 * `dev.nylo.plugin.screenshots.run.ScreenshotOrchestrator`.
 */

/**
 * Reads `key=value` from a marker line, matching the framework driver's format. Mirrors the Kotlin
 * `kv()` regex (`\bkey=(\S+)`): value runs to the next whitespace, so it never spans keys.
 */
export function markerValue(line: string, key: string): string | null {
  const match = new RegExp(`\\b${escapeRegExp(key)}=(\\S+)`).exec(line);
  return match ? match[1] : null;
}

/**
 * Resolves the numbered-shot index. The driver usually supplies `index=`; when it's absent or not a
 * number we fall back to a caller-supplied running counter (Kotlin: `?.toIntOrNull() ?: captured`).
 */
export function resolveShotIndex(rawIndex: string | null, fallback: number): number {
  const parsed = parseInt(rawIndex ?? '', 10);
  return Number.isNaN(parsed) ? fallback : parsed;
}

/**
 * Builds the capture target path `<outputBase>/<deviceSlug>/<locale>/NN-slug.png`, matching Kotlin's
 * `%02d-%s.png` layout (two-digit zero-padded index, ASCII digits).
 */
export function captureTargetPath(
  outputBase: string,
  deviceSlug: string,
  locale: string,
  slug: string,
  index: number,
): string {
  const name = `${String(index).padStart(2, '0')}-${slug}.png`;
  return path.join(outputBase, deviceSlug, locale, name);
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

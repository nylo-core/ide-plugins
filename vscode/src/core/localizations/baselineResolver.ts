/**
 * Port of `dev.nylo.plugin.localizations.scan.BaselineResolver`.
 *
 * Preference order: a persisted [override], then `DEFAULT_LOCALE` from the project's `.env`, then `en`,
 * then the first locale. Null only when there are no locale files.
 */
export function resolveBaseline(
  localeCodes: string[],
  override: string | null | undefined,
  envDefaultLocale: string | null | undefined,
): string | null {
  if (localeCodes.length === 0) {
    return null;
  }
  if (override && localeCodes.includes(override)) {
    return override;
  }
  if (envDefaultLocale && localeCodes.includes(envDefaultLocale)) {
    return envDefaultLocale;
  }
  if (localeCodes.includes('en')) {
    return 'en';
  }
  return localeCodes[0];
}

const DEFAULT_LOCALE_LINE = /^\s*DEFAULT_LOCALE\s*=\s*["']?([A-Za-z0-9_\-]+)["']?\s*$/m;

/** Extracts `DEFAULT_LOCALE` from a `.env` file's contents (e.g. `DEFAULT_LOCALE="en"`). Null if absent. */
export function readEnvDefaultLocale(envText: string): string | null {
  const value = DEFAULT_LOCALE_LINE.exec(envText)?.[1];
  return value && value.trim().length > 0 ? value : null;
}

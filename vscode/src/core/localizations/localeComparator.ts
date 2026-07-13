import { KeyStatus, LocaleIssue, LocaleSummary, LocalizationReport, makeSummary } from './model';

/**
 * Port of `dev.nylo.plugin.localizations.compare.LocaleComparator`.
 *
 * Mirrors Nylo's `locale:check-missing-keys`: MISSING = baseline key absent, EMPTY = present but
 * blank, EXTRA = key here but not in baseline. [flagSameAsBase] additionally flags values identical to
 * a non-blank baseline value (off by default — brand names/tokens are false positives).
 */
export function compareLocales(
  baseline: string,
  localeValues: Map<string, Map<string, string>>,
  parseErrors: Map<string, string> = new Map(),
  flagSameAsBase = false,
): LocalizationReport {
  // Without a readable baseline there is nothing to compare against. Falling back to an empty map
  // would misclassify every key of every locale as EXTRA, flooding the report with bogus issues.
  // Emit summaries (the baseline row carries its parse error) but no issues at all.
  const baselineReadable = localeValues.has(baseline) && !parseErrors.has(baseline);
  const baseMap = baselineReadable ? localeValues.get(baseline) ?? new Map<string, string>() : new Map<string, string>();
  const baselineKeyCount = baseMap.size;

  const summaries: LocaleSummary[] = [];
  const issues: LocaleIssue[] = [];

  summaries.push(
    makeSummary({
      locale: baseline,
      isBaseline: true,
      baselineKeyCount,
      translated: countNonBlank(baseMap),
      missing: 0,
      empty: 0,
      extra: 0,
      sameAsBase: 0,
      parseError: parseErrors.get(baseline) ?? null,
    }),
  );

  const otherLocales = [...new Set([...localeValues.keys(), ...parseErrors.keys()])]
    .filter((locale) => locale !== baseline)
    .sort(compareStrings);

  for (const locale of otherLocales) {
    const parseError = parseErrors.get(locale);
    if (parseError != null) {
      summaries.push(
        makeSummary({
          locale,
          isBaseline: false,
          baselineKeyCount,
          translated: 0,
          missing: 0,
          empty: 0,
          extra: 0,
          sameAsBase: 0,
          parseError,
        }),
      );
      continue;
    }

    const map = localeValues.get(locale) ?? new Map<string, string>();

    if (!baselineReadable) {
      summaries.push(
        makeSummary({
          locale,
          isBaseline: false,
          baselineKeyCount: 0,
          translated: countNonBlank(map),
          missing: 0,
          empty: 0,
          extra: 0,
          sameAsBase: 0,
          parseError: null,
        }),
      );
      continue;
    }

    let translated = 0;
    let missing = 0;
    let empty = 0;
    let sameAsBase = 0;

    // Walk baseline keys in baseline (insertion) order so issues come out file-ordered.
    for (const [key, baseValue] of baseMap) {
      const current = map.get(key);
      if (current === undefined) {
        missing++;
        issues.push(issue(locale, key, 'missing', baseValue, null));
      } else if (isBlank(current)) {
        empty++;
        issues.push(issue(locale, key, 'empty', baseValue, current));
      } else if (flagSameAsBase && !isBlank(baseValue) && current === baseValue) {
        translated++;
        sameAsBase++;
        issues.push(issue(locale, key, 'same_as_base', baseValue, current));
      } else {
        translated++;
      }
    }

    let extra = 0;
    for (const [key, current] of map) {
      if (!baseMap.has(key)) {
        extra++;
        issues.push(issue(locale, key, 'extra', null, current));
      }
    }

    summaries.push(
      makeSummary({
        locale,
        isBaseline: false,
        baselineKeyCount,
        translated,
        missing,
        empty,
        extra,
        sameAsBase,
        parseError: null,
      }),
    );
  }

  return { baseline, summaries, issues };
}

function issue(
  locale: string,
  key: string,
  status: KeyStatus,
  baseValue: string | null,
  currentValue: string | null,
): LocaleIssue {
  return { locale, key, status, baseValue, currentValue };
}

function countNonBlank(map: Map<string, string>): number {
  let count = 0;
  for (const value of map.values()) {
    if (!isBlank(value)) {
      count++;
    }
  }
  return count;
}

function isBlank(value: string): boolean {
  return value.trim().length === 0;
}

function compareStrings(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

/** Port of the `dev.nylo.plugin.localizations.model` types. */

export type KeyStatus = 'translated' | 'missing' | 'empty' | 'same_as_base' | 'extra';

/** One actionable problem: [key] in [locale] is [status] relative to the baseline. */
export interface LocaleIssue {
  locale: string;
  key: string;
  status: KeyStatus;
  /** Baseline value; null only for `extra` (the key isn't in the baseline). */
  baseValue: string | null;
  /** The locale's own value; null for `missing`. */
  currentValue: string | null;
}

/** Per-locale health roll-up shown in the Summary view. */
export interface LocaleSummary {
  locale: string;
  isBaseline: boolean;
  baselineKeyCount: number;
  translated: number;
  missing: number;
  empty: number;
  extra: number;
  sameAsBase: number;
  parseError: string | null;
  /** Baseline keys present and non-blank, as a percentage. 100 when the baseline has no keys. */
  percentComplete: number;
  /** Problems counting against sync health (same-as-base excluded). */
  issueCount: number;
}

export interface LocalizationReport {
  baseline: string;
  summaries: LocaleSummary[];
  issues: LocaleIssue[];
}

export const EMPTY_REPORT: LocalizationReport = { baseline: '', summaries: [], issues: [] };

export function makeSummary(
  fields: Omit<LocaleSummary, 'percentComplete' | 'issueCount'>,
): LocaleSummary {
  const percentComplete =
    fields.baselineKeyCount === 0 ? 100 : Math.floor((fields.translated * 100) / fields.baselineKeyCount);
  return { ...fields, percentComplete, issueCount: fields.missing + fields.empty + fields.extra };
}

import { LogEntry } from './logModel';

/** Port of `dev.nylo.plugin.logs.model.LogCategory` + `LogCategorizer`. */

export type LogCategory = 'all' | 'console' | 'networking' | 'errors';

/** Single source of truth for what counts as an "error" in the Errors category filter. */
export const ERROR_LEVELS = new Set([
  'error',
  'err',
  'warn',
  'warning',
  'severe',
  'fatal',
  'alert',
  'emergency',
]);

export function isNetwork(entry: LogEntry): boolean {
  return entry.entryType === 'network';
}

export function isError(entry: LogEntry): boolean {
  if (entry.entryType === 'network') {
    return entry.netKind === 'error';
  }
  if (entry.entryType === 'standard') {
    return entry.level != null && ERROR_LEVELS.has(entry.level.toLowerCase());
  }
  return false;
}

export function matchesCategory(entry: LogEntry, category: LogCategory): boolean {
  switch (category) {
    case 'all':
      return true;
    case 'networking':
      return isNetwork(entry);
    case 'errors':
      return isError(entry);
    case 'console':
      return !isNetwork(entry);
  }
}

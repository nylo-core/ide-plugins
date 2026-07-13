import * as fs from 'fs';
import * as path from 'path';

/** Port of `dev.nylo.plugin.logs.parse.LogFileScanner`. Dates are `yyyy-MM-dd` strings. */

export const LOGS_DIR = 'logs';
const FILENAME = /^(\d{4}-\d{2}-\d{2})\.log$/;

export interface LogFileRef {
  filePath: string;
  /** `yyyy-MM-dd`. */
  date: string;
}

/** Returns the `.log` files in [dir], newest date first. Non-matching/invalid names are ignored. */
export function scanLogDir(dir: string): LogFileRef[] {
  let names: string[];
  try {
    if (!fs.statSync(dir).isDirectory()) {
      return [];
    }
    names = fs.readdirSync(dir);
  } catch {
    return [];
  }

  const refs: LogFileRef[] = [];
  for (const name of names) {
    const full = path.join(dir, name);
    try {
      if (!fs.statSync(full).isFile()) {
        continue;
      }
    } catch {
      continue;
    }
    const match = FILENAME.exec(name);
    if (!match || !isValidDate(match[1])) {
      continue;
    }
    refs.push({ filePath: full, date: match[1] });
  }
  // `yyyy-MM-dd` sorts lexicographically; newest first.
  refs.sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : 0));
  return refs;
}

/** Default selection: [today] when present, otherwise the newest available date; null when empty. */
export function defaultLogDate(dates: string[], today: string): string | null {
  if (dates.length === 0) {
    return null;
  }
  if (dates.includes(today)) {
    return today;
  }
  return dates.reduce((max, d) => (d > max ? d : max));
}

function isValidDate(value: string): boolean {
  const [y, mo, d] = value.split('-').map(Number);
  const dt = new Date(Date.UTC(y, mo - 1, d));
  return dt.getUTCFullYear() === y && dt.getUTCMonth() === mo - 1 && dt.getUTCDate() === d;
}

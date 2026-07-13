import * as fs from 'fs';
import * as path from 'path';

/** Port of `dev.nylo.plugin.localizations.scan.LangFileScanner`. */

export const LANG_DIR = 'lang';

export interface LangFile {
  /** Locale code, e.g. `en`. */
  code: string;
  filePath: string;
}

/** Discovers `lang/<code>.json` files under [projectDir], sorted by code. */
export function scanLangFiles(projectDir: string): LangFile[] {
  const langDir = path.join(projectDir, LANG_DIR);
  let names: string[];
  try {
    if (!fs.statSync(langDir).isDirectory()) {
      return [];
    }
    names = fs.readdirSync(langDir);
  } catch {
    return [];
  }

  const files: LangFile[] = [];
  for (const name of names) {
    if (!name.endsWith('.json')) {
      continue;
    }
    const full = path.join(langDir, name);
    try {
      if (!fs.statSync(full).isFile()) {
        continue;
      }
    } catch {
      continue;
    }
    files.push({ code: name.slice(0, -'.json'.length), filePath: full });
  }
  files.sort((a, b) => (a.code < b.code ? -1 : a.code > b.code ? 1 : 0));
  return files;
}

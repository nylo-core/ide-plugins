import * as fs from 'fs';
import * as path from 'path';

/**
 * Port of `dev.nylo.plugin.localizations.cli.NyloCliLocator` + `FindUntranslatedRunner.parseFindings`
 * + `Finding`.
 */

export interface Finding {
  /** Project-relative source file. */
  file: string;
  /** 1-based line. */
  line: number;
  value: string;
  context: string;
}

const INSTALLER_DEP = /^\s*nylo_installer\s*:/m;

/**
 * Prefers `dart run nylo_installer:nylo` when the project depends on `nylo_installer`; otherwise
 * assumes a globally-activated `nylo` on the PATH.
 */
export function resolveNyloCli(projectDir: string): string[] {
  return referencesInstaller(projectDir) ? ['dart', 'run', 'nylo_installer:nylo'] : ['nylo'];
}

function referencesInstaller(projectDir: string): boolean {
  if (fileMatches(path.join(projectDir, 'pubspec.yaml'), (text) => INSTALLER_DEP.test(text))) {
    return true;
  }
  return fileMatches(path.join(projectDir, 'pubspec.lock'), (text) => text.includes('nylo_installer'));
}

function fileMatches(file: string, predicate: (text: string) => boolean): boolean {
  try {
    return fs.statSync(file).isFile() && predicate(fs.readFileSync(file, 'utf8'));
  } catch {
    return false;
  }
}

/**
 * Parses the `{ "findings": [ { file, line, value, context } ] }` JSON. Tolerant of missing fields, but
 * throws on malformed JSON so the caller reports a scan failure rather than silently rendering "no
 * findings" (mirrors Kotlin `FindUntranslatedRunner`, where a parse error routes to the `cli.failed` path).
 */
export function parseFindings(json: string): Finding[] {
  const trimmed = json.trim();
  if (trimmed.length === 0) {
    return [];
  }
  const root: unknown = JSON.parse(trimmed);
  if (root === null || typeof root !== 'object' || Array.isArray(root)) {
    return [];
  }
  const array = (root as { findings?: unknown }).findings;
  if (!Array.isArray(array)) {
    return [];
  }

  const findings: Finding[] = [];
  for (const element of array) {
    if (element === null || typeof element !== 'object' || Array.isArray(element)) {
      continue;
    }
    const obj = element as Record<string, unknown>;
    if (typeof obj.file !== 'string') {
      continue;
    }
    findings.push({
      file: obj.file,
      line: typeof obj.line === 'number' ? Math.trunc(obj.line) : 0,
      value: typeof obj.value === 'string' ? obj.value : '',
      context: typeof obj.context === 'string' ? obj.context : '',
    });
  }
  return findings;
}

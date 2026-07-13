import * as fs from 'fs';
import * as path from 'path';
import { EnvFile } from './envFile';
import { envDisplayName } from './envFileNaming';

/** Port of `dev.nylo.plugin.env.EnvFileScanner`. */

const EXAMPLE_FILENAME = '.env-example';
const ENV_PATTERN = /^\.env(?:\.(.+))?$/;

/**
 * Whether [name] (a bare file name, not a path) is an `.env*` file the plugin manages.
 * Single source of truth for what [scanEnvFiles] picks up; `.env-example` is excluded.
 */
export function isEnvFileName(name: string): boolean {
  return name !== EXAMPLE_FILENAME && ENV_PATTERN.test(name);
}

/** Scans [projectDir] for `.env*` files, sorted by display name. */
export function scanEnvFiles(projectDir: string): EnvFile[] {
  let names: string[];
  try {
    if (!fs.statSync(projectDir).isDirectory()) {
      return [];
    }
    names = fs.readdirSync(projectDir);
  } catch {
    return [];
  }

  const files: EnvFile[] = [];
  for (const name of names) {
    const env = toEnvFile(projectDir, name);
    if (env) {
      files.push(env);
    }
  }
  // Match Kotlin's `sortedBy { displayName }` (UTF-16 code-unit ordering), not locale-aware sort.
  files.sort((a, b) => (a.displayName < b.displayName ? -1 : a.displayName > b.displayName ? 1 : 0));
  return files;
}

function toEnvFile(projectDir: string, name: string): EnvFile | null {
  if (!isEnvFileName(name)) {
    return null;
  }
  const match = ENV_PATTERN.exec(name);
  if (!match) {
    return null;
  }
  const suffix = match[1] && match[1].length > 0 ? match[1] : null;
  return {
    filePath: path.join(projectDir, name),
    fileName: name,
    suffix,
    displayName: envDisplayName(suffix),
  };
}

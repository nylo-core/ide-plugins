import * as fs from 'fs';
import * as path from 'path';

/**
 * Port of `dev.nylo.plugin.project.NyloProjectDetector`.
 *
 * A project is a Nylo project when its `pubspec.yaml` declares a `nylo_framework` dependency.
 * The regex requires the marker to start a line (after optional whitespace), so a `#`-commented
 * mention does not count.
 */
const NYLO_FRAMEWORK_LINE = /^\s*nylo_framework\s*:/m;

/** Pure predicate over the raw `pubspec.yaml` contents. */
export function isNyloPubspec(pubspecContents: string): boolean {
  return NYLO_FRAMEWORK_LINE.test(pubspecContents);
}

/** Whether the directory contains a Nylo `pubspec.yaml`. Returns false if missing/unreadable. */
export function isNyloProjectDir(projectDir: string): boolean {
  const pubspec = path.join(projectDir, 'pubspec.yaml');
  try {
    if (!fs.statSync(pubspec).isFile()) {
      return false;
    }
    return isNyloPubspec(fs.readFileSync(pubspec, 'utf8'));
  } catch {
    return false;
  }
}

import * as fs from 'fs';
import * as path from 'path';
import { scanLangFiles } from '../../core/localizations/langFileScanner';
import { parseDevices } from '../../core/screenshots/deviceDetector';
import { NyloPage, TargetDevice } from '../../core/screenshots/model';
import { parseRouter, scanPageRoutes } from '../../core/screenshots/routerParser';
import { SCREENSHOTS_CONFIG_PATH, screenshotsConfigTemplate } from '../../core/screenshots/scaffolder';
import { runProcess } from '../shared/processRunner';

/** Host-side introspection: reads project files and shells out, delegating logic to `core/screenshots`. */

export function readPages(folderPath: string): NyloPage[] {
  let routerText: string;
  try {
    routerText = fs.readFileSync(path.join(folderPath, 'lib/routes/router.dart'), 'utf8');
  } catch {
    return [];
  }
  return parseRouter(routerText, scanPageRoutes(collectDartSources(path.join(folderPath, 'lib'))));
}

export function scanLocaleCodes(folderPath: string): string[] {
  return scanLangFiles(folderPath).map((f) => f.code);
}

export async function detectDevices(folderPath: string): Promise<TargetDevice[]> {
  let result;
  try {
    result = await runProcess('flutter', ['devices', '--machine'], folderPath, 60_000);
  } catch {
    return [];
  }
  return result.code === 0 ? parseDevices(result.stdout) : [];
}

export function scaffoldExists(folderPath: string): boolean {
  try {
    return fs.statSync(path.join(folderPath, SCREENSHOTS_CONFIG_PATH)).isFile();
  } catch {
    return false;
  }
}

export function writeScaffold(folderPath: string, pages: NyloPage[]): string {
  const target = path.join(folderPath, SCREENSHOTS_CONFIG_PATH);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, screenshotsConfigTemplate(pages), 'utf8');
  return target;
}

function collectDartSources(dir: string): string[] {
  const out: string[] = [];
  const walk = (current: string) => {
    let entries: fs.Dirent[];
    try {
      entries = fs.readdirSync(current, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      const full = path.join(current, entry.name);
      if (entry.isDirectory()) {
        walk(full);
      } else if (entry.isFile() && entry.name.endsWith('.dart')) {
        try {
          out.push(fs.readFileSync(full, 'utf8'));
        } catch {
          // ignore unreadable file
        }
      }
    }
  };
  walk(dir);
  return out;
}

import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { readEnvDefaultLocale, resolveBaseline } from '../../core/localizations/baselineResolver';
import { LANG_DIR, LangFile, scanLangFiles } from '../../core/localizations/langFileScanner';
import { parseFlattened } from '../../core/localizations/langJson';
import { compareLocales } from '../../core/localizations/localeComparator';
import { EMPTY_REPORT, LocalizationReport } from '../../core/localizations/model';
import { writeValue } from './langFileWriter';

const KEY_BASELINE = 'nylo.loc.baseline';
const KEY_SAME = 'nylo.loc.sameAsBase';

/**
 * Port of `dev.nylo.plugin.localizations.service.LocalizationService`: reads + flattens the lang files,
 * caches the per-locale value maps, recomputes the report on baseline/flag/edit changes, and publishes
 * via {@link onDidChange}.
 */
export class LocalizationService implements vscode.Disposable {
  private readonly emitter = new vscode.EventEmitter<void>();
  readonly onDidChange = this.emitter.event;

  private folder: vscode.WorkspaceFolder | undefined;
  private langFiles: LangFile[] = [];
  private values = new Map<string, Map<string, string>>();
  private parseErrors = new Map<string, string>();
  private report: LocalizationReport = EMPTY_REPORT;
  private baselineOverride: string | null;
  private flagSameAsBase: boolean;

  constructor(private readonly context: vscode.ExtensionContext) {
    this.baselineOverride = context.workspaceState.get<string | null>(KEY_BASELINE, null);
    this.flagSameAsBase = context.workspaceState.get<boolean>(KEY_SAME, false);
  }

  setFolder(folder: vscode.WorkspaceFolder | undefined): void {
    this.folder = folder;
    this.refresh();
  }

  getReport(): LocalizationReport {
    return this.report;
  }

  getValues(): Map<string, Map<string, string>> {
    return this.values;
  }

  getLocaleCodes(): string[] {
    return this.langFiles.map((f) => f.code);
  }

  getFlagSameAsBase(): boolean {
    return this.flagSameAsBase;
  }

  fileFor(locale: string): string | undefined {
    return this.langFiles.find((f) => f.code === locale)?.filePath;
  }

  /** Whether the project actually has a lang/ directory (vs. just not being scanned yet). */
  hasLangDir(): boolean {
    if (!this.folder) {
      return false;
    }
    try {
      return fs.statSync(path.join(this.folder.uri.fsPath, LANG_DIR)).isDirectory();
    } catch {
      return false;
    }
  }

  /** Re-reads all lang files from disk and recomputes. */
  refresh(): void {
    this.values = new Map();
    this.parseErrors = new Map();
    if (!this.folder) {
      this.langFiles = [];
      this.report = EMPTY_REPORT;
      this.emitter.fire();
      return;
    }
    this.langFiles = scanLangFiles(this.folder.uri.fsPath);
    for (const lang of this.langFiles) {
      try {
        this.values.set(lang.code, parseFlattened(fs.readFileSync(lang.filePath, 'utf8')));
      } catch (e) {
        this.parseErrors.set(lang.code, (e as Error).message);
      }
    }
    this.recompute();
  }

  /** Recomputes the report from the in-memory maps (no disk read) — for baseline/flag/edit changes. */
  recompute(): void {
    const codes = this.langFiles.map((f) => f.code);
    const baseline = resolveBaseline(codes, this.baselineOverride, this.readEnvDefault());
    this.report = baseline
      ? compareLocales(baseline, this.values, this.parseErrors, this.flagSameAsBase)
      : EMPTY_REPORT;
    this.emitter.fire();
  }

  setBaselineOverride(code: string | null): void {
    this.baselineOverride = code;
    this.context.workspaceState.update(KEY_BASELINE, code);
    this.recompute();
  }

  toggleSameAsBase(): void {
    this.flagSameAsBase = !this.flagSameAsBase;
    this.context.workspaceState.update(KEY_SAME, this.flagSameAsBase);
    this.recompute();
  }

  async setValue(locale: string, key: string, value: string): Promise<string | null> {
    const file = this.fileFor(locale);
    if (!file) {
      return `No lang file for ${locale}`;
    }
    // No-change commits must not rewrite the file: the write re-serialises the whole JSON
    // (reformatting hand-formatted files) and triggers a refresh cycle.
    if (this.values.get(locale)?.get(key) === value) {
      return null;
    }
    const error = await writeValue(file, key, value);
    if (error) {
      return error;
    }
    // Only update a locale that already has a parsed value map — never fabricate one for a locale
    // with none (e.g. a parse-error locale), which would make the comparator treat it as a 1-key file.
    this.values.get(locale)?.set(key, value);
    this.recompute();
    return null;
  }

  private readEnvDefault(): string | null {
    if (!this.folder) {
      return null;
    }
    try {
      return readEnvDefaultLocale(fs.readFileSync(path.join(this.folder.uri.fsPath, '.env'), 'utf8'));
    } catch {
      return null;
    }
  }

  dispose(): void {
    this.emitter.dispose();
  }
}

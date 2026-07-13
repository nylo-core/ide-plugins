import * as path from 'path';
import * as vscode from 'vscode';
import { LANG_DIR } from '../../core/localizations/langFileScanner';
import { Finding } from '../../core/localizations/nyloCli';
import { firstNyloFolder } from '../shared/workspace';
import { runFindUntranslated } from './findUntranslated';
import { LocalizationService } from './localizationService';
import { LocMode, LocNode, LocalizationsTreeProvider } from './localizationsTree';

/** Registers the Localizations tree view and its commands. */
export function registerLocalizationsView(context: vscode.ExtensionContext): void {
  const controller = new LocalizationsController(context);
  context.subscriptions.push(
    controller,
    vscode.commands.registerCommand('nylo.localizations.refresh', () => controller.refresh()),
    vscode.commands.registerCommand('nylo.localizations.selectMode', () => controller.selectMode()),
    vscode.commands.registerCommand('nylo.localizations.setBaseline', () => controller.setBaseline()),
    vscode.commands.registerCommand('nylo.localizations.toggleSameAsBase', () => controller.toggleSameAsBase()),
    vscode.commands.registerCommand('nylo.localizations.toggleProblemsOnly', () => controller.toggleProblemsOnly()),
    vscode.commands.registerCommand('nylo.localizations.scanHardcoded', () => controller.scanHardcoded()),
    vscode.commands.registerCommand('nylo.localizations.editValue', (node?: LocNode) => controller.editValue(node)),
    vscode.commands.registerCommand('nylo.localizations.openTarget', (node?: LocNode) => controller.openTarget(node)),
    vscode.commands.registerCommand('nylo.localizations.setKeyFilter', () => controller.setKeyFilter()),
    vscode.commands.registerCommand('nylo.localizations.setLocaleFilter', () => controller.setLocaleFilter()),
    vscode.commands.registerCommand('nylo.localizations.toggleShowMissing', () => controller.toggleShowMissing()),
    vscode.commands.registerCommand('nylo.localizations.toggleShowEmpty', () => controller.toggleShowEmpty()),
    vscode.commands.registerCommand('nylo.localizations.toggleShowExtra', () => controller.toggleShowExtra()),
  );
}

// workspaceState keys, following the existing `nylo.loc.*` convention (see LocalizationService).
const KEY_PROBLEMS_ONLY = 'nylo.loc.problemsOnly';
const KEY_SHOW_MISSING = 'nylo.loc.showMissing';
const KEY_SHOW_EMPTY = 'nylo.loc.showEmpty';
const KEY_SHOW_EXTRA = 'nylo.loc.showExtra';
const KEY_LOCALE_FILTER = 'nylo.loc.localeFilter';

const WATCH_DEBOUNCE_MS = 300;
const ALL_LOCALES = 'All locales';

class LocalizationsController implements vscode.Disposable {
  private readonly service: LocalizationService;
  private readonly provider = new LocalizationsTreeProvider();
  private readonly treeView: vscode.TreeView<LocNode>;
  private readonly disposables: vscode.Disposable[] = [];
  private langWatcher: vscode.FileSystemWatcher | undefined;

  private folder: vscode.WorkspaceFolder | undefined;
  private mode: LocMode = 'summary';
  private problemsOnly: boolean;
  private showMissing: boolean;
  private showEmpty: boolean;
  private showExtra: boolean;
  private keyFilter = '';
  private localeFilter: string | null;
  private findings: Finding[] = [];
  private hasScanned = false;

  /** A lang change arrived while the tree was hidden; consumed on the next show (Kotlin LangFileWatcher). */
  private pendingWhileHidden = false;
  private refreshTimer: ReturnType<typeof setTimeout> | undefined;

  constructor(private readonly context: vscode.ExtensionContext) {
    this.service = new LocalizationService(context);
    this.problemsOnly = context.workspaceState.get<boolean>(KEY_PROBLEMS_ONLY, true);
    this.showMissing = context.workspaceState.get<boolean>(KEY_SHOW_MISSING, true);
    this.showEmpty = context.workspaceState.get<boolean>(KEY_SHOW_EMPTY, true);
    this.showExtra = context.workspaceState.get<boolean>(KEY_SHOW_EXTRA, true);
    this.localeFilter = context.workspaceState.get<string | null>(KEY_LOCALE_FILTER, null);
    this.treeView = vscode.window.createTreeView('nylo.localizations', {
      treeDataProvider: this.provider,
      showCollapseAll: true,
    });
    this.disposables.push(
      this.service,
      this.treeView,
      this.service.onDidChange(() => this.pushState()),
      vscode.workspace.onDidChangeWorkspaceFolders(() => this.rebindFolder()),
      // Refreshes skipped while hidden must not be lost — replay on the next show.
      this.treeView.onDidChangeVisibility((e) => {
        if (e.visible && this.pendingWhileHidden) {
          this.pendingWhileHidden = false;
          this.service.refresh();
        }
      }),
    );
    this.rebindFolder();
  }

  private rebindFolder(): void {
    this.folder = firstNyloFolder();
    this.langWatcher?.dispose();
    if (this.folder) {
      this.langWatcher = vscode.workspace.createFileSystemWatcher(
        new vscode.RelativePattern(this.folder, `${LANG_DIR}/*.json`),
      );
      const onChange = () => this.scheduleRefresh();
      this.langWatcher.onDidCreate(onChange);
      this.langWatcher.onDidDelete(onChange);
      this.langWatcher.onDidChange(onChange);
    }
    this.service.setFolder(this.folder);
  }

  /** Debounces a burst of lang-file events (Kotlin uses a 300ms alarm) into a single refresh. */
  private scheduleRefresh(): void {
    if (this.refreshTimer) {
      clearTimeout(this.refreshTimer);
    }
    this.refreshTimer = setTimeout(() => {
      this.refreshTimer = undefined;
      if (this.treeView.visible) {
        this.service.refresh();
      } else {
        this.pendingWhileHidden = true; // don't drop it; replay on next show
      }
    }, WATCH_DEBOUNCE_MS);
  }

  private pushState(): void {
    const report = this.service.getReport();
    // Drop a persisted locale focus that no longer resolves (locale deleted, or it became the baseline).
    if (this.localeFilter && (this.localeFilter === report.baseline || !this.service.getLocaleCodes().includes(this.localeFilter))) {
      this.localeFilter = null;
      this.context.workspaceState.update(KEY_LOCALE_FILTER, null);
    }
    this.provider.setState({
      mode: this.mode,
      report,
      values: this.service.getValues(),
      localeCodes: this.service.getLocaleCodes(),
      findings: this.findings,
      problemsOnly: this.problemsOnly,
      hasScanned: this.hasScanned,
      hasLangDir: this.service.hasLangDir(),
      showMissing: this.showMissing,
      showEmpty: this.showEmpty,
      showExtra: this.showExtra,
      keyFilter: this.keyFilter,
      localeFilter: this.localeFilter,
    });
    const modeLabel = this.mode[0].toUpperCase() + this.mode.slice(1);
    this.treeView.description = report.baseline ? `${modeLabel} · base ${report.baseline}` : modeLabel;
    this.treeView.message = this.buildMessage();
  }

  /**
   * Surfaces the active filter state in the view message. VS Code toggles have no checkbox UI, so the
   * on/off state of each summary status filter is shown explicitly (✓ / ✗) alongside any locale/key focus.
   */
  private buildMessage(): string | undefined {
    if (this.mode === 'hardcoded') {
      const n = this.findings.length;
      if (n === 0) {
        return this.hasScanned ? 'No hardcoded strings found.' : 'Run the scan to list hardcoded strings.';
      }
      return `${n} hardcoded string${n === 1 ? '' : 's'}`;
    }
    if (this.mode === 'matrix') {
      const parts = [`Problems only ${mark(this.problemsOnly)}`];
      if (this.keyFilter) {
        parts.push(`Key "${this.keyFilter}"`);
      }
      return parts.join('  ·  ');
    }
    const parts = [`Missing ${mark(this.showMissing)}`, `Empty ${mark(this.showEmpty)}`, `Extra ${mark(this.showExtra)}`];
    if (this.localeFilter) {
      parts.push(`Locale ${this.localeFilter}`);
    }
    if (this.keyFilter) {
      parts.push(`Key "${this.keyFilter}"`);
    }
    if (this.service.getFlagSameAsBase()) {
      parts.push('Same-as-base');
    }
    return parts.join('  ·  ');
  }

  refresh(): void {
    this.service.refresh();
  }

  async selectMode(): Promise<void> {
    const pick = await vscode.window.showQuickPick(
      [
        { label: 'Summary', value: 'summary' as LocMode },
        { label: 'Matrix', value: 'matrix' as LocMode },
        { label: 'Hardcoded strings', value: 'hardcoded' as LocMode },
      ],
      { placeHolder: 'Localizations view' },
    );
    if (pick) {
      this.mode = pick.value;
      this.pushState();
    }
  }

  async setBaseline(): Promise<void> {
    const codes = this.service.getLocaleCodes();
    if (codes.length === 0) {
      return;
    }
    const pick = await vscode.window.showQuickPick(
      [{ label: 'Auto (env / en)', value: null as string | null }, ...codes.map((c) => ({ label: c, value: c as string | null }))],
      { placeHolder: 'Baseline locale' },
    );
    if (pick !== undefined) {
      this.service.setBaselineOverride(pick.value);
    }
  }

  toggleSameAsBase(): void {
    this.service.toggleSameAsBase();
  }

  toggleProblemsOnly(): void {
    this.problemsOnly = !this.problemsOnly;
    this.context.workspaceState.update(KEY_PROBLEMS_ONLY, this.problemsOnly);
    this.mode = 'matrix';
    this.pushState();
  }

  toggleShowMissing(): void {
    this.showMissing = !this.showMissing;
    this.context.workspaceState.update(KEY_SHOW_MISSING, this.showMissing);
    this.mode = 'summary';
    this.pushState();
  }

  toggleShowEmpty(): void {
    this.showEmpty = !this.showEmpty;
    this.context.workspaceState.update(KEY_SHOW_EMPTY, this.showEmpty);
    this.mode = 'summary';
    this.pushState();
  }

  toggleShowExtra(): void {
    this.showExtra = !this.showExtra;
    this.context.workspaceState.update(KEY_SHOW_EXTRA, this.showExtra);
    this.mode = 'summary';
    this.pushState();
  }

  async setKeyFilter(): Promise<void> {
    // Narrows both the summary issue list (key/base/current) and the matrix keys (key only). Not
    // persisted, mirroring the transient Kotlin search box.
    const value = await vscode.window.showInputBox({
      prompt: 'Filter keys (matches key, base and current values); leave empty to clear',
      value: this.keyFilter,
      placeHolder: 'Substring to match',
    });
    if (value === undefined) {
      return; // cancelled
    }
    this.keyFilter = value.trim();
    this.pushState();
  }

  async setLocaleFilter(): Promise<void> {
    const baseline = this.service.getReport().baseline;
    const codes = this.service.getLocaleCodes().filter((c) => c !== baseline);
    const pick = await vscode.window.showQuickPick([ALL_LOCALES, ...codes], {
      placeHolder: 'Focus the summary issues on one locale',
    });
    if (pick === undefined) {
      return; // cancelled
    }
    this.localeFilter = pick === ALL_LOCALES ? null : pick;
    this.context.workspaceState.update(KEY_LOCALE_FILTER, this.localeFilter);
    this.mode = 'summary';
    this.pushState();
  }

  async scanHardcoded(): Promise<void> {
    if (!this.folder) {
      return;
    }
    this.mode = 'hardcoded';
    this.pushState();
    const folder = this.folder;
    const outcome = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: 'Nylo: scanning for hardcoded strings…' },
      () => runFindUntranslated(folder),
    );
    if (outcome.error) {
      vscode.window.showWarningMessage(outcome.error);
      this.findings = [];
    } else {
      this.findings = outcome.findings ?? [];
      this.hasScanned = true; // a scan completed, so an empty list now means "none found"
    }
    this.pushState();
  }

  async editValue(node?: LocNode): Promise<void> {
    let locale: string;
    let key: string;
    let current: string;
    if (node?.t === 'issue') {
      locale = node.issue.locale;
      key = node.issue.key;
      current = node.issue.currentValue ?? '';
    } else if (node?.t === 'cell') {
      locale = node.locale;
      key = node.key;
      current = node.value ?? '';
    } else {
      return;
    }
    const value = await vscode.window.showInputBox({ prompt: `${key} — ${locale}`, value: current });
    if (value === undefined) {
      return;
    }
    const error = await this.service.setValue(locale, key, value);
    if (error) {
      vscode.window.showErrorMessage(error);
    }
  }

  async openTarget(node?: LocNode): Promise<void> {
    if (node?.t === 'issue') {
      const file = this.service.fileFor(node.issue.locale);
      if (file) {
        await openAndReveal(file, leafKey(node.issue.key));
      }
    } else if (node?.t === 'finding' && this.folder) {
      await openAtLine(path.join(this.folder.uri.fsPath, node.finding.file), node.finding.line);
    }
  }

  dispose(): void {
    if (this.refreshTimer) {
      clearTimeout(this.refreshTimer);
    }
    this.langWatcher?.dispose();
    this.disposables.forEach((d) => d.dispose());
  }
}

/** ✓ for an enabled toggle, ✗ for a disabled one — used in the view message since toggles have no checkbox UI. */
function mark(on: boolean): string {
  return on ? '✓' : '✗';
}

function leafKey(dottedKey: string): string {
  const idx = dottedKey.lastIndexOf('.');
  return idx < 0 ? dottedKey : dottedKey.slice(idx + 1);
}

async function openAndReveal(filePath: string, needle: string): Promise<void> {
  const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(filePath));
  const editor = await vscode.window.showTextDocument(doc);
  const offset = doc.getText().indexOf(`"${needle}"`);
  if (offset >= 0) {
    const pos = doc.positionAt(offset);
    editor.selection = new vscode.Selection(pos, pos);
    editor.revealRange(new vscode.Range(pos, pos), vscode.TextEditorRevealType.InCenter);
  }
}

async function openAtLine(filePath: string, line: number): Promise<void> {
  try {
    const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(filePath));
    const editor = await vscode.window.showTextDocument(doc);
    const target = new vscode.Position(Math.max(0, line - 1), 0);
    editor.selection = new vscode.Selection(target, target);
    editor.revealRange(new vscode.Range(target, target), vscode.TextEditorRevealType.InCenter);
  } catch {
    vscode.window.showWarningMessage(`Nylo: could not open ${filePath}`);
  }
}

import * as path from 'path';
import * as vscode from 'vscode';
import { NyloPage, TargetDevice } from '../../core/screenshots/model';
import { filterPages } from '../../core/screenshots/pageFilter';
import { firstNyloFolder } from '../shared/workspace';
import { runCapture, ScreenshotRunRequest } from './orchestrator';
import { detectDevices, readPages, scanLocaleCodes, scaffoldExists, writeScaffold } from './project';

type Section = 'pages' | 'locales' | 'devices';

type SNode =
  | { t: 'section'; section: Section }
  | { t: 'page'; page: NyloPage }
  | { t: 'locale'; code: string }
  | { t: 'device'; device: TargetDevice }
  | { t: 'message'; text: string };

interface ScreenshotsState {
  pages: NyloPage[];
  locales: string[];
  devices: TargetDevice[];
  /** Selected page routes (Kotlin keys selection by route, not class name). */
  selPages: Set<string>;
  selLocales: Set<string>;
  selDevices: Set<string>;
}

const KEY_PAGES = 'nylo.ss.pages';
const KEY_LOCALES = 'nylo.ss.locales';
const KEY_DEVICES = 'nylo.ss.devices';

export function registerScreenshotsView(context: vscode.ExtensionContext): void {
  const controller = new ScreenshotsController(context);
  context.subscriptions.push(
    controller,
    vscode.commands.registerCommand('nylo.screenshots.refresh', () => controller.refreshAll()),
    vscode.commands.registerCommand('nylo.screenshots.refreshDevices', () => controller.refreshDevices()),
    vscode.commands.registerCommand('nylo.screenshots.start', () => controller.start()),
    vscode.commands.registerCommand('nylo.screenshots.stop', () => controller.stop()),
    vscode.commands.registerCommand('nylo.screenshots.scaffold', () => controller.scaffold()),
    vscode.commands.registerCommand('nylo.screenshots.filterPages', () => controller.filterPages()),
    vscode.commands.registerCommand('nylo.screenshots.pagesSelectAll', () => controller.pagesSelectAll()),
    vscode.commands.registerCommand('nylo.screenshots.pagesSelectNone', () => controller.pagesSelectNone()),
  );
}

class ScreenshotsTreeProvider implements vscode.TreeDataProvider<SNode> {
  private readonly emitter = new vscode.EventEmitter<SNode | undefined>();
  readonly onDidChangeTreeData = this.emitter.event;
  /** Active case-insensitive page search; empty means "show all". */
  pageFilter = '';
  state: ScreenshotsState = {
    pages: [],
    locales: [],
    devices: [],
    selPages: new Set(),
    selLocales: new Set(),
    selDevices: new Set(),
  };

  refresh(): void {
    this.emitter.fire(undefined);
  }

  /** Pages surviving the current search filter — the scope for Select All/None. */
  filteredPages(): NyloPage[] {
    return filterPages(this.state.pages, this.pageFilter);
  }

  getChildren(element?: SNode): SNode[] {
    if (!element) {
      return [
        { t: 'section', section: 'pages' },
        { t: 'section', section: 'locales' },
        { t: 'section', section: 'devices' },
      ];
    }
    if (element.t !== 'section') {
      return [];
    }
    if (element.section === 'pages') {
      if (this.state.pages.length === 0) {
        return [{ t: 'message', text: 'No pages found in lib/routes/router.dart.' }];
      }
      const filtered = this.filteredPages();
      if (filtered.length === 0) {
        return [{ t: 'message', text: `No pages match "${this.pageFilter}".` }];
      }
      return filtered.map((page) => ({ t: 'page', page }));
    }
    if (element.section === 'locales') {
      return this.state.locales.length
        ? this.state.locales.map((code) => ({ t: 'locale', code }))
        : [{ t: 'message', text: 'No locales found in lang/.' }];
    }
    return this.state.devices.length
      ? this.state.devices.map((device) => ({ t: 'device', device }))
      : [{ t: 'message', text: 'No running devices. Start a simulator/emulator, then Refresh Devices.' }];
  }

  getTreeItem(node: SNode): vscode.TreeItem {
    if (node.t === 'section') {
      const labels: Record<Section, string> = { pages: 'Pages', locales: 'Locales', devices: 'Devices' };
      const item = new vscode.TreeItem(labels[node.section], vscode.TreeItemCollapsibleState.Expanded);
      if (node.section === 'pages') {
        const selected = this.state.pages.filter((p) => this.state.selPages.has(p.route)).length;
        item.description = this.pageFilter ? `${selected} selected · filter: "${this.pageFilter}"` : `${selected} selected`;
      } else if (node.section === 'locales') {
        item.description = `${this.state.locales.length}`;
      } else {
        item.description = `${this.state.devices.length}`;
      }
      item.contextValue = `nyloSsSection-${node.section}`;
      return item;
    }
    if (node.t === 'page') {
      const item = new vscode.TreeItem(node.page.displayName || node.page.className, vscode.TreeItemCollapsibleState.None);
      item.description = [node.page.route, node.page.authenticated ? 'auth' : '', node.page.routeResolved ? '' : 'derived']
        .filter(Boolean)
        .join('  ·  ');
      item.checkboxState = this.state.selPages.has(node.page.route)
        ? vscode.TreeItemCheckboxState.Checked
        : vscode.TreeItemCheckboxState.Unchecked;
      return item;
    }
    if (node.t === 'locale') {
      const item = new vscode.TreeItem(node.code, vscode.TreeItemCollapsibleState.None);
      item.checkboxState = this.state.selLocales.has(node.code)
        ? vscode.TreeItemCheckboxState.Checked
        : vscode.TreeItemCheckboxState.Unchecked;
      item.iconPath = new vscode.ThemeIcon('globe');
      return item;
    }
    if (node.t === 'device') {
      const item = new vscode.TreeItem(node.device.name, vscode.TreeItemCollapsibleState.None);
      item.description = [node.device.platform, node.device.emulator ? 'emulator' : ''].filter(Boolean).join('  ·  ');
      item.checkboxState = this.state.selDevices.has(node.device.id)
        ? vscode.TreeItemCheckboxState.Checked
        : vscode.TreeItemCheckboxState.Unchecked;
      item.iconPath = new vscode.ThemeIcon('device-mobile');
      return item;
    }
    return new vscode.TreeItem(node.text);
  }
}

class ScreenshotsController implements vscode.Disposable {
  private readonly provider = new ScreenshotsTreeProvider();
  private readonly treeView: vscode.TreeView<SNode>;
  private readonly output = vscode.window.createOutputChannel('Nylo Screenshots');
  private readonly disposables: vscode.Disposable[] = [];
  private folder: vscode.WorkspaceFolder | undefined;
  private running = false;
  private cancelled = false;

  constructor(private readonly context: vscode.ExtensionContext) {
    this.treeView = vscode.window.createTreeView('nylo.screenshots', { treeDataProvider: this.provider });
    this.disposables.push(
      this.treeView,
      this.output,
      this.treeView.onDidChangeCheckboxState((e) => this.onCheckbox(e)),
      vscode.workspace.onDidChangeWorkspaceFolders(() => this.rebindFolder()),
    );
    this.rebindFolder();
  }

  private rebindFolder(): void {
    this.folder = firstNyloFolder();
    this.refreshAll();
  }

  refreshAll(): void {
    if (!this.folder) {
      return;
    }
    this.provider.state.pages = readPages(this.folder.uri.fsPath);
    this.provider.state.locales = scanLocaleCodes(this.folder.uri.fsPath);
    // Pages default to NONE; a single locale (`en` else the first) is the default (Kotlin populate()).
    this.provider.state.selPages = this.effectiveSelection(KEY_PAGES, () => new Set());
    this.provider.state.selLocales = this.effectiveSelection(KEY_LOCALES, () => this.defaultLocaleSet());
    this.provider.refresh();
    void this.refreshDevices();
  }

  async refreshDevices(): Promise<void> {
    if (!this.folder) {
      return;
    }
    this.provider.state.devices = await detectDevices(this.folder.uri.fsPath);
    // Devices default to ALL.
    this.provider.state.selDevices = this.effectiveSelection(
      KEY_DEVICES,
      () => new Set(this.provider.state.devices.map((d) => d.id)),
    );
    this.provider.refresh();
  }

  private onCheckbox(e: vscode.TreeCheckboxChangeEvent<SNode>): void {
    for (const [node, state] of e.items) {
      const checked = state === vscode.TreeItemCheckboxState.Checked;
      if (node.t === 'page') {
        toggle(this.provider.state.selPages, node.page.route, checked);
        this.saveSet(KEY_PAGES, this.provider.state.selPages);
      } else if (node.t === 'locale') {
        toggle(this.provider.state.selLocales, node.code, checked);
        this.saveSet(KEY_LOCALES, this.provider.state.selLocales);
      } else if (node.t === 'device') {
        toggle(this.provider.state.selDevices, node.device.id, checked);
        this.saveSet(KEY_DEVICES, this.provider.state.selDevices);
      }
    }
    // Keep the Pages "N selected" description live.
    this.provider.refresh();
  }

  async filterPages(): Promise<void> {
    const value = await vscode.window.showInputBox({
      prompt: 'Filter pages by name, route, or class (leave blank to clear)',
      placeHolder: 'Search pages',
      value: this.provider.pageFilter,
    });
    if (value === undefined) {
      return; // dismissed — leave the filter unchanged
    }
    this.provider.pageFilter = value.trim();
    this.provider.refresh();
  }

  pagesSelectAll(): void {
    for (const page of this.provider.filteredPages()) {
      this.provider.state.selPages.add(page.route);
    }
    this.saveSet(KEY_PAGES, this.provider.state.selPages);
    this.provider.refresh();
  }

  pagesSelectNone(): void {
    for (const page of this.provider.filteredPages()) {
      this.provider.state.selPages.delete(page.route);
    }
    this.saveSet(KEY_PAGES, this.provider.state.selPages);
    this.provider.refresh();
  }

  async scaffold(): Promise<void> {
    if (!this.folder) {
      return;
    }
    // Scaffold the selected pages, or all pages when nothing is selected (Kotlin: selectedPages().ifEmpty { allPages }).
    const selected = this.provider.state.pages.filter((p) => this.provider.state.selPages.has(p.route));
    const pages = selected.length > 0 ? selected : this.provider.state.pages;
    if (pages.length === 0) {
      vscode.window.showInformationMessage('Nylo: no pages to scaffold — run "Screenshots: Refresh" first.');
      return;
    }
    if (scaffoldExists(this.folder.uri.fsPath)) {
      const choice = await vscode.window.showWarningMessage(
        'lib/config/screenshots.dart already exists. Overwrite it with a fresh stub? Any dataForRoute builders and seed() code you added will be lost.',
        { modal: true },
        'Overwrite',
      );
      if (choice !== 'Overwrite') {
        return;
      }
    }
    const target = writeScaffold(this.folder.uri.fsPath, pages);
    const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(target));
    await vscode.window.showTextDocument(doc);
    vscode.window.showInformationMessage(
      'Nylo: generated lib/config/screenshots.dart — fill in per-route data, then call ScreenshotsConfig.register() in main().',
    );
  }

  async start(): Promise<void> {
    if (!this.folder) {
      return;
    }
    if (this.running) {
      vscode.window.showInformationMessage('Nylo: a screenshot run is already in progress.');
      return;
    }
    const pages = this.provider.state.pages.filter((p) => this.provider.state.selPages.has(p.route));
    const locales = this.provider.state.locales.filter((c) => this.provider.state.selLocales.has(c));
    const devices = this.provider.state.devices.filter((d) => this.provider.state.selDevices.has(d.id));
    if (pages.length === 0 || locales.length === 0 || devices.length === 0) {
      vscode.window.showWarningMessage('Nylo: select at least one page, locale and device.');
      return;
    }

    // Persist exactly what's being run (Kotlin persists the selection on Start).
    this.saveSet(KEY_PAGES, new Set(pages.map((p) => p.route)));
    this.saveSet(KEY_LOCALES, new Set(locales));
    this.saveSet(KEY_DEVICES, new Set(devices.map((d) => d.id)));

    const cfg = vscode.workspace.getConfiguration('nylo.screenshots');
    const outputDir = cfg.get<string>('outputDir', 'screenshots');
    const outDirAbs = path.isAbsolute(outputDir) ? outputDir : path.join(this.folder.uri.fsPath, outputDir);
    const request: ScreenshotRunRequest = {
      projectDir: this.folder.uri.fsPath,
      devices,
      pages,
      locales,
      outputDir,
      settleMs: cfg.get<number>('settleMs', 1200),
      windowMs: cfg.get<number>('windowMs', 1200),
      cleanStatusBar: cfg.get<boolean>('cleanStatusBar', true),
    };

    this.running = true;
    this.cancelled = false;
    this.output.clear();
    this.output.show(true);
    const total = pages.length * locales.length * devices.length;

    await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: 'Nylo: capturing screenshots', cancellable: true },
      async (progress, token) => {
        token.onCancellationRequested(() => {
          this.cancelled = true;
        });
        // VS Code progress is additive; report the delta of the absolute done/total so the bar tracks
        // captures AND skips and still reaches 100% (Kotlin sets an absolute fraction).
        let lastPct = 0;
        const result = await runCapture(request, {
          log: (message) => this.output.appendLine(message),
          onProgress: (done, reportedTotal, label) => {
            const pct = reportedTotal > 0 ? (done / reportedTotal) * 100 : 0;
            const increment = Math.max(0, pct - lastPct);
            lastPct = pct;
            progress.report({ message: `${done}/${total} · ${label}`, increment });
          },
          isCancelled: () => this.cancelled,
        });
        this.output.appendLine(`\nDone — ${result.captured} captured, ${result.skipped} skipped.`);
        if (result.captured > 0) {
          this.notifyCaptured(result.captured, outDirAbs);
        }
      },
    );
    this.running = false;
  }

  stop(): void {
    if (this.running) {
      this.cancelled = true;
      this.output.appendLine('Stopping…');
    }
  }

  private notifyCaptured(captured: number, outDirAbs: string): void {
    const noun = captured === 1 ? 'screenshot' : 'screenshots';
    void vscode.window
      .showInformationMessage(`Nylo: captured ${captured} ${noun} into ${path.basename(outDirAbs)}/.`, 'Reveal Folder')
      .then((choice) => {
        if (choice === 'Reveal Folder') {
          void vscode.commands.executeCommand('revealFileInOS', vscode.Uri.file(outDirAbs));
        }
      });
  }

  private defaultLocaleSet(): Set<string> {
    const locales = this.provider.state.locales;
    const preferred = locales.includes('en') ? 'en' : locales[0];
    return preferred ? new Set([preferred]) : new Set();
  }

  /** Saved selection when non-empty, else the supplied default — a blank saved set means "use default". */
  private effectiveSelection(key: string, computeDefault: () => Set<string>): Set<string> {
    const saved = this.context.workspaceState.get<string[] | undefined>(key, undefined);
    if (saved && saved.length > 0) {
      return new Set(saved);
    }
    return computeDefault();
  }

  private saveSet(key: string, set: Set<string>): void {
    this.context.workspaceState.update(key, [...set]);
  }

  dispose(): void {
    this.disposables.forEach((d) => d.dispose());
  }
}

function toggle(set: Set<string>, id: string, on: boolean): void {
  if (on) {
    set.add(id);
  } else {
    set.delete(id);
  }
}

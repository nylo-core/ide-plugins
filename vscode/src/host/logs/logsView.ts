import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { LogCategory } from '../../core/logs/logCategory';
import { defaultLogDate, LogFileRef, LOGS_DIR, scanLogDir } from '../../core/logs/logFileScanner';
import { applyFilter, LogQuery } from '../../core/logs/logFilter';
import { EMPTY_DOCUMENT, LogDocument } from '../../core/logs/logModel';
import { parseLog } from '../../core/logs/logParser';
import { firstNyloFolder } from '../shared/workspace';
import { LogNode, LogsTreeProvider } from './logsTreeProvider';
import { LogTailer } from './logTailer';

const KEY_SORT = 'nylo.logs.sort';
const KEY_CATEGORY = 'nylo.logs.category';
const KEY_FOLLOW = 'nylo.logs.follow';

const CATEGORY_LABELS: Record<LogCategory, string> = {
  all: 'All',
  console: 'Console',
  networking: 'Networking',
  errors: 'Errors',
};

/** Registers the Logs tree view and its toolbar commands. */
export function registerLogsView(context: vscode.ExtensionContext): void {
  const controller = new LogsController(context);
  context.subscriptions.push(
    controller,
    vscode.commands.registerCommand('nylo.logs.refresh', () => controller.reload()),
    vscode.commands.registerCommand('nylo.logs.pickDate', () => controller.pickDate()),
    vscode.commands.registerCommand('nylo.logs.search', () => controller.search()),
    vscode.commands.registerCommand('nylo.logs.filterSession', () => controller.filterSession()),
    vscode.commands.registerCommand('nylo.logs.selectCategory', () => controller.selectCategory()),
    vscode.commands.registerCommand('nylo.logs.toggleSort', () => controller.toggleSort()),
    vscode.commands.registerCommand('nylo.logs.toggleFollow', () => controller.toggleFollow()),
    vscode.commands.registerCommand('nylo.logs.clearFilters', () => controller.clearFilters()),
  );
}

class LogsController implements vscode.Disposable {
  private readonly provider = new LogsTreeProvider();
  private readonly treeView: vscode.TreeView<LogNode>;
  private readonly tailer = new LogTailer();
  private readonly disposables: vscode.Disposable[] = [];
  private dirWatcher: vscode.FileSystemWatcher | undefined;

  private folder: vscode.WorkspaceFolder | undefined;
  private refs: LogFileRef[] = [];
  private currentDate: string | undefined;
  private fullDoc: LogDocument = EMPTY_DOCUMENT;
  private filteredDoc: LogDocument = EMPTY_DOCUMENT;
  private query: LogQuery;
  private follow: boolean;

  constructor(private readonly context: vscode.ExtensionContext) {
    this.query = {
      sort: context.workspaceState.get<LogQuery['sort']>(KEY_SORT, 'newest'),
      category: context.workspaceState.get<LogCategory>(KEY_CATEGORY, 'all'),
      text: null,
      sessionTag: null,
    };
    this.follow = context.workspaceState.get<boolean>(KEY_FOLLOW, true);

    this.treeView = vscode.window.createTreeView('nylo.logs', {
      treeDataProvider: this.provider,
      showCollapseAll: true,
    });
    this.disposables.push(
      this.treeView,
      this.treeView.onDidChangeVisibility((e) => (e.visible ? this.updateTailer() : this.tailer.stop())),
      vscode.workspace.onDidChangeWorkspaceFolders(() => this.rebindFolder()),
    );

    this.rebindFolder();
  }

  // --- folder + file watching -------------------------------------------------------------------

  private rebindFolder(): void {
    this.folder = firstNyloFolder();
    this.dirWatcher?.dispose();
    if (this.folder) {
      const pattern = new vscode.RelativePattern(this.folder, `${LOGS_DIR}/*.log`);
      this.dirWatcher = vscode.workspace.createFileSystemWatcher(pattern);
      this.dirWatcher.onDidCreate(() => this.reload());
      this.dirWatcher.onDidDelete(() => this.reload());
    }
    this.reload();
  }

  // --- core reload / render ---------------------------------------------------------------------

  reload(): void {
    this.refreshDates();
    const file = this.currentRef()?.filePath;
    if (!file) {
      this.fullDoc = EMPTY_DOCUMENT;
      this.filteredDoc = EMPTY_DOCUMENT;
      this.provider.setDocument(EMPTY_DOCUMENT);
      // Keep the tailer decision in one place: with no file selected it still watches the
      // expected today path into existence (first app run of the day).
      this.updateTailer();
      this.updateStatus();
      return;
    }
    this.fullDoc = readDocument(file);
    this.render();
    this.updateTailer();
    this.updateStatus();
  }

  private reloadFromDisk(): void {
    const file = this.currentRef()?.filePath;
    if (!file) {
      return;
    }
    this.fullDoc = readDocument(file);
    this.render();
    this.updateStatus();
  }

  private render(): void {
    this.filteredDoc = applyFilter(this.fullDoc, this.query);
    this.provider.setDocument(this.filteredDoc);
  }

  private refreshDates(): void {
    const dir = this.folder ? path.join(this.folder.uri.fsPath, LOGS_DIR) : undefined;
    this.refs = dir ? scanLogDir(dir) : [];
    const dates = this.refs.map((r) => r.date);
    if (!this.currentDate || !dates.includes(this.currentDate)) {
      this.currentDate = defaultLogDate(dates, localDateString()) ?? undefined;
    }
  }

  private currentRef(): LogFileRef | undefined {
    return this.refs.find((r) => r.date === this.currentDate);
  }

  /**
   * Tails when Follow is on and the view is "current": today is selected, or the selection is
   * still the default (newest available / nothing) because today's file doesn't exist yet. The
   * tailer targets the *expected* today path, re-resolved every poll, so the first app run of the
   * day and the midnight rollover are picked up without a manual refresh. Explicitly selected
   * older dates never tail.
   */
  private updateTailer(): void {
    const dir = this.folder ? path.join(this.folder.uri.fsPath, LOGS_DIR) : undefined;
    const newestKnown = this.refs[0]?.date; // refs are sorted newest first
    const followEligible =
      !this.currentDate || this.currentDate === localDateString() || this.currentDate === newestKnown;
    if (this.follow && followEligible && dir && this.treeView.visible) {
      this.tailer.start(
        () => path.join(dir, `${localDateString()}.log`),
        () => this.onTailTick(),
      );
    } else {
      this.tailer.stop();
    }
  }

  /**
   * A tail tick means today's file grew, appeared for the first time, or the day rolled over.
   * When the current view already shows today, just re-render; otherwise the date list is stale
   * (the tailer noticed a file the last scan didn't) — rescan and jump to today, which is what
   * Follow promises to show.
   */
  private onTailTick(): void {
    const today = localDateString();
    if (this.currentDate === today && this.refs.some((r) => r.date === today)) {
      this.reloadFromDisk();
    } else {
      this.currentDate = undefined; // re-resolve the default; today's file exists now
      this.reload();
    }
    void this.revealNewest();
  }

  /** Scrolls to where new lines land (see LogsTreeProvider.newestEntryNode). Best-effort. */
  private async revealNewest(): Promise<void> {
    const node = this.provider.newestEntryNode(this.query.sort ?? 'newest');
    if (!node || !this.treeView.visible) {
      return;
    }
    try {
      await this.treeView.reveal(node, { select: false, focus: false });
    } catch {
      // reveal is best-effort; the view may have been disposed or the node replaced mid-flight
    }
  }

  private updateStatus(): void {
    this.treeView.description = this.currentDate ? annotateDate(this.currentDate) : 'no logs';
    if (this.refs.length === 0 || !this.currentRef()) {
      this.treeView.message = EMPTY_LOGS_MESSAGE;
      return;
    }
    if (isDocEmpty(this.filteredDoc)) {
      this.treeView.message = this.hasActiveFilter()
        ? this.emptyMessageForQuery()
        : 'This log file has no entries yet.';
      return;
    }
    const bits = [
      this.query.sort === 'newest' ? 'Newest first' : 'Oldest first',
      CATEGORY_LABELS[this.query.category ?? 'all'],
      this.follow ? 'Following' : 'Paused',
    ];
    if (this.query.text) {
      bits.push(`“${this.query.text}”`);
    }
    if (this.query.sessionTag) {
      bits.push(`session ${this.query.sessionTag}`);
    }
    this.treeView.message = bits.join('  ·  ');
  }

  private hasActiveFilter(): boolean {
    return !!this.query.sessionTag || !!this.query.text || (this.query.category ?? 'all') !== 'all';
  }

  /** A category-specific empty message when only a category is active; the generic one otherwise. */
  private emptyMessageForQuery(): string {
    const onlyCategory = !this.query.sessionTag && !this.query.text;
    if (!onlyCategory) {
      return 'No log entries match the current filters.';
    }
    switch (this.query.category ?? 'all') {
      case 'console':
        return 'No console logs in this view.';
      case 'networking':
        return 'No networking logs in this view.';
      case 'errors':
        return 'No errors in this view.';
      default:
        return 'No log entries match the current filters.';
    }
  }

  // --- commands ---------------------------------------------------------------------------------

  async pickDate(): Promise<void> {
    if (this.refs.length === 0) {
      vscode.window.showInformationMessage(`Nylo: ${EMPTY_LOGS_MESSAGE}`);
      return;
    }
    const pick = await vscode.window.showQuickPick(
      this.refs.map((r) => ({ label: annotateDate(r.date), date: r.date, picked: r.date === this.currentDate })),
      { placeHolder: 'Select a log date' },
    );
    if (pick) {
      this.currentDate = pick.date;
      this.reload();
    }
  }

  async search(): Promise<void> {
    const text = await vscode.window.showInputBox({
      prompt: 'Filter log text',
      value: this.query.text ?? '',
      placeHolder: 'e.g. /api/v1/user',
    });
    if (text === undefined) {
      return;
    }
    this.query = { ...this.query, text: text.trim() || null };
    this.render();
    this.updateStatus();
  }

  async filterSession(): Promise<void> {
    const tag = await vscode.window.showInputBox({
      prompt: 'Filter by session id (short tag or full id)',
      value: this.query.sessionTag ?? '',
      placeHolder: 'e.g. 9qbhab',
    });
    if (tag === undefined) {
      return;
    }
    this.query = { ...this.query, sessionTag: tag.trim() || null };
    this.render();
    this.updateStatus();
  }

  async selectCategory(): Promise<void> {
    const order: LogCategory[] = ['all', 'console', 'networking', 'errors'];
    const pick = await vscode.window.showQuickPick(
      order.map((c) => ({ label: CATEGORY_LABELS[c], value: c, picked: c === this.query.category })),
      { placeHolder: 'Show category' },
    );
    if (pick) {
      this.query = { ...this.query, category: pick.value };
      this.context.workspaceState.update(KEY_CATEGORY, pick.value);
      this.render();
      this.updateStatus();
    }
  }

  toggleSort(): void {
    const sort = this.query.sort === 'newest' ? 'oldest' : 'newest';
    this.query = { ...this.query, sort };
    this.context.workspaceState.update(KEY_SORT, sort);
    this.render();
    this.updateStatus();
  }

  toggleFollow(): void {
    this.follow = !this.follow;
    this.context.workspaceState.update(KEY_FOLLOW, this.follow);
    this.updateTailer();
    if (this.follow) {
      void this.revealNewest();
    }
    this.updateStatus();
  }

  clearFilters(): void {
    this.query = { ...this.query, text: null, sessionTag: null, category: 'all' };
    this.context.workspaceState.update(KEY_CATEGORY, 'all');
    this.render();
    this.updateStatus();
  }

  dispose(): void {
    this.tailer.dispose();
    this.dirWatcher?.dispose();
    this.disposables.forEach((d) => d.dispose());
  }
}

const EMPTY_LOGS_MESSAGE = "No Nylo logs found in the project's logs/ folder.";

function readDocument(file: string): LogDocument {
  try {
    return parseLog(fs.readFileSync(file, 'utf8'));
  } catch {
    return EMPTY_DOCUMENT;
  }
}

function isDocEmpty(doc: LogDocument): boolean {
  return doc.preamble.length === 0 && doc.sessions.length === 0;
}

function localDateString(date = new Date()): string {
  const z = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${z(date.getMonth() + 1)}-${z(date.getDate())}`;
}

/** Labels today's and yesterday's dates like the JetBrains date dropdown. */
function annotateDate(date: string): string {
  if (date === localDateString()) {
    return `${date}  (today)`;
  }
  const yesterday = new Date();
  yesterday.setDate(yesterday.getDate() - 1);
  if (date === localDateString(yesterday)) {
    return `${date}  (yesterday)`;
  }
  return date;
}

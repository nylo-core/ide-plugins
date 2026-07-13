import * as vscode from 'vscode';
import { Finding } from '../../core/localizations/nyloCli';
import { KeyStatus, LocaleIssue, LocaleSummary, LocalizationReport } from '../../core/localizations/model';

export type LocMode = 'summary' | 'matrix' | 'hardcoded';

export interface LocViewState {
  mode: LocMode;
  report: LocalizationReport;
  values: Map<string, Map<string, string>>;
  localeCodes: string[];
  findings: Finding[];
  problemsOnly: boolean;
  /** True once a hardcoded-strings scan has completed (distinguishes "none found" from "not scanned"). */
  hasScanned: boolean;
  /** Whether the project has a lang/ directory at all (distinguishes the two empty states). */
  hasLangDir: boolean;
  /** Summary issue-list status filters (matches Kotlin localizationsShow{Missing,Empty,Extra}). */
  showMissing: boolean;
  showEmpty: boolean;
  showExtra: boolean;
  /** Narrows summary issues (key/base/current) and matrix keys (key only); empty = no filter. */
  keyFilter: string;
  /** Focus the summary issue list on one locale; null = all locales. */
  localeFilter: string | null;
}

// Empty-state and hint wording, aligned with the Kotlin NyloBundle.properties messages.
const MSG_NO_LANG_DIR = 'No lang/ folder found in this project.';
const MSG_NO_LOCALES = 'No locale files found in lang/.';
const MSG_NO_BASELINE_KEYS = 'No baseline keys to show.';
const MSG_NO_PROBLEM_KEYS = 'No problem keys.';
const MSG_HARDCODED_HINT = 'Run the hardcoded-strings scan to populate this list.';
const MSG_NO_HARDCODED = 'No hardcoded strings found.';

export type LocNode =
  | { t: 'summary'; summary: LocaleSummary }
  | { t: 'issue'; issue: LocaleIssue }
  | { t: 'key'; key: string }
  | { t: 'cell'; key: string; locale: string; value: string | undefined }
  | { t: 'file'; file: string; findings: Finding[] }
  | { t: 'finding'; finding: Finding }
  | { t: 'message'; text: string };

const EDITABLE: ReadonlySet<KeyStatus> = new Set<KeyStatus>(['missing', 'empty', 'same_as_base']);
const STATUS_LABEL: Record<KeyStatus, string> = {
  translated: 'translated',
  missing: 'missing',
  empty: 'empty',
  same_as_base: 'same as base',
  extra: 'extra',
};

export class LocalizationsTreeProvider implements vscode.TreeDataProvider<LocNode> {
  private readonly emitter = new vscode.EventEmitter<LocNode | undefined>();
  readonly onDidChangeTreeData = this.emitter.event;
  private state: LocViewState = {
    mode: 'summary',
    report: { baseline: '', summaries: [], issues: [] },
    values: new Map(),
    localeCodes: [],
    findings: [],
    problemsOnly: true,
    hasScanned: false,
    hasLangDir: false,
    showMissing: true,
    showEmpty: true,
    showExtra: true,
    keyFilter: '',
    localeFilter: null,
  };

  setState(state: LocViewState): void {
    this.state = state;
    this.emitter.fire(undefined);
  }

  getChildren(element?: LocNode): LocNode[] {
    if (!element) {
      return this.rootNodes();
    }
    if (element.t === 'summary') {
      return this.filteredIssues(element.summary.locale).map((issue) => ({ t: 'issue', issue }));
    }
    if (element.t === 'key') {
      const baseline = this.state.report.baseline;
      return this.state.localeCodes
        .filter((code) => code !== baseline)
        .map((locale) => ({ t: 'cell', key: element.key, locale, value: this.state.values.get(locale)?.get(element.key) }));
    }
    if (element.t === 'file') {
      return element.findings.map((finding) => ({ t: 'finding', finding }));
    }
    return [];
  }

  /**
   * The summary issues for [locale] after applying the status toggles, the locale focus and the key
   * filter. Mirrors Kotlin SummaryPanel.refilterIssues: status enabled AND (no locale focus or matches)
   * AND (no query or matches key/base/current). same-as-base issues only exist when flagging is on, so
   * they always pass the status gate.
   */
  private filteredIssues(locale: string): LocaleIssue[] {
    const { showMissing, showEmpty, showExtra, localeFilter } = this.state;
    const query = this.state.keyFilter.trim().toLowerCase();
    return this.state.report.issues.filter(
      (i) =>
        i.locale === locale &&
        statusEnabled(i.status, showMissing, showEmpty, showExtra) &&
        (localeFilter === null || i.locale === localeFilter) &&
        (query === '' || issueMatchesQuery(i, query)),
    );
  }

  private rootNodes(): LocNode[] {
    if (this.state.mode === 'summary') {
      if (this.state.report.summaries.length === 0) {
        return [{ t: 'message', text: this.state.hasLangDir ? MSG_NO_LOCALES : MSG_NO_LANG_DIR }];
      }
      return this.state.report.summaries.map((summary) => ({ t: 'summary', summary }));
    }
    if (this.state.mode === 'matrix') {
      if (!this.state.hasLangDir) {
        return [{ t: 'message', text: MSG_NO_LANG_DIR }];
      }
      if (this.state.localeCodes.length === 0) {
        return [{ t: 'message', text: MSG_NO_LOCALES }];
      }
      const baseMap = this.state.values.get(this.state.report.baseline);
      if (!baseMap || baseMap.size === 0) {
        return [{ t: 'message', text: MSG_NO_BASELINE_KEYS }];
      }
      // Problems-only restricts to keys that are MISSING or EMPTY somewhere (Kotlin MatrixPanel:104-111).
      const problemKeys = new Set(
        this.state.report.issues.filter((i) => i.status === 'missing' || i.status === 'empty').map((i) => i.key),
      );
      const query = this.state.keyFilter.trim().toLowerCase();
      const keys = [...baseMap.keys()].filter(
        (key) =>
          (!this.state.problemsOnly || problemKeys.has(key)) && (query === '' || key.toLowerCase().includes(query)),
      );
      if (keys.length === 0) {
        return [{ t: 'message', text: MSG_NO_PROBLEM_KEYS }];
      }
      return keys.map((key) => ({ t: 'key', key }));
    }
    // hardcoded
    if (this.state.findings.length === 0) {
      return [{ t: 'message', text: this.state.hasScanned ? MSG_NO_HARDCODED : MSG_HARDCODED_HINT }];
    }
    const byFile = new Map<string, Finding[]>();
    for (const finding of this.state.findings) {
      const list = byFile.get(finding.file) ?? [];
      list.push(finding);
      byFile.set(finding.file, list);
    }
    return [...byFile].map(([file, findings]) => ({ t: 'file', file, findings }));
  }

  getTreeItem(node: LocNode): vscode.TreeItem {
    switch (node.t) {
      case 'summary':
        return summaryItem(node.summary, this.filteredIssues(node.summary.locale).length);
      case 'issue':
        return issueItem(node);
      case 'key':
        return keyItem(node.key, this.state.values.get(this.state.report.baseline)?.get(node.key));
      case 'cell':
        return cellItem(node);
      case 'file':
        return fileItem(node.file, node.findings.length);
      case 'finding':
        return findingItem(node);
      case 'message':
        return new vscode.TreeItem(node.text);
    }
  }
}

function summaryItem(summary: LocaleSummary, filteredCount: number): vscode.TreeItem {
  const label = summary.isBaseline ? `${summary.locale}  ·  baseline` : summary.locale;
  // Expandable only when the current filters leave issues to show, so an expand arrow never opens onto
  // an empty node. The description below still reports the unfiltered health counts.
  const item = new vscode.TreeItem(
    label,
    filteredCount > 0 ? vscode.TreeItemCollapsibleState.Collapsed : vscode.TreeItemCollapsibleState.None,
  );
  if (summary.parseError) {
    item.description = 'parse error';
    item.tooltip = summary.parseError;
    item.iconPath = new vscode.ThemeIcon('error', new vscode.ThemeColor('charts.red'));
    return item;
  }
  item.description = `${summary.percentComplete}%  ·  ${summary.issueCount} issue${summary.issueCount === 1 ? '' : 's'}`;
  item.iconPath = summary.isBaseline
    ? new vscode.ThemeIcon('star-full')
    : summary.issueCount === 0
      ? new vscode.ThemeIcon('pass', new vscode.ThemeColor('charts.green'))
      : new vscode.ThemeIcon('warning', new vscode.ThemeColor('charts.yellow'));
  return item;
}

function issueItem(node: { t: 'issue'; issue: LocaleIssue }): vscode.TreeItem {
  const { issue } = node;
  const item = new vscode.TreeItem(issue.key, vscode.TreeItemCollapsibleState.None);
  item.description = `${STATUS_LABEL[issue.status]}${issue.baseValue ? `  ·  ${truncate(issue.baseValue)}` : ''}`;
  item.tooltip = new vscode.MarkdownString(
    [`**${issue.key}** — ${STATUS_LABEL[issue.status]}`, issue.baseValue != null ? `base: \`${issue.baseValue}\`` : '', issue.currentValue != null ? `current: \`${issue.currentValue}\`` : '']
      .filter(Boolean)
      .join('\n\n'),
  );
  item.iconPath = statusIcon(issue.status);
  if (EDITABLE.has(issue.status)) {
    item.contextValue = 'nyloLocEditable';
    item.command = { command: 'nylo.localizations.editValue', title: 'Edit translation', arguments: [node] };
  } else {
    item.contextValue = 'nyloLocOpen';
    item.command = { command: 'nylo.localizations.openTarget', title: 'Open', arguments: [node] };
  }
  return item;
}

function keyItem(key: string, baseValue: string | undefined): vscode.TreeItem {
  const item = new vscode.TreeItem(key, vscode.TreeItemCollapsibleState.Collapsed);
  item.description = baseValue != null ? truncate(baseValue) : '';
  item.iconPath = new vscode.ThemeIcon('key');
  return item;
}

function cellItem(node: { t: 'cell'; key: string; locale: string; value: string | undefined }): vscode.TreeItem {
  const item = new vscode.TreeItem(node.locale, vscode.TreeItemCollapsibleState.None);
  // Distinguish a missing key (no binding at all) from an empty value (present but blank), matching
  // the Kotlin matrix cell renderer (null → missing/red, blank → empty/orange).
  if (node.value === undefined) {
    item.description = '(missing)';
    item.iconPath = new vscode.ThemeIcon('error', new vscode.ThemeColor('charts.red'));
  } else if (node.value.trim().length === 0) {
    item.description = '(empty)';
    item.iconPath = new vscode.ThemeIcon('warning', new vscode.ThemeColor('charts.yellow'));
  } else {
    item.description = truncate(node.value);
    item.iconPath = new vscode.ThemeIcon('pass', new vscode.ThemeColor('charts.green'));
  }
  item.contextValue = 'nyloLocEditable';
  item.command = { command: 'nylo.localizations.editValue', title: 'Edit translation', arguments: [node] };
  return item;
}

function fileItem(file: string, count: number): vscode.TreeItem {
  const item = new vscode.TreeItem(file, vscode.TreeItemCollapsibleState.Collapsed);
  item.description = `${count}`;
  item.iconPath = new vscode.ThemeIcon('file-code');
  item.resourceUri = vscode.Uri.file(file);
  return item;
}

function findingItem(node: { t: 'finding'; finding: Finding }): vscode.TreeItem {
  const item = new vscode.TreeItem(node.finding.value || '(empty)', vscode.TreeItemCollapsibleState.None);
  item.description = `${node.finding.context}  ·  :${node.finding.line}`;
  item.iconPath = new vscode.ThemeIcon('symbol-string');
  item.command = { command: 'nylo.localizations.openTarget', title: 'Open', arguments: [node] };
  return item;
}

function statusIcon(status: KeyStatus): vscode.ThemeIcon {
  switch (status) {
    case 'missing':
      return new vscode.ThemeIcon('error', new vscode.ThemeColor('charts.red'));
    case 'empty':
      return new vscode.ThemeIcon('warning', new vscode.ThemeColor('charts.yellow'));
    case 'extra':
      return new vscode.ThemeIcon('trash', new vscode.ThemeColor('charts.orange'));
    case 'same_as_base':
      return new vscode.ThemeIcon('copy', new vscode.ThemeColor('charts.blue'));
    default:
      return new vscode.ThemeIcon('pass', new vscode.ThemeColor('charts.green'));
  }
}

function statusEnabled(status: KeyStatus, showMissing: boolean, showEmpty: boolean, showExtra: boolean): boolean {
  switch (status) {
    case 'missing':
      return showMissing;
    case 'empty':
      return showEmpty;
    case 'extra':
      return showExtra;
    case 'same_as_base':
      return true; // only present when same-as-base flagging is on; always shown when it is
    default:
      return false; // translated is never emitted as an issue
  }
}

function issueMatchesQuery(issue: LocaleIssue, query: string): boolean {
  return (
    issue.key.toLowerCase().includes(query) ||
    (issue.baseValue?.toLowerCase().includes(query) ?? false) ||
    (issue.currentValue?.toLowerCase().includes(query) ?? false)
  );
}

function truncate(text: string, max = 60): string {
  const flat = text.replace(/\s+/g, ' ').trim();
  return flat.length <= max ? flat : `${flat.slice(0, max - 1)}…`;
}

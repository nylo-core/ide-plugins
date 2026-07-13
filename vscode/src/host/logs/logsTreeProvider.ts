import * as vscode from 'vscode';
import { LogDocument, LogEntry, LogSession, NetworkEntry } from '../../core/logs/logModel';

/**
 * Renders a parsed+filtered {@link LogDocument} as a tree: sessions → entries, where a network entry
 * expands to its request/response detail lines (the native equivalent of the JetBrains foldable
 * network blocks).
 */
export type LogNode =
  | { kind: 'session'; session: LogSession }
  | { kind: 'entry'; entry: LogEntry; session?: LogSession }
  | { kind: 'detail'; line: string; key: string; entry: LogEntry; session?: LogSession };

export class LogsTreeProvider implements vscode.TreeDataProvider<LogNode> {
  private readonly emitter = new vscode.EventEmitter<LogNode | undefined>();
  readonly onDidChangeTreeData = this.emitter.event;
  private doc: LogDocument = { preamble: [], sessions: [] };

  setDocument(doc: LogDocument): void {
    this.doc = doc;
    this.emitter.fire(undefined);
  }

  getDocument(): LogDocument {
    return this.doc;
  }

  getChildren(element?: LogNode): LogNode[] {
    if (!element) {
      const roots: LogNode[] = this.doc.preamble.map((entry): LogNode => ({ kind: 'entry', entry }));
      for (const session of this.doc.sessions) {
        roots.push({ kind: 'session', session });
      }
      return roots;
    }
    if (element.kind === 'session') {
      return element.session.entries.map(
        (entry): LogNode => ({ kind: 'entry', entry, session: element.session }),
      );
    }
    if (element.kind === 'entry' && element.entry.entryType === 'network') {
      return element.entry.raw
        .split('\n')
        .slice(1)
        .map(
          (line, i): LogNode => ({
            kind: 'detail',
            line,
            key: `${element.entry.lineStart}:${i}`,
            entry: element.entry,
            session: element.session,
          }),
        );
    }
    return [];
  }

  /** Required for `TreeView.reveal` (used by Follow's scroll-to-newest). */
  getParent(element: LogNode): LogNode | undefined {
    if (element.kind === 'entry') {
      return element.session ? { kind: 'session', session: element.session } : undefined;
    }
    if (element.kind === 'detail') {
      return { kind: 'entry', entry: element.entry, session: element.session };
    }
    return undefined;
  }

  /**
   * The node where new lines land while tailing: the last entry of the first session under
   * newest-first (the growing session renders first, its entries stay chronological) or of the
   * last session under oldest-first. Falls back to the session banner / last preamble entry.
   */
  newestEntryNode(sort: 'newest' | 'oldest'): LogNode | undefined {
    const sessions = this.doc.sessions;
    const target = sort === 'newest' ? sessions[0] : sessions[sessions.length - 1];
    if (target) {
      const last = target.entries[target.entries.length - 1];
      return last ? { kind: 'entry', entry: last, session: target } : { kind: 'session', session: target };
    }
    const lastPreamble = this.doc.preamble[this.doc.preamble.length - 1];
    return lastPreamble ? { kind: 'entry', entry: lastPreamble } : undefined;
  }

  getTreeItem(element: LogNode): vscode.TreeItem {
    switch (element.kind) {
      case 'session':
        return sessionItem(element.session);
      case 'entry':
        return entryItem(element.entry);
      case 'detail':
        return detailItem(element.line);
    }
  }
}

function sessionItem(session: LogSession): vscode.TreeItem {
  const item = new vscode.TreeItem(
    session.tag === '-' ? 'session' : session.tag,
    vscode.TreeItemCollapsibleState.Expanded,
  );
  // Stable identity (source line is unique per node) so reveal() can resolve fresh node objects.
  item.id = `s:${session.lineStart}`;
  item.description = [session.startedRaw, session.env, session.app].filter((x) => x && x !== '-').join('  ·  ');
  item.tooltip = session.bannerLines.join('\n');
  item.iconPath = new vscode.ThemeIcon('history');
  item.contextValue = 'nyloLogSession';
  return item;
}

function entryItem(entry: LogEntry): vscode.TreeItem {
  if (entry.entryType === 'network') {
    const hasDetail = entry.raw.includes('\n');
    const item = new vscode.TreeItem(
      entry.summary,
      hasDetail ? vscode.TreeItemCollapsibleState.Collapsed : vscode.TreeItemCollapsibleState.None,
    );
    item.id = `e:${entry.lineStart}`;
    item.tooltip = new vscode.MarkdownString().appendCodeblock(entry.raw, 'text');
    item.iconPath = networkIcon(entry);
    item.contextValue = 'nyloLogEntry';
    return item;
  }
  if (entry.entryType === 'standard') {
    const item = new vscode.TreeItem(entry.message || firstLine(entry.raw), vscode.TreeItemCollapsibleState.None);
    item.id = `e:${entry.lineStart}`;
    item.description = [entry.timestampRaw, entry.level].filter(Boolean).join(' ');
    item.tooltip = new vscode.MarkdownString().appendCodeblock(entry.raw, 'text');
    item.iconPath = levelIcon(entry.level);
    item.contextValue = 'nyloLogEntry';
    return item;
  }
  const item = new vscode.TreeItem(firstLine(entry.raw) || ' ', vscode.TreeItemCollapsibleState.None);
  item.id = `e:${entry.lineStart}`;
  item.tooltip = entry.raw;
  item.contextValue = 'nyloLogEntry';
  return item;
}

function detailItem(line: string): vscode.TreeItem {
  const item = new vscode.TreeItem(line.length === 0 ? ' ' : line, vscode.TreeItemCollapsibleState.None);
  item.tooltip = line;
  return item;
}

function networkIcon(entry: NetworkEntry): vscode.ThemeIcon {
  if (entry.netKind === 'request') {
    return new vscode.ThemeIcon('arrow-small-right');
  }
  if (entry.netKind === 'error') {
    return new vscode.ThemeIcon('error', new vscode.ThemeColor('charts.red'));
  }
  if (entry.statusCode != null && entry.statusCode >= 200 && entry.statusCode <= 299) {
    return new vscode.ThemeIcon('pass', new vscode.ThemeColor('charts.green'));
  }
  return new vscode.ThemeIcon('warning', new vscode.ThemeColor('charts.yellow'));
}

function levelIcon(level: string | null): vscode.ThemeIcon | undefined {
  const l = level?.toLowerCase();
  if (l === 'error' || l === 'err' || l === 'severe' || l === 'fatal' || l === 'emergency') {
    return new vscode.ThemeIcon('error', new vscode.ThemeColor('charts.red'));
  }
  if (l === 'warn' || l === 'warning' || l === 'alert') {
    return new vscode.ThemeIcon('warning', new vscode.ThemeColor('charts.yellow'));
  }
  return undefined;
}

function firstLine(text: string): string {
  const idx = text.indexOf('\n');
  return idx < 0 ? text : text.slice(0, idx);
}

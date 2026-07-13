import { LogCategory, matchesCategory } from './logCategory';
import { LogDocument, LogSession } from './logModel';

/** Port of `dev.nylo.plugin.logs.filter.LogQuery` + `LogFilter`. */

export type SortOrder = 'newest' | 'oldest';

export interface LogQuery {
  sessionTag?: string | null;
  text?: string | null;
  sort?: SortOrder;
  category?: LogCategory;
}

export function applyFilter(doc: LogDocument, query: LogQuery): LogDocument {
  let sessions = doc.sessions;
  let preamble = doc.preamble;
  const sort = query.sort ?? 'newest';
  const category = query.category ?? 'all';

  // Session filter — accepts the short tag or a full banner id (suffix after the last '-').
  const rawTag = query.sessionTag?.trim();
  const tag = rawTag ? substringAfterLast(rawTag, '-') : null;
  if (tag) {
    const lower = tag.toLowerCase();
    sessions = sessions.filter((s) => s.tag.toLowerCase() === lower);
    preamble = [];
  }

  // Category (tab) filter — keep entries in the active tab; drop sessions left with none.
  if (category !== 'all') {
    sessions = sessions
      .map((s) => ({ ...s, entries: s.entries.filter((e) => matchesCategory(e, category)) }))
      .filter((s) => s.entries.length > 0);
    preamble = preamble.filter((e) => matchesCategory(e, category));
  }

  // Text filter — keep sessions with a match; narrow them to the matching entries.
  const text = query.text?.trim();
  if (text) {
    sessions = sessions
      .map((s) => narrowToText(s, text))
      .filter((s): s is LogSession => s !== null);
    preamble = preamble.filter((e) => containsIgnoreCase(e.raw, text));
  }

  sessions = [...sessions].sort((a, b) => {
    if (a.started === null && b.started === null) {
      return 0;
    }
    if (a.started === null) {
      return 1; // nulls last
    }
    if (b.started === null) {
      return -1;
    }
    return sort === 'newest' ? b.started - a.started : a.started - b.started;
  });

  return { preamble, sessions };
}

function narrowToText(session: LogSession, text: string): LogSession | null {
  const matched = session.entries.filter((e) => containsIgnoreCase(e.raw, text));
  if (matched.length > 0) {
    return { ...session, entries: matched };
  }
  if (session.bannerLines.some((l) => containsIgnoreCase(l, text))) {
    return { ...session, entries: [] };
  }
  return null;
}

function containsIgnoreCase(haystack: string, needle: string): boolean {
  return haystack.toLowerCase().includes(needle.toLowerCase());
}

function substringAfterLast(value: string, delimiter: string): string {
  const idx = value.lastIndexOf(delimiter);
  return idx < 0 ? value : value.slice(idx + delimiter.length);
}

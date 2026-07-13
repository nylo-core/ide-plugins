import { NyloPage } from './model';

/**
 * Case-insensitive page search, ported from `ScreenshotStudioPanel.filteredPages`
 * (`ScreenshotStudioPanel.kt:286-294`). An empty/blank query returns every page; otherwise a page
 * matches when the (lowercased) query is a substring of its display name, route, or class name.
 */
export function filterPages(pages: NyloPage[], query: string): NyloPage[] {
  const q = query.trim().toLowerCase();
  if (q.length === 0) {
    return pages;
  }
  return pages.filter(
    (p) =>
      p.displayName.toLowerCase().includes(q) ||
      p.route.toLowerCase().includes(q) ||
      p.className.toLowerCase().includes(q),
  );
}

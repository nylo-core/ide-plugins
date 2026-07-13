import { NyloPage } from './model';

/**
 * Port of `dev.nylo.plugin.screenshots.project.RouterParser` (pure half).
 *
 * The router lists routes as `router.add(XxxPage.path)` (optionally chained with
 * `.authenticatedRoute()`). Each `XxxPage` declares its route string in its own source as
 * `static RouteView path = ("/x", ...)`, so {@link scanPageRoutes} resolves class → route from the
 * `lib/**` Dart sources and {@link parseRouter} applies it.
 */

const ADD_LINE = /router\s*\.\s*add\s*\(\s*(\w+)\s*\.\s*path/;
const AUTH = /\.authenticatedRoute\s*\(/;
const CLASS_DECL = /class\s+(\w+)/g;
const PATH_DECL = /static\s+RouteView\s+path\s*=\s*\(\s*["']([^"']+)["']/g;

/** Builds `XxxPage` → `/x` from every Dart source that declares a `RouteView path`. */
export function scanPageRoutes(dartTexts: string[]): Map<string, string> {
  const out = new Map<string, string>();
  for (const text of dartTexts) {
    if (!text.includes('RouteView path')) {
      continue;
    }
    const classes = [...text.matchAll(CLASS_DECL)].map((m) => ({ name: m[1], index: m.index ?? 0 }));
    for (const pathMatch of text.matchAll(PATH_DECL)) {
      const route = pathMatch[1];
      const owning = classes.filter((c) => c.index < (pathMatch.index ?? 0)).pop();
      if (owning && !out.has(owning.name)) {
        out.set(owning.name, route);
      }
    }
  }
  return out;
}

export function parseRouter(routerText: string, classToRoute: Map<string, string>): NyloPage[] {
  const pages = new Map<string, NyloPage>();
  for (const raw of routerText.split('\n')) {
    const line = raw.trim();
    if (line.startsWith('//')) {
      continue;
    }
    const match = ADD_LINE.exec(line);
    if (!match) {
      continue;
    }
    const className = match[1];
    if (pages.has(className)) {
      continue;
    }
    const resolved = classToRoute.get(className);
    pages.set(className, {
      className,
      route: resolved ?? deriveRoute(className),
      displayName: pageDisplayName(className),
      authenticated: AUTH.test(line),
      routeResolved: resolved !== undefined,
    });
  }
  return [...pages.values()];
}

function deriveRoute(className: string): string {
  return '/' + stripSuffix(className, 'Page').replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase();
}

function pageDisplayName(className: string): string {
  return stripSuffix(className, 'Page').replace(/([a-z0-9])([A-Z])/g, '$1 $2').trim();
}

function stripSuffix(value: string, suffix: string): string {
  return value.endsWith(suffix) ? value.slice(0, value.length - suffix.length) : value;
}

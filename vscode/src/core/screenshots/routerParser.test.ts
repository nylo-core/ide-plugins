import * as assert from 'assert';
import { parseRouter, scanPageRoutes } from './routerParser';

describe('routerParser', () => {
  it('scanPageRoutes maps class to its RouteView path', () => {
    const dart = [
      'class HomePage extends NyStatefulWidget {',
      '  static RouteView path = ("/home", (_) => HomePage());',
      '}',
    ].join('\n');
    assert.strictEqual(scanPageRoutes([dart]).get('HomePage'), '/home');
  });

  it('parseRouter resolves, derives, and flags authenticated routes', () => {
    const router = [
      'router.add(HomePage.path);',
      'router.add(DashboardPage.path).authenticatedRoute();',
      '// router.add(CommentedPage.path);',
      'router.add(SettingsPage.path);',
    ].join('\n');
    const classToRoute = new Map([
      ['HomePage', '/home'],
      ['DashboardPage', '/dashboard'],
    ]);
    const pages = parseRouter(router, classToRoute);

    assert.deepStrictEqual(pages.map((p) => p.className), ['HomePage', 'DashboardPage', 'SettingsPage']);
    assert.strictEqual(pages[0].route, '/home');
    assert.ok(pages[0].routeResolved);
    assert.ok(!pages[0].authenticated);
    assert.ok(pages[1].authenticated);
    assert.strictEqual(pages[2].route, '/settings');
    assert.ok(!pages[2].routeResolved);
    assert.strictEqual(pages[2].displayName, 'Settings');
  });

  it('derives a kebab route and a spaced display name from camel case', () => {
    const pages = parseRouter('router.add(UserProfilePage.path);', new Map());
    assert.strictEqual(pages[0].route, '/user-profile');
    assert.strictEqual(pages[0].displayName, 'User Profile');
  });

  it('dedupes repeated pages', () => {
    const pages = parseRouter('router.add(HomePage.path);\nrouter.add(HomePage.path);', new Map());
    assert.strictEqual(pages.length, 1);
  });
});

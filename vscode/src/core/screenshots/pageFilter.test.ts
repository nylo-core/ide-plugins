import * as assert from 'assert';
import { NyloPage } from './model';
import { filterPages } from './pageFilter';

function page(className: string, route: string, displayName: string): NyloPage {
  return { className, route, displayName, authenticated: false, routeResolved: true };
}

describe('screenshots/pageFilter', () => {
  const pages = [
    page('HomePage', '/home', 'Home'),
    page('UserProfilePage', '/user-profile', 'User Profile'),
    page('SettingsPage', '/settings', 'Settings'),
  ];

  it('returns every page for a blank query', () => {
    assert.deepStrictEqual(filterPages(pages, ''), pages);
    assert.deepStrictEqual(filterPages(pages, '   '), pages);
  });

  it('matches case-insensitively on the display name', () => {
    assert.deepStrictEqual(
      filterPages(pages, 'profile').map((p) => p.className),
      ['UserProfilePage'],
    );
  });

  it('matches on route and on class name', () => {
    assert.deepStrictEqual(
      filterPages(pages, '/settings').map((p) => p.className),
      ['SettingsPage'],
    );
    assert.deepStrictEqual(
      filterPages(pages, 'homepage').map((p) => p.className),
      ['HomePage'],
    );
  });

  it('returns an empty list when nothing matches', () => {
    assert.deepStrictEqual(filterPages(pages, 'zzz'), []);
  });
});

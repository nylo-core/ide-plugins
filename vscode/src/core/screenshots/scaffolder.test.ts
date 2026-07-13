import * as assert from 'assert';
import { NyloPage } from './model';
import { SCREENSHOTS_CONFIG_PATH, screenshotsConfigTemplate } from './scaffolder';

function page(route: string, displayName: string): NyloPage {
  return { className: `${displayName}Page`, route, displayName, authenticated: false, routeResolved: true };
}

describe('screenshots/scaffolder', () => {
  it('targets lib/config/screenshots.dart', () => {
    assert.strictEqual(SCREENSHOTS_CONFIG_PATH, 'lib/config/screenshots.dart');
  });

  it('emits the config class with register/seed and a commented stub per route', () => {
    const out = screenshotsConfigTemplate([page('/home', 'Home'), page('/settings', 'Settings')]);

    assert.ok(out.includes("import 'package:nylo_framework/nylo_framework.dart';"));
    assert.ok(out.includes('class ScreenshotsConfig'));
    assert.ok(out.includes('static void register() => NyScreenshots.register('));
    assert.ok(out.includes('dataForRoute: {'));
    assert.ok(out.includes('seed: () async {'));
    assert.ok(out.includes("// '/home': () => null, // Home"));
    assert.ok(out.includes("// '/settings': () => null, // Settings"));
  });

  it('still produces a valid (empty) dataForRoute map for no pages', () => {
    const out = screenshotsConfigTemplate([]);
    assert.ok(out.includes('dataForRoute: {'));
    assert.ok(out.includes('class ScreenshotsConfig'));
  });
});

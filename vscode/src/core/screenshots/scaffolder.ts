import { NyloPage } from './model';

/**
 * Port of `dev.nylo.plugin.screenshots.scaffold.ScreenshotsConfigScaffolder.template`. The generated
 * `lib/config/screenshots.dart` is the one piece the GUI can't own — per-route `data` objects and auth
 * seeding are real Dart.
 */
export const SCREENSHOTS_CONFIG_PATH = 'lib/config/screenshots.dart';

export function screenshotsConfigTemplate(pages: NyloPage[]): string {
  const stubs = pages.map((p) => `          // '${p.route}': () => null, // ${p.displayName}`).join('\n');
  return `import 'package:nylo_framework/nylo_framework.dart';

/// Screenshot Studio configuration.
///
/// Call [register] from your \`main()\` BEFORE \`Nylo.init(...)\`:
///
/// \`\`\`dart
/// void main() async {
///   ScreenshotsConfig.register();
///   await Nylo.init(setup: Boot.nylo());
/// }
/// \`\`\`
///
/// The "Nylo Screenshots" view supplies the routes, locales and target device at
/// launch. Here you only provide what the GUI can't: the \`data\` object some pages
/// expect (as in \`routeTo(route, data: x)\`), and a [seed] hook to set up auth/state
/// so guarded and data-driven pages render realistically.
class ScreenshotsConfig {
  static void register() => NyScreenshots.register(
        // Provide a \`data\` builder for any route whose page needs one.
        // Uncomment + fill in the routes you need:
        dataForRoute: {
${stubs}
        },
        // Runs once before the first capture — seed an authenticated user or any
        // state your screenshots should display:
        seed: () async {
          // e.g. await Auth.set(User(name: 'Jane Doe'));
        },
      );
}
`;
}

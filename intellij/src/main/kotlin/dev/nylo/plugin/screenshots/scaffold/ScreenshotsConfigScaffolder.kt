package dev.nylo.plugin.screenshots.scaffold

import com.intellij.openapi.project.Project
import dev.nylo.plugin.screenshots.model.NyloPage
import java.io.File

/**
 * Writes the app-side `lib/config/screenshots.dart` — the one piece the GUI can't
 * own because per-route `data` objects and auth seeding are real Dart. Generated
 * with a commented stub per route for the developer to fill in.
 */
object ScreenshotsConfigScaffolder {
    fun targetFile(project: Project): File? =
        project.basePath?.let { File(it, "lib/config/screenshots.dart") }

    fun exists(project: Project): Boolean = targetFile(project)?.isFile == true

    fun write(project: Project, pages: List<NyloPage>): File? {
        val target = targetFile(project) ?: return null
        target.parentFile?.mkdirs()
        target.writeText(template(pages))
        return target
    }

    private fun template(pages: List<NyloPage>): String {
        val stubs = pages.joinToString("\n") { p ->
            "          // '${p.route}': () => null, // ${p.displayName}"
        }
        return """import 'package:nylo_framework/nylo_framework.dart';

/// Screenshot Studio configuration.
///
/// Call [register] from your `main()` BEFORE `Nylo.init(...)`:
///
/// ```dart
/// void main() async {
///   ScreenshotsConfig.register();
///   await Nylo.init(setup: Boot.nylo());
/// }
/// ```
///
/// The Android Studio "Nylo Screenshots" tool window supplies the routes,
/// locales and target device at launch. Here you only provide what the GUI
/// can't: the `data` object some pages expect (as in `routeTo(route, data: x)`),
/// and a [seed] hook to set up auth/state so guarded and data-driven pages
/// render realistically.
class ScreenshotsConfig {
  static void register() => NyScreenshots.register(
        // Provide a `data` builder for any route whose page needs one.
        // Uncomment + fill in the routes you need:
        dataForRoute: {
$stubs
        },
        // Runs once before the first capture — seed an authenticated user or any
        // state your screenshots should display:
        seed: () async {
          // e.g. await Auth.set(User(name: 'Jane Doe'));
        },
      );
}
"""
    }
}

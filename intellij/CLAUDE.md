# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A JetBrains/IntelliJ Platform plugin (Kotlin) that gives [Nylo](https://nylo.dev) Flutter
projects first-class support in **Android Studio**. It ships four feature areas: `.env*` →
Flutter run-config / Metro external-tool sync, and three tool windows — **Nylo Logs**,
**Nylo Screenshots**, and **Nylo Localizations**. Published to the JetBrains Marketplace.

Stack: Kotlin 2.2.0, Java 21, Gradle 9, IntelliJ Platform Gradle Plugin 2.16.0.

## Build & test

```bash
./gradlew test                  # unit tests (plain JUnit4, no IDE fixture — see below)
./gradlew test --tests "dev.nylo.plugin.logs.parse.LogParserTest"          # single class
./gradlew test --tests "*.LogParserTest.parses*"                           # single method — names are backtick strings with spaces, so wildcard the prefix
./gradlew buildPlugin           # → build/distributions/Nylo-<version>.zip
./gradlew runIde                # boots a sandboxed Android Studio with the plugin
./gradlew verifyPlugin          # JetBrains Plugin Verifier
```

### Local setup is mandatory before the first build

Without `local.properties`, resolving the IntelliJ Platform triggers a **multi-GB Android
Studio download that takes ~2h and then fails** (no AS installers repo is declared).
`local.properties` (gitignored) is already present on this machine and points the build at the
installed IDE + build-matched Flutter/Dart plugins via `localIdePath` / `localFlutterPlugin` /
`localDartPlugin` / `localLsp4ijPlugin`. With it, a clean run is ~1 min. `build.gradle.kts`
reads these keys and uses `local(...)` / `localPlugin(...)` instead of `create(...)` / `plugins(...)`;
absent them it falls back to the CI path (Marketplace downloads). **Versions are build-specific**
(currently AS build 261: flutter-intellij v93, Dart v506, lsp4ij 0.20.1) — re-point after an AS update.

Local plugin dirs are **mirrored** into `.gradle/local-plugin-mirrors/` excluding `jxbrowser/`
(the live flutter-intellij dir holds unix sockets Gradle can't snapshot). Never point
`localPlugin` at a live install dir; if a new runtime-state dir breaks snapshotting, extend the
exclude list in `build.gradle.kts` (`mirrorLocalPlugin`).

### runIde / verification gotchas

- **Fully quit any open sandbox IDE first.** With one running, `runIde` hot-reloads into it, and
  `postStartupActivity` + file watchers do **not** re-arm — the on-open sync appears to do nothing.
- **Run configs persist only on a clean exit** (frame-deactivation / proper quit serializes to the
  project's `.idea/workspace.xml`). A `kill`/SIGTERM does not flush them — don't conclude "configs
  don't persist" from a killed session.
- Sandbox log: `.intellijPlatform/sandbox/nylo-plugin/<build>/log/idea.log`. Per-project plugin
  state lands in the opened project's `.idea/nylo.xml`.

Tests are plain **JUnit4 over pure logic** — no IntelliJ test fixture. Keep it that way: put
IDE-free decision/parse logic in objects that tests can call directly (see the architecture split below).

### Version targets

`gradle.properties` sets `pluginSinceBuild=243` (AS 2024.3) and `pluginUntilBuild=261.*`. Compile
target is build 243 (`platformVersion=2024.3.2.15`) for API safety, but the installed/runIde IDE is
build 261 — `pluginUntilBuild` must cover the running IDE or the plugin is silently **disabled** even
in runIde. `instrumentCode` and `buildSearchableOptions` are deliberately **off** (faster builds; the
former also avoids an instrumentation-task failure against the local build-261 IDE).

## Architecture

### Everything is gated on Nylo-project detection

`NyloProjectDetector.isNyloProject` returns true iff the project's `pubspec.yaml` has a top-level
`nylo_framework:` line. Every entry point checks this first and no-ops otherwise. Entry points
(registered in `src/main/resources/META-INF/plugin.xml`):
- `startup/NyloProjectActivity` — `postStartupActivity`, runs the env sync on project open.
- `env/EnvFileWatcher` + `localizations/watch/LangFileWatcher` — VFS `BulkFileListener`s for live sync.
- Three `toolWindow`s (Logs / Screenshots / Localizations), each with a `ToolWindowFactory`.
- `actions/SyncNyloEnvironmentsAction` — Tools → Sync Nylo Environments.

### The core pattern: pure compute vs IDE mutation, split across threads

This is the single most important convention and it recurs in every subsystem. Filesystem/scan/parse
work is **pure and IDE-independent** (unit-testable, safe off the EDT); anything touching
`RunManager` / `ToolManager` / IntelliJ `Document`s **must run on the EDT**, and some mutations
additionally need a **write action**. The idiom:

- Compute a plan off the EDT, apply it on the EDT. `EnvSyncService.computePlan()` (background:
  detect + scan) returns a `Plan`; `applyPlan()` (EDT) mutates RunManager/ToolManager, with the
  run-config reconcile wrapped in `runWriteAction {}` (`addConfiguration` silently no-ops without the
  write lock on modern platforms). `NyloProjectActivity` runs compute off-EDT then `withContext(Dispatchers.EDT)`;
  EDT-bound callers (action, watcher) use `computeAndApplyOnEdt`.
- Pure decision logic lives in standalone objects: `runconfig/EnvConfigReconciler` (ownership diff),
  `env/EnvFileScanner` + `env/EnvFileNaming`, `logs/parse/LogParser`, `localizations/**` compare/json,
  `screenshots/project/{RouterParser,LocaleScanner}`. These have direct unit tests and no IDE imports.
- `LocalizationService` mirrors the shape: `refreshAsync()` reads/flattens `lang/*.json` off the EDT,
  caches in-memory maps, then `publish()`es on the EDT via a `messageBus` topic
  (`LocalizationDataListener`). Panels react to the topic; they never read disk themselves.

When adding IDE-mutating code, follow this split — don't do scans on the EDT (triggers "slow
operations on EDT" warnings) and don't mutate RunManager/Documents off it.

### State (project-level persistence)

`state/NyloPluginState` — **project-level** (`@Service(PROJECT)`, `nylo.xml`), a
`SimplePersistentStateComponent`. Holds the env-sync **ownership map** (`.env` file name → generated
config display name) plus persisted tool-window UI state (log sort/follow/category, localization
filters, screenshot selection).

### Env-sync ownership model

Run configs and Metro external tools are tracked by **env file name, not display name**, so renames
and naming-scheme changes don't lose track. The policy is **additive**: configs/tools the plugin
doesn't own are never overwritten or removed. External tools are **IDE-global**, so orphan cleanup
consults other open projects' ownership sets before deleting a shared tool (`EnvSyncService.removableTools`).
Naming: `.env.prod.staging` → `Prod Staging`, bare `.env` → `Default` (`EnvFileNaming`).

### Nylo Logs — an NDJSON contract shared with `nylo_support`

Log files are **NDJSON** (one JSON object per line), written by `nylo_support`'s `NyFileLogger`
(a separate repo at `~/StudioProjects/support`) and read by `logs/parse/LogParser` here. The two
repos share the record schema and **must change together**. Record types: `session`, `log`,
`console`, `net`. The parser tolerates field aliases (`type`/`timestamp`/`message`), ISO-8601 with
ms **or µs**, and reconstructs human-readable display text into each entry's `raw` so the UI renders
as plain text. The contract is guarded by `LogParserFrameworkSampleTest` (real emitted lines) —
update that fixture when the schema changes. There is **no** backward compatibility with the old
plaintext log format.

### External-process integrations

Several features shell out. All go through small runners, and captured/streamed output is assembled
line-by-line via `screenshots/run/LineBuffer`:
- **Screenshots**: `flutter run --dart-define=NYLO_SCREENSHOT=true …`, watched for `__NYLO_SHOT_*__`
  stdout markers; captures via `xcrun simctl io` (iOS) / `adb exec-out screencap` (Android).
  `ScreenshotOrchestrator` is the driver.
- **Nylo CLI** (`NyloCliLocator`, `FindUntranslatedRunner`) for localization scans.

## This directory is the `intellij/` plugin in a monorepo

This plugin lives at `intellij/` inside the `nylo-core/ide-plugins` monorepo. Build/test commands
here assume `intellij/` as the working directory (`cd intellij && ./gradlew test`). See the
repo-root `CLAUDE.md` for the one cross-plugin rule: the Logs NDJSON schema, `.env` naming, and
router/locale parsing are shared **contracts** ported into the VS Code plugin, and Kotlin here is
the reference spec — change both sides together.

## Related repos

- `nylo_support` (`~/StudioProjects/support`) — the Flutter framework; owns the log NDJSON writer.
- `../vscode` — the sibling VS Code extension in this monorepo (TypeScript). Its `core/` package ports
  the pure Kotlin logic (scanner/naming, LogParser, LangJson, router/device parsing) near-1:1; keep the
  Kotlin source here as the reference spec when working there.

Note: `README.md`'s "Repository layout" section predates the Logs/Screenshots/Localizations tool
windows and describes only the original env-sync feature.

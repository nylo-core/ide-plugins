# Nylo for Android Studio

A JetBrains plugin that gives [Nylo](https://nylo.dev) Flutter projects first-class IDE support
in Android Studio. The first feature auto-detects your `.env*` files and generates matching
Flutter run configurations and Metro `make:env` external tools — so switching between
environments is one click instead of manual XML editing.

## Features

- **`.env*` auto-detection** — scans your project root for `.env`, `.env.dev`, `.env.prod`,
  `.env.prod.staging`, `.env.valet`, etc. (excluding `.env-example`).
- **One Flutter run configuration per env** — named after the suffix in title case
  (`.env.prod.staging` → `Prod Staging`). The bare `.env` becomes `Default`.
- **Metro `make:env` external tools** — added to *Tools → External Tools* with the same
  naming convention you would use by hand.
- **Auto-sync on project open** — runs after the IDE has fully loaded the project.
- **Manual *Tools → Sync Nylo Environments*** — re-run the sync any time.
- **Orphan removal** — if you rename `.env.valet` to `.env.staging`, the next sync removes
  the now-stale `Valet` configuration and creates `Staging`.
- **Balloon notifications** — only when something actually changed.

## How a Nylo project is identified

The plugin scans the project's `pubspec.yaml` for a top-level `nylo_framework:` dependency.
If it isn't there, the plugin stays out of the way.

## Requirements

| | Version |
|---|---|
| Android Studio | Ladybug 2024.3 (build 243) or newer |
| Flutter plugin | required (declared via `<depends>` in `plugin.xml`) |
| Dart plugin | bundled with Android Studio |
| Nylo framework | 5.x or newer (project must have a `pubspec.yaml` listing `nylo_framework`) |

## Building from source

```bash
./gradlew buildPlugin     # → build/distributions/Nylo-<version>.zip
./gradlew runIde          # boots a sandboxed Android Studio with the plugin installed
./gradlew test            # unit tests
./gradlew verifyPlugin    # JetBrains Plugin Verifier
```

The first run downloads the IntelliJ Platform IDE that the build is configured to compile
against (~1 GB).

## Repository layout

```
src/main/kotlin/dev/nylo/plugin/
├── project/      # Nylo project detection (pubspec.yaml regex)
├── env/          # .env* scanner + display-name logic + sync orchestrator
├── runconfig/    # Flutter run configuration generator (RunManager + reflection)
├── externaltools/# Metro make:env External Tool generator (ToolManager)
├── state/        # PersistentStateComponent — tracks plugin-managed configs
├── actions/      # Tools → Sync Nylo Environments
├── startup/      # ProjectActivity — auto-sync on project open
└── ui/           # Balloon notifications
```

## Contributing

Pull requests welcome. See [CHANGELOG.md](CHANGELOG.md) for the list of changes.

## License

[MIT](LICENSE)

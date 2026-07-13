# Changelog

All notable changes to the Nylo Android Studio plugin will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - TBD

### Added

- Initial release.
- Auto-detects `.env*` files (excluding `.env-example`) in projects whose `pubspec.yaml` lists `nylo_framework`.
- Generates a Flutter run configuration per environment (e.g. `Dev`, `Prod`, `Prod Staging`).
- Generates matching `Metro make:env <Name>` external tools as before-run tasks.
- Project-open auto-sync plus a manual **Tools → Sync Nylo Environments** action.
- Removes orphaned run configurations when their `.env` file is deleted (full sync).
- Balloon notification summarising configurations created or removed.

[Unreleased]: https://github.com/nylo-core/nylo-plugin/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/nylo-core/nylo-plugin/releases/tag/v0.1.0

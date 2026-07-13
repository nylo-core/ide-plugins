# Nylo IDE Plugins

First-class [Nylo](https://nylo.dev) support for the IDEs the team ships for, maintained together
in one repository so shared behaviour stays in lock-step.

| Directory | Platform | Language | Marketplace |
|-----------|----------|----------|-------------|
| [`intellij/`](intellij/) | IntelliJ Platform / Android Studio | Kotlin (Gradle) | JetBrains Marketplace |
| [`vscode/`](vscode/) | Visual Studio Code | TypeScript (esbuild) | VS Code Marketplace |

Both plugins deliver the same feature set against Nylo Flutter projects: `.env*` → Flutter
run-config / Metro external-tool sync, plus the **Logs**, **Screenshots**, and **Localizations**
tool windows.

## Why a monorepo

The two implementations can't share source (Kotlin vs TypeScript), but they **do** share
contracts — the Nylo Logs NDJSON schema, the `.env` naming/ownership rules, and the router/locale
parsing logic are ported between them near 1:1. Keeping them side by side means a contract change
lands as a single reviewed commit instead of drifting across two repos. **The Kotlin source in
`intellij/` is the reference spec; the VS Code port follows it.**

## Getting started

### IntelliJ / Android Studio plugin

```bash
cd intellij
./gradlew test          # unit tests
./gradlew buildPlugin    # → build/distributions/Nylo-<version>.zip
```

A gitignored `local.properties` pointing the build at the installed IDE is **mandatory before the
first build** — see [`intellij/CLAUDE.md`](intellij/CLAUDE.md) for the full setup and architecture.

### VS Code extension

```bash
cd vscode
npm ci
npm run compile          # esbuild → dist/extension.js
npm test                 # mocha
```

## CI

Workflows live in [`.github/workflows/`](.github/workflows/) and are **path-filtered per plugin** —
`intellij/**` changes trigger the Gradle build/verify, `vscode/**` changes trigger the Node build.

Releases are **tag-driven** — bump the manifest, commit, then push the matching tag (the published
version comes from the manifest, not the tag):

| Tag | Publishes | Version source | Required secrets |
|-----|-----------|----------------|------------------|
| `intellij-v*` | IntelliJ plugin → JetBrains Marketplace | `intellij/gradle.properties` `pluginVersion` | `PUBLISH_TOKEN` (+ `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` to sign) |
| `vscode-v*` | VS Code extension → VS Code Marketplace (+ Open VSX if `OVSX_PAT` is set) | `vscode/package.json` `version` | `VSCE_PAT` (optional `OVSX_PAT`) |

## License

See [`LICENSE`](LICENSE).

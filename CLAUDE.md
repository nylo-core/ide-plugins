# CLAUDE.md — Nylo IDE Plugins (monorepo root)

This repository (`nylo-core/ide-plugins`) holds the team's IDE plugins for [Nylo](https://nylo.dev),
one directory per target IDE. Each plugin has its own build, toolchain, and detailed `CLAUDE.md` —
**read the per-plugin file before working inside a plugin.**

## Layout

- `intellij/` — the IntelliJ Platform / Android Studio plugin (Kotlin, Gradle). **Start here:**
  [`intellij/CLAUDE.md`](intellij/CLAUDE.md) has the full architecture and the mandatory local build
  setup. All its build/test commands assume `intellij/` as the working directory.
- `vscode/` — the VS Code extension (TypeScript, esbuild). Ports the pure Kotlin logic near 1:1.
- `.github/workflows/` — path-filtered CI. **Must stay at the repo root** (GitHub only reads
  workflows from the root `.github/`).

## The one cross-cutting rule

The two plugins **share contracts, not code**: the Logs NDJSON record schema, the `.env`
naming/ownership rules, and the router/locale parsing all exist in both languages and must change
together. **Kotlin (`intellij/`) is the reference spec** — port changes from it into `vscode/`,
never the reverse, and land both sides in the same commit when a contract moves.

## Build / test entry points

| Plugin | From | Build | Test |
|--------|------|-------|------|
| IntelliJ | `intellij/` | `./gradlew buildPlugin` | `./gradlew test` |
| VS Code | `vscode/` | `npm run compile` | `npm test` |

`intellij/` needs a gitignored `local.properties` before its first build, or resolving the platform
triggers a multi-hour Android Studio download — see [`intellij/CLAUDE.md`](intellij/CLAUDE.md).

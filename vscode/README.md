# Nylo for VS Code

Utilities for [Nylo](https://nylo.dev) Flutter projects — a VS Code port of the Nylo
JetBrains/Android Studio plugin.

The extension activates in any workspace folder whose `pubspec.yaml` lists `nylo_framework`.

## Features

### Per-environment run configurations (env-sync)

Scans the project root for `.env*` files (e.g. `.env`, `.env.dev`, `.env.prod.staging`;
`.env-example` is ignored) and exposes one Flutter debug configuration per environment.

- Open the **Run and Debug** dropdown — each environment appears as a launch entry (e.g.
  *Default*, *Dev*, *Prod Staging*). No `launch.json` is written; the entries are provided
  dynamically and always reflect the current `.env*` files.
- Or run **Nylo: Run Environment…** from the Command Palette to pick an environment and launch.

Before the app launches, the extension runs Nylo's Metro `make:env` step for the chosen file
(`dart run nylo_framework:main make:env --file=<envfile>`), mirroring the JetBrains plugin's
before-run task.

> Requires the [Dart](https://marketplace.visualstudio.com/items?itemName=Dart-Code.dart-code)
> / [Flutter](https://marketplace.visualstudio.com/items?itemName=Dart-Code.flutter) extensions
> (they provide the `dart` debug type) and `dart` on your `PATH`.

### Planned

Logs, Localizations, and Screenshots views (ports of the JetBrains tool windows) — see
`../nylo-plugin` for the reference implementation.

## Development

```bash
npm install
npm run compile      # bundle to dist/extension.js
npm test             # run core/ unit tests
```

Press <kbd>F5</kbd> ("Run Extension") to launch an Extension Development Host.

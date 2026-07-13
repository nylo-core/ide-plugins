import * as vscode from 'vscode';
import { scanEnvFiles } from '../../core/env/envFileScanner';
import { metroMakeEnvArgs } from '../../core/env/metroNaming';
import { runProcess } from '../shared/processRunner';

/**
 * Env-sync, VS Code style. Instead of writing run configs + a Metro before-run external tool
 * (the JetBrains approach), we contribute one **dynamic** `dart` launch config per `.env*` file
 * and run the Metro `make:env` step in the resolve hook — so nothing is written to `.vscode/`.
 *
 * - {@link nyloDartConfigProvider} (Dynamic trigger): supplies the dropdown entries.
 * - {@link nyloDartResolver} (default trigger): runs `make:env` before any config carrying our
 *   marker launches. Kept separate so it never injects entries into a user's launch.json.
 */

const NYLO_ENV_KEY = '__nyloEnv';
const PROGRAM = 'lib/main.dart';

interface NyloDartConfig extends vscode.DebugConfiguration {
  [NYLO_ENV_KEY]?: string;
  program?: string;
}

/** Dynamic provider: one launch entry per `.env*` file in the folder. */
export const nyloDartConfigProvider: vscode.DebugConfigurationProvider = {
  provideDebugConfigurations(folder): vscode.DebugConfiguration[] {
    if (!folder) {
      return [];
    }
    // Never shadow a launch.json config the user already has by that name — same additive rule as
    // the JetBrains reconciler: a name owned by someone else is skipped, not duplicated.
    const takenNames = new Set(
      (vscode.workspace.getConfiguration('launch', folder.uri).get<{ name?: unknown }[]>('configurations') ?? [])
        .map((c) => c.name)
        .filter((n): n is string => typeof n === 'string'),
    );
    return scanEnvFiles(folder.uri.fsPath)
      .filter((env) => !takenNames.has(env.displayName))
      .map(
        (env): NyloDartConfig => ({
          type: 'dart',
          request: 'launch',
          name: env.displayName,
          program: PROGRAM,
          [NYLO_ENV_KEY]: env.fileName,
        }),
      );
  },
};

/** Resolver: runs the Metro `make:env` step before launching one of our configs. */
export const nyloDartResolver: vscode.DebugConfigurationProvider = {
  async resolveDebugConfiguration(folder, config) {
    const nyloConfig = config as NyloDartConfig;
    const envFile = nyloConfig[NYLO_ENV_KEY];
    if (typeof envFile !== 'string' || !folder) {
      return config;
    }
    const ok = await runMakeEnv(folder, envFile);
    if (!ok) {
      return undefined; // abort the launch
    }
    delete nyloConfig[NYLO_ENV_KEY];
    return config;
  },
};

/**
 * Runs `dart run nylo_framework:main make:env --file=<envFile>` in [folder], with a progress
 * notification. Returns true on success; surfaces an error and returns false otherwise.
 */
export async function runMakeEnv(folder: vscode.WorkspaceFolder, envFile: string): Promise<boolean> {
  return vscode.window.withProgress(
    { location: vscode.ProgressLocation.Notification, title: `Nylo: make:env ${envFile}`, cancellable: false },
    async () => {
      const result = await runProcess('dart', metroMakeEnvArgs(envFile), folder.uri.fsPath);
      if (result.code !== 0) {
        const detail = (result.stderr || result.stdout || '').trim();
        vscode.window.showErrorMessage(
          `Nylo: \`make:env\` failed for ${envFile}${detail ? `\n${detail}` : ''}`,
        );
        return false;
      }
      return true;
    },
  );
}

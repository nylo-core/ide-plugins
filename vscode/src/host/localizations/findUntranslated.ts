import * as vscode from 'vscode';
import { Finding, parseFindings, resolveNyloCli } from '../../core/localizations/nyloCli';
import { runProcess } from '../shared/processRunner';

const INSTALL_HINT =
  'Nylo CLI not found. Install it with `dart pub global activate nylo_installer`, or add `nylo_installer` to your dev dependencies.';

export interface ScanOutcome {
  findings: Finding[] | null;
  error: string | null;
}

/** Port of `dev.nylo.plugin.localizations.cli.FindUntranslatedRunner.run`. */
export async function runFindUntranslated(folder: vscode.WorkspaceFolder): Promise<ScanOutcome> {
  const [command, ...prefix] = resolveNyloCli(folder.uri.fsPath);
  const args = [...prefix, 'locale:find-untranslated', '--stdout', '--format', 'json'];

  let result;
  try {
    result = await runProcess(command, args, folder.uri.fsPath, 120_000);
  } catch {
    return { findings: null, error: INSTALL_HINT };
  }

  if (result.code !== 0) {
    const combined = `${result.stderr}\n${result.stdout}`.toLowerCase();
    const missing = ['command not found', 'could not find', 'no such file', 'is not recognized'].some((s) =>
      combined.includes(s),
    );
    return {
      findings: null,
      error: missing ? INSTALL_HINT : `find-untranslated failed: ${result.stderr.trim() || `exit code ${result.code}`}`,
    };
  }

  try {
    return { findings: parseFindings(result.stdout), error: null };
  } catch (e) {
    return { findings: null, error: `Could not run the scan: ${(e as Error).message || 'could not parse output'}` };
  }
}

import * as vscode from 'vscode';
import { isNyloProjectDir } from './core/project/nyloProjectDetector';
import { registerEnvSync } from './host/env/envSync';
import { registerLocalizationsView } from './host/localizations/localizationsView';
import { registerLogsView } from './host/logs/logsView';
import { registerScreenshotsView } from './host/screenshots/screenshotsView';

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  await refreshNyloContext();

  context.subscriptions.push(
    vscode.workspace.onDidChangeWorkspaceFolders(() => {
      void refreshNyloContext();
    }),
  );

  registerEnvSync(context);
  registerLogsView(context);
  registerLocalizationsView(context);
  registerScreenshotsView(context);
}

export function deactivate(): void {
  // no-op
}

/** Sets the `nylo.isNyloProject` context key used to gate commands/views. */
async function refreshNyloContext(): Promise<void> {
  const folders = vscode.workspace.workspaceFolders ?? [];
  const isNylo = folders.some((folder) => isNyloProjectDir(folder.uri.fsPath));
  await vscode.commands.executeCommand('setContext', 'nylo.isNyloProject', isNylo);
}

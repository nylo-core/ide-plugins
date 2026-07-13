import * as vscode from 'vscode';
import { scanEnvFiles } from '../../core/env/envFileScanner';
import { pickNyloFolder } from '../shared/workspace';
import { nyloDartConfigProvider, nyloDartResolver, runMakeEnv } from './dartDebugProvider';

/** Registers the dynamic `dart` debug configs + the `nylo.runEnvironment` command. */
export function registerEnvSync(context: vscode.ExtensionContext): void {
  context.subscriptions.push(
    vscode.debug.registerDebugConfigurationProvider(
      'dart',
      nyloDartConfigProvider,
      vscode.DebugConfigurationProviderTriggerKind.Dynamic,
    ),
    // Default trigger so the resolve hook fires for every launch, without contributing entries.
    vscode.debug.registerDebugConfigurationProvider('dart', nyloDartResolver),
    vscode.commands.registerCommand('nylo.runEnvironment', runEnvironmentCommand),
  );
}

async function runEnvironmentCommand(): Promise<void> {
  const folder = await pickNyloFolder();
  if (!folder) {
    return;
  }

  // Mirror of the JetBrains plugin's Flutter-availability gate: without the Dart extension the
  // `dart` debug type has no debugger and the launch below would fail opaquely.
  if (!vscode.extensions.getExtension('Dart-Code.dart-code')) {
    vscode.window.showWarningMessage(
      'Nylo: install the Dart extension (Dart-Code.dart-code) to run environments.',
    );
    return;
  }

  const envs = scanEnvFiles(folder.uri.fsPath);
  if (envs.length === 0) {
    vscode.window.showInformationMessage('Nylo: no `.env*` files found in the project root.');
    return;
  }

  const pick = await vscode.window.showQuickPick(
    envs.map((env) => ({ label: env.displayName, description: env.fileName, env })),
    { placeHolder: 'Select a Nylo environment to run' },
  );
  if (!pick) {
    return;
  }

  if (!(await runMakeEnv(folder, pick.env.fileName))) {
    return;
  }

  await vscode.debug.startDebugging(folder, {
    type: 'dart',
    request: 'launch',
    name: pick.env.displayName,
    program: 'lib/main.dart',
  });
}

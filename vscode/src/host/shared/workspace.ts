import * as vscode from 'vscode';
import { isNyloProjectDir } from '../../core/project/nyloProjectDetector';

/** Workspace folders whose `pubspec.yaml` declares `nylo_framework`. */
export function nyloFolders(): vscode.WorkspaceFolder[] {
  return (vscode.workspace.workspaceFolders ?? []).filter((folder) => isNyloProjectDir(folder.uri.fsPath));
}

export function firstNyloFolder(): vscode.WorkspaceFolder | undefined {
  return nyloFolders()[0];
}

/** Resolves a Nylo folder, prompting when more than one is present. */
export async function pickNyloFolder(
  placeHolder = 'Select a Nylo project',
): Promise<vscode.WorkspaceFolder | undefined> {
  const folders = nyloFolders();
  if (folders.length === 0) {
    vscode.window.showInformationMessage('Nylo: no Nylo project found in this workspace.');
    return undefined;
  }
  if (folders.length === 1) {
    return folders[0];
  }
  const pick = await vscode.window.showQuickPick(
    folders.map((folder) => ({ label: folder.name, description: folder.uri.fsPath, folder })),
    { placeHolder },
  );
  return pick?.folder;
}

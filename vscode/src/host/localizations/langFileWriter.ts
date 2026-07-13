import * as fs from 'fs';
import * as vscode from 'vscode';
import { withValue } from '../../core/localizations/langJson';

/**
 * Port of `dev.nylo.plugin.localizations.edit.LangFileWriter`. Writes a single translation value back
 * into a `lang/<code>.json` file. Goes through a `WorkspaceEdit` when the file is open (so an editor
 * refreshes and the change joins undo); falls back to a direct write otherwise. Returns null on success.
 */
export async function writeValue(filePath: string, key: string, value: string): Promise<string | null> {
  const open = vscode.workspace.textDocuments.find((d) => d.uri.fsPath === filePath && !d.isClosed);
  try {
    if (open) {
      const newText = withValue(open.getText(), key, value);
      const edit = new vscode.WorkspaceEdit();
      const fullRange = new vscode.Range(open.positionAt(0), open.positionAt(open.getText().length));
      edit.replace(open.uri, fullRange, newText);
      if (!(await vscode.workspace.applyEdit(edit))) {
        return `Could not apply the edit to ${key}.`;
      }
      await open.save();
      return null;
    }
    const text = fs.readFileSync(filePath, 'utf8');
    fs.writeFileSync(filePath, withValue(text, key, value), 'utf8');
    return null;
  } catch (e) {
    return `Failed to write ${key}: ${(e as Error).message}`;
  }
}

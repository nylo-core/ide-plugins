import { execFile } from 'child_process';
import * as vscode from 'vscode';

export interface RunResult {
  code: number;
  stdout: string;
  stderr: string;
}

/**
 * Loose port of `dev.nylo.plugin.screenshots.process.ProcessRunner`, plus login-shell PATH
 * resolution. A GUI-launched VS Code extension host often lacks the login-shell `PATH`, so
 * `dart` / `flutter` / `xcrun` / `adb` may not resolve. We capture the login shell's environment
 * once and reuse it for every spawned tool.
 */
export async function runProcess(
  command: string,
  args: string[],
  cwd: string,
  timeoutMs = 0,
): Promise<RunResult> {
  const env = await shellEnv();
  return new Promise((resolve) => {
    execFile(command, args, { cwd, env, timeout: timeoutMs, maxBuffer: 16 * 1024 * 1024 }, (err, stdout, stderr) => {
      const anyErr = err as (NodeJS.ErrnoException & { code?: number | string }) | null;
      let code = 0;
      if (anyErr) {
        code = typeof anyErr.code === 'number' ? anyErr.code : 1;
      }
      resolve({ code, stdout: stdout ?? '', stderr: stderr ?? '' });
    });
  });
}

let cachedEnv: Promise<NodeJS.ProcessEnv> | undefined;

/** The login-shell environment (cached). Falls back to `process.env` on Windows or on failure. */
export function shellEnv(): Promise<NodeJS.ProcessEnv> {
  if (!cachedEnv) {
    cachedEnv = resolveShellEnv();
  }
  return cachedEnv;
}

function resolveShellEnv(): Promise<NodeJS.ProcessEnv> {
  if (process.platform === 'win32') {
    return Promise.resolve(process.env);
  }
  const shell = vscode.env.shell || process.env.SHELL || '/bin/zsh';
  return new Promise((resolve) => {
    // `-l -c env`: a login shell that sources the user's profile, then prints its environment.
    execFile(shell, ['-l', '-c', 'env'], { timeout: 5000 }, (err, stdout) => {
      if (err || !stdout) {
        resolve(process.env);
        return;
      }
      const env: NodeJS.ProcessEnv = { ...process.env };
      for (const line of stdout.split('\n')) {
        const eq = line.indexOf('=');
        if (eq > 0) {
          env[line.slice(0, eq)] = line.slice(eq + 1);
        }
      }
      resolve(env);
    });
  });
}

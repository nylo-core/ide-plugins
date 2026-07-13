import { spawn } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import * as readline from 'readline';
import { captureTargetPath, markerValue, resolveShotIndex } from '../../core/screenshots/markers';
import { deviceSlug, NyloPage, TargetDevice } from '../../core/screenshots/model';
import { runProcess, shellEnv } from '../shared/processRunner';
import { applyStatusBar, clearStatusBar } from './statusBar';

/**
 * Port of `dev.nylo.plugin.screenshots.run.ScreenshotOrchestrator`.
 *
 * For each device: launch `flutter run … --dart-define=NYLO_SCREENSHOT=true`, watch stdout+stderr for
 * the `__NYLO_SHOT_*__` markers, and on each `READY` grab the device screen into
 * `<outputDir>/<device>/<locale>/<NN-slug>.png`.
 */
export interface ScreenshotRunRequest {
  projectDir: string;
  devices: TargetDevice[];
  pages: NyloPage[];
  locales: string[];
  /** Absolute path, or relative to projectDir. */
  outputDir: string;
  settleMs: number;
  windowMs: number;
  cleanStatusBar: boolean;
}

export interface RunCallbacks {
  log: (message: string) => void;
  onProgress: (done: number, total: number, label: string) => void;
  isCancelled: () => boolean;
}

export interface RunResult {
  captured: number;
  skipped: number;
  files: string[];
}

/** Android `adb exec-out screencap` copy bound, matching Kotlin `ProcessRunner.runToFile`. */
const ANDROID_CAPTURE_TIMEOUT_MS = 30_000;

export async function runCapture(request: ScreenshotRunRequest, cb: RunCallbacks): Promise<RunResult> {
  return new Orchestrator(request, cb).run();
}

class Orchestrator {
  private readonly files: string[] = [];
  /**
   * Captures run one at a time in arrival order: each `READY` marker chains its capture onto this
   * promise (Kotlin serializes them under a lock). Keeps the no-`index` fallback counter
   * deterministic and lets a device's close handler await every capture by awaiting the tail.
   */
  private captureChain: Promise<void> = Promise.resolve();
  private captured = 0;
  private skipped = 0;
  private doneOverall = 0;
  private sawBegin = false;
  private readonly totalOverall: number;
  private readonly outputBase: string;
  private env: NodeJS.ProcessEnv = process.env;

  constructor(
    private readonly request: ScreenshotRunRequest,
    private readonly cb: RunCallbacks,
  ) {
    this.totalOverall = request.pages.length * request.locales.length * request.devices.length;
    this.outputBase = path.isAbsolute(request.outputDir)
      ? request.outputDir
      : path.join(request.projectDir, request.outputDir);
  }

  async run(): Promise<RunResult> {
    this.env = await shellEnv();
    for (const device of this.request.devices) {
      if (this.cb.isCancelled()) {
        break;
      }
      await this.runDevice(device);
    }
    return { captured: this.captured, skipped: this.skipped, files: this.files.slice() };
  }

  private async runDevice(device: TargetDevice): Promise<void> {
    this.cb.log(`▶ ${device.name} (${device.platform})`);
    this.sawBegin = false;
    if (this.request.cleanStatusBar) {
      await applyStatusBar(device, this.request.projectDir);
    }

    try {
      await this.runFlutter(device);
    } finally {
      // Guaranteed even if the run throws or `close` never fires (Kotlin clears in a `finally`).
      if (this.request.cleanStatusBar) {
        await clearStatusBar(device, this.request.projectDir);
      }
    }
  }

  private runFlutter(device: TargetDevice): Promise<void> {
    const args = [
      'run',
      '-d',
      device.id,
      '--dart-define=NYLO_SCREENSHOT=true',
      `--dart-define=NYLO_SHOT_ROUTES=${this.request.pages.map((p) => p.route).join(',')}`,
      `--dart-define=NYLO_SHOT_LOCALES=${this.request.locales.join(',')}`,
      `--dart-define=NYLO_SHOT_DEVICE=${deviceSlug(device)}`,
      `--dart-define=NYLO_SHOT_SETTLE_MS=${this.request.settleMs}`,
      `--dart-define=NYLO_SHOT_WINDOW_MS=${this.request.windowMs}`,
    ];
    this.cb.log(`$ flutter ${args.join(' ')}`);

    return new Promise<void>((resolve) => {
      const child = spawn('flutter', args, { cwd: this.request.projectDir, env: this.env });
      const perDevice = this.request.pages.length * this.request.locales.length;
      const maxMs = 5 * 60_000 + perDevice * (this.request.settleMs + this.request.windowMs + 1_500);

      const stdout = readline.createInterface({ input: child.stdout });
      const stderr = readline.createInterface({ input: child.stderr });
      // Both streams feed the same handler so a marker printed to stderr is still acted on (Kotlin
      // pumps stdout+stderr through one `handleLine`). Trim a trailing `\r` on either stream.
      const onLine = (line: string) => this.handleLine(device, line.replace(/\r$/, ''), child);
      stdout.on('line', onLine);
      stderr.on('line', onLine);

      // Distinguish a user stop from a timeout, and log each exactly once (Kotlin: "Cancelled." vs
      // "Timed out waiting for <device>.").
      let stopReason: 'cancel' | 'timeout' | null = null;
      const timeout = setTimeout(() => {
        if (stopReason) {
          return;
        }
        stopReason = 'timeout';
        this.cb.log(`Timed out waiting for ${device.name}.`);
        child.kill();
      }, maxMs);
      const cancelPoll = setInterval(() => {
        if (stopReason) {
          return;
        }
        if (this.cb.isCancelled()) {
          stopReason = 'cancel';
          this.cb.log('Cancelled.');
          child.kill();
        }
      }, 500);

      let settled = false;
      const settle = async () => {
        if (settled) {
          return;
        }
        settled = true;
        clearTimeout(timeout);
        clearInterval(cancelPoll);
        stdout.close();
        stderr.close();
        // Wait for every queued capture to finish before the device is considered done.
        await this.captureChain;
        if (!this.sawBegin && !this.cb.isCancelled()) {
          this.cb.log('  No capture markers seen — is this app on a Nylo version with screenshot mode?');
        }
        resolve();
      };

      child.on('error', (e) => {
        this.cb.log(`  ✗ ${e.message}`);
        void settle();
      });
      child.on('close', () => void settle());
    });
  }

  private handleLine(device: TargetDevice, line: string, child: ReturnType<typeof spawn>): void {
    if (line.includes('__NYLO_SHOTS_BEGIN__')) {
      this.sawBegin = true;
      this.cb.log(`  begin: ${markerValue(line, 'count') ?? '?'} screens`);
    } else if (line.includes('__NYLO_SHOT_READY__')) {
      // capture() swallows its own errors, but keep the chain resolvable so `await` in settle() can't hang.
      this.captureChain = this.captureChain.then(() => this.capture(device, line)).catch(() => undefined);
    } else if (line.includes('__NYLO_SHOT_SKIP__')) {
      const route = markerValue(line, 'route');
      this.skipped++;
      this.doneOverall++;
      this.cb.log(`  skip ${route} (${markerValue(line, 'reason')})`);
      // A skip is a completed unit, so it advances the bar (Kotlin counts skips in `doneOverall`).
      this.cb.onProgress(this.doneOverall, this.totalOverall, `${device.name} · skip ${route ?? ''}`);
    } else if (line.includes('__NYLO_SHOTS_DONE__')) {
      this.cb.log(`  done: ${device.name}`);
      child.kill();
    } else if (isNoteworthy(line)) {
      this.cb.log(`  ${line}`);
    }
  }

  private async capture(device: TargetDevice, line: string): Promise<void> {
    const locale = markerValue(line, 'locale') ?? 'default';
    const slug = markerValue(line, 'slug') ?? 'screen';
    const index = resolveShotIndex(markerValue(line, 'index'), this.captured);
    const target = captureTargetPath(this.outputBase, deviceSlug(device), locale, slug, index);
    fs.mkdirSync(path.dirname(target), { recursive: true });

    let ok = false;
    try {
      if (device.platform === 'ios') {
        ok = (await runProcess('xcrun', ['simctl', 'io', device.id, 'screenshot', target], this.request.projectDir, 30_000)).code === 0;
      } else {
        ok = await captureAndroid(device.id, target, this.request.projectDir, this.env);
      }
    } catch {
      ok = false;
    }

    this.doneOverall++;
    if (ok) {
      this.captured++;
      this.files.push(target);
      this.cb.log(`  ✓ ${deviceSlug(device)}/${locale}/${path.basename(target)}`);
    } else {
      this.cb.log(`  ✗ capture failed: ${slug} (${locale})`);
    }
    // Report after every capture (success or failure) so the absolute bar accounts for it.
    this.cb.onProgress(this.doneOverall, this.totalOverall, `${device.name} · ${locale} · /${slug}`);
  }
}

/**
 * Streams `adb exec-out screencap -p` into `target`, bounded by a timeout. Ports Kotlin
 * `ProcessRunner.runToFile`: a wedged adb (a known failure mode that keeps stdout open) is
 * force-killed, and a zero-byte file counts as failure even on a clean exit.
 */
function captureAndroid(
  serial: string,
  target: string,
  cwd: string,
  env: NodeJS.ProcessEnv,
  timeoutMs = ANDROID_CAPTURE_TIMEOUT_MS,
): Promise<boolean> {
  return new Promise((resolve) => {
    const out = fs.createWriteStream(target);
    const child = spawn('adb', ['-s', serial, 'exec-out', 'screencap', '-p'], { cwd, env });

    let settled = false;
    let outClosed = false;
    let childClosed = false;
    let exitCode: number | null = null;

    const done = (ok: boolean) => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(timer);
      resolve(ok);
    };
    const evaluate = () => {
      if (!outClosed || !childClosed) {
        return;
      }
      let size = 0;
      try {
        size = fs.statSync(target).size;
      } catch {
        size = 0;
      }
      done(exitCode === 0 && size > 0);
    };

    const timer = setTimeout(() => {
      child.kill('SIGKILL'); // closes the pipe, unblocking the copy
      out.destroy();
      done(false);
    }, timeoutMs);

    child.stdout.pipe(out);
    out.on('finish', () => {
      outClosed = true;
      evaluate();
    });
    out.on('error', () => done(false));
    child.on('error', () => {
      out.destroy();
      done(false);
    });
    child.on('close', (code) => {
      exitCode = code ?? 1;
      childClosed = true;
      evaluate();
    });
  });
}

function isNoteworthy(line: string): boolean {
  return (
    line.includes('Launching') ||
    line.includes('Installing') ||
    line.includes('Error') ||
    line.includes('Exception') ||
    line.includes('Unable to')
  );
}

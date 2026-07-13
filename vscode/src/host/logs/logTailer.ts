import * as fs from 'fs';

/**
 * Polls a log file and fires [onChanged] when it grows (or is truncated/rotated), appears, or when
 * the resolver starts pointing at a different path. Port of `dev.nylo.plugin.logs.tail.LogTailer` —
 * the Flutter process writes outside the editor's file events, so polling is the reliable signal.
 *
 * The target is a resolver, not a fixed path, so "today's file" is re-evaluated on every poll:
 * a file that doesn't exist yet (first app run of the day) is watched into existence, and the
 * midnight rollover to a new daily filename is picked up automatically.
 */
export class LogTailer {
  private timer: NodeJS.Timeout | undefined;
  private lastPath: string | undefined;
  private lastLength = -1;

  constructor(private readonly intervalMs = 1200) {}

  /** Points the tailer at [resolvePath] (resetting the baseline when the path changes) and starts polling. */
  start(resolvePath: () => string, onChanged: () => void): void {
    this.stop();
    const current = resolvePath();
    if (current !== this.lastPath) {
      this.lastPath = current;
      this.lastLength = fileLength(current);
    }
    this.timer = setInterval(() => {
      const target = resolvePath();
      const length = fileLength(target);
      const changed = target !== this.lastPath || length !== this.lastLength;
      this.lastPath = target;
      this.lastLength = length;
      if (changed) {
        onChanged();
      }
    }, this.intervalMs);
  }

  stop(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = undefined;
    }
  }

  dispose(): void {
    this.stop();
  }
}

/** File length in bytes, or -1 when missing/not a file — so appearing counts as a change. */
function fileLength(filePath: string): number {
  try {
    const stat = fs.statSync(filePath);
    return stat.isFile() ? stat.size : -1;
  } catch {
    return -1;
  }
}

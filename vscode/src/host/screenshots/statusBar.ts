import { TargetDevice } from '../../core/screenshots/model';
import { runProcess } from '../shared/processRunner';

/**
 * Port of `dev.nylo.plugin.screenshots.device.StatusBarStyler`. Best-effort "marketing" status bar
 * (9:41, full battery/signal). Every call is swallowed so a failure never aborts a run.
 */
export async function applyStatusBar(device: TargetDevice, cwd: string): Promise<void> {
  try {
    if (device.platform === 'ios') {
      await runProcess(
        'xcrun',
        ['simctl', 'status_bar', device.id, 'override', '--time', '9:41', '--batteryState', 'charged', '--batteryLevel', '100', '--cellularBars', '4', '--wifiBars', '3'],
        cwd,
        15_000,
      );
    } else {
      await androidDemo(device.id, true, cwd);
    }
  } catch {
    // ignore
  }
}

export async function clearStatusBar(device: TargetDevice, cwd: string): Promise<void> {
  try {
    if (device.platform === 'ios') {
      await runProcess('xcrun', ['simctl', 'status_bar', device.id, 'clear'], cwd, 15_000);
    } else {
      await androidDemo(device.id, false, cwd);
    }
  } catch {
    // ignore
  }
}

async function androidDemo(serial: string, enter: boolean, cwd: string): Promise<void> {
  const broadcast = (extras: string[]) =>
    runProcess('adb', ['-s', serial, 'shell', 'am', 'broadcast', '-a', 'com.android.systemui.demo', ...extras], cwd, 10_000);

  if (!enter) {
    await broadcast(['-e', 'command', 'exit']);
    return;
  }
  await runProcess('adb', ['-s', serial, 'shell', 'settings', 'put', 'global', 'sysui_demo_allowed', '1'], cwd, 10_000);
  await broadcast(['-e', 'command', 'enter']);
  await broadcast(['-e', 'command', 'clock', '-e', 'hhmm', '0941']);
  await broadcast(['-e', 'command', 'battery', '-e', 'level', '100', '-e', 'plugged', 'false']);
  await broadcast(['-e', 'command', 'network', '-e', 'wifi', 'show', '-e', 'level', '4']);
  await broadcast(['-e', 'command', 'notifications', '-e', 'visible', 'false']);
}

import { DevicePlatform, TargetDevice } from './model';

/**
 * Port of `dev.nylo.plugin.screenshots.device.DeviceDetector` (pure half) — parses
 * `flutter devices --machine` JSON. Only iOS simulators and Android emulators/devices are surfaced
 * (those are what `xcrun simctl` / `adb` can capture).
 */
export function parseDevices(json: string): TargetDevice[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) {
    return [];
  }

  const devices: TargetDevice[] = [];
  for (const entry of parsed) {
    if (entry === null || typeof entry !== 'object' || Array.isArray(entry)) {
      continue;
    }
    const obj = entry as Record<string, unknown>;
    const id = typeof obj.id === 'string' ? obj.id : null;
    if (!id) {
      continue;
    }
    const emulator = typeof obj.emulator === 'boolean' ? obj.emulator : false;
    // flutter reports the OS in `targetPlatform` (e.g. "ios", "android-arm64"); older builds used `platformType`.
    const rawPlatform = typeof obj.platformType === 'string' ? obj.platformType : typeof obj.targetPlatform === 'string' ? obj.targetPlatform : null;
    const raw = rawPlatform?.toLowerCase() ?? null;

    let platform: DevicePlatform;
    if (raw === null) {
      continue;
    } else if (raw.startsWith('ios')) {
      platform = 'ios';
    } else if (raw.startsWith('android')) {
      platform = 'android';
    } else {
      continue; // skip macOS / web / desktop
    }
    // iOS capture via `xcrun simctl` only works on simulators.
    if (platform === 'ios' && !emulator) {
      continue;
    }
    devices.push({ id, name: typeof obj.name === 'string' ? obj.name : id, platform, emulator });
  }
  return devices;
}

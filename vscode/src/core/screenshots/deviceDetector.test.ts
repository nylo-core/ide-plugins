import * as assert from 'assert';
import { parseDevices } from './deviceDetector';
import { deviceSlug } from './model';

describe('deviceDetector', () => {
  it('parses devices and keeps only iOS simulators + Android devices', () => {
    const json = JSON.stringify([
      { id: 'sim-1', name: 'iPhone 15', emulator: true, targetPlatform: 'ios' },
      { id: 'real-ios', name: 'My iPhone', emulator: false, targetPlatform: 'ios' },
      { id: 'emu-1', name: 'Pixel 7', emulator: true, targetPlatform: 'android-arm64' },
      { id: 'real-android', name: 'Galaxy', emulator: false, platformType: 'android' },
      { id: 'mac', name: 'macOS', emulator: false, targetPlatform: 'darwin' },
      { id: 'web', name: 'Chrome', emulator: false, targetPlatform: 'web-javascript' },
    ]);
    const devices = parseDevices(json);
    assert.deepStrictEqual(devices.map((d) => d.id), ['sim-1', 'emu-1', 'real-android']);
    assert.strictEqual(devices[0].platform, 'ios');
    assert.strictEqual(devices[1].platform, 'android');
  });

  it('returns empty on malformed or non-array input', () => {
    assert.deepStrictEqual(parseDevices(''), []);
    assert.deepStrictEqual(parseDevices('{}'), []);
  });

  it('deviceSlug sanitises the device name', () => {
    assert.strictEqual(deviceSlug({ id: 'x', name: 'iPhone 15 Pro', platform: 'ios', emulator: true }), 'iphone-15-pro');
    assert.strictEqual(deviceSlug({ id: 'serial', name: '!!!', platform: 'android', emulator: false }), 'serial');
  });
});

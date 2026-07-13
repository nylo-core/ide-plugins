/** Port of the `dev.nylo.plugin.screenshots.model` types. */

export type DevicePlatform = 'ios' | 'android';

/** A page discovered from the app's `lib/routes/router.dart`. */
export interface NyloPage {
  className: string;
  /** Navigable path the screenshot driver will route to (e.g. `/login`). */
  route: string;
  displayName: string;
  authenticated: boolean;
  /** False when the route was derived from the class name (not found in source). */
  routeResolved: boolean;
}

/**
 * A device `flutter run` can target now. [id] doubles as the capture handle: a simulator UDID for
 * iOS (`xcrun simctl`) and an adb serial for Android (`adb -s`).
 */
export interface TargetDevice {
  id: string;
  name: string;
  platform: DevicePlatform;
  emulator: boolean;
}

/** Filesystem-safe folder name for a device's screenshots. */
export function deviceSlug(device: TargetDevice): string {
  const slug = device.name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return slug.length > 0 ? slug : device.id;
}

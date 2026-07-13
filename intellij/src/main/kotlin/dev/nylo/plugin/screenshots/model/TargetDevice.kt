package dev.nylo.plugin.screenshots.model

enum class DevicePlatform { IOS, ANDROID }

/**
 * A device `flutter run` can target right now — a booted simulator/emulator or a
 * connected device. [id] is the flutter device id, which doubles as the handle
 * for screen capture: a simulator UDID for [DevicePlatform.IOS] (`xcrun simctl`)
 * and an adb serial for [DevicePlatform.ANDROID] (`adb -s`).
 */
data class TargetDevice(
    val id: String,
    val name: String,
    val platform: DevicePlatform,
    val emulator: Boolean,
) {
    /** Filesystem-safe folder name for this device's screenshots. */
    val slug: String
        get() = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { id }
}

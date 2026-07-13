package dev.nylo.plugin.screenshots.device

import com.intellij.openapi.project.Project
import dev.nylo.plugin.screenshots.model.DevicePlatform
import dev.nylo.plugin.screenshots.model.TargetDevice
import dev.nylo.plugin.screenshots.process.JsonLite
import dev.nylo.plugin.screenshots.process.ProcessRunner
import java.io.File

/**
 * Lists devices `flutter run` can target right now via `flutter devices --machine`.
 * Only iOS simulators and Android emulators/devices are surfaced — those are what
 * we can capture with `xcrun simctl` / `adb`.
 */
object DeviceDetector {
    fun detect(project: Project): List<TargetDevice> {
        val workDir = project.basePath?.let(::File)
        val output = runCatching {
            ProcessRunner.run(listOf("flutter", "devices", "--machine"), workDir, timeoutMs = 60_000)
        }.getOrNull() ?: return emptyList()
        if (output.exitCode != 0) return emptyList()

        return JsonLite.objects(output.stdout).mapNotNull { obj ->
            val id = JsonLite.string(obj, "id") ?: return@mapNotNull null
            val emulator = JsonLite.bool(obj, "emulator") ?: false
            // `flutter devices --machine` reports the OS in `targetPlatform`
            // (e.g. "ios", "android-arm64"); older versions also had `platformType`.
            val raw = (JsonLite.string(obj, "platformType") ?: JsonLite.string(obj, "targetPlatform"))?.lowercase()
            val platform = when {
                raw == null -> return@mapNotNull null
                raw.startsWith("ios") -> DevicePlatform.IOS
                raw.startsWith("android") -> DevicePlatform.ANDROID
                else -> return@mapNotNull null // skip macOS / web / desktop
            }
            // We capture iOS via `xcrun simctl`, which only works on simulators.
            // Android `adb screencap` works on both emulators and real devices.
            if (platform == DevicePlatform.IOS && !emulator) return@mapNotNull null
            TargetDevice(
                id = id,
                name = JsonLite.string(obj, "name") ?: id,
                platform = platform,
                emulator = emulator,
            )
        }
    }
}

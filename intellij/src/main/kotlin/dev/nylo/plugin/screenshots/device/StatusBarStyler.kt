package dev.nylo.plugin.screenshots.device

import dev.nylo.plugin.screenshots.model.DevicePlatform
import dev.nylo.plugin.screenshots.model.TargetDevice
import dev.nylo.plugin.screenshots.process.ProcessRunner

/**
 * Best-effort "marketing" status bar (9:41, full battery & signal) for cleaner
 * store screenshots. All calls are wrapped so a failure never aborts a run.
 *
 * - iOS: `xcrun simctl status_bar <udid> override` / `clear`.
 * - Android: SystemUI demo mode broadcasts.
 */
object StatusBarStyler {
    fun apply(device: TargetDevice) {
        runCatching {
            when (device.platform) {
                DevicePlatform.IOS -> ProcessRunner.run(
                    listOf(
                        "xcrun", "simctl", "status_bar", device.id, "override",
                        "--time", "9:41",
                        "--batteryState", "charged", "--batteryLevel", "100",
                        "--cellularBars", "4", "--wifiBars", "3",
                    ),
                    timeoutMs = 15_000,
                )
                DevicePlatform.ANDROID -> androidDemo(device.id, enter = true)
            }
        }
    }

    fun clear(device: TargetDevice) {
        runCatching {
            when (device.platform) {
                DevicePlatform.IOS -> ProcessRunner.run(
                    listOf("xcrun", "simctl", "status_bar", device.id, "clear"),
                    timeoutMs = 15_000,
                )
                DevicePlatform.ANDROID -> androidDemo(device.id, enter = false)
            }
        }
    }

    private fun androidDemo(serial: String, enter: Boolean) {
        fun broadcast(vararg extras: String) = ProcessRunner.run(
            listOf("adb", "-s", serial, "shell", "am", "broadcast", "-a", "com.android.systemui.demo", *extras),
            timeoutMs = 10_000,
        )
        if (!enter) {
            broadcast("-e", "command", "exit")
            return
        }
        ProcessRunner.run(listOf("adb", "-s", serial, "shell", "settings", "put", "global", "sysui_demo_allowed", "1"), timeoutMs = 10_000)
        broadcast("-e", "command", "enter")
        broadcast("-e", "command", "clock", "-e", "hhmm", "0941")
        broadcast("-e", "command", "battery", "-e", "level", "100", "-e", "plugged", "false")
        broadcast("-e", "command", "network", "-e", "wifi", "show", "-e", "level", "4")
        broadcast("-e", "command", "notifications", "-e", "visible", "false")
    }
}

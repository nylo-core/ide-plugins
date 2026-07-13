package dev.nylo.plugin.screenshots.run

import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.util.Key
import dev.nylo.plugin.screenshots.device.StatusBarStyler
import dev.nylo.plugin.screenshots.model.DevicePlatform
import dev.nylo.plugin.screenshots.model.TargetDevice
import dev.nylo.plugin.screenshots.process.ProcessRunner
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs the capture for a [ScreenshotRunRequest].
 *
 * For each selected device it launches `flutter run … --dart-define=NYLO_SCREENSHOT=true`,
 * watches the process stdout for the `__NYLO_SHOT_*__` markers the framework's
 * screenshot driver prints, and on each `READY` marker grabs the device screen
 * (`xcrun simctl io …` / `adb exec-out screencap`) into
 * `<outputDir>/<device>/<locale>/<NN-slug>.png`. The driver holds each screen for
 * `windowMs`, so no signal needs to travel back to the app — the capture just
 * lands inside that window.
 */
class ScreenshotOrchestrator(
    private val request: ScreenshotRunRequest,
    private val log: (String) -> Unit,
    private val onProgress: (done: Int, total: Int, label: String) -> Unit,
    private val isCancelled: () -> Boolean,
) {
    data class Result(val captured: Int, val skipped: Int, val files: List<File>)

    private val files = mutableListOf<File>()
    private var captured = 0
    private var skipped = 0
    private var doneOverall = 0
    private var sawBegin = false
    private val totalOverall = request.totalPerDevice * request.devices.size

    fun run(): Result {
        for (device in request.devices) {
            if (isCancelled()) break
            runDevice(device)
        }
        return Result(captured, skipped, files.toList())
    }

    private fun runDevice(device: TargetDevice) {
        log("▶ ${device.name} (${device.platform.name.lowercase()})")
        sawBegin = false
        if (request.cleanStatusBar) StatusBarStyler.apply(device)
        try {
            val handler = startFlutterRun(device)
            val latch = CountDownLatch(1)
            // stdout and stderr are pumped by separate reader threads, so a single shared buffer
            // would let a stderr fragment splice into a stdout __NYLO_SHOT_*__ marker line (silently
            // losing that screenshot) or corrupt the builder outright. One LineBuffer per stream,
            // each locked, with line handling serialized across both.
            val handleLock = Any()
            val emit = { line: String -> synchronized(handleLock) { handleLine(device, line, handler) } }
            val stdoutLines = LineBuffer(emit)
            val stderrLines = LineBuffer(emit)
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val buffer = if (ProcessOutputType.isStderr(outputType)) stderrLines else stdoutLines
                    synchronized(buffer) { buffer.append(event.text) }
                }

                override fun processTerminated(event: ProcessEvent) = latch.countDown()
            })
            handler.startNotify()

            // First run includes a full build; allow generous time plus the loop's own pacing.
            val maxMs = 5 * 60_000L +
                request.totalPerDevice.toLong() * (request.settleMs + request.windowMs + 1_500L)
            var waited = 0L
            while (latch.count > 0) {
                if (latch.await(500, TimeUnit.MILLISECONDS)) break
                waited += 500
                if (isCancelled()) {
                    log("Cancelled.")
                    break
                }
                if (waited > maxMs) {
                    log("Timed out waiting for ${device.name}.")
                    break
                }
            }
            if (!handler.isProcessTerminated) handler.destroyProcess()
            handler.waitFor(5_000)
            if (!sawBegin && !isCancelled()) {
                log("  No capture markers seen — is this app on a Nylo version with screenshot mode?")
            }
        } finally {
            if (request.cleanStatusBar) StatusBarStyler.clear(device)
        }
    }

    private fun startFlutterRun(device: TargetDevice): OSProcessHandler {
        val args = listOf(
            "flutter", "run", "-d", device.id,
            "--dart-define=NYLO_SCREENSHOT=true",
            "--dart-define=NYLO_SHOT_ROUTES=${request.routesCsv}",
            "--dart-define=NYLO_SHOT_LOCALES=${request.localesCsv}",
            "--dart-define=NYLO_SHOT_DEVICE=${device.slug}",
            "--dart-define=NYLO_SHOT_SETTLE_MS=${request.settleMs}",
            "--dart-define=NYLO_SHOT_WINDOW_MS=${request.windowMs}",
        )
        log("$ ${args.joinToString(" ")}")
        return OSProcessHandler(ProcessRunner.commandLine(args, request.projectDir))
    }

    private fun handleLine(device: TargetDevice, line: String, handler: OSProcessHandler) {
        when {
            line.contains("__NYLO_SHOTS_BEGIN__") -> {
                sawBegin = true
                log("  begin: ${kv(line, "count") ?: "?"} screens")
            }
            line.contains("__NYLO_SHOT_READY__") -> capture(device, line)
            line.contains("__NYLO_SHOT_SKIP__") -> {
                skipped++
                doneOverall++
                log("  skip ${kv(line, "route")} (${kv(line, "reason")})")
            }
            line.contains("__NYLO_SHOTS_DONE__") -> {
                log("  done: ${device.name}")
                handler.destroyProcess()
            }
            isNoteworthy(line) -> log("  $line")
        }
    }

    private fun capture(device: TargetDevice, line: String) {
        val locale = kv(line, "locale") ?: "default"
        val slug = kv(line, "slug") ?: "screen"
        val index = kv(line, "index")?.toIntOrNull() ?: captured
        val dir = File(File(request.outputDir, device.slug), locale).apply { mkdirs() }
        // Locale.ROOT: the default locale can render %02d with non-ASCII digits (e.g. arabic-indic).
        val target = File(dir, "%02d-%s.png".format(Locale.ROOT, index, slug))

        val ok = runCatching {
            when (device.platform) {
                DevicePlatform.IOS -> ProcessRunner.run(
                    listOf("xcrun", "simctl", "io", device.id, "screenshot", target.absolutePath),
                    timeoutMs = 30_000,
                ).exitCode == 0
                DevicePlatform.ANDROID -> ProcessRunner.runToFile(
                    listOf("adb", "-s", device.id, "exec-out", "screencap", "-p"),
                    target,
                )
            }
        }.getOrDefault(false)

        doneOverall++
        if (ok) {
            captured++
            files.add(target)
            onProgress(doneOverall, totalOverall, "${device.name} · $locale · /$slug")
            log("  ✓ ${device.slug}/$locale/${target.name}")
        } else {
            log("  ✗ capture failed: $slug ($locale)")
        }
    }

    private fun isNoteworthy(line: String): Boolean =
        line.contains("Launching") || line.contains("Installing") ||
            line.contains("Error") || line.contains("Exception") || line.contains("Unable to")

    private fun kv(line: String, key: String): String? =
        Regex("""\b${Regex.escape(key)}=(\S+)""").find(line)?.groupValues?.get(1)
}

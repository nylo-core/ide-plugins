package dev.nylo.plugin.screenshots.process

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import java.io.File

/**
 * Thin helpers for running external CLIs (`flutter`, `xcrun`, `adb`).
 *
 * Uses [GeneralCommandLine]'s default `CONSOLE` parent-environment so the user's
 * login-shell `PATH` is honored — important on macOS, where a GUI-launched IDE
 * otherwise can't find `flutter`/`xcrun`/`adb`.
 */
object ProcessRunner {
    fun commandLine(command: List<String>, workDir: File? = null): GeneralCommandLine {
        val cmd = GeneralCommandLine(command)
        if (workDir != null) cmd.workDirectory = workDir
        return cmd
    }

    /** Runs [command] to completion and captures its output as text. */
    fun run(command: List<String>, workDir: File? = null, timeoutMs: Int = 60_000): ProcessOutput =
        CapturingProcessHandler(commandLine(command, workDir)).runProcess(timeoutMs)

    /**
     * Runs [command] and streams its raw stdout bytes into [target]. Used for
     * `adb exec-out screencap -p`, whose PNG output must not be decoded as text.
     *
     * The copy itself is bounded by [timeoutMs]: a wedged child that keeps stdout open (a known
     * adb failure mode) would otherwise block `copyTo` forever and make the wait unreachable.
     */
    fun runToFile(command: List<String>, target: File, workDir: File? = null, timeoutMs: Int = 30_000): Boolean {
        val process = commandLine(command, workDir).createProcess()
        val deadline = System.currentTimeMillis() + timeoutMs
        val copier = Thread({
            runCatching { target.outputStream().use { out -> process.inputStream.copyTo(out) } }
        }, "nylo-screenshot-copy").apply {
            isDaemon = true
            start()
        }
        copier.join(timeoutMs.toLong())
        if (copier.isAlive) {
            process.destroyForcibly() // closes the pipe, unblocking the copier's read
            copier.join(2_000)
            return false
        }
        val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
        val finished = process.waitFor(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return false
        }
        return process.exitValue() == 0 && target.length() > 0
    }
}

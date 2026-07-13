package dev.nylo.plugin.localizations.cli

import com.google.gson.JsonParser
import dev.nylo.plugin.NyloBundle
import dev.nylo.plugin.screenshots.process.ProcessRunner
import java.io.File

/**
 * Runs `nylo locale:find-untranslated --stdout --format json` (the one localization concern that can't
 * be reimplemented natively — it needs the Dart analyzer) and parses its findings.
 *
 * [run] resolves the invocation via [NyloCliLocator], executes it off the EDT through the shared
 * [ProcessRunner] (which honors the login-shell PATH), and degrades gracefully: if the CLI can't start
 * or isn't installed it returns an [ScanOutcome] whose [ScanOutcome.error] points the user at the
 * install command. [parseFindings] is pure and unit-tested.
 */
object FindUntranslatedRunner {

    data class ScanOutcome(val findings: List<Finding>?, val error: String?)

    fun run(projectDir: File): ScanOutcome {
        val command = NyloCliLocator.resolve(projectDir) +
            listOf("locale:find-untranslated", "--stdout", "--format", "json")

        val output = try {
            ProcessRunner.run(command, projectDir, timeoutMs = 120_000)
        } catch (e: Exception) {
            return ScanOutcome(null, NyloBundle.message("localizations.cli.missing"))
        }

        if (output.exitCode != 0) {
            val combined = output.stderr + "\n" + output.stdout
            val message = if (looksLikeMissing(combined)) {
                NyloBundle.message("localizations.cli.missing")
            } else {
                NyloBundle.message("localizations.cli.failed", output.stderr.trim().ifEmpty { "exit code ${output.exitCode}" })
            }
            return ScanOutcome(null, message)
        }

        return try {
            ScanOutcome(parseFindings(output.stdout), null)
        } catch (e: Exception) {
            ScanOutcome(null, NyloBundle.message("localizations.cli.failed", e.message ?: "could not parse output"))
        }
    }

    /** Parses the `{ "findings": [ { file, line, value, context } ] }` JSON. Pure; tolerant of missing fields. */
    fun parseFindings(json: String): List<Finding> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyList()
        val root = JsonParser.parseString(trimmed)
        if (!root.isJsonObject) return emptyList()
        val array = root.asJsonObject.getAsJsonArray("findings") ?: return emptyList()
        return array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            val file = obj.get("file")?.takeUnless { it.isJsonNull }?.asString ?: return@mapNotNull null
            Finding(
                file = file,
                line = obj.get("line")?.takeUnless { it.isJsonNull }?.asInt ?: 0,
                value = obj.get("value")?.takeUnless { it.isJsonNull }?.asString ?: "",
                context = obj.get("context")?.takeUnless { it.isJsonNull }?.asString ?: "",
            )
        }
    }

    private fun looksLikeMissing(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("command not found") ||
            lower.contains("could not find") ||
            lower.contains("no such file") ||
            lower.contains("is not recognized")
    }
}

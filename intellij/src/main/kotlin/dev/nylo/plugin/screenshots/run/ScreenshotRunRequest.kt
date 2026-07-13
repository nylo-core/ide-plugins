package dev.nylo.plugin.screenshots.run

import dev.nylo.plugin.screenshots.model.NyloPage
import dev.nylo.plugin.screenshots.model.TargetDevice
import java.io.File

/** Everything the orchestrator needs for one capture run. */
data class ScreenshotRunRequest(
    val projectDir: File,
    val devices: List<TargetDevice>,
    val pages: List<NyloPage>,
    val locales: List<String>,
    val outputDir: File,
    val settleMs: Int,
    val windowMs: Int,
    val cleanStatusBar: Boolean,
) {
    val routesCsv: String get() = pages.joinToString(",") { it.route }
    val localesCsv: String get() = locales.joinToString(",")
    val totalPerDevice: Int get() = pages.size * locales.size
}

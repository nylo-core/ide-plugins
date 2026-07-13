package dev.nylo.plugin.logs.parse

import com.intellij.openapi.project.Project
import java.io.File
import java.time.LocalDate

/** A discovered daily log file and the date parsed from its filename. */
data class LogFileRef(val file: File, val date: LocalDate)

/**
 * Discovers Nylo daily log files (`<projectRoot>/logs/<yyyy-MM-dd>.log`) and resolves which
 * date to show by default. IDE-independent core (operates on [File]) so it can be unit-tested.
 */
object LogFileScanner {

    const val LOGS_DIR = "logs"
    private val FILENAME = Regex("""^(\d{4}-\d{2}-\d{2})\.log$""")

    fun logsDir(project: Project): File? = project.basePath?.let { File(it, LOGS_DIR) }

    fun scan(project: Project): List<LogFileRef> = logsDir(project)?.let { scan(it) } ?: emptyList()

    /** Returns the `.log` files in [dir], newest date first. Non-matching names are ignored. */
    fun scan(dir: File): List<LogFileRef> {
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files.mapNotNull { f ->
            if (!f.isFile) return@mapNotNull null
            val match = FILENAME.matchEntire(f.name) ?: return@mapNotNull null
            val date = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return@mapNotNull null
            LogFileRef(f, date)
        }.sortedByDescending { it.date }
    }

    /** Default selection: [today] when a file exists for it, otherwise the newest available date. */
    fun defaultDate(dates: List<LocalDate>, today: LocalDate): LocalDate? = when {
        dates.isEmpty() -> null
        today in dates -> today
        else -> dates.max()
    }
}

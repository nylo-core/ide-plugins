package dev.nylo.plugin.screenshots.project

import com.intellij.openapi.project.Project
import dev.nylo.plugin.screenshots.model.NyloPage
import java.io.File

/**
 * Extracts the app's pages from `lib/routes/router.dart`.
 *
 * The router lists routes as `router.add(XxxPage.path)` (optionally chained with
 * `.authenticatedRoute()` / `.previewRoute()`). Each `XxxPage` declares its real
 * route string in its own source as `static RouteView path = ("/x", ...)`, so we
 * additionally scan `lib/` for those declarations to resolve class -> route.
 */
object RouterParser {
    private val ADD_LINE = Regex("""router\s*\.\s*add\s*\(\s*(\w+)\s*\.\s*path""")
    private val AUTH = Regex("""\.authenticatedRoute\s*\(""")
    private val CLASS_DECL = Regex("""class\s+(\w+)""")
    private val PATH_DECL = Regex("""static\s+RouteView\s+path\s*=\s*\(\s*["']([^"']+)["']""")

    fun parse(project: Project): List<NyloPage> {
        val base = project.basePath?.let(::File) ?: return emptyList()
        val routerFile = File(base, "lib/routes/router.dart")
        if (!routerFile.isFile) return emptyList()

        val classToRoute = scanPageRoutes(File(base, "lib"))
        val pages = LinkedHashMap<String, NyloPage>()
        routerFile.readText().lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("//")) return@forEach
            val match = ADD_LINE.find(line) ?: return@forEach
            val className = match.groupValues[1]
            if (pages.containsKey(className)) return@forEach
            val resolved = classToRoute[className]
            pages[className] = NyloPage(
                className = className,
                route = resolved ?: deriveRoute(className),
                displayName = displayName(className),
                authenticated = AUTH.containsMatchIn(line),
                routeResolved = resolved != null,
            )
        }
        return pages.values.toList()
    }

    /** Map `XxxPage` -> "/x" by scanning every Dart file that declares a `RouteView path`. */
    private fun scanPageRoutes(libDir: File): Map<String, String> {
        if (!libDir.isDirectory) return emptyMap()
        val out = HashMap<String, String>()
        libDir.walkTopDown()
            .filter { it.isFile && it.extension == "dart" }
            .forEach { file ->
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                if (!text.contains("RouteView path")) return@forEach
                val classes = CLASS_DECL.findAll(text).toList()
                PATH_DECL.findAll(text).forEach { pathMatch ->
                    val route = pathMatch.groupValues[1]
                    val owningClass = classes.lastOrNull { it.range.first < pathMatch.range.first }
                    owningClass?.groupValues?.get(1)?.let { out.putIfAbsent(it, route) }
                }
            }
        return out
    }

    private fun deriveRoute(className: String): String =
        "/" + className.removeSuffix("Page").replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()

    private fun displayName(className: String): String =
        className.removeSuffix("Page").replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").trim()
}

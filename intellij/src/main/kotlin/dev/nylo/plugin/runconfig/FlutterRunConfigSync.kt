package dev.nylo.plugin.runconfig

import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.tools.ToolBeforeRunTask
import com.intellij.tools.ToolBeforeRunTaskProvider
import dev.nylo.plugin.env.EnvFile
import dev.nylo.plugin.state.NyloPluginState

/**
 * Creates, repairs and removes Flutter run configurations driven by the project's `.env*` files.
 *
 * The Flutter plugin (`io.flutter`) provides a `ConfigurationType` with id
 * `FlutterRunConfigurationType` whose factory builds `SdkRunConfig` instances.
 * We discover both at runtime via [ConfigurationTypeUtil] so we don't have to pin a
 * specific Flutter plugin version at compile time. `filePath` is set on the config's
 * `SdkFields` sub-object via reflection because we don't have Flutter on the classpath.
 *
 * Identity is tracked by env file name in [NyloPluginState.managedConfigs], not by display name,
 * so config renames and naming-scheme changes don't cause drift or duplicates. All mutations here
 * must run on the EDT (callers arrange that).
 */
object FlutterRunConfigSync {
    private const val FLUTTER_TYPE_ID = "FlutterRunConfigurationType"

    /**
     * The `$PROJECT_DIR$` macro form is valid only inside workspace.xml, where the platform
     * collapses/expands it during (de)serialization. At RUNTIME the Flutter plugin validates
     * `filePath` verbatim, so a config must carry the absolute path ([mainDartPath]) — setting the
     * literal macro produces "Entrypoint file not found at $PROJECT_DIR$/lib/main.dart". The
     * constant is kept to recognize (and heal) configs written by builds that had this bug.
     */
    private const val MAIN_DART_MACRO_PATH = "\$PROJECT_DIR\$/lib/main.dart"

    /** Ownership-map key prefix for migrated legacy configs whose env file is already gone. */
    private const val LEGACY_ORPHAN_KEY_PREFIX = "legacy::"
    private val LOG = logger<FlutterRunConfigSync>()

    /** The runtime (absolute, `/`-separated) entrypoint path; workspace.xml macro-izes it on save. */
    private fun mainDartPath(project: Project): String? =
        project.basePath?.let { "$it/lib/main.dart" }

    fun isFlutterAvailable(): Boolean = flutterFactory() != null

    /**
     * Reconciles run configurations against [envFiles] using the plugin's ownership map (additive:
     * never touches configs the plugin doesn't own). Returns `(addedNames, removedNames)`.
     */
    fun reconcile(project: Project, envFiles: List<EnvFile>, state: NyloPluginState): Pair<List<String>, List<String>> {
        val factory = flutterFactory() ?: return emptyList<String>() to emptyList()
        val runManager = RunManager.getInstance(project)

        migrateLegacyOwnership(state, envFiles, runManager.allSettings.map { it.name }.toSet())

        val plan = EnvConfigReconciler.plan(
            envFiles = envFiles,
            ownership = state.managedConfigs,
            existingConfigNames = runManager.allSettings.map { it.name }.toSet(),
        )

        val removed = mutableListOf<String>()
        for (name in plan.toRemove) {
            val settings = runManager.allSettings.firstOrNull { it.name == name } ?: continue
            // Ownership is tracked by name, and the name may have been taken over by a config the
            // user created after deleting ours. Only remove configs that look plugin-generated;
            // a false negative strands a stale config, a false positive would delete user work.
            if (!isPluginShaped(project, settings.configuration)) continue
            runManager.removeConfiguration(settings)
            removed.add(name)
        }
        plan.toForget.forEach(state::forgetConfig)

        val added = mutableListOf<String>()
        for (env in plan.toCreate) {
            val settings = runManager.createConfiguration(env.displayName, factory)
            // The Flutter factory names the template after the project (e.g. "pretalk_flutter"),
            // ignoring the name passed above — so addConfiguration would collide with the existing
            // project config and silently no-op. Force our intended name on both settings and config.
            settings.name = env.displayName
            settings.configuration.name = env.displayName
            mainDartPath(project)?.let { applyMainDartPath(settings.configuration, it) }
            // Add to RunManager BEFORE attaching before-run tasks: setBeforeRunTasks looks up
            // the configuration in RunManager's internal map, so the config has to be there.
            runManager.addConfiguration(settings)
            attachExternalToolBeforeRun(project, settings.configuration, env.toolActionId)
            state.rememberConfig(env.fileName, env.displayName)
            added.add(env.displayName)
        }

        // Repair: make sure each owned, still-present config carries its Metro before-run task
        // (e.g. one the user accidentally stripped the task from) and a valid entrypoint path.
        for (env in envFiles) {
            val ownedName = state.managedConfigs[env.fileName] ?: continue
            val settings = runManager.allSettings.firstOrNull { it.name == ownedName } ?: continue
            if (!isPluginShaped(project, settings.configuration)) continue
            ensureEntrypoint(project, settings.configuration)
            ensureBeforeRun(project, settings.configuration, env.toolActionId)
        }

        return added to removed
    }

    /**
     * One-time migration from the old name-only state ([NyloPluginState.legacyRunConfigNames]) to the
     * ownership map. Seeds ownership for any legacy name that maps to a scanned env file and still
     * exists as a config. A legacy config whose env file is already gone is seeded under a synthetic
     * key that can never match a scanned file, so the very next [EnvConfigReconciler.plan] removes
     * and forgets it — the same cleanup the old orphan sweep would have done. Clears the legacy set
     * so this runs only once.
     */
    private fun migrateLegacyOwnership(state: NyloPluginState, envFiles: List<EnvFile>, existingNames: Set<String>) {
        if (state.managedConfigs.isNotEmpty() || state.legacyRunConfigNames.isEmpty()) return
        for (legacyName in state.legacyRunConfigNames) {
            if (legacyName !in existingNames) continue
            val env = envFiles.firstOrNull { it.displayName == legacyName }
            state.rememberConfig(env?.fileName ?: (LEGACY_ORPHAN_KEY_PREFIX + legacyName), legacyName)
        }
        state.clearLegacyRunConfigs()
    }

    private fun flutterFactory(): ConfigurationFactory? {
        val type = ConfigurationTypeUtil.findConfigurationType(FLUTTER_TYPE_ID) ?: return null
        return type.configurationFactories.firstOrNull()
    }

    /**
     * Whether [configuration] looks like one this plugin generates: the Flutter run type targeting
     * the project-root `lib/main.dart` — either the runtime absolute path or the unexpanded macro
     * form older builds wrote. Used as a guard before mutating or removing a config resolved by
     * display name, so a user-created config that happens to reuse a generated name (including one
     * pointing at some other module's `lib/main.dart`) is never touched.
     */
    private fun isPluginShaped(project: Project, configuration: RunConfiguration): Boolean {
        if (configuration.type.id != FLUTTER_TYPE_ID) return false
        val path = readMainDartPath(configuration)?.replace('\\', '/') ?: return false
        return path == MAIN_DART_MACRO_PATH || path == mainDartPath(project)
    }

    /** Heals a config whose runtime path is the unexpanded macro a buggy build wrote. */
    private fun ensureEntrypoint(project: Project, configuration: RunConfiguration) {
        if (readMainDartPath(configuration)?.replace('\\', '/') == MAIN_DART_MACRO_PATH) {
            mainDartPath(project)?.let { applyMainDartPath(configuration, it) }
        }
    }

    /** Reads `getFields().getFilePath()` reflectively; see [applyMainDartPath] for why reflection. */
    private fun readMainDartPath(configuration: RunConfiguration): String? = try {
        val fields = configuration.javaClass.methods
            .firstOrNull { it.name == "getFields" && it.parameterCount == 0 }
            ?.invoke(configuration)
        fields?.javaClass?.methods
            ?.firstOrNull { it.name == "getFilePath" && it.parameterCount == 0 }
            ?.invoke(fields) as? String
    } catch (e: Exception) {
        LOG.warn("Failed to read filePath from Flutter run configuration", e)
        null
    }

    /**
     * Sets `lib/main.dart` as the run target. The Flutter plugin's `SdkRunConfig` exposes
     * `getFields(): SdkFields` whose `setFilePath(String)` is what we need. Done via
     * reflection so we don't have a compile-time Flutter dependency.
     */
    private fun applyMainDartPath(configuration: RunConfiguration, path: String) {
        runCatching {
            val getFields = configuration.javaClass.methods
                .firstOrNull { it.name == "getFields" && it.parameterCount == 0 }
                ?: return logMissing("getFields() on ${configuration.javaClass.name}")
            val fields = getFields.invoke(configuration)
                ?: return logMissing("getFields() returned null on ${configuration.javaClass.name}")
            val setFilePath = fields.javaClass.methods
                .firstOrNull { it.name == "setFilePath" && it.parameterCount == 1 }
                ?: return logMissing("setFilePath(String) on ${fields.javaClass.name}")
            setFilePath.invoke(fields, path)
        }.onFailure { LOG.warn("Failed to set filePath on Flutter run configuration", it) }
    }

    private fun logMissing(what: String) {
        LOG.warn("Could not locate $what; created config without filePath")
    }

    private fun attachExternalToolBeforeRun(project: Project, configuration: RunConfiguration, toolActionId: String) {
        // BeforeRunTaskProvider.getProvider(project, key) uses identity (==) on the Key,
        // and the registered ToolBeforeRunTaskProvider's Key is package-private. Iterate
        // the extension list and pick by class instead. The ToolBeforeRunTaskProvider class
        // has bridge methods that confuse Kotlin's overload resolution on createTask, so we
        // walk it through the abstract parent that has a single typed createTask signature.
        @Suppress("UNCHECKED_CAST")
        val provider = BeforeRunTaskProvider.EP_NAME.getExtensions(project)
            .firstOrNull { it is ToolBeforeRunTaskProvider } as? BeforeRunTaskProvider<ToolBeforeRunTask>
            ?: run {
                LOG.warn("ToolBeforeRunTaskProvider not registered; skipping Metro before-run task for $toolActionId")
                return
            }
        val task: ToolBeforeRunTask = provider.createTask(configuration) ?: return
        task.toolActionId = toolActionId
        task.isEnabled = true

        val runManagerImpl = RunManager.getInstance(project) as? RunManagerImpl ?: run {
            LOG.warn("RunManager is not RunManagerImpl; cannot attach before-run task")
            return
        }
        val current = runManagerImpl.getBeforeRunTasks(configuration).toMutableList()
        current.add(task)
        runManagerImpl.setBeforeRunTasks(configuration, current)
    }

    /** Whether [configuration] already has our Metro before-run task ([toolActionId]). */
    private fun hasBeforeRun(project: Project, configuration: RunConfiguration, toolActionId: String): Boolean {
        val runManagerImpl = RunManager.getInstance(project) as? RunManagerImpl ?: return false
        return runManagerImpl.getBeforeRunTasks(configuration)
            .any { it is ToolBeforeRunTask && it.toolActionId == toolActionId }
    }

    /** Attaches the Metro before-run task only if it isn't already present (idempotent repair). */
    private fun ensureBeforeRun(project: Project, configuration: RunConfiguration, toolActionId: String) {
        if (!hasBeforeRun(project, configuration, toolActionId)) {
            attachExternalToolBeforeRun(project, configuration, toolActionId)
        }
    }
}

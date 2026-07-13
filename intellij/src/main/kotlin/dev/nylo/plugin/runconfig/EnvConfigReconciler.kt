package dev.nylo.plugin.runconfig

import dev.nylo.plugin.env.EnvFile

/**
 * Pure (IDE-free) decision logic for keeping generated run configurations in sync with the
 * project's `.env*` files, given the plugin's ownership map and the names of the configs that
 * currently exist.
 *
 * Identity is by **env file name**, not by display name: [ownership] maps an env file name
 * (e.g. `.env.staging`) to the display name of the config the plugin generated for it. This keeps
 * the reconcile stable across config renames and naming-scheme changes.
 *
 * The policy is **additive**: configs the plugin does not own are never created over (no
 * duplicates) and never removed.
 */
object EnvConfigReconciler {

    data class Plan(
        /** Env files that need a brand-new config (named [EnvFile.displayName]). */
        val toCreate: List<EnvFile>,
        /** Display names of owned configs whose env file is gone and that still exist — remove them. */
        val toRemove: List<String>,
        /** Ownership keys (env file names) to drop because their env file no longer exists. */
        val toForget: List<String>,
    )

    /**
     * @param envFiles            the `.env*` files currently on disk
     * @param ownership           env file name -> generated config display name (plugin-owned)
     * @param existingConfigNames names of every run configuration that currently exists in the IDE
     */
    fun plan(
        envFiles: List<EnvFile>,
        ownership: Map<String, String>,
        existingConfigNames: Set<String>,
    ): Plan {
        val scannedFileNames = envFiles.map { it.fileName }.toSet()

        // Owned entries whose env file disappeared: forget the ownership; remove the config if present.
        val goneFileNames = ownership.keys.filter { it !in scannedFileNames }
        val toRemove = goneFileNames
            .mapNotNull { ownership[it] }
            .filter { it in existingConfigNames }

        // Each scanned env file needs a config unless we already own a live one for it, and we only
        // create when the target name is free (never duplicate an un-owned, same-named config).
        val toCreate = envFiles.filter { env ->
            val ownedName = ownership[env.fileName]
            val ownedAlive = ownedName != null && ownedName in existingConfigNames
            !ownedAlive && env.displayName !in existingConfigNames
        }

        return Plan(toCreate = toCreate, toRemove = toRemove, toForget = goneFileNames)
    }
}

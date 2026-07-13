package dev.nylo.plugin.runconfig

import dev.nylo.plugin.env.EnvFile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class EnvConfigReconcilerTest {

    private fun env(fileName: String, displayName: String): EnvFile =
        EnvFile(file = File(fileName), suffix = null, displayName = displayName)

    @Test
    fun `creates configs for env files with no owned config and a free name`() {
        val envFiles = listOf(
            env(".env", "Default"),
            env(".env.dev", "Dev"),
            env(".env.staging", "Staging"),
        )
        // Pre-existing, un-owned configs from an earlier build — must be left alone.
        val existing = setOf("Developing", "Production", "Valet", "main.dart")

        val plan = EnvConfigReconciler.plan(envFiles, ownership = emptyMap(), existingConfigNames = existing)

        assertEquals(listOf("Default", "Dev", "Staging"), plan.toCreate.map { it.displayName })
        assertEquals(emptyList<String>(), plan.toRemove)
        assertEquals(emptyList<String>(), plan.toForget)
    }

    @Test
    fun `does not duplicate an un-owned config that already uses the target name`() {
        val envFiles = listOf(env(".env.valet", "Valet"))
        val plan = EnvConfigReconciler.plan(envFiles, ownership = emptyMap(), existingConfigNames = setOf("Valet"))
        assertEquals(emptyList<EnvFile>(), plan.toCreate)
    }

    @Test
    fun `leaves an owned config that still exists`() {
        val envFiles = listOf(env(".env.dev", "Dev"))
        val plan = EnvConfigReconciler.plan(
            envFiles,
            ownership = mapOf(".env.dev" to "Dev"),
            existingConfigNames = setOf("Dev"),
        )
        assertEquals(emptyList<EnvFile>(), plan.toCreate)
        assertEquals(emptyList<String>(), plan.toRemove)
    }

    @Test
    fun `removes and forgets an owned config whose env file is gone`() {
        val plan = EnvConfigReconciler.plan(
            envFiles = listOf(env(".env", "Default")),
            ownership = mapOf(".env" to "Default", ".env.old" to "Old"),
            existingConfigNames = setOf("Default", "Old"),
        )
        assertEquals(listOf("Old"), plan.toRemove)
        assertEquals(listOf(".env.old"), plan.toForget)
        assertEquals(emptyList<EnvFile>(), plan.toCreate)
    }

    @Test
    fun `forgets a gone env file even when its config was already deleted`() {
        val plan = EnvConfigReconciler.plan(
            envFiles = emptyList(),
            ownership = mapOf(".env.old" to "Old"),
            existingConfigNames = emptySet(),
        )
        assertEquals(emptyList<String>(), plan.toRemove)
        assertEquals(listOf(".env.old"), plan.toForget)
    }

    @Test
    fun `recreates an owned config that the user deleted while its env file remains`() {
        val envFiles = listOf(env(".env.staging", "Staging"))
        val plan = EnvConfigReconciler.plan(
            envFiles,
            ownership = mapOf(".env.staging" to "Staging"),
            existingConfigNames = emptySet(), // config no longer present
        )
        assertEquals(listOf("Staging"), plan.toCreate.map { it.displayName })
    }
}

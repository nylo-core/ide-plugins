package dev.nylo.plugin.env

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvFileWatcherTest {

    private val root = "/Users/me/project"

    @Test
    fun `matches env files directly in the project root`() {
        assertTrue(EnvFileWatcher.isEnvFileChangeInRoot(root, "$root/.env"))
        assertTrue(EnvFileWatcher.isEnvFileChangeInRoot(root, "$root/.env.staging"))
        assertTrue(EnvFileWatcher.isEnvFileChangeInRoot(root, "$root/.env.prod.staging"))
    }

    @Test
    fun `rejects env files nested below the root`() {
        assertFalse(EnvFileWatcher.isEnvFileChangeInRoot(root, "$root/lib/.env.staging"))
        assertFalse(EnvFileWatcher.isEnvFileChangeInRoot(root, "$root/config/.env"))
    }

    @Test
    fun `rejects env-example and non-env files in the root`() {
        assertFalse(EnvFileWatcher.isEnvFileChangeInRoot(root, "$root/.env-example"))
        assertFalse(EnvFileWatcher.isEnvFileChangeInRoot(root, "$root/.envrc"))
        assertFalse(EnvFileWatcher.isEnvFileChangeInRoot(root, "$root/pubspec.yaml"))
    }

    @Test
    fun `tolerates a trailing slash on the base path`() {
        assertTrue(EnvFileWatcher.isEnvFileChangeInRoot("$root/", "$root/.env.dev"))
    }
}

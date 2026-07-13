package dev.nylo.plugin.localizations.cli

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NyloCliLocatorTest {

    private lateinit var projectDir: File

    @Before
    fun setUp() {
        projectDir = Files.createTempDirectory("nylo-cli-test").toFile()
    }

    @After
    fun tearDown() {
        projectDir.deleteRecursively()
    }

    @Test
    fun `uses dart run when pubspec depends on nylo_installer`() {
        File(projectDir, "pubspec.yaml").writeText("dependencies:\n  nylo_installer: ^1.0.0\n")
        assertEquals(listOf("dart", "run", "nylo_installer:nylo"), NyloCliLocator.resolve(projectDir))
    }

    @Test
    fun `uses dart run when only the lockfile mentions nylo_installer`() {
        File(projectDir, "pubspec.yaml").writeText("name: app\n")
        File(projectDir, "pubspec.lock").writeText("packages:\n  nylo_installer:\n    version: \"1.0.0\"\n")
        assertEquals(listOf("dart", "run", "nylo_installer:nylo"), NyloCliLocator.resolve(projectDir))
    }

    @Test
    fun `falls back to the global nylo CLI`() {
        File(projectDir, "pubspec.yaml").writeText("name: app\ndependencies:\n  nylo_framework: ^7.0.0\n")
        assertEquals(listOf("nylo"), NyloCliLocator.resolve(projectDir))
    }
}

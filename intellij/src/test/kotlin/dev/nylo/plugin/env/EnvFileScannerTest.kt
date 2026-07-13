package dev.nylo.plugin.env

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EnvFileScannerTest {

    private lateinit var projectDir: File

    @Before
    fun setUp() {
        projectDir = Files.createTempDirectory("nylo-scanner-test").toFile()
    }

    @After
    fun tearDown() {
        projectDir.deleteRecursively()
    }

    @Test
    fun `returns empty when project dir does not exist`() {
        val ghost = File(projectDir, "does-not-exist")
        assertTrue(EnvFileScanner.scan(ghost).isEmpty())
    }

    @Test
    fun `returns empty when no env files present`() {
        File(projectDir, "pubspec.yaml").writeText("name: foo\n")
        File(projectDir, ".gitignore").writeText("build/\n")
        assertTrue(EnvFileScanner.scan(projectDir).isEmpty())
    }

    @Test
    fun `excludes env-example`() {
        File(projectDir, ".env-example").writeText("FOO=BAR\n")
        File(projectDir, ".env").writeText("FOO=BAZ\n")
        val results = EnvFileScanner.scan(projectDir)
        assertEquals(1, results.size)
        assertEquals(".env", results.single().fileName)
        assertEquals("Default", results.single().displayName)
    }

    @Test
    fun `picks up dotted suffixes`() {
        File(projectDir, ".env").writeText("X=1")
        File(projectDir, ".env.dev").writeText("X=1")
        File(projectDir, ".env.prod").writeText("X=1")
        File(projectDir, ".env.prod.staging").writeText("X=1")
        File(projectDir, ".env.valet").writeText("X=1")
        File(projectDir, ".env-example").writeText("X=1")

        val results = EnvFileScanner.scan(projectDir)
        val displayNames = results.map { it.displayName }.toSet()
        assertEquals(setOf("Default", "Dev", "Prod", "Prod Staging", "Valet"), displayNames)
    }

    @Test
    fun `results are sorted by display name`() {
        File(projectDir, ".env.valet").writeText("X=1")
        File(projectDir, ".env.dev").writeText("X=1")
        File(projectDir, ".env").writeText("X=1")
        File(projectDir, ".env.prod").writeText("X=1")

        val displayNames = EnvFileScanner.scan(projectDir).map { it.displayName }
        assertEquals(listOf("Default", "Dev", "Prod", "Valet"), displayNames)
    }

    @Test
    fun `isEnvFileName accepts env files and rejects everything else`() {
        assertTrue(EnvFileScanner.isEnvFileName(".env"))
        assertTrue(EnvFileScanner.isEnvFileName(".env.dev"))
        assertTrue(EnvFileScanner.isEnvFileName(".env.prod.staging"))

        assertFalse(EnvFileScanner.isEnvFileName(".env-example"))
        assertFalse(EnvFileScanner.isEnvFileName(".envrc"))
        assertFalse(EnvFileScanner.isEnvFileName("env.dev"))
        assertFalse(EnvFileScanner.isEnvFileName("random.txt"))
    }

    @Test
    fun `ignores non-env files even if they share a prefix`() {
        File(projectDir, ".env.dev").writeText("X=1")
        File(projectDir, ".envrc").writeText("export FOO=1\n")  // direnv - should be ignored
        File(projectDir, "env.dev").writeText("X=1")            // missing leading dot - should be ignored

        val results = EnvFileScanner.scan(projectDir)
        assertEquals(1, results.size)
        assertEquals(".env.dev", results.single().fileName)
    }
}

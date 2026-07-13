package dev.nylo.plugin.project

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NyloProjectDetectorTest {

    private lateinit var projectDir: File

    @Before
    fun setUp() {
        projectDir = Files.createTempDirectory("nylo-detector-test").toFile()
    }

    @After
    fun tearDown() {
        projectDir.deleteRecursively()
    }

    @Test
    fun `false when pubspec missing`() {
        assertFalse(NyloProjectDetector.isNyloProject(projectDir))
    }

    @Test
    fun `false when pubspec lacks nylo_framework`() {
        File(projectDir, "pubspec.yaml").writeText(
            """
            name: foo
            dependencies:
              flutter:
                sdk: flutter
              http: ^1.0.0
            """.trimIndent()
        )
        assertFalse(NyloProjectDetector.isNyloProject(projectDir))
    }

    @Test
    fun `true when pubspec lists nylo_framework as direct dependency`() {
        File(projectDir, "pubspec.yaml").writeText(
            """
            name: foo
            dependencies:
              flutter:
                sdk: flutter
              nylo_framework: ^7.0.0
            """.trimIndent()
        )
        assertTrue(NyloProjectDetector.isNyloProject(projectDir))
    }

    @Test
    fun `true with leading whitespace and version pin`() {
        File(projectDir, "pubspec.yaml").writeText(
            """
            dependencies:
              nylo_framework:
                git:
                  url: https://github.com/nylo-core/nylo
                  ref: main
            """.trimIndent()
        )
        assertTrue(NyloProjectDetector.isNyloProject(projectDir))
    }

    @Test
    fun `false when nylo_framework appears only inside a comment string`() {
        File(projectDir, "pubspec.yaml").writeText(
            """
            # we use to depend on nylo_framework: but moved off it
            name: foo
            dependencies:
              flutter:
                sdk: flutter
            """.trimIndent()
        )
        // The regex requires the line to start with optional whitespace followed by `nylo_framework:`,
        // not a `#` comment marker, so this should be false.
        assertFalse(NyloProjectDetector.isNyloProject(projectDir))
    }
}

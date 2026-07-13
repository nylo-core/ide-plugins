package dev.nylo.plugin.env

import org.junit.Assert.assertEquals
import org.junit.Test

class MetroToolNamingTest {

    @Test
    fun `tool name follows the existing manual pattern`() {
        assertEquals("Metro make:env Dev", MetroToolNaming.toolName("Dev"))
        assertEquals("Metro make:env Prod Staging", MetroToolNaming.toolName("Prod Staging"))
        assertEquals("Metro make:env Default", MetroToolNaming.toolName("Default"))
    }

    @Test
    fun `EnvFile produces matching toolActionId`() {
        val env = EnvFile(file = java.io.File(".env.prod"), suffix = "prod", displayName = "Prod")
        assertEquals("Tool_External Tools_Metro make:env Prod", env.toolActionId)
    }
}

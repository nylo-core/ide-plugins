package dev.nylo.plugin.env

import org.junit.Assert.assertEquals
import org.junit.Test

class EnvFileNamingTest {

    @Test
    fun `null suffix becomes Default`() {
        assertEquals("Default", EnvFileNaming.displayName(null))
    }

    @Test
    fun `empty suffix becomes Default`() {
        assertEquals("Default", EnvFileNaming.displayName(""))
        assertEquals("Default", EnvFileNaming.displayName("   "))
    }

    @Test
    fun `single segment is title-cased`() {
        assertEquals("Dev", EnvFileNaming.displayName("dev"))
        assertEquals("Prod", EnvFileNaming.displayName("prod"))
        assertEquals("Valet", EnvFileNaming.displayName("valet"))
    }

    @Test
    fun `dotted segments are split and title-cased and space joined`() {
        assertEquals("Prod Staging", EnvFileNaming.displayName("prod.staging"))
        assertEquals("Dev Local Mac", EnvFileNaming.displayName("dev.local.mac"))
    }

    @Test
    fun `existing capitalisation is preserved on non-leading characters`() {
        assertEquals("ProdQA", EnvFileNaming.displayName("prodQA"))
    }

    @Test
    fun `consecutive dots produce no empty segments`() {
        assertEquals("Dev Local", EnvFileNaming.displayName("dev..local"))
    }
}

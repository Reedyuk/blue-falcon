package dev.bluefalcon.engine.windows

import dev.bluefalcon.core.BondCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsEngineAdapterSelectionTest {
    @Test
    fun `windows engine reports adapter selection support`() {
        val engine = WindowsEngine()
        assertTrue(engine.supportsAdapterSelection)
    }

    @Test
    fun `windows engine reports pairing support`() {
        val engine = WindowsEngine()
        assertEquals(BondCapability.Supported, engine.centralCapabilities.bondCapability)
    }
}

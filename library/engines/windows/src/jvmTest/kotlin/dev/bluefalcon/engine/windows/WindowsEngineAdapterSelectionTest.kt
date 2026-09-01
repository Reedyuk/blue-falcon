package dev.bluefalcon.engine.windows

import kotlin.test.Test
import kotlin.test.assertTrue

class WindowsEngineAdapterSelectionTest {
    @Test
    fun `windows engine reports adapter selection support`() {
        val engine = WindowsEngine()
        assertTrue(engine.supportsAdapterSelection)
    }
}

package dev.bluefalcon.engine.macos

import kotlin.test.Test
import kotlin.test.assertTrue

class MacosEngineDelegationTest {

    @Test
    fun `public wrapper exposes shared Apple central capabilities`() {
        val engine = MacosEngine()

        assertTrue(engine.centralCapabilities.reliableWriteResults)
        assertTrue(engine.centralCapabilities.writeWithoutResponseReadiness)
        assertTrue(engine.centralCapabilities.perConnectionMaximumWriteLength)
        assertTrue(engine.centralCapabilities.notificationSubscriptionResults)
    }
}

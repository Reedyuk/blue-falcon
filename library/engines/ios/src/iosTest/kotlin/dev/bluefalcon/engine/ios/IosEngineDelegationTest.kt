package dev.bluefalcon.engine.ios

import kotlin.test.Test
import kotlin.test.assertTrue

class IosEngineDelegationTest {

    @Test
    fun `public wrapper exposes shared Apple central capabilities`() {
        val engine = IosEngine()

        assertTrue(engine.centralCapabilities.reliableWriteResults)
        assertTrue(engine.centralCapabilities.writeWithoutResponseReadiness)
        assertTrue(engine.centralCapabilities.perConnectionMaximumWriteLength)
        assertTrue(engine.centralCapabilities.notificationSubscriptionResults)
    }
}

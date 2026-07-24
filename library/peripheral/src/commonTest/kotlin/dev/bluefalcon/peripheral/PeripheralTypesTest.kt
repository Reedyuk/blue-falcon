package dev.bluefalcon.peripheral

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration

class PeripheralTypesTest {

    @Test
    fun sessionIdsWithSameOpaqueValueAreEqual() {
        assertEquals(
            PeripheralSessionId("central-1"),
            PeripheralSessionId("central-1"),
        )
    }

    @Test
    fun emptySessionIdIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            PeripheralSessionId("")
        }
    }

    @Test
    fun unsupportedCapabilitiesAreExplicit() {
        val capabilities = PeripheralCapabilities.Unsupported

        assertFalse(capabilities.localGattServer)
        assertFalse(capabilities.connectableAdvertising)
        assertFalse(capabilities.multiCentral)
        assertFalse(capabilities.targetedNotifications)
        assertFalse(capabilities.notificationReadiness)
        assertFalse(capabilities.maximumUpdateValueLength)
        assertFalse(capabilities.forcedDisconnect)
        assertFalse(capabilities.connectionLifecycleVisibility)
        assertFalse(capabilities.preparedWrites)
        assertFalse(capabilities.stateRestoration)
    }

    @Test
    fun responseDeadlineMustBePositiveAndFinite() {
        assertFailsWith<IllegalArgumentException> {
            PeripheralConfig(
                advertiseConfig = AdvertiseConfig(),
                responseDeadline = Duration.ZERO,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PeripheralConfig(
                advertiseConfig = AdvertiseConfig(),
                responseDeadline = Duration.INFINITE,
            )
        }
    }
}

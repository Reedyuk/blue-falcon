package dev.bluefalcon.peripheral

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
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

    @Test
    fun advertiseConfigDoesNotSendSystemDeviceNameByDefault() {
        val config = AdvertiseConfig(localName = "node")

        assertFalse(config.includeSystemDeviceName)
        assertTrue(config.serviceData.isEmpty())
    }

    @Test
    fun backendCopyDetachesServiceDataPayloads() {
        val payload = byteArrayOf(1, 2, 3)
        val config = PeripheralConfig(AdvertiseConfig(serviceData = mapOf("180d" to payload)))

        val copied = config.copyForBackend().advertiseConfig.serviceData.getValue("180d")
        payload[0] = 9

        assertNotSame(payload, copied)
        assertContentEquals(byteArrayOf(1, 2, 3), copied)
    }
}

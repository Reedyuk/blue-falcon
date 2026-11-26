package dev.bluefalcon.peripheral.android

import dev.bluefalcon.peripheral.AdvertiseConfig
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class AndroidAdvertisementPacketTest {
    private val serviceUuid = "0000180d-0000-1000-8000-00805f9b34fb"

    @Test
    fun defaultConfigScanResponseContainsNoDeviceName() {
        val scanResponse = AdvertiseConfig().toAndroidScanResponsePacket()

        assertFalse(scanResponse.includeDeviceName)
    }

    @Test
    fun localNameAloneDoesNotLeakSystemDeviceName() {
        val config = AdvertiseConfig(localName = "mesh-node", serviceUuids = listOf(serviceUuid))

        assertFalse(config.toAndroidScanResponsePacket().includeDeviceName)
        assertFalse(config.toAndroidAdvertisementPacket().includeDeviceName)
    }

    @Test
    fun systemDeviceNameIsSentOnlyInScanResponseWhenRequested() {
        val config = AdvertiseConfig(includeSystemDeviceName = true)

        assertTrue(config.toAndroidScanResponsePacket().includeDeviceName)
        assertFalse(config.toAndroidAdvertisementPacket().includeDeviceName)
    }

    @Test
    fun primaryPacketCarriesUuidsManufacturerAndServiceData() {
        val payload = byteArrayOf(1, 2, 3)
        val config = AdvertiseConfig(
            serviceUuids = listOf(serviceUuid),
            manufacturerData = mapOf(0x004C to byteArrayOf(9)),
            includeTxPower = true,
            serviceData = mapOf(serviceUuid to payload),
        )

        val packet = config.toAndroidAdvertisementPacket()

        assertEquals(listOf(Uuid.parse(serviceUuid)), packet.serviceUuids)
        assertContentEquals(byteArrayOf(9), packet.manufacturerData.getValue(0x004C))
        assertContentEquals(payload, packet.serviceData.getValue(Uuid.parse(serviceUuid)))
        assertTrue(packet.includeTxPowerLevel)
        assertTrue(config.toAndroidScanResponsePacket().serviceData.isEmpty())
    }
}

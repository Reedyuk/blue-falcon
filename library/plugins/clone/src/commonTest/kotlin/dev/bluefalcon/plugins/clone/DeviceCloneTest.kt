package dev.bluefalcon.plugins.clone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceCloneTest {

    @Test
    fun testDeviceCloneSerialization() {
        val plugin = DeviceClonePlugin(CloneConfig())

        val clone = DeviceClone(
            peripheralId = "00:11:22:33:44:55",
            peripheralName = "Test Device",
            capturedAt = "2026-05-15T10:00:00Z",
            platform = "Android",
            rssi = -65.0f,
            mtuSize = 517,
            advertisement = AdvertisementClone(
                localName = "Test Device",
                manufacturerData = byteArrayOf(0x01, 0x02, 0x03),
                serviceUuids = listOf("0000180a-0000-1000-8000-00805f9b34fb"),
                txPowerLevel = 4
            ),
            services = listOf(
                ServiceClone(
                    uuid = "0000180a-0000-1000-8000-00805f9b34fb",
                    name = "Device Information",
                    characteristics = listOf(
                        CharacteristicClone(
                            uuid = "00002a29-0000-1000-8000-00805f9b34fb",
                            name = "Manufacturer Name",
                            value = "TestCo".encodeToByteArray(),
                            isNotifying = false,
                            descriptors = listOf(
                                DescriptorClone(
                                    uuid = "00002902-0000-1000-8000-00805f9b34fb",
                                    value = byteArrayOf(0x00, 0x00)
                                )
                            )
                        )
                    )
                )
            )
        )

        // Serialize to JSON
        val json = plugin.exportToJson(clone)
        assertNotNull(json)
        assertTrue(json.contains("Test Device"))
        assertTrue(json.contains("00:11:22:33:44:55"))
        assertTrue(json.contains("Device Information"))
        assertTrue(json.contains("Manufacturer Name"))

        // Deserialize back
        val restored = plugin.importFromJson(json)
        assertEquals(clone.peripheralId, restored.peripheralId)
        assertEquals(clone.peripheralName, restored.peripheralName)
        assertEquals(clone.capturedAt, restored.capturedAt)
        assertEquals(clone.platform, restored.platform)
        assertEquals(clone.rssi, restored.rssi)
        assertEquals(clone.mtuSize, restored.mtuSize)
        assertEquals(clone.services.size, restored.services.size)
        assertEquals(clone.services[0].uuid, restored.services[0].uuid)
        assertEquals(clone.services[0].name, restored.services[0].name)
        assertEquals(clone.services[0].characteristics.size, restored.services[0].characteristics.size)
        assertEquals(
            clone.services[0].characteristics[0].uuid,
            restored.services[0].characteristics[0].uuid
        )
        assertEquals(
            clone.services[0].characteristics[0].descriptors.size,
            restored.services[0].characteristics[0].descriptors.size
        )
    }

    @Test
    fun testEmptyDeviceClone() {
        val plugin = DeviceClonePlugin(CloneConfig())

        val clone = DeviceClone(
            peripheralId = "test-id",
            peripheralName = null,
            capturedAt = "",
            platform = "iOS"
        )

        val json = plugin.exportToJson(clone)
        val restored = plugin.importFromJson(json)

        assertEquals("test-id", restored.peripheralId)
        assertNull(restored.peripheralName)
        assertEquals("iOS", restored.platform)
        assertTrue(restored.services.isEmpty())
    }

    @Test
    fun testAdvertisementCache() {
        val plugin = DeviceClonePlugin(CloneConfig())

        val advertisement = AdvertisementClone(
            localName = "Cached Device",
            serviceUuids = listOf("1800", "1801")
        )

        plugin.cacheAdvertisementData("device-123", advertisement)

        val cached = plugin.getCachedAdvertisement("device-123")
        assertNotNull(cached)
        assertEquals("Cached Device", cached.localName)
        assertEquals(2, cached.serviceUuids.size)

        assertNull(plugin.getCachedAdvertisement("nonexistent"))

        plugin.clearCache()
        assertNull(plugin.getCachedAdvertisement("device-123"))
    }

    @Test
    fun testCloneConfigDefaults() {
        val config = CloneConfig()
        assertTrue(config.readCharacteristicValues)
        assertTrue(config.readDescriptorValues)
        assertTrue(config.includeAdvertisementData)
        assertEquals("Unknown", config.platform)
        assertNull(config.callback)
    }
}

package dev.bluefalcon.plugins.clone

import dev.bluefalcon.core.*
import dev.bluefalcon.core.plugin.*
import kotlinx.serialization.json.Json

/**
 * Plugin that captures a complete snapshot of a BLE peripheral device,
 * including advertisement data, GATT services, characteristics, and descriptors.
 *
 * Usage:
 * ```
 * val clonePlugin = DeviceClonePlugin(CloneConfig().apply {
 *     readCharacteristicValues = true
 *     readDescriptorValues = true
 *     platform = "Android"
 * })
 * blueFalcon.plugins.install(clonePlugin)
 *
 * // After connecting to a peripheral and discovering services:
 * val clone = clonePlugin.cloneDevice(peripheral)
 * val json = clonePlugin.exportToJson(clone)
 * ```
 */
class DeviceClonePlugin(
    private val config: CloneConfig = CloneConfig()
) : BlueFalconPlugin {

    private var client: BlueFalconClient? = null

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /**
     * Advertisement data cache — populated from scan events via onAfterScan hook.
     * Keyed by peripheral UUID.
     */
    private val advertisementCache = mutableMapOf<String, AdvertisementClone>()

    override fun install(client: BlueFalconClient, config: PluginConfig) {
        this.client = client
    }

    override suspend fun onBeforeScan(call: ScanCall): ScanCall = call

    override suspend fun onAfterScan(call: ScanCall) {
        // Advertisement data is captured per-peripheral in cacheAdvertisementData
    }

    /**
     * Cache advertisement data for a peripheral.
     * Call this from your scan result handler to store advertisement info before connecting.
     *
     * @param peripheralId The peripheral UUID/identifier
     * @param advertisement The advertisement data to cache
     */
    fun cacheAdvertisementData(peripheralId: String, advertisement: AdvertisementClone) {
        advertisementCache[peripheralId] = advertisement
    }

    /**
     * Clone a connected peripheral device.
     *
     * The peripheral must already be connected with services discovered.
     * This method will iterate through all services and characteristics,
     * optionally reading their values and descriptors.
     *
     * @param peripheral The connected peripheral to clone
     * @param engine The BlueFalconEngine to use for read operations
     * @return A complete DeviceClone snapshot
     * @throws IllegalStateException if the peripheral has no discovered services
     */
    suspend fun cloneDevice(
        peripheral: BluetoothPeripheral,
        engine: BlueFalconEngine
    ): DeviceClone {
        try {
            val services = peripheral.services
            if (services.isEmpty()) {
                throw IllegalStateException(
                    "No services discovered on peripheral ${peripheral.uuid}. " +
                        "Ensure services are discovered before cloning."
                )
            }

            val totalItems = countTotalItems(services)
            var processedItems = 0

            val serviceClones = services.map { service ->
                val characteristicClones = service.characteristics.map { characteristic ->
                    processedItems++
                    config.callback?.onCloneProgress(
                        processedItems,
                        totalItems,
                        "Reading characteristic ${characteristic.uuid}"
                    )

                    val value = if (config.readCharacteristicValues) {
                        readCharacteristicSafe(engine, peripheral, characteristic)
                    } else {
                        characteristic.value
                    }

                    val descriptorClones = characteristic.descriptors.map { descriptor ->
                        processedItems++
                        config.callback?.onCloneProgress(
                            processedItems,
                            totalItems,
                            "Reading descriptor ${descriptor.uuid}"
                        )

                        val descriptorValue = if (config.readDescriptorValues) {
                            readDescriptorSafe(engine, peripheral, characteristic, descriptor)
                        } else {
                            descriptor.value
                        }

                        DescriptorClone(
                            uuid = descriptor.uuid.toString(),
                            value = descriptorValue
                        )
                    }

                    CharacteristicClone(
                        uuid = characteristic.uuid.toString(),
                        name = characteristic.name,
                        value = value,
                        isNotifying = characteristic.isNotifying,
                        descriptors = descriptorClones
                    )
                }

                ServiceClone(
                    uuid = service.uuid.toString(),
                    name = service.name,
                    characteristics = characteristicClones
                )
            }

            val advertisement = if (config.includeAdvertisementData) {
                advertisementCache[peripheral.uuid] ?: AdvertisementClone(
                    localName = peripheral.name
                )
            } else {
                AdvertisementClone()
            }

            val clone = DeviceClone(
                peripheralId = peripheral.uuid,
                peripheralName = peripheral.name,
                capturedAt = config.timestampProvider(),
                platform = config.platform,
                rssi = peripheral.rssi,
                mtuSize = peripheral.mtuSize,
                advertisement = advertisement,
                services = serviceClones
            )

            config.callback?.onCloneComplete(clone)
            return clone
        } catch (e: Exception) {
            config.callback?.onCloneError(e)
            throw e
        }
    }

    /**
     * Export a DeviceClone to a JSON string.
     *
     * @param clone The clone to serialize
     * @return Pretty-printed JSON representation
     */
    fun exportToJson(clone: DeviceClone): String {
        return json.encodeToString(DeviceClone.serializer(), clone)
    }

    /**
     * Import a DeviceClone from a JSON string.
     *
     * @param jsonString The JSON to deserialize
     * @return The deserialized DeviceClone
     */
    fun importFromJson(jsonString: String): DeviceClone {
        return json.decodeFromString(DeviceClone.serializer(), jsonString)
    }

    /**
     * Get cached advertisement data for a peripheral.
     *
     * @param peripheralId The peripheral identifier
     * @return Cached advertisement data, or null if not cached
     */
    fun getCachedAdvertisement(peripheralId: String): AdvertisementClone? {
        return advertisementCache[peripheralId]
    }

    /**
     * Clear the advertisement cache.
     */
    fun clearCache() {
        advertisementCache.clear()
    }

    private fun countTotalItems(services: List<BluetoothService>): Int {
        var count = 0
        for (service in services) {
            for (characteristic in service.characteristics) {
                count++ // characteristic itself
                count += characteristic.descriptors.size // descriptors
            }
        }
        return count
    }

    private suspend fun readCharacteristicSafe(
        engine: BlueFalconEngine,
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic
    ): ByteArray? {
        return try {
            engine.readCharacteristic(peripheral, characteristic)
            characteristic.value
        } catch (_: Exception) {
            // Some characteristics may not be readable (write-only, require auth, etc.)
            characteristic.value
        }
    }

    private suspend fun readDescriptorSafe(
        engine: BlueFalconEngine,
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        descriptor: BluetoothCharacteristicDescriptor
    ): ByteArray? {
        return try {
            engine.readDescriptor(peripheral, characteristic, descriptor)
            descriptor.value
        } catch (_: Exception) {
            // Some descriptors may not be readable
            descriptor.value
        }
    }

    companion object : PluginFactory {
        override fun create(config: PluginConfig): BlueFalconPlugin {
            return DeviceClonePlugin(config as CloneConfig)
        }
    }
}

/**
 * Factory interface for creating plugins
 */
interface PluginFactory {
    fun create(config: PluginConfig): BlueFalconPlugin
}

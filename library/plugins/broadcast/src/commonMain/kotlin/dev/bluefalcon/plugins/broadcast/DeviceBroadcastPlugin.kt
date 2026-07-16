package dev.bluefalcon.plugins.broadcast

import dev.bluefalcon.peripheral.*
import dev.bluefalcon.plugins.clone.CharacteristicClone
import dev.bluefalcon.plugins.clone.DeviceClone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Plugin that re-advertises a captured [DeviceClone] as a local BLE peripheral.
 *
 * Usage:
 * ```kotlin
 * // Android; Apple exposes an equivalent factory without Context.
 * val advertiser = createBluetoothAdvertiser(context, logger)
 *
 * val broadcastPlugin = DeviceBroadcastPlugin()
 * broadcastPlugin.startBroadcast(clone, advertiser)
 *
 * broadcastPlugin.broadcastState.collect { state -> ... }
 * broadcastPlugin.writeRequests.collect { request -> ... }
 *
 * broadcastPlugin.stopBroadcast()
 * ```
 *
 * The plugin converts the [DeviceClone] into an [AdvertiseConfig] and drives the
 * [BluetoothAdvertiser]. It does **not** implement [dev.bluefalcon.core.plugin.BlueFalconPlugin]
 * because the broadcast lifecycle is orthogonal to the scan/connect hook pipeline.
 */
class DeviceBroadcastPlugin {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _broadcastState = MutableStateFlow(BroadcastState.Idle)

    /** Current state of the local BLE peripheral. */
    val broadcastState: StateFlow<BroadcastState> = _broadcastState.asStateFlow()

    private val _writeRequests = MutableSharedFlow<CharacteristicWriteRequest>(extraBufferCapacity = 64)

    /**
     * Emitted whenever a remote central writes to a hosted characteristic.
     * Forwarded from the underlying [BluetoothAdvertiser].
     */
    val writeRequests: SharedFlow<CharacteristicWriteRequest> = _writeRequests

    private var currentAdvertiser: BluetoothAdvertiser? = null
    private var stateCollectorJob: Job? = null
    private var writeRequestCollectorJob: Job? = null

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Convert [clone] into an [AdvertiseConfig] and start broadcasting via [advertiser].
     *
     * If a broadcast is already active it is stopped first.
     *
     * @param clone    The captured device profile to replay.
     * @param advertiser Platform-specific [BluetoothAdvertiser].
     * @param config   Optional broadcast configuration overrides.
     */
    suspend fun startBroadcast(
        clone: DeviceClone,
        advertiser: BluetoothAdvertiser,
        config: BroadcastConfig = BroadcastConfig()
    ) {
        stopBroadcast()

        currentAdvertiser = advertiser
        _broadcastState.value = BroadcastState.Starting

        // Mirror advertiser state → broadcastState
        stateCollectorJob = scope.launch {
            advertiser.state.collect { state ->
                _broadcastState.value = when (state) {
                    AdvertiserState.Idle -> BroadcastState.Idle
                    AdvertiserState.Advertising -> BroadcastState.Broadcasting
                    AdvertiserState.Error -> BroadcastState.Error
                }
            }
        }

        // Forward write requests to our own SharedFlow
        writeRequestCollectorJob = scope.launch {
            advertiser.characteristicWriteRequests.collect { request ->
                _writeRequests.emit(request)
            }
        }

        advertiser.startAdvertising(clone.toAdvertiseConfig(config))
    }

    /** Stop broadcasting and reset state to [BroadcastState.Idle]. */
    suspend fun stopBroadcast() {
        stateCollectorJob?.cancel()
        stateCollectorJob = null
        writeRequestCollectorJob?.cancel()
        writeRequestCollectorJob = null

        currentAdvertiser?.stopAdvertising()
        currentAdvertiser = null
        _broadcastState.value = BroadcastState.Idle
    }

    /**
     * Push a new value to a hosted characteristic and notify subscribed centrals.
     *
     * This is a no-op if [startBroadcast] has not been called yet.
     *
     * @param serviceUuid        UUID of the service (full 128-bit string).
     * @param characteristicUuid UUID of the characteristic to update.
     * @param value              New value bytes.
     */
    suspend fun updateCharacteristicValue(
        serviceUuid: String,
        characteristicUuid: String,
        value: ByteArray
    ) {
        currentAdvertiser?.updateCharacteristicValue(serviceUuid, characteristicUuid, value)
    }

    // -------------------------------------------------------------------------
    // DeviceClone → AdvertiseConfig conversion
    // -------------------------------------------------------------------------

    private fun DeviceClone.toAdvertiseConfig(config: BroadcastConfig): AdvertiseConfig {
        val localName = config.overrideLocalName
            ?: advertisement.localName
            ?: peripheralName

        val manufacturerData: Map<Int, ByteArray> = if (config.includeManufacturerData) {
            parseManufacturerData(advertisement.manufacturerData)
        } else {
            emptyMap()
        }

        // Prefer service UUIDs from the advertisement packet; fall back to GATT service UUIDs
        val serviceUuids = advertisement.serviceUuids.ifEmpty {
            services.map { it.uuid }
        }

        val gattServices = services.map { service ->
            GattServiceConfig(
                uuid = service.uuid,
                characteristics = service.characteristics.map { char ->
                    GattCharacteristicConfig(
                        uuid = char.uuid,
                        properties = inferProperties(char, config),
                        initialValue = char.value,
                        descriptors = char.descriptors.map { desc ->
                            GattDescriptorConfig(
                                uuid = desc.uuid,
                                initialValue = desc.value
                            )
                        }
                    )
                }
            )
        }

        return AdvertiseConfig(
            localName = localName,
            serviceUuids = serviceUuids,
            manufacturerData = manufacturerData,
            services = gattServices,
            includeTxPower = config.includeTxPower
        )
    }

    /**
     * Infer [CharacteristicProperty] set for a cloned characteristic.
     *
     * Starts from [BroadcastConfig.defaultCharacteristicProperties] and adds
     * [CharacteristicProperty.NOTIFY] when the characteristic was notifying at capture time
     * or carries a CCCD (0x2902) descriptor.
     */
    private fun inferProperties(
        char: CharacteristicClone,
        config: BroadcastConfig
    ): Set<CharacteristicProperty> {
        val props = config.defaultCharacteristicProperties.toMutableSet()
        val hasCccd = char.descriptors.any { it.uuid.lowercase().startsWith("00002902") }
        if (char.isNotifying || hasCccd) {
            props.add(CharacteristicProperty.NOTIFY)
        }
        return props
    }

    /**
     * Parse raw manufacturer data bytes (as captured from BLE advertisement) into a
     * manufacturer ID → payload map.
     *
     * BLE manufacturer-specific data format: [companyId LSB][companyId MSB][payload...]
     * If the data is fewer than 2 bytes, returns an empty map.
     */
    private fun parseManufacturerData(raw: ByteArray?): Map<Int, ByteArray> {
        if (raw == null || raw.size < 2) return emptyMap()
        val companyId = (raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8)
        val payload = raw.copyOfRange(2, raw.size)
        return mapOf(companyId to payload)
    }
}

package dev.bluefalcon.peripheral

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Peripheral-role BLE interface: advertisement packet + optional local GATT server.
 *
 * Obtain an instance via the platform-specific peripheral factory.
 * On platforms or configurations where advertising is not supported, a
 * [NoOpBluetoothAdvertiser] is returned.
 *
 * Typical usage:
 * ```kotlin
 * // Android (use dev.bluefalcon.peripheral.apple.createBluetoothAdvertiser on Apple).
 * import dev.bluefalcon.peripheral.android.createBluetoothAdvertiser
 *
 * val advertiser = createBluetoothAdvertiser(context, logger)
 * advertiser.startAdvertising(config)
 * advertiser.state.collect { state -> ... }
 * advertiser.stopAdvertising()
 * ```
 */
interface BluetoothAdvertiser {

    /** Current advertiser/GATT server state. */
    val state: StateFlow<AdvertiserState>

    /**
     * Events emitted when a remote central writes to a hosted characteristic.
     * Only fired if the relevant characteristic was configured with [CharacteristicProperty.WRITE]
     * or [CharacteristicProperty.WRITE_NO_RESPONSE].
     */
    val characteristicWriteRequests: SharedFlow<CharacteristicWriteRequest>

    /**
     * Start advertising and (if [config] contains services) bring up the GATT server.
     *
     * Calling this while already advertising replaces the current advertisement with [config].
     *
     * @throws UnsupportedOperationException if the platform does not support advertising.
     */
    suspend fun startAdvertising(config: AdvertiseConfig)

    /** Stop advertising and shut down the GATT server. */
    suspend fun stopAdvertising()

    /**
     * Push a new value for a hosted characteristic and notify/indicate subscribed centrals.
     *
     * This is a no-op on platforms that do not support a GATT server (e.g. Windows).
     *
     * @param serviceUuid        UUID of the service (full 128-bit string).
     * @param characteristicUuid UUID of the characteristic to update.
     * @param value              New value bytes.
     */
    suspend fun updateCharacteristicValue(
        serviceUuid: String,
        characteristicUuid: String,
        value: ByteArray
    )
}

/**
 * No-op implementation returned on platforms that do not support advertising,
 * or when the required hardware/permission is unavailable.
 *
 * [startAdvertising] always throws [UnsupportedOperationException].
 */
class NoOpBluetoothAdvertiser : BluetoothAdvertiser {

    override val state: StateFlow<AdvertiserState> =
        kotlinx.coroutines.flow.MutableStateFlow(AdvertiserState.Idle)

    override val characteristicWriteRequests: SharedFlow<CharacteristicWriteRequest> =
        kotlinx.coroutines.flow.MutableSharedFlow()

    override suspend fun startAdvertising(config: AdvertiseConfig) {
        throw UnsupportedOperationException(
            "BLE advertising is not supported on this platform or configuration."
        )
    }

    override suspend fun stopAdvertising() = Unit

    override suspend fun updateCharacteristicValue(
        serviceUuid: String,
        characteristicUuid: String,
        value: ByteArray
    ) = Unit
}

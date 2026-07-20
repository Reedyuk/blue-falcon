package dev.bluefalcon.peripheral.android

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import dev.bluefalcon.core.Logger
import dev.bluefalcon.peripheral.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Android implementation of [BluetoothAdvertiser].
 *
 * Uses [BluetoothLeAdvertiser] for the advertisement packet and
 * [BluetoothGattServer] to host a local GATT service tree.
 *
 * Requires:
 *   - `android.permission.BLUETOOTH_ADVERTISE` on API 31+
 *   - `android.permission.BLUETOOTH_CONNECT` on API 31+
 *
 * Obtain via [createBluetoothAdvertiser].
 */
class AndroidBluetoothAdvertiser(
    private val context: Context,
    private val logger: Logger? = null
) : BluetoothAdvertiser {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(AdvertiserState.Idle)
    override val state: StateFlow<AdvertiserState> = _state.asStateFlow()

    private val _writeRequests = MutableSharedFlow<CharacteristicWriteRequest>(extraBufferCapacity = 64)
    override val characteristicWriteRequests: SharedFlow<CharacteristicWriteRequest> = _writeRequests

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter get() = bluetoothManager.adapter

    private var leAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var currentConfig: AdvertiseConfig? = null

    // Map of serviceUUID -> characteristicUUID -> characteristic, for value updates
    private val hostedCharacteristics =
        mutableMapOf<String, MutableMap<String, BluetoothGattCharacteristic>>()

    // -------------------------------------------------------------------------
    // GATT server callback
    // -------------------------------------------------------------------------

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            logger?.debug("AndroidAdvertiser: connection state $newState for ${device.address}")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: ByteArray(0)
            val slice = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val written = value ?: ByteArray(0)
            // Update the stored value so subsequent reads reflect the write
            characteristic.value = written

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, written)
            }

            val serviceUuid = characteristic.service?.uuid?.toString() ?: return
            scope.launch {
                _writeRequests.emit(
                    CharacteristicWriteRequest(
                        serviceUuid = serviceUuid,
                        characteristicUuid = characteristic.uuid.toString(),
                        value = written,
                        requestId = if (responseNeeded) requestId else -1
                    )
                )
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            descriptor.value = value
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            val value = descriptor.value ?: ByteArray(0)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }
    }

    // -------------------------------------------------------------------------
    // Advertising callback
    // -------------------------------------------------------------------------

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            logger?.info("AndroidAdvertiser: advertising started")
            _state.value = AdvertiserState.Advertising
        }

        override fun onStartFailure(errorCode: Int) {
            logger?.error("AndroidAdvertiser: advertising failed, errorCode=$errorCode")
            _state.value = AdvertiserState.Error
            teardownGattServer()
        }
    }

    // -------------------------------------------------------------------------
    // BluetoothAdvertiser implementation
    // -------------------------------------------------------------------------

    override suspend fun startAdvertising(config: AdvertiseConfig) {
        stopAdvertising()
        currentConfig = config

        // Build GATT server first so it is ready before the advertisement is visible
        setupGattServer(config)

        leAdvertiser = adapter.bluetoothLeAdvertiser
            ?: run {
                logger?.error("AndroidAdvertiser: BluetoothLeAdvertiser not available (BLE advertising not supported)")
                _state.value = AdvertiserState.Error
                return
            }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(config.services.isNotEmpty())
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val dataBuilder = AdvertiseData.Builder().apply {
            setIncludeDeviceName(config.localName != null)
            setIncludeTxPowerLevel(config.includeTxPower)
            config.serviceUuids.forEach { uuid ->
                addServiceUuid(ParcelUuid(UUID.fromString(uuid)))
            }
            config.manufacturerData.forEach { (id, data) ->
                addManufacturerData(id, data)
            }
        }

        leAdvertiser?.startAdvertising(settings, dataBuilder.build(), advertiseCallback)
    }

    override suspend fun stopAdvertising() {
        if (_state.value == AdvertiserState.Idle) return
        try {
            leAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            logger?.error("AndroidAdvertiser: error stopping advertising", e)
        }
        leAdvertiser = null
        teardownGattServer()
        _state.value = AdvertiserState.Idle
        logger?.info("AndroidAdvertiser: stopped")
    }

    override suspend fun updateCharacteristicValue(
        serviceUuid: String,
        characteristicUuid: String,
        value: ByteArray
    ) {
        val characteristic = hostedCharacteristics[serviceUuid.lowercase()]
            ?.get(characteristicUuid.lowercase())
            ?: run {
                logger?.error("AndroidAdvertiser: characteristic $characteristicUuid not found in service $serviceUuid")
                return
            }

        characteristic.value = value

        // Notify / indicate all connected centrals that have subscribed
        val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
        val server = gattServer ?: return
        for (device in connectedDevices) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, characteristic, false, value)
            } else {
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun setupGattServer(config: AdvertiseConfig) {
        if (config.services.isEmpty()) return
        hostedCharacteristics.clear()

        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        for (serviceConfig in config.services) {
            val service = BluetoothGattService(
                UUID.fromString(serviceConfig.uuid),
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            val charMap = mutableMapOf<String, BluetoothGattCharacteristic>()

            for (charConfig in serviceConfig.characteristics) {
                val properties = charConfig.properties.toAndroidProperties()
                val permissions = if (charConfig.permissions != 0) {
                    charConfig.permissions
                } else {
                    charConfig.properties.toAndroidPermissions()
                }

                val characteristic = BluetoothGattCharacteristic(
                    UUID.fromString(charConfig.uuid),
                    properties,
                    permissions
                )
                charConfig.initialValue?.let { characteristic.value = it }

                for (descConfig in charConfig.descriptors) {
                    val descriptor = BluetoothGattDescriptor(
                        UUID.fromString(descConfig.uuid),
                        if (descConfig.permissions != 0) descConfig.permissions
                        else BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                    )
                    descConfig.initialValue?.let { descriptor.value = it }
                    characteristic.addDescriptor(descriptor)
                }

                // Auto-add CCCD for notify/indicate characteristics if not already present
                if (charConfig.properties.any { it == CharacteristicProperty.NOTIFY || it == CharacteristicProperty.INDICATE }) {
                    val cccdUuid = UUID.fromString(CCCD_UUID)
                    if (characteristic.getDescriptor(cccdUuid) == null) {
                        characteristic.addDescriptor(
                            BluetoothGattDescriptor(
                                cccdUuid,
                                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                            ).also { it.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE }
                        )
                    }
                }

                service.addCharacteristic(characteristic)
                charMap[charConfig.uuid.lowercase()] = characteristic
            }

            gattServer?.addService(service)
            hostedCharacteristics[serviceConfig.uuid.lowercase()] = charMap
        }

        logger?.info("AndroidAdvertiser: GATT server set up with ${config.services.size} service(s)")
    }

    private fun teardownGattServer() {
        gattServer?.close()
        gattServer = null
        hostedCharacteristics.clear()
    }

    // -------------------------------------------------------------------------
    // Platform conversion helpers
    // -------------------------------------------------------------------------

    private fun Set<CharacteristicProperty>.toAndroidProperties(): Int {
        var props = 0
        for (p in this) {
            props = props or when (p) {
                CharacteristicProperty.READ -> BluetoothGattCharacteristic.PROPERTY_READ
                CharacteristicProperty.WRITE -> BluetoothGattCharacteristic.PROPERTY_WRITE
                CharacteristicProperty.WRITE_NO_RESPONSE -> BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                CharacteristicProperty.NOTIFY -> BluetoothGattCharacteristic.PROPERTY_NOTIFY
                CharacteristicProperty.INDICATE -> BluetoothGattCharacteristic.PROPERTY_INDICATE
                CharacteristicProperty.SIGNED_WRITE -> BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE
                CharacteristicProperty.EXTENDED_PROPERTIES -> BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS
            }
        }
        return props
    }

    private fun Set<CharacteristicProperty>.toAndroidPermissions(): Int {
        var perms = 0
        for (p in this) {
            perms = perms or when (p) {
                CharacteristicProperty.READ -> BluetoothGattCharacteristic.PERMISSION_READ
                CharacteristicProperty.WRITE,
                CharacteristicProperty.WRITE_NO_RESPONSE,
                CharacteristicProperty.SIGNED_WRITE -> BluetoothGattCharacteristic.PERMISSION_WRITE
                else -> 0
            }
        }
        return perms
    }

    companion object {
        /** Client Characteristic Configuration Descriptor UUID */
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    }
}

/**
 * Create an [AndroidBluetoothAdvertiser] for this Android context.
 *
 * Returns a [NoOpBluetoothAdvertiser] if BLE advertising is not supported
 * on this device (e.g. no hardware support, or Bluetooth is off).
 */
fun createBluetoothAdvertiser(
    context: Context,
    logger: Logger? = null,
): BluetoothAdvertiser {
    return try {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (mgr?.adapter?.isMultipleAdvertisementSupported == true) {
            AndroidBluetoothAdvertiser(context, logger)
        } else {
            NoOpBluetoothAdvertiser()
        }
    } catch (e: Exception) {
        NoOpBluetoothAdvertiser()
    }
}

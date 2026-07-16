package dev.bluefalcon.peripheral.apple

import dev.bluefalcon.core.Logger
import dev.bluefalcon.peripheral.*
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.CoreBluetooth.*
import platform.Foundation.*
import platform.darwin.NSObject

/**
 * Apple (iOS / macOS) implementation of [BluetoothAdvertiser] using [CBPeripheralManager].
 *
 * Platform notes for iOS:
 * - Only [AdvertiseConfig.localName] and [AdvertiseConfig.serviceUuids] are included in the
 *   advertisement packet; manufacturer data is silently ignored by the OS.
 * - Background advertising restricts the packet to service UUIDs only.
 *
 * On macOS all standard advertisement fields are supported.
 *
 * Obtain via [createBluetoothAdvertiser].
 */
@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
class AppleBluetoothAdvertiser(
    private val logger: Logger? = null
) : BluetoothAdvertiser {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(AdvertiserState.Idle)
    override val state: StateFlow<AdvertiserState> = _state.asStateFlow()

    private val _writeRequests = MutableSharedFlow<CharacteristicWriteRequest>(extraBufferCapacity = 64)
    override val characteristicWriteRequests: SharedFlow<CharacteristicWriteRequest> = _writeRequests

    // serviceUUID (lowercase) -> charUUID (lowercase) -> CBMutableCharacteristic
    private val hostedCharacteristics = mutableMapOf<String, MutableMap<String, CBMutableCharacteristic>>()

    // charUUID (lowercase) -> current NSData value (for dynamic read handling)
    private val characteristicValues = mutableMapOf<String, NSData>()

    private var pendingServicesCount = 0
    private var addedServicesCount = 0
    private var currentConfig: AdvertiseConfig? = null

    // -------------------------------------------------------------------------
    // CBPeripheralManager delegate
    // -------------------------------------------------------------------------

    private val peripheralManagerDelegate = object : NSObject(), CBPeripheralManagerDelegateProtocol {

        override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
            logger?.debug("AppleAdvertiser: peripheral manager state ${peripheral.state}")
            when (peripheral.state) {
                CBManagerStateUnsupported -> {
                    logger?.error("AppleAdvertiser: BLE peripheral role not supported on this device/platform")
                    _state.value = AdvertiserState.Error
                }
                CBManagerStateUnauthorized -> {
                    logger?.error("AppleAdvertiser: not authorized to use BLE peripheral role")
                    _state.value = AdvertiserState.Error
                }
                else -> { /* handled by start/stop callbacks */ }
            }
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didAddService: CBService,
            error: NSError?
        ) {
            if (error != null) {
                logger?.error("AppleAdvertiser: error adding service ${didAddService.UUID.UUIDString}: ${error.localizedDescription}")
                _state.value = AdvertiserState.Error
                return
            }
            addedServicesCount++
            if (addedServicesCount >= pendingServicesCount) {
                startAdvertisingPacket(peripheral)
            }
        }

        override fun peripheralManagerDidStartAdvertising(peripheral: CBPeripheralManager, error: NSError?) {
            if (error != null) {
                logger?.error("AppleAdvertiser: start advertising failed: ${error.localizedDescription}")
                _state.value = AdvertiserState.Error
            } else {
                logger?.info("AppleAdvertiser: advertising started")
                _state.value = AdvertiserState.Advertising
            }
        }

        @ObjCSignatureOverride
        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            central: CBCentral,
            didSubscribeToCharacteristic: CBCharacteristic
        ) {
            logger?.debug("AppleAdvertiser: central subscribed to ${didSubscribeToCharacteristic.UUID.UUIDString}")
        }

        @ObjCSignatureOverride
        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            central: CBCentral,
            didUnsubscribeFromCharacteristic: CBCharacteristic
        ) {
            logger?.debug("AppleAdvertiser: central unsubscribed from ${didUnsubscribeFromCharacteristic.UUID.UUIDString}")
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didReceiveReadRequest: CBATTRequest
        ) {
            val charUuid = didReceiveReadRequest.characteristic.UUID.UUIDString.lowercase()
            val data = characteristicValues[charUuid]

            if (data == null) {
                peripheral.respondToRequest(didReceiveReadRequest, CBATTErrorAttributeNotFound)
                return
            }

            val offset = didReceiveReadRequest.offset.toInt()
            val bytes = data.toByteArray()

            if (offset > bytes.size) {
                peripheral.respondToRequest(didReceiveReadRequest, CBATTErrorInvalidOffset)
                return
            }

            didReceiveReadRequest.value = bytes.copyOfRange(offset, bytes.size).toData()
            peripheral.respondToRequest(didReceiveReadRequest, CBATTErrorSuccess)
        }

        @Suppress("UNCHECKED_CAST")
        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didReceiveWriteRequests: List<*>
        ) {
            val requests = didReceiveWriteRequests as List<CBATTRequest>
            for (request in requests) {
                val serviceUuid = request.characteristic.service?.UUID?.UUIDString ?: continue
                val charUuid = request.characteristic.UUID.UUIDString
                val value = request.value?.toByteArray() ?: ByteArray(0)

                // Persist the written value for future reads
                request.value?.let { characteristicValues[charUuid.lowercase()] = it }

                scope.launch {
                    _writeRequests.emit(
                        CharacteristicWriteRequest(
                            serviceUuid = serviceUuid,
                            characteristicUuid = charUuid,
                            value = value
                        )
                    )
                }
            }
            // Send a single response covering all requests
            requests.firstOrNull()?.let {
                peripheral.respondToRequest(it, CBATTErrorSuccess)
            }
        }

        override fun peripheralManagerIsReadyToUpdateSubscribers(peripheral: CBPeripheralManager) {
            logger?.debug("AppleAdvertiser: ready to update subscribers")
        }
    }

    // CBPeripheralManager is created lazily on first use to avoid triggering
    // Bluetooth permission prompts (and entitlement checks) at app startup.
    private val peripheralManager: CBPeripheralManager by lazy {
        CBPeripheralManager(peripheralManagerDelegate, null)
    }

    // -------------------------------------------------------------------------
    // BluetoothAdvertiser implementation
    // -------------------------------------------------------------------------

    override suspend fun startAdvertising(config: AdvertiseConfig) {
        stopAdvertising()
        currentConfig = config
        hostedCharacteristics.clear()
        characteristicValues.clear()
        addedServicesCount = 0

        if (config.services.isEmpty()) {
            pendingServicesCount = 0
            startAdvertisingPacket(peripheralManager)
            return
        }

        pendingServicesCount = config.services.size

        for (serviceConfig in config.services) {
            val mutableService = CBMutableService(
                type = CBUUID.UUIDWithString(serviceConfig.uuid),
                primary = true
            )
            val charMap = mutableMapOf<String, CBMutableCharacteristic>()

            val mutableCharacteristics = serviceConfig.characteristics.map { charConfig ->
                val mutableChar = CBMutableCharacteristic(
                    type = CBUUID.UUIDWithString(charConfig.uuid),
                    properties = charConfig.properties.toAppleProperties(),
                    value = null, // null = dynamic; reads handled in delegate
                    permissions = charConfig.properties.toApplePermissions()
                )

                charConfig.initialValue?.let {
                    characteristicValues[charConfig.uuid.lowercase()] = it.toData()
                }

                // CoreBluetooth automatically adds the CCCD (0x2902) for notify/indicate
                // characteristics, so we must NOT add it manually — doing so crashes.
                // For any other 128-bit UUID descriptor, CBMutableDescriptor requires a
                // non-nil NSData value; never pass nil.
                val mutableDescriptors = charConfig.descriptors
                    .filterNot { isCccdUuid(it.uuid) }
                    .map { descConfig ->
                        CBMutableDescriptor(
                            type = CBUUID.UUIDWithString(descConfig.uuid),
                            value = descConfig.initialValue?.toData() ?: NSData()
                        )
                    }
                if (mutableDescriptors.isNotEmpty()) {
                    mutableChar.setDescriptors(mutableDescriptors)
                }

                charMap[charConfig.uuid.lowercase()] = mutableChar
                mutableChar
            }

            mutableService.setCharacteristics(mutableCharacteristics)
            hostedCharacteristics[serviceConfig.uuid.lowercase()] = charMap
            peripheralManager.addService(mutableService)
        }
    }

    override suspend fun stopAdvertising() {
        if (_state.value == AdvertiserState.Idle) return
        peripheralManager.stopAdvertising()
        peripheralManager.removeAllServices()
        hostedCharacteristics.clear()
        characteristicValues.clear()
        currentConfig = null
        _state.value = AdvertiserState.Idle
        logger?.info("AppleAdvertiser: stopped")
    }

    override suspend fun updateCharacteristicValue(
        serviceUuid: String,
        characteristicUuid: String,
        value: ByteArray
    ) {
        val characteristic = hostedCharacteristics[serviceUuid.lowercase()]
            ?.get(characteristicUuid.lowercase())
            ?: run {
                logger?.error("AppleAdvertiser: characteristic $characteristicUuid not found in $serviceUuid")
                return
            }

        val data = value.toData()
        characteristicValues[characteristicUuid.lowercase()] = data

        val success = peripheralManager.updateValue(
            value = data,
            forCharacteristic = characteristic,
            onSubscribedCentrals = null
        )
        if (!success) {
            logger?.debug("AppleAdvertiser: updateValue queued (subscriber transmit queue full)")
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun startAdvertisingPacket(peripheral: CBPeripheralManager) {
        val config = currentConfig ?: return
        val advertisementData = mutableMapOf<Any?, Any?>()

        config.localName?.let {
            advertisementData[CBAdvertisementDataLocalNameKey] = it
        }
        if (config.serviceUuids.isNotEmpty()) {
            advertisementData[CBAdvertisementDataServiceUUIDsKey] =
                config.serviceUuids.map { CBUUID.UUIDWithString(it) }
        }

        peripheral.startAdvertising(advertisementData.ifEmpty { null })
    }

    private fun Set<CharacteristicProperty>.toAppleProperties(): CBCharacteristicProperties {
        var props = 0UL
        for (p in this) {
            props = props or when (p) {
                CharacteristicProperty.READ -> CBCharacteristicPropertyRead
                CharacteristicProperty.WRITE -> CBCharacteristicPropertyWrite
                CharacteristicProperty.WRITE_NO_RESPONSE -> CBCharacteristicPropertyWriteWithoutResponse
                CharacteristicProperty.NOTIFY -> CBCharacteristicPropertyNotify
                CharacteristicProperty.INDICATE -> CBCharacteristicPropertyIndicate
                CharacteristicProperty.SIGNED_WRITE -> CBCharacteristicPropertyAuthenticatedSignedWrites
                CharacteristicProperty.EXTENDED_PROPERTIES -> CBCharacteristicPropertyExtendedProperties
            }
        }
        return props
    }

    private fun Set<CharacteristicProperty>.toApplePermissions(): CBAttributePermissions {
        var perms = 0UL
        for (p in this) {
            perms = perms or when (p) {
                CharacteristicProperty.READ -> CBAttributePermissionsReadable
                CharacteristicProperty.WRITE,
                CharacteristicProperty.WRITE_NO_RESPONSE,
                CharacteristicProperty.SIGNED_WRITE -> CBAttributePermissionsWriteable
                else -> 0UL
            }
        }
        return perms
    }

    /** Returns true for any form of the CCCD UUID (16-bit or 128-bit canonical form). */
    private fun isCccdUuid(uuid: String): Boolean {
        val upper = uuid.uppercase()
        return upper == "2902" ||
            upper == "00002902-0000-1000-8000-00805F9B34FB"
    }
}

fun createBluetoothAdvertiser(logger: Logger? = null): BluetoothAdvertiser =
    AppleBluetoothAdvertiser(logger)

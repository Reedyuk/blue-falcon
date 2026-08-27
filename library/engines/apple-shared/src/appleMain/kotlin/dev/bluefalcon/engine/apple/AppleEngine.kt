package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.*
import kotlinx.cinterop.BetaInteropApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.CoreBluetooth.*
import platform.Foundation.*

/**
 * Shared Apple implementation of BlueFalconEngine for iOS and macOS
 * Uses CoreBluetooth framework
 */
@OptIn(BetaInteropApi::class)
class AppleEngine : BlueFalconEngine, CBCentralManagerCallback, CBPeripheralCallback {
    
    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    override val peripherals: StateFlow<Set<BluetoothPeripheral>> = _peripherals.asStateFlow()
    
    private val _managerState = MutableStateFlow(BluetoothManagerState.NotReady)
    override val managerState: StateFlow<BluetoothManagerState> = _managerState.asStateFlow()

    private val _characteristicNotifications = MutableSharedFlow<CharacteristicNotification>(extraBufferCapacity = 64)
    override val characteristicNotifications: SharedFlow<CharacteristicNotification> = _characteristicNotifications

    private val _rssiUpdates = MutableSharedFlow<Pair<String, Float>>(extraBufferCapacity = 64)
    override val rssiUpdates: SharedFlow<Pair<String, Float>> = _rssiUpdates

    private val _connectionStateUpdates = MutableSharedFlow<ConnectionStateUpdate>(extraBufferCapacity = 64)
    override val connectionStateUpdates: SharedFlow<ConnectionStateUpdate> = _connectionStateUpdates

    private val _serviceDiscoveryUpdates = MutableSharedFlow<ServiceDiscoveryUpdate>(extraBufferCapacity = 64)
    override val serviceDiscoveryUpdates: SharedFlow<ServiceDiscoveryUpdate> = _serviceDiscoveryUpdates

    private val centralWriteController = AppleCentralWriteController(scope)
    private val callbackDispatcher = AppleCentralCallbackDispatcher(scope)
    private val nativeConnectionOwnership =
        AppleNativeConnectionOwnership<CBPeripheral>()
    override val centralCapabilities = CentralCapabilities(
        reliableWriteResults = true,
        writeWithoutResponseReadiness = true,
        perConnectionMaximumWriteLength = true,
        notificationSubscriptionResults = true,
        restoration = false,
        bondCapability = BondCapability.Implicit,
    )
    override val characteristicWriteCapabilities = centralWriteController.capabilities
    override val characteristicWriteReady = centralWriteController.ready
    override val notificationSubscriptionUpdates = centralWriteController.notificationUpdates
    
    override var isScanning: Boolean = false
        private set
    
    // CoreBluetooth manager
    private val bluetoothManager = BluetoothPeripheralManager(this)
    private val centralManager: CBCentralManager
        get() = bluetoothManager.centralManager
    
    // Peripheral delegate for handling peripheral events
    private val peripheralDelegate = CBPeripheralDelegateWrapper(this)
    
    // Map to track connected peripherals
    private val connectedPeripherals = mutableMapOf<String, ActiveAppleConnection>()

    // Pending L2CAP channel opens, keyed by peripheral identifier, bridged from
    // the async openL2CAPChannel(...) / onL2CAPChannelOpened(...) callback pair.
    private val l2capDeferreds = mutableMapOf<String, CompletableDeferred<CBL2CAPChannel>>()
    
    override suspend fun scan(filters: List<ServiceFilter>) {
        isScanning = true
        
        when (centralManager.state) {
            CBManagerStateUnknown -> throw BluetoothUnknownException("Authorization state: ${centralManager.authorization()}")
            CBManagerStateResetting -> throw BluetoothResettingException()
            CBManagerStateUnsupported -> throw BluetoothUnsupportedException()
            CBManagerStateUnauthorized -> throw BluetoothPermissionException()
            CBManagerStatePoweredOff -> throw BluetoothNotEnabledException()
            CBManagerStatePoweredOn -> {
                val serviceUUIDs = if (filters.isEmpty()) {
                    null
                } else {
                    filters.map { CBUUID.UUIDWithString(it.uuid.toString()) }
                }
                
                centralManager.scanForPeripheralsWithServices(
                    serviceUUIDs,
                    mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to true)
                )
            }
        }
    }
    
    override suspend fun stopScanning() {
        isScanning = false
        centralManager.stopScan()
    }
    
    override fun clearPeripherals() {
        _peripherals.value = emptySet()
    }
    
    override suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean) {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        
        val cbPeripheral = applePeripheral.cbPeripheral
        
        // If already connected, trigger connection callback
        if (cbPeripheral.state == CBPeripheralStateConnected) {
            val replacementDevice = centralManager.retrievePeripheralsWithIdentifiers(
                listOf(cbPeripheral.identifier)
            ).firstOrNull() as? CBPeripheral
            
            if (replacementDevice != null) {
                if (replacementDevice.state == CBPeripheralStateDisconnected || 
                    replacementDevice.state == CBPeripheralStateDisconnecting) {
                    centralManager.connectPeripheral(replacementDevice, null)
                } else {
                    onPeripheralConnected(replacementDevice)
                }
            } else {
                onPeripheralConnected(cbPeripheral)
            }
        } else {
            centralManager.connectPeripheral(cbPeripheral, null)
        }
    }
    
    override suspend fun disconnect(peripheral: BluetoothPeripheral) {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        
        centralManager.cancelPeripheralConnection(applePeripheral.cbPeripheral)
    }
    
    override fun connectionState(peripheral: BluetoothPeripheral): BluetoothPeripheralState {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: return BluetoothPeripheralState.Unknown
        
        return when (applePeripheral.cbPeripheral.state) {
            CBPeripheralStateConnected -> BluetoothPeripheralState.Connected
            CBPeripheralStateConnecting -> BluetoothPeripheralState.Connecting
            CBPeripheralStateDisconnected -> BluetoothPeripheralState.Disconnected
            CBPeripheralStateDisconnecting -> BluetoothPeripheralState.Disconnecting
            else -> BluetoothPeripheralState.Unknown
        }
    }
    
    override fun retrievePeripheral(identifier: String): BluetoothPeripheral? {
        return runCatching {
            centralManager
                .retrievePeripheralsWithIdentifiers(listOf(NSUUID(identifier)))
                .filterIsInstance<CBPeripheral>()
                .firstOrNull()
                ?.let { AppleBluetoothPeripheral(it, null) }
        }.getOrNull()
    }
    
    override fun requestConnectionPriority(peripheral: BluetoothPeripheral, priority: ConnectionPriority) {
        // No-op on Apple platforms
    }
    
    override suspend fun discoverServices(peripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid>) {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        
        // Ensure delegate is set before discovering services
        applePeripheral.cbPeripheral.delegate = peripheralDelegate
        
        val uuids = if (serviceUUIDs.isEmpty()) {
            null
        } else {
            serviceUUIDs.map { CBUUID.UUIDWithString(it.toString()) }
        }
        
        // Only discover services if peripheral is connected
        if (applePeripheral.cbPeripheral.state == CBPeripheralStateConnected) {
            applePeripheral.cbPeripheral.discoverServices(uuids)
        }
    }
    
    override suspend fun discoverCharacteristics(
        peripheral: BluetoothPeripheral,
        service: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        
        val appleService = service as? AppleBluetoothService
            ?: throw IllegalArgumentException("Service must be an AppleBluetoothService")
        
        // Ensure delegate is set before discovering characteristics
        applePeripheral.cbPeripheral.delegate = peripheralDelegate
        
        val uuids = if (characteristicUUIDs.isEmpty()) {
            null
        } else {
            characteristicUUIDs.map { CBUUID.UUIDWithString(it.toString()) }
        }
        
        // Only discover characteristics if peripheral is connected
        if (applePeripheral.cbPeripheral.state == CBPeripheralStateConnected) {
            applePeripheral.cbPeripheral.discoverCharacteristics(uuids, appleService.cbService)
        }
    }
    
    override suspend fun readCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic
    ) {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        
        val appleCharacteristic = characteristic as? AppleBluetoothCharacteristic
            ?: throw IllegalArgumentException("Characteristic must be an AppleBluetoothCharacteristic")
        
        // Ensure delegate is set
        applePeripheral.cbPeripheral.delegate = peripheralDelegate
        
        if (applePeripheral.cbPeripheral.state == CBPeripheralStateConnected) {
            applePeripheral.cbPeripheral.readValueForCharacteristic(appleCharacteristic.cbCharacteristic)
        }
    }
    
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
        writeCharacteristic(
            peripheral,
            characteristic,
            value.encodeToByteArray(),
            writeType.toCharacteristicWriteType(),
        )
    }

    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: CharacteristicWriteType,
    ): CharacteristicWriteResult {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: return CharacteristicWriteResult.Failed(
                IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
            )
        val appleCharacteristic = characteristic as? AppleBluetoothCharacteristic
            ?: return CharacteristicWriteResult.Failed(
                IllegalArgumentException(
                    "Characteristic must be an AppleBluetoothCharacteristic"
                )
            )
        if (!nativeAttributeBelongsTo(
                applePeripheral.cbPeripheral,
                appleCharacteristic.cbCharacteristic.service?.peripheral,
            )
        ) {
            return CharacteristicWriteResult.Failed(
                IllegalArgumentException(
                    "Characteristic ${characteristic.uuid} is not owned by the target peripheral"
                )
            )
        }
        applePeripheral.cbPeripheral.delegate = peripheralDelegate
        return centralWriteController.write(
            CoreBluetoothWriteTarget(
                peripheral = applePeripheral.cbPeripheral,
                characteristic = appleCharacteristic.cbCharacteristic,
            ),
            value,
            writeType,
        )
    }
    
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        writeCharacteristic(
            peripheral,
            characteristic,
            value,
            writeType.toCharacteristicWriteType(),
        )
    }
    
    override suspend fun notifyCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        setNotificationSubscription(peripheral, characteristic, notify)
    }

    override suspend fun setNotificationSubscription(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        enabled: Boolean,
    ): NotificationSubscriptionResult {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: return centralWriteController.reportNotificationUpdate(
                peripheralUuid = peripheral.uuid,
                characteristicUuid = characteristic.uuid,
                result = NotificationSubscriptionResult.Failed(
                    IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
                ),
            )
        val appleCharacteristic = characteristic as? AppleBluetoothCharacteristic
            ?: return centralWriteController.reportNotificationUpdate(
                peripheralUuid = peripheral.uuid,
                characteristicUuid = characteristic.uuid,
                result = NotificationSubscriptionResult.Failed(
                    IllegalArgumentException(
                        "Characteristic must be an AppleBluetoothCharacteristic"
                    )
                ),
            )
        if (!nativeAttributeBelongsTo(
                applePeripheral.cbPeripheral,
                appleCharacteristic.cbCharacteristic.service?.peripheral,
            )
        ) {
            return centralWriteController.reportNotificationUpdate(
                peripheralUuid = peripheral.uuid,
                characteristicUuid = characteristic.uuid,
                result = NotificationSubscriptionResult.Failed(
                    IllegalArgumentException(
                        "Characteristic ${characteristic.uuid} is not owned by the target peripheral"
                    )
                ),
            )
        }
        applePeripheral.cbPeripheral.delegate = peripheralDelegate
        return centralWriteController.setNotificationSubscription(
            CoreBluetoothNotificationTarget(
                peripheral = applePeripheral.cbPeripheral,
                characteristic = appleCharacteristic.cbCharacteristic,
                characteristicUuid = appleCharacteristic.uuid,
            ),
            enabled,
        )
    }
    
    override suspend fun indicateCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        // On Apple platforms, notifications and indications use the same API
        notifyCharacteristic(peripheral, characteristic, indicate)
    }
    
    override suspend fun readDescriptor(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        descriptor: BluetoothCharacteristicDescriptor
    ) {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        
        val appleCharacteristic = characteristic as? AppleBluetoothCharacteristic
            ?: throw IllegalArgumentException("Characteristic must be an AppleBluetoothCharacteristic")
        
        // Discover descriptors first
        applePeripheral.cbPeripheral.discoverDescriptorsForCharacteristic(appleCharacteristic.cbCharacteristic)
    }
    
    override suspend fun writeDescriptor(
        peripheral: BluetoothPeripheral,
        descriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        
        val appleDescriptor = descriptor as? AppleBluetoothCharacteristicDescriptor
            ?: throw IllegalArgumentException("Descriptor must be an AppleBluetoothCharacteristicDescriptor")
        
        applePeripheral.cbPeripheral.writeValue(
            data = value.toData(),
            forDescriptor = appleDescriptor.cbDescriptor
        )
    }
    
    override suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int) {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        
        // Get the actual MTU size from the peripheral
        val actualMtu = applePeripheral.cbPeripheral.maximumWriteValueLengthForType(
            CBCharacteristicWriteWithResponse
        ).toInt()
        
        applePeripheral.mtuSize = actualMtu
    }
    
    override fun refreshGattCache(peripheral: BluetoothPeripheral): Boolean {
        // Not supported on Apple platforms
        return false
    }
    
    override suspend fun openL2capChannel(
        peripheral: BluetoothPeripheral,
        psm: Int,
        secure: Boolean
    ): BluetoothSocket {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw L2capException("Peripheral must be an AppleBluetoothPeripheral")

        val cbPeripheral = applePeripheral.cbPeripheral
        cbPeripheral.delegate = peripheralDelegate

        val identifier = cbPeripheral.identifier.UUIDString
        val deferred = CompletableDeferred<CBL2CAPChannel>()
        l2capDeferreds[identifier] = deferred

        val channel = try {
            cbPeripheral.openL2CAPChannel(psm.toUShort())
            deferred.await()
        } finally {
            l2capDeferreds.remove(identifier)
        }

        return AppleL2CapSocket(channel, psm, peripheral)
    }
    
    override suspend fun createBond(peripheral: BluetoothPeripheral) {
        // Not required on Apple platforms - bonding is handled automatically
    }
    
    override suspend fun removeBond(peripheral: BluetoothPeripheral) {
        // Not supported on Apple platforms - must be done through system settings
    }
    
    // CBCentralManagerCallback implementation
    
    override fun onStateUpdated(state: CBManagerState) {
        _managerState.value = when (state) {
            CBManagerStatePoweredOn -> BluetoothManagerState.Ready
            else -> BluetoothManagerState.NotReady
        }
    }
    
    override fun onPeripheralDiscovered(
        peripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        rssi: NSNumber
    ) {
        if (isScanning) {
            val uuid = peripheral.identifier.UUIDString
            val rssiValue = rssi.floatValue
            val mfData = parseManufacturerData(advertisementData)
            val existing = _peripherals.value.find { it.uuid == uuid } as? AppleBluetoothPeripheral
            if (existing != null) {
                existing.rssi = rssiValue
                if (mfData.isNotEmpty()) existing.manufacturerData = mfData
                _rssiUpdates.tryEmit(uuid to rssiValue)
            } else {
                val device = AppleBluetoothPeripheral(peripheral, rssiValue, mfData)
                _peripherals.value = _peripherals.value + device
            }
        }
    }

    private fun parseManufacturerData(advertisementData: Map<Any?, *>): Map<Int, ByteArray> {
        val raw = advertisementData["kCBAdvDataManufacturerData"] as? NSData ?: return emptyMap()
        val bytes = raw.toByteArray()
        if (bytes.size < 2) return emptyMap()
        val companyId = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        val payload = bytes.copyOfRange(2, bytes.size)
        return mapOf(companyId to payload)
    }
    
    override fun onPeripheralConnected(peripheral: CBPeripheral) {
        peripheral.delegate = peripheralDelegate
        nativeConnectionOwnership.connected(
            peripheral.identifier.UUIDString,
            peripheral,
        )
        callbackDispatcher.dispatch {
            val uuid = peripheral.identifier.UUIDString
            val existingConnection = connectedPeripherals[uuid]
            // Reuse the existing device wrapper (updating its native reference in-place so
            // callers that hold a reference to the old object continue to work), but always
            // create a fresh write-peer so it holds the current CBPeripheral instance.
            val device = existingConnection?.device?.also {
                it.updatePeripheral(peripheral)
            } ?: AppleBluetoothPeripheral(peripheral, null)
            val connection = centralWriteController.connected(CoreBluetoothWritePeer(peripheral))
            connectedPeripherals[uuid] = ActiveAppleConnection(
                peripheral = peripheral,
                device = device,
                connection = connection,
            )
            _connectionStateUpdates.tryEmit(
                ConnectionStateUpdate(device, BluetoothPeripheralState.Connected)
            )
        }
    }
    
    override fun onPeripheralDisconnected(peripheral: CBPeripheral, error: NSError?) {
        val uuid = peripheral.identifier.UUIDString
        nativeConnectionOwnership.disconnected(uuid, peripheral)
        callbackDispatcher.dispatch {
            val active = connectedPeripherals[uuid]
            val device = active?.device ?: AppleBluetoothPeripheral(peripheral, null)
            connectedPeripherals.remove(uuid)
            peripheral.delegate = null
            active?.let { centralWriteController.disconnected(it.connection) }
            _connectionStateUpdates.tryEmit(
                ConnectionStateUpdate(device, BluetoothPeripheralState.Disconnected)
            )
        }
    }
    
    override fun onPeripheralConnectionFailed(peripheral: CBPeripheral, error: NSError?) {
        // The peripheral was never successfully connected, so it is not in connectedPeripherals.
        // Still emit Disconnected to notify any code waiting on the connection outcome.
        nativeConnectionOwnership.disconnected(
            peripheral.identifier.UUIDString,
            peripheral,
        )
        callbackDispatcher.dispatch {
            val device = AppleBluetoothPeripheral(peripheral, null)
            val active = connectedPeripherals[device.uuid]
            if (active != null) {
                connectedPeripherals.remove(device.uuid)
                centralWriteController.disconnected(active.connection)
            }
            _connectionStateUpdates.tryEmit(
                ConnectionStateUpdate(device, BluetoothPeripheralState.Disconnected)
            )
        }
    }
    
    // CBPeripheralCallback implementation
    
    override fun onServicesDiscovered(peripheral: CBPeripheral, error: NSError?) {
        callbackDispatcher.dispatch {
            if (error != null) return@dispatch
            val active = activeConnection(peripheral) ?: return@dispatch
            _serviceDiscoveryUpdates.tryEmit(
                ServiceDiscoveryUpdate(
                    active.device,
                    ServiceDiscoveryPhase.ServicesDiscovered,
                )
            )
        }
    }

    override fun onCharacteristicsDiscovered(peripheral: CBPeripheral, service: CBService, error: NSError?) {
        callbackDispatcher.dispatch {
            if (error != null) return@dispatch
            val active = activeConnection(peripheral) ?: return@dispatch
            val bluetoothService = AppleBluetoothService(service)
            _serviceDiscoveryUpdates.tryEmit(
                ServiceDiscoveryUpdate(
                    active.device,
                    ServiceDiscoveryPhase.CharacteristicsDiscovered,
                    bluetoothService,
                )
            )
            service.characteristics
                ?.mapNotNull { it as? CBCharacteristic }
                ?.forEach { characteristic ->
                    peripheral.discoverDescriptorsForCharacteristic(characteristic)
                }
        }
    }
    
    override fun onCharacteristicValueUpdated(
        peripheral: CBPeripheral,
        characteristic: CBCharacteristic,
        error: NSError?
    ) {
        if (error != null) return
        // Gate on the UUID-tracked connection (not referential equality of the CBPeripheral
        // instance) to stay consistent with activeConnection(). CoreBluetooth can hand back a
        // different CBPeripheral wrapper for the same underlying device (e.g. after a
        // reconnect via retrievePeripheralsWithIdentifiers), which previously caused valid
        // notifications to be silently dropped on iOS.
        if (connectedPeripherals[peripheral.identifier.UUIDString] == null) {
            return
        }
        val value = snapshotCallbackPayload(
            characteristic.value?.toByteArray()
        ) ?: return
        val bluetoothCharacteristic = AppleBluetoothCharacteristic(
            cbCharacteristic = characteristic,
            service = characteristic.service?.let { AppleBluetoothService(it) }
        )
        bluetoothCharacteristic.emitNotification(value)
        _characteristicNotifications.tryEmit(
            CharacteristicNotification(
                peripheral = AppleBluetoothPeripheral(peripheral, null),
                characteristic = bluetoothCharacteristic,
                value = value,
            )
        )
    }
    
    override fun onCharacteristicWritten(
        peripheral: CBPeripheral,
        characteristic: CBCharacteristic,
        error: NSError?
    ) {
        callbackDispatcher.dispatch {
            val active = activeConnection(peripheral) ?: return@dispatch
            centralWriteController.onCharacteristicWritten(
                connection = active.connection,
                characteristicUuid = appleCharacteristicIdentity(
                    characteristic.service?.UUID?.UUIDString,
                    characteristic.UUID.UUIDString,
                ),
                failure = error?.let {
                    IllegalStateException(it.localizedDescription)
                },
            )
        }
    }
    
    override fun onDescriptorsDiscovered(
        peripheral: CBPeripheral,
        characteristic: CBCharacteristic,
        error: NSError?
    ) {
        // Descriptors discovered - automatically handled through characteristic.descriptors property
    }
    
    override fun onNotificationStateUpdated(
        peripheral: CBPeripheral,
        characteristic: CBCharacteristic,
        error: NSError?
    ) {
        callbackDispatcher.dispatch {
            val active = activeConnection(peripheral) ?: return@dispatch
            centralWriteController.onNotificationStateUpdated(
                connection = active.connection,
                characteristicIdentity = appleCharacteristicIdentity(
                    characteristic.service?.UUID?.UUIDString,
                    characteristic.UUID.UUIDString,
                ),
                isNotifying = characteristic.isNotifying,
                failure = error?.let {
                    IllegalStateException(it.localizedDescription)
                },
            )
        }
    }

    override fun onReadyToSendWriteWithoutResponse(peripheral: CBPeripheral) {
        callbackDispatcher.dispatch {
            val active = activeConnection(peripheral) ?: return@dispatch
            centralWriteController.onReadyToSendWithoutResponse(
                active.connection,
                CoreBluetoothWritePeer(peripheral),
            )
        }
    }
    
    override fun onL2CAPChannelOpened(peripheral: CBPeripheral, channel: CBL2CAPChannel?, error: NSError?) {
        val deferred = l2capDeferreds[peripheral.identifier.UUIDString] ?: return
        when {
            error != null ->
                deferred.completeExceptionally(
                    L2capException("Failed to open L2CAP channel: ${error.localizedDescription}")
                )
            channel == null ->
                deferred.completeExceptionally(L2capException("L2CAP channel was null"))
            else -> deferred.complete(channel)
        }
    }
    
    override fun onDescriptorWritten(peripheral: CBPeripheral, descriptor: CBDescriptor, error: NSError?) {
        // Descriptor written - could expose this through a callback if needed
    }

    private fun activeConnection(peripheral: CBPeripheral): ActiveAppleConnection? =
        connectedPeripherals[peripheral.identifier.UUIDString]
}

private data class ActiveAppleConnection(
    val peripheral: CBPeripheral,
    val device: AppleBluetoothPeripheral,
    val connection: AppleCentralConnectionKey,
)

private open class CoreBluetoothWritePeer(
    protected val peripheral: CBPeripheral,
) : AppleCentralWritePeer {
    override val peripheralUuid: String
        get() = peripheral.identifier.UUIDString
    override val connected: Boolean
        get() = peripheral.state == CBPeripheralStateConnected
    override val canSendWithoutResponse: Boolean
        get() = peripheral.canSendWriteWithoutResponse

    override fun maximumWriteValueLength(writeType: CharacteristicWriteType): Int =
        peripheral.maximumWriteValueLengthForType(writeType.toNativeWriteType()).toInt()
}

private class CoreBluetoothWriteTarget(
    peripheral: CBPeripheral,
    private val characteristic: CBCharacteristic,
) : CoreBluetoothWritePeer(peripheral), AppleCentralWriteTarget {
    override val characteristicUuid: String
        get() = appleCharacteristicIdentity(
            characteristic.service?.UUID?.UUIDString,
            characteristic.UUID.UUIDString,
        )

    override fun writeValue(
        payload: ByteArray,
        writeType: CharacteristicWriteType,
    ) {
        peripheral.writeValue(
            payload.toData(),
            characteristic,
            writeType.toNativeWriteType(),
        )
    }
}

private class CoreBluetoothNotificationTarget(
    private val peripheral: CBPeripheral,
    private val characteristic: CBCharacteristic,
    override val characteristicUuid: Uuid,
) : AppleNotificationTarget {
    override val peripheralUuid: String
        get() = peripheral.identifier.UUIDString
    override val characteristicIdentity: String
        get() = appleCharacteristicIdentity(
            characteristic.service?.UUID?.UUIDString,
            characteristic.UUID.UUIDString,
        )
    override val connected: Boolean
        get() = peripheral.state == CBPeripheralStateConnected

    override suspend fun setNotifyValue(enabled: Boolean) {
        peripheral.setNotifyValue(enabled, characteristic)
    }
}

private fun CharacteristicWriteType.toNativeWriteType() = when (this) {
    CharacteristicWriteType.WithResponse -> CBCharacteristicWriteWithResponse
    CharacteristicWriteType.WithoutResponse -> CBCharacteristicWriteWithoutResponse
}

private fun Int?.toCharacteristicWriteType() = when (this) {
    1 -> CharacteristicWriteType.WithoutResponse
    else -> CharacteristicWriteType.WithResponse
}

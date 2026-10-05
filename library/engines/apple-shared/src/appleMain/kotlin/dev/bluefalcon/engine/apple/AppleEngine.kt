package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.*
import kotlinx.cinterop.BetaInteropApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import platform.CoreBluetooth.*
import platform.Foundation.*

/**
 * Shared Apple implementation of BlueFalconEngine for iOS and macOS
 * Uses CoreBluetooth framework
 */
@OptIn(BetaInteropApi::class)
class AppleEngine : BlueFalconEngine, CBCentralManagerCallback, CBPeripheralCallback {
    
    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val discovery = AppleDiscoveryStore<BluetoothPeripheral>(
        keyOf = { it.uuid },
        payloadSize = { it.manufacturerData.values.sumOf { bytes -> bytes.size.toLong() } },
        isPinned = { device ->
            val token = nativeConnectionOwnership.current(device.uuid)
            device is AppleBluetoothPeripheral && token != null &&
                token.owner === device.cbPeripheral && nativeConnectionOwnership.isActive(token)
        },
    )
    override val peripherals: StateFlow<Set<BluetoothPeripheral>> = discovery.peripherals
    val discoveryStorageStatus: AppleDiscoveryStorageStatus get() = discovery.status
    
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

    private val centralWriteController = AppleCentralWriteController(scope, onQuarantine = { connection ->
        val token = nativeConnectionOwnership.current(connection.peripheralUuid)
        if (token != null && token.operationOwner.value == connection) {
            retireRejectedCallback(token)
        } else if (token != null) callbackDispatcher.dispatch {
            if (connectedPeripherals[connection.peripheralUuid]?.connection == connection) requestTermination(token)
        }
    })
    private val callbackDispatcher = AppleCentralCallbackDispatcher(scope)
    /** Bounded private ingress diagnostics; overload rejects newest native callback. */
    val callbackIngressStatus: StateFlow<AppleCallbackIngressStatus> = callbackDispatcher.status
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
    private val connectionAttempts = AppleConnectionAttemptCoordinator()
    val connectionAttemptStorageStatus: StateFlow<AppleConnectionAttemptStorageStatus> = connectionAttempts.status
    private val peerManagers = ApplePeerManagerEpochs<BluetoothPeripheralManager>()
    private val terminalWatches = AppleTerminalWatchdogs<AppleNativeConnectionToken<CBPeripheral>>({ action ->
        scope.launch { delay(10_000L); action() }
    })
    private val peripheralDelegates = MutableStateFlow<
        Map<AppleNativeConnectionToken<CBPeripheral>, CBPeripheralDelegateWrapper>
    >(emptyMap())

    private fun installPeripheralDelegate(peripheral: CBPeripheral) {
        val token = nativeConnectionOwnership.capture(peripheral.identifier.UUIDString, peripheral)
            ?: return
        peripheral.delegate = peripheralDelegates.value[token]
    }
    
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
        discovery.clear()
    }
    
    override suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean) {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        
        val uuid = applePeripheral.cbPeripheral.identifier.UUIDString
        connectionAttempts.withAttempt(uuid) {
            peerManagers.current(uuid)?.takeIf { !peerManagers.isCurrent(it) }?.let {
                withTimeout(11_000L) { it.terminated.await() }
            }
            val previous = nativeConnectionOwnership.current(uuid)
            if (previous != null) {
                if (peerManagers.current(uuid)?.let(peerManagers::isCurrent) == true &&
                    nativeConnectionOwnership.isActive(previous) && !terminalWatches.isPending(previous) &&
                    (previous.owner.state == CBPeripheralStateConnected || previous.owner.state == CBPeripheralStateConnecting)) {
                    applePeripheral.updatePeripheral(previous.owner)
                    installPeripheralDelegate(previous.owner)
                    return@withAttempt
                }
                requestTermination(previous)
                withTimeout(11_000L) { previous.terminated.await() }
            }
            val epoch = peerManagers.reserve(uuid)
            try {
                val manager = BluetoothPeripheralManager(PeerManagerCallback(epoch))
                epoch.manager = manager
                manager.awaitPoweredOn()
                if (!peerManagers.isCurrent(epoch)) throw IllegalStateException("Apple manager retired during connection")
                // CBPeripheral objects belong to their originating manager. Transfer identity,
                // never a scanned/retired native handle, to this isolated manager.
                val cbPeripheral = manager.centralManager.retrievePeripheralsWithIdentifiers(listOf(NSUUID(uuid)))
                    .filterIsInstance<CBPeripheral>().firstOrNull()
                    ?: throw IllegalStateException("Peripheral unavailable to replacement Apple manager; scan again")
                check(peerManagers.nativeAllowed(cbPeripheral)) {
                    "Replacement Apple manager returned a quarantined native handle or recovery capacity exhausted"
                }
                val token = nativeConnectionOwnership.connected(uuid, cbPeripheral, epoch)
                val delegate = CBPeripheralDelegateWrapper(ConnectionCallback(token))
                peripheralDelegates.update { it + (token to delegate) }
                cbPeripheral.delegate = delegate
                applePeripheral.updatePeripheral(cbPeripheral)
                manager.centralManager.connectPeripheral(cbPeripheral, null)
            } catch (failure: Throwable) {
                retirePeer(epoch, nativeConnectionOwnership.current(uuid)?.takeIf { it.origin === epoch }, forced = true)
                throw failure
            }
        }
    }

    override suspend fun disconnect(peripheral: BluetoothPeripheral) {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        nativeConnectionOwnership.current(applePeripheral.uuid)?.let(::requestTermination)
    }

    private fun requestTermination(token: AppleNativeConnectionToken<CBPeripheral>) {
        if (!nativeConnectionOwnership.isActive(token)) return
        val epoch = peerManagers.current(token.peripheralUuid) ?: return
        terminalWatches.start(token,
            stillCurrent = { nativeConnectionOwnership.isActive(token) && peerManagers.isCurrent(epoch) },
            expire = {
                retirePeer(epoch, token, forced = true)
            },
        )
        epoch.manager?.centralManager?.cancelPeripheralConnection(token.owner)
    }

    private fun retirePeer(
        epoch: ApplePeerManagerEpochs.Epoch<BluetoothPeripheralManager>,
        token: AppleNativeConnectionToken<CBPeripheral>?,
        forced: Boolean,
    ) {
        peerManagers.retireAfterClose(epoch, close = {
            token?.let { nativeConnectionOwnership.beginRetirement(it) }
            if (forced && token != null) peerManagers.quarantineNative(token.owner)
            epoch.manager?.close(token?.owner)
        }, cleanup = {
            if (token == null) peerManagers.finishRetirement(epoch)
            else retireConnection(token, epoch)
        })
    }

    private fun retireConnection(token: AppleNativeConnectionToken<CBPeripheral>, epoch: ApplePeerManagerEpochs.Epoch<BluetoothPeripheralManager>) {
        val admitted = callbackDispatcher.dispatchTerminal {
            try {
                val active = connectedPeripherals[token.peripheralUuid]
                if (active?.ownership === token) {
                    connectedPeripherals.remove(token.peripheralUuid)
                    centralWriteController.disconnected(active.connection)
                    l2capDeferreds.remove(token.peripheralUuid)?.completeExceptionally(L2capException("Apple connection retired"))
                    _connectionStateUpdates.tryEmit(ConnectionStateUpdate(active.device, BluetoothPeripheralState.Disconnected))
                }
            } finally {
                peripheralDelegates.update { it - token }
                terminalWatches.complete(token)
                nativeConnectionOwnership.disconnected(token)
                peerManagers.finishRetirement(epoch)
                token.terminated.complete(Unit)
            }
        }
        // Capacity cannot reject while open: 32 held peer admissions each schedule one
        // retirement and fit 64 reserved slots. Closed dispatcher lifecycle belongs to
        // the engine final-owner teardown contract (R08), not callback overload recovery.
        check(admitted || callbackDispatcher.status.value.closed) { "Apple terminal callback reserve exhausted" }
    }

    private inner class PeerManagerCallback(
        private val epoch: ApplePeerManagerEpochs.Epoch<BluetoothPeripheralManager>,
    ) : CBCentralManagerCallback {
        override fun onStateUpdated(state: CBManagerState) = Unit
        override fun onPeripheralDiscovered(peripheral: CBPeripheral, advertisementData: Map<Any?, *>, rssi: NSNumber) = Unit
        override fun onPeripheralConnected(peripheral: CBPeripheral) {
            if (peerManagers.isCurrent(epoch)) onPeerConnected(epoch, peripheral)
        }
        override fun onPeripheralDisconnected(peripheral: CBPeripheral, error: NSError?) {
            if (peerManagers.isCurrent(epoch)) finish(peripheral)
        }
        override fun onPeripheralConnectionFailed(peripheral: CBPeripheral, error: NSError?) {
            if (peerManagers.isCurrent(epoch)) finish(peripheral)
        }
        private fun finish(peripheral: CBPeripheral) {
            val token = peerManagers.capture(epoch, nativeConnectionOwnership, peripheral) ?: return
            retirePeer(epoch, token, forced = false)
        }
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
        installPeripheralDelegate(applePeripheral.cbPeripheral)
        
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
        installPeripheralDelegate(applePeripheral.cbPeripheral)
        
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
    ): ByteArray? {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        
        val appleCharacteristic = characteristic as? AppleBluetoothCharacteristic
            ?: throw IllegalArgumentException("Characteristic must be an AppleBluetoothCharacteristic")

        if (!nativeAttributeBelongsTo(
                applePeripheral.cbPeripheral,
                appleCharacteristic.cbCharacteristic.service?.peripheral,
            )
        ) {
            throw IllegalArgumentException(
                "Characteristic ${characteristic.uuid} is not owned by the target peripheral"
            )
        }
        
        // Ensure delegate is set
        installPeripheralDelegate(applePeripheral.cbPeripheral)

        val outcome = centralWriteController.read(
            CoreBluetoothReadTarget(
                peripheral = applePeripheral.cbPeripheral,
                characteristic = appleCharacteristic.cbCharacteristic,
            )
        )
        return when (outcome) {
            is AppleReadOutcome.Success -> outcome.value
            is AppleReadOutcome.Disconnected ->
                throw IllegalStateException("Peripheral disconnected before the read completed")
            is AppleReadOutcome.Failed -> throw outcome.cause
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
        installPeripheralDelegate(applePeripheral.cbPeripheral)
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
        installPeripheralDelegate(applePeripheral.cbPeripheral)
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
        installPeripheralDelegate(cbPeripheral)

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
        advertisementData: Map<Any?,
        *>,
        rssi: NSNumber,
    ) {
        if (isScanning) {
            val uuid = peripheral.identifier.UUIDString
            val rssiValue = rssi.floatValue
            val mfData = parseManufacturerData(advertisementData) ?: run { discovery.reject(); return }
            val existing = discovery.valueFor(uuid) as? AppleBluetoothPeripheral
            if (existing != null) {
                val retainedData = mfData.ifEmpty { existing.manufacturerData }
                if (!discovery.put(existing, retainedData.values.sumOf { it.size.toLong() })) return
                existing.rssi = rssiValue
                if (mfData.isNotEmpty()) existing.manufacturerData = mfData
                _rssiUpdates.tryEmit(uuid to rssiValue)
            } else {
                val device = AppleBluetoothPeripheral(peripheral, rssiValue, mfData)
                discovery.put(device, mfData.values.sumOf { it.size.toLong() })
            }
        }
    }

    private fun parseManufacturerData(advertisementData: Map<Any?, *>): Map<Int, ByteArray>? {
        val raw = advertisementData["kCBAdvDataManufacturerData"] as? NSData ?: return emptyMap()
        return decodeAppleManufacturerData(raw.length) { raw.toByteArray() }
    }
    
    override fun onPeripheralConnected(peripheral: CBPeripheral) = Unit

    private fun onPeerConnected(epoch: ApplePeerManagerEpochs.Epoch<BluetoothPeripheralManager>, peripheral: CBPeripheral) {
        val token = peerManagers.capture(epoch, nativeConnectionOwnership, peripheral) ?: return
        installPeripheralDelegate(peripheral)
        dispatchOwned(token) {
            if (!peerManagers.isCurrent(epoch) || !nativeConnectionOwnership.isActive(token)) return@dispatchOwned
            val uuid = peripheral.identifier.UUIDString
            val existingConnection = connectedPeripherals[uuid]
            if (existingConnection?.ownership === token) return@dispatchOwned
            val scannedDevice = discovery.valueFor(uuid) as? AppleBluetoothPeripheral
            // Prefer the wrapper previously emitted by scanning so its advertisement metadata
            // (manufacturer data, RSSI, and advertised identity) survives the transition to a
            // GATT connection. CoreBluetooth's connected CBPeripheral does not carry that data.
            val device = selectConnectionPeripheral(
                connected = existingConnection?.device,
                scanned = scannedDevice,
                create = { AppleBluetoothPeripheral(peripheral, null) },
                updateNativePeripheral = {},
            )
            if (!discovery.put(device, device.manufacturerData.values.sumOf { it.size.toLong() }, evictInactive = true)) {
                retirePeer(epoch, token, forced = false)
                _connectionStateUpdates.tryEmit(ConnectionStateUpdate(device, BluetoothPeripheralState.Disconnected))
                return@dispatchOwned
            }
            device.updatePeripheral(peripheral)
            val connection = centralWriteController.connected(CoreBluetoothWritePeer(peripheral))
            check(token.operationOwner.compareAndSet(null, connection)) { "Apple native token already has an operation owner" }
            connectedPeripherals[uuid] = ActiveAppleConnection(
                peripheral = peripheral,
                device = device,
                connection = connection,
                ownership = token,
            )
            _connectionStateUpdates.tryEmit(
                ConnectionStateUpdate(device, BluetoothPeripheralState.Connected)
            )
        }
    }
    
    override fun onPeripheralDisconnected(peripheral: CBPeripheral, error: NSError?) = Unit
    override fun onPeripheralConnectionFailed(peripheral: CBPeripheral, error: NSError?) = Unit

    private inner class ConnectionCallback(token: AppleNativeConnectionToken<CBPeripheral>) : CBPeripheralCallback {
        private val callbacks = AppleNativeConnectionCallbacks(token, nativeConnectionOwnership)

        override fun onServicesDiscovered(peripheral: CBPeripheral, error: NSError?) {
            callbacks.forward { token ->
                this@AppleEngine.onServicesDiscovered(token, peripheral, error)
            }
        }

        override fun onCharacteristicsDiscovered(
            peripheral: CBPeripheral,
            service: CBService,
            error: NSError?,
        ) {
            callbacks.forward { token ->
                this@AppleEngine.onCharacteristicsDiscovered(token, peripheral, service, error)
            }
        }

        override fun onCharacteristicValueUpdated(
            peripheral: CBPeripheral,
            characteristic: CBCharacteristic,
            error: NSError?,
        ) {
            callbacks.forward { token ->
                this@AppleEngine.onCharacteristicValueUpdated(token, peripheral, characteristic, error)
            }
        }

        override fun onCharacteristicWritten(
            peripheral: CBPeripheral,
            characteristic: CBCharacteristic,
            error: NSError?,
        ) {
            callbacks.forward { token ->
                this@AppleEngine.onCharacteristicWritten(token, peripheral, characteristic, error)
            }
        }

        override fun onDescriptorsDiscovered(
            peripheral: CBPeripheral,
            characteristic: CBCharacteristic,
            error: NSError?,
        ) {
            callbacks.forward { token ->
                this@AppleEngine.onDescriptorsDiscovered(token, peripheral, characteristic, error)
            }
        }

        override fun onNotificationStateUpdated(
            peripheral: CBPeripheral,
            characteristic: CBCharacteristic,
            error: NSError?,
        ) {
            callbacks.forward { token ->
                this@AppleEngine.onNotificationStateUpdated(token, peripheral, characteristic, error)
            }
        }

        override fun onReadyToSendWriteWithoutResponse(peripheral: CBPeripheral) {
            callbacks.forward { token ->
                this@AppleEngine.onReadyToSendWriteWithoutResponse(token, peripheral)
            }
        }

        override fun onL2CAPChannelOpened(
            peripheral: CBPeripheral,
            channel: CBL2CAPChannel?,
            error: NSError?,
        ) {
            callbacks.forward { token ->
                this@AppleEngine.onL2CAPChannelOpened(token, peripheral, channel, error)
            }
        }

        override fun onDescriptorWritten(
            peripheral: CBPeripheral,
            descriptor: CBDescriptor,
            error: NSError?,
        ) {
            callbacks.forward { token ->
                this@AppleEngine.onDescriptorWritten(token, peripheral, descriptor, error)
            }
        }
    }

    // CBPeripheralCallback implementation
    
    override fun onServicesDiscovered(peripheral: CBPeripheral, error: NSError?) {
        val token = nativeConnectionOwnership.capture(peripheral.identifier.UUIDString, peripheral)
            ?: return
        onServicesDiscovered(token, peripheral, error)
    }

    private fun onServicesDiscovered(
        token: AppleNativeConnectionToken<CBPeripheral>,
        peripheral: CBPeripheral,
        error: NSError?,
    ) {
        dispatchOwned(token) {
            if (error != null) return@dispatchOwned
            val active = activeConnection(token) ?: return@dispatchOwned
            _serviceDiscoveryUpdates.tryEmit(
                ServiceDiscoveryUpdate(
                    active.device,
                    ServiceDiscoveryPhase.ServicesDiscovered,
                )
            )
        }
    }

    override fun onCharacteristicsDiscovered(
        peripheral: CBPeripheral,
        service: CBService,
        error: NSError?,
    ) {
        val token = nativeConnectionOwnership.capture(peripheral.identifier.UUIDString, peripheral)
            ?: return
        onCharacteristicsDiscovered(token, peripheral, service, error)
    }

    private fun onCharacteristicsDiscovered(
        token: AppleNativeConnectionToken<CBPeripheral>,
        peripheral: CBPeripheral,
        service: CBService,
        error: NSError?,
    ) {
        dispatchOwned(token) {
            if (error != null) return@dispatchOwned
            val active = activeConnection(token) ?: return@dispatchOwned
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
        error: NSError?,
    ) {
        val token = nativeConnectionOwnership.capture(peripheral.identifier.UUIDString, peripheral)
            ?: return
        onCharacteristicValueUpdated(token, peripheral, characteristic, error)
    }

    private fun onCharacteristicValueUpdated(
        token: AppleNativeConnectionToken<CBPeripheral>,
        peripheral: CBPeripheral,
        characteristic: CBCharacteristic,
        error: NSError?,
    ) {
        val characteristicIdentity = appleCharacteristicIdentity(
            characteristic.service?.UUID?.UUIDString,
            characteristic.UUID.UUIDString,
        )
        val value = if (error == null) {
            snapshotCallbackPayload(characteristic.value?.toByteArray())
        } else {
            null
        }
        val isNotifying = characteristic.isNotifying
        // This callback fires for both solicited reads (readValueForCharacteristic) and
        // unsolicited notifications - resolve any pending read for this exact characteristic
        // (ADR 0014) without disturbing the notification flow below, which must keep firing
        // for genuine subscription updates regardless of whether a read happens to be pending.
        dispatchOwned(token, payloadBytes = value?.size ?: 0) {
            val active = activeConnection(token) ?: return@dispatchOwned
            centralWriteController.onCharacteristicValueReceived(
                connection = active.connection,
                characteristicUuid = characteristicIdentity,
                value = value,
                failure = error?.let { IllegalStateException(it.localizedDescription) },
            )
        }
        if (error != null || !isNotifying) return
        val safeValue = value ?: return
        val bluetoothCharacteristic = AppleBluetoothCharacteristic(
            cbCharacteristic = characteristic,
            service = characteristic.service?.let { AppleBluetoothService(it) }
        )
        dispatchOwned(token, payloadBytes = safeValue.size) {
            val active = activeConnection(token) ?: return@dispatchOwned
            bluetoothCharacteristic.emitNotification(safeValue)
            _characteristicNotifications.tryEmit(
                CharacteristicNotification(
                    peripheral = active.device,
                    characteristic = bluetoothCharacteristic,
                    value = safeValue,
                )
            )
        }
    }
    
    override fun onCharacteristicWritten(
        peripheral: CBPeripheral,
        characteristic: CBCharacteristic,
        error: NSError?,
    ) {
        val token = nativeConnectionOwnership.capture(peripheral.identifier.UUIDString, peripheral)
            ?: return
        onCharacteristicWritten(token, peripheral, characteristic, error)
    }

    private fun onCharacteristicWritten(
        token: AppleNativeConnectionToken<CBPeripheral>,
        peripheral: CBPeripheral,
        characteristic: CBCharacteristic,
        error: NSError?,
    ) {
        dispatchOwned(token) {
            val active = activeConnection(token) ?: return@dispatchOwned
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
        error: NSError?,
    ) {
        val token = nativeConnectionOwnership.capture(peripheral.identifier.UUIDString, peripheral)
            ?: return
        onDescriptorsDiscovered(token, peripheral, characteristic, error)
    }

    private fun onDescriptorsDiscovered(
        token: AppleNativeConnectionToken<CBPeripheral>,
        peripheral: CBPeripheral,
        characteristic: CBCharacteristic,
        error: NSError?,
    ) {
        // Descriptors discovered - automatically handled through characteristic.descriptors property
    }
    
    override fun onNotificationStateUpdated(
        peripheral: CBPeripheral,
        characteristic: CBCharacteristic,
        error: NSError?,
    ) {
        val token = nativeConnectionOwnership.capture(peripheral.identifier.UUIDString, peripheral)
            ?: return
        onNotificationStateUpdated(token, peripheral, characteristic, error)
    }

    private fun onNotificationStateUpdated(
        token: AppleNativeConnectionToken<CBPeripheral>,
        peripheral: CBPeripheral,
        characteristic: CBCharacteristic,
        error: NSError?,
    ) {
        val isNotifying = characteristic.isNotifying
        dispatchOwned(token) {
            val active = activeConnection(token) ?: return@dispatchOwned
            centralWriteController.onNotificationStateUpdated(
                connection = active.connection,
                characteristicIdentity = appleCharacteristicIdentity(
                    characteristic.service?.UUID?.UUIDString,
                    characteristic.UUID.UUIDString,
                ),
                isNotifying = isNotifying,
                failure = error?.let {
                    IllegalStateException(it.localizedDescription)
                },
            )
        }
    }

    override fun onReadyToSendWriteWithoutResponse(peripheral: CBPeripheral) {
        val token = nativeConnectionOwnership.capture(peripheral.identifier.UUIDString, peripheral)
            ?: return
        onReadyToSendWriteWithoutResponse(token, peripheral)
    }

    private fun onReadyToSendWriteWithoutResponse(
        token: AppleNativeConnectionToken<CBPeripheral>,
        peripheral: CBPeripheral,
    ) {
        dispatchOwned(token) {
            val active = activeConnection(token) ?: return@dispatchOwned
            centralWriteController.onReadyToSendWithoutResponse(
                active.connection,
                CoreBluetoothWritePeer(peripheral),
            )
        }
    }
    
    override fun onL2CAPChannelOpened(
        peripheral: CBPeripheral,
        channel: CBL2CAPChannel?,
        error: NSError?,
    ) {
        val token = nativeConnectionOwnership.capture(peripheral.identifier.UUIDString, peripheral)
            ?: return
        onL2CAPChannelOpened(token, peripheral, channel, error)
    }

    private fun onL2CAPChannelOpened(
        token: AppleNativeConnectionToken<CBPeripheral>,
        peripheral: CBPeripheral,
        channel: CBL2CAPChannel?,
        error: NSError?,
    ) {
        dispatchOwned(token) {
            activeConnection(token) ?: return@dispatchOwned
            val deferred = l2capDeferreds[peripheral.identifier.UUIDString] ?: return@dispatchOwned
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
    }
    
    override fun onDescriptorWritten(
        peripheral: CBPeripheral,
        descriptor: CBDescriptor,
        error: NSError?,
    ) {
        val token = nativeConnectionOwnership.capture(peripheral.identifier.UUIDString, peripheral)
            ?: return
        onDescriptorWritten(token, peripheral, descriptor, error)
    }

    private fun onDescriptorWritten(
        token: AppleNativeConnectionToken<CBPeripheral>,
        peripheral: CBPeripheral,
        descriptor: CBDescriptor,
        error: NSError?,
    ) {
        // Descriptor written - could expose this through a callback if needed
    }

    private fun dispatchOwned(
        token: AppleNativeConnectionToken<CBPeripheral>,
        payloadBytes: Int = 0,
        callback: suspend () -> Unit,
    ): Boolean = callbackDispatcher.dispatchOwned(
        token, nativeConnectionOwnership, payloadBytes,
        onRejected = ::retireRejectedCallback, callback = callback,
    )

    private fun retireRejectedCallback(token: AppleNativeConnectionToken<CBPeripheral>) {
        if (!nativeConnectionOwnership.isActive(token)) return
        val epoch = peerManagers.current(token.peripheralUuid) ?: return
        if (epoch !== token.origin || !peerManagers.isCurrent(epoch)) return
        retirePeer(epoch, token, forced = true)
    }

    private fun activeConnection(token: AppleNativeConnectionToken<CBPeripheral>): ActiveAppleConnection? =
        connectedPeripherals[token.peripheralUuid]?.takeIf {
            it.ownership === token && nativeConnectionOwnership.isActive(token) &&
                peerManagers.current(token.peripheralUuid)?.let { epoch ->
                    epoch === token.origin && peerManagers.isCurrent(epoch)
                } == true
        }
}

private data class ActiveAppleConnection(
    val peripheral: CBPeripheral,
    val device: AppleBluetoothPeripheral,
    val connection: AppleCentralConnectionKey,
    val ownership: AppleNativeConnectionToken<CBPeripheral>,
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

internal fun <T> selectConnectionPeripheral(
    connected: T?,
    scanned: T?,
    create: () -> T,
    updateNativePeripheral: (T) -> Unit,
): T {
    val selected = connected ?: scanned ?: create()
    updateNativePeripheral(selected)
    return selected
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

private class CoreBluetoothReadTarget(
    private val peripheral: CBPeripheral,
    private val characteristic: CBCharacteristic,
) : AppleCentralReadTarget {
    override val isNotifying: Boolean
        get() = characteristic.isNotifying
    override val peripheralUuid: String
        get() = peripheral.identifier.UUIDString
    override val characteristicUuid: String
        get() = appleCharacteristicIdentity(
            characteristic.service?.UUID?.UUIDString,
            characteristic.UUID.UUIDString,
        )
    override val connected: Boolean
        get() = peripheral.state == CBPeripheralStateConnected

    override fun readValue() {
        peripheral.readValueForCharacteristic(characteristic)
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

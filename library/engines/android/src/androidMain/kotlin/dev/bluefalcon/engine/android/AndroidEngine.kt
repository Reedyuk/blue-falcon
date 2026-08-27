package dev.bluefalcon.engine.android

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.bluefalcon.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

/**
 * Android implementation of BlueFalconEngine using Android BLE APIs.
 * Provides full BLE support including bonding, L2CAP, connection priority, and GATT operations.
 */
class AndroidEngine(
    internal val context: Context,
    private val logger: Logger? = null,
    private val autoDiscoverAllServicesAndCharacteristics: Boolean = true
) : BlueFalconEngine {
    
    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
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

    private val _notificationSubscriptionUpdates =
        MutableSharedFlow<NotificationSubscriptionUpdate>(extraBufferCapacity = 64)
    override val notificationSubscriptionUpdates: SharedFlow<NotificationSubscriptionUpdate> =
        _notificationSubscriptionUpdates

    private val _bondStateUpdates = MutableSharedFlow<BondStateUpdate>(extraBufferCapacity = 64)
    override val bondStateUpdates: SharedFlow<BondStateUpdate> = _bondStateUpdates

    private val centralWriteState = AndroidCentralWriteState()
    override val centralCapabilities = CentralCapabilities(
        reliableWriteResults = true,
        writeWithoutResponseReadiness = true,
        perConnectionMaximumWriteLength = true,
        notificationSubscriptionResults = true,
        restoration = false,
        bondCapability = BondCapability.Supported,
    )
    override val characteristicWriteCapabilities = centralWriteState.capabilities
    override val characteristicWriteReady = centralWriteState.writeReady
    
    override var isScanning: Boolean = false
        private set

    // Filters requested via scan(); matching is performed in software in the scan callback -
    // see the comment in scan() for why we don't rely on the native ScanFilter for this.
    @Volatile
    private var activeScanFilters: List<ServiceFilter> = emptyList()
    
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    
    private val scanCallback = BluetoothScanCallBack()
    private val gattCallback = GattClientCallback()
    
    var transportMethod: Int = BluetoothDevice.TRANSPORT_LE
    
    private var isBondReceiverRegistered = false
    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return
                
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                val mappedState = when (bondState) {
                    BluetoothDevice.BOND_BONDED -> BlueFalconBondState.Bonded
                    BluetoothDevice.BOND_BONDING -> BlueFalconBondState.Bonding
                    else -> BlueFalconBondState.None
                }
                logger?.debug("Bond state changed for ${device.address}: $mappedState")
                _bondStateUpdates.tryEmit(BondStateUpdate(device.address, mappedState))
            }
        }
    }
    
    init {
        BluetoothStateMonitor.register(context, this)
        _managerState.value = try {
            if (bluetoothManager.adapter?.isEnabled == true) BluetoothManagerState.Ready
            else BluetoothManagerState.NotReady
        } catch (_: SecurityException) {
            BluetoothManagerState.NotReady
        }
        logger?.info("AndroidEngine initialized")
    }
    
    internal fun onAdapterStateChanged(adapterOn: Boolean) {
        _managerState.value = if (adapterOn) {
            BluetoothManagerState.Ready
        } else {
            gattCallback.disconnectAllOnAdapterOff()
            BluetoothManagerState.NotReady
        }
    }
    
    override suspend fun scan(filters: List<ServiceFilter>) {
        logger?.info("Starting scan with ${filters.size} filters")
        isScanning = true
        activeScanFilters = filters

        // Android's native ScanFilter.setServiceUuid() only matches the "complete/incomplete
        // service UUID list" AD structure. Many real devices (e.g. Xiaomi/Mi Home accessories,
        // which advertise 0000fe95-...) only put their service UUID in the "service data" AD
        // structure instead, so a hardware-level ScanFilter silently drops them before they ever
        // reach our callback - see https://github.com/Reedyuk/blue-falcon/issues/222. To match the
        // more lenient behaviour of CoreBluetooth (macOS/iOS) we always scan unfiltered at the
        // platform level and apply [ServiceFilter] matching in software against every advertised
        // service UUID we can observe (see [matchesActiveFilters]).
        val scanFilters: List<ScanFilter> = listOf(ScanFilter.Builder().build())

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bluetoothManager.adapter?.bluetoothLeScanner?.startScan(scanFilters, settings, scanCallback)
    }
    
    override suspend fun stopScanning() {
        logger?.info("Stopping scan")
        isScanning = false
        bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }
    
    override fun clearPeripherals() {
        _peripherals.value = emptySet()
    }

    override suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean) {
        logger?.debug("Connecting to ${peripheral.name ?: peripheral.uuid}")
        val androidPeripheral = (peripheral as? AndroidBluetoothPeripheral) ?: return
        // Reset any stale per-connection state before reconnecting so consumers wait for the new
        // connection's discovery/MTU instead of being satisfied instantly by the previous session's
        // values. This also covers the case where the STATE_DISCONNECTED callback is delayed or never
        // arrives. Reset both the caller's instance and the tracked instance in [_peripherals]; they
        // are normally the same object, but a re-scan can produce a fresh instance.
        androidPeripheral.resetConnectionState()
        resetPeripheralState(androidPeripheral.device.address)
        val gatt = androidPeripheral.device.connectGatt(context, autoConnect, gattCallback, transportMethod)
        // Track the returned handle IMMEDIATELY, not only once it reaches STATE_CONNECTED. A direct
        // (autoConnect=false) connect that never establishes never fires onConnectionStateChange, so
        // without this it would never enter [gatts] — meaning neither disconnect() nor a later
        // connect() could ever close it, and the Android stack keeps initiating it for ~30 s. Against
        // a peripheral that accepts only one connection, several such orphaned initiations overlap and
        // wedge it (it stops completing any new connection until power-cycled). Registering here lets
        // the next connect()/disconnect() tear the orphan down, so at most one initiation is ever
        // outstanding per address.
        gatt?.let { gattCallback.trackConnecting(it) }
            ?: logger?.warn("connectGatt returned null for ${androidPeripheral.device.address}")
    }

    private fun peripheralFor(address: String): AndroidBluetoothPeripheral? =
        _peripherals.value.firstOrNull {
            (it as? AndroidBluetoothPeripheral)?.device?.address == address
        } as? AndroidBluetoothPeripheral

    private fun resetPeripheralState(address: String) {
        peripheralFor(address)?.resetConnectionState()
    }
    
    override suspend fun disconnect(peripheral: BluetoothPeripheral) {
        logger?.debug("Disconnecting from ${peripheral.name ?: peripheral.uuid}")
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        gattCallback.gattsForDevice(device).forEach { gatt ->
            gatt.disconnect()
            gattCallback.scheduleDisconnectTimeout(gatt)
        }
    }
    
    override fun connectionState(peripheral: BluetoothPeripheral): BluetoothPeripheralState {
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return BluetoothPeripheralState.Unknown
        return when (bluetoothManager.getConnectionState(device, BluetoothProfile.GATT)) {
            BluetoothProfile.STATE_CONNECTED -> BluetoothPeripheralState.Connected
            BluetoothProfile.STATE_CONNECTING -> BluetoothPeripheralState.Connecting
            BluetoothProfile.STATE_DISCONNECTED -> BluetoothPeripheralState.Disconnected
            BluetoothProfile.STATE_DISCONNECTING -> BluetoothPeripheralState.Disconnecting
            else -> BluetoothPeripheralState.Unknown
        }
    }
    
    override fun retrievePeripheral(identifier: String): BluetoothPeripheral? {
        return runCatching {
            bluetoothManager.adapter
                ?.getRemoteDevice(identifier)
                ?.let { AndroidBluetoothPeripheral(it) }
        }.onFailure { e ->
            logger?.error("retrievePeripheral error: ${e.message}")
        }.getOrNull()
    }
    
    override fun requestConnectionPriority(peripheral: BluetoothPeripheral, priority: ConnectionPriority) {
        logger?.debug("requestConnectionPriority: $priority")
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        gattCallback.gattsForDevice(device).forEach { gatt ->
            gatt.requestConnectionPriority(priority.toNative())
        }
    }
    
    override suspend fun discoverServices(peripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid>) {
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        gattCallback.gattsForDevice(device).forEach { gatt ->
            gattCallback.enqueueOperation(gatt, CentralGattOperationType.DiscoverServices, "discoverServices") {
                it.discoverServices()
            }
        }
    }

    override suspend fun discoverCharacteristics(
        peripheral: BluetoothPeripheral,
        service: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        val androidPeripheral = peripheral as AndroidBluetoothPeripheral
        if (!androidPeripheral.services.any { it.uuid == service.uuid }) {
            gattCallback.gattsForDevice(device).forEach { gatt ->
                gattCallback.enqueueOperation(gatt, CentralGattOperationType.DiscoverServices, "discoverServices") {
                    it.discoverServices()
                }
            }
        }
    }

    override suspend fun readCharacteristic(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic) {
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        val androidChar = (characteristic as? AndroidBluetoothCharacteristic)?.characteristic ?: return
        gattCallback.gattsForDevice(device).forEach { gatt ->
            fetchCharacteristic(androidChar, gatt).forEach { char ->
                gattCallback.enqueueOperation(
                    gatt,
                    CentralGattOperationType.ReadCharacteristic,
                    "readCharacteristic ${char.uuid}",
                    identity = characteristicOperationIdentity(
                        char.service?.uuid?.toString(),
                        char.uuid.toString(),
                    )
                ) {
                    it.readCharacteristic(char)
                }
            }
        }
    }
    
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
        writeCharacteristic(peripheral, characteristic, value.encodeToByteArray(), writeType)
    }
    
    @OptIn(ExperimentalStdlibApi::class)
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        logger?.debug("Writing value {length = ${value.size}, bytes = 0x${value.toHexString()}} with response $writeType")
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        val androidChar = (characteristic as? AndroidBluetoothCharacteristic)?.characteristic ?: return

        // Snapshot the payload so a caller that reuses/mutates its array after this call cannot change
        // the bytes that get written when the queued operation is finally dispatched.
        val payload = value.copyOf()
        gattCallback.gattsForDevice(device).forEach { gatt ->
            fetchCharacteristic(androidChar, gatt).forEach { char ->
                gattCallback.enqueueOperation(
                    gatt,
                    CentralGattOperationType.WriteCharacteristic,
                    "writeCharacteristic ${char.uuid}",
                    identity = characteristicOperationIdentity(
                        char.service?.uuid?.toString(),
                        char.uuid.toString(),
                    )
                ) {
                    // Apply the value/writeType at dispatch time so a queued write never mutates the
                    // characteristic while a previously queued operation on it is still in flight.
                    writeType?.let { wt -> char.writeType = wt }
                    char.setValue(payload)
                    it.writeCharacteristic(char)
                }
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: CharacteristicWriteType,
    ): CharacteristicWriteResult {
        logger?.debug(
            "Writing typed value {length = ${value.size}, bytes = 0x${value.toHexString()}} " +
                "type=$writeType"
        )
        val androidPeripheral = peripheral as? AndroidBluetoothPeripheral
            ?: return CharacteristicWriteResult.Failed(
                IllegalArgumentException("Peripheral must be an AndroidBluetoothPeripheral")
            )
        val requestedCharacteristic =
            (characteristic as? AndroidBluetoothCharacteristic)?.characteristic
                ?: return CharacteristicWriteResult.Failed(
                    IllegalArgumentException(
                        "Characteristic must be an AndroidBluetoothCharacteristic"
                    )
                )
        val gatt = gattCallback.activeGattForDevice(androidPeripheral.device)
            ?: return CharacteristicWriteResult.Disconnected
        val generation = gattCallback.generationFor(gatt)
            ?: return CharacteristicWriteResult.Disconnected
        centralWriteState.validateWrite(
            peripheralUuid = peripheral.uuid,
            generation = generation,
            writeType = writeType,
            payloadSize = value.size,
        )?.let { return it }
        val targetCharacteristic =
            fetchCharacteristic(requestedCharacteristic, gatt, exactNativeIdentity = true)
                .firstOrNull()
            ?: return CharacteristicWriteResult.Failed(
                IllegalArgumentException(
                    "Characteristic ${characteristic.uuid} is not part of the active GATT"
                )
            )
        val operationKey = CentralGattOperationKey(
            generation = generation,
            type = CentralGattOperationType.WriteCharacteristic,
            identity = characteristicOperationIdentity(
                targetCharacteristic.service?.uuid?.toString(),
                targetCharacteristic.uuid.toString(),
            ),
        )
        val payload = value.copyOf()
        val nativeWriteType = when (writeType) {
            CharacteristicWriteType.WithResponse ->
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            CharacteristicWriteType.WithoutResponse ->
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val gate = gattCallback.operationGateFor(gatt, generation)
            ?: return CharacteristicWriteResult.Disconnected

        return suspendCancellableCoroutine { continuation ->
            val accepted = gate.trySubmitTyped(
                key = operationKey,
                label = "writeCharacteristic ${targetCharacteristic.uuid}",
                action = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(
                            targetCharacteristic,
                            payload,
                            nativeWriteType,
                        ) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        targetCharacteristic.writeType = nativeWriteType
                        @Suppress("DEPRECATION")
                        targetCharacteristic.value = payload
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(targetCharacteristic)
                    }
                },
                onComplete = { outcome ->
                    if (continuation.isActive) {
                        continuation.resume(outcome.toWriteResult())
                    }
                },
            )
            if (!accepted) {
                continuation.resume(
                    if (gate.isPoisoned) {
                        CharacteristicWriteResult.Disconnected
                    } else {
                        CharacteristicWriteResult.Backpressured
                    }
                )
            } else {
                continuation.invokeOnCancellation {
                    gate.abandon(operationKey)
                }
            }
        }
    }

    override suspend fun setNotificationSubscription(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        enabled: Boolean,
    ): NotificationSubscriptionResult {
        fun report(result: NotificationSubscriptionResult): NotificationSubscriptionResult {
            _notificationSubscriptionUpdates.tryEmit(
                NotificationSubscriptionUpdate(
                    peripheralUuid = peripheral.uuid,
                    characteristicUuid = characteristic.uuid,
                    result = result,
                )
            )
            return result
        }

        val androidPeripheral = peripheral as? AndroidBluetoothPeripheral
            ?: return report(
                NotificationSubscriptionResult.Failed(
                    IllegalArgumentException("Peripheral must be an AndroidBluetoothPeripheral")
                )
            )
        val requestedCharacteristic =
            (characteristic as? AndroidBluetoothCharacteristic)?.characteristic
                ?: return report(
                    NotificationSubscriptionResult.Failed(
                        IllegalArgumentException(
                            "Characteristic must be an AndroidBluetoothCharacteristic"
                        )
                    )
                )
        val gatt = gattCallback.activeGattForDevice(androidPeripheral.device)
            ?: return report(NotificationSubscriptionResult.Disconnected)
        val generation = gattCallback.generationFor(gatt)
            ?: return report(NotificationSubscriptionResult.Disconnected)
        val targetCharacteristic =
            fetchCharacteristic(requestedCharacteristic, gatt, exactNativeIdentity = true)
                .firstOrNull()
            ?: return report(
                NotificationSubscriptionResult.Failed(
                    IllegalArgumentException(
                        "Characteristic ${characteristic.uuid} is not part of the active GATT"
                    )
                )
            )
        if (enabled &&
            targetCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
        ) {
            return report(NotificationSubscriptionResult.Unsupported)
        }
        val cccd = targetCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            ?: return report(NotificationSubscriptionResult.Unsupported)
        val operationKey = CentralGattOperationKey(
            generation = generation,
            type = CentralGattOperationType.WriteDescriptor,
            identity = descriptorOperationIdentity(
                targetCharacteristic.service?.uuid?.toString(),
                targetCharacteristic.uuid.toString(),
                cccd.uuid.toString(),
            ),
        )
        val gate = gattCallback.operationGateFor(gatt, generation)
            ?: return report(NotificationSubscriptionResult.Disconnected)

        return suspendCancellableCoroutine { continuation ->
            val action = AndroidNotificationSubscriptionAction(
                enabled = enabled,
                setLocalNotification = {
                    gatt.setCharacteristicNotification(targetCharacteristic, it)
                },
                writeCccd = { value ->
                    val payload = value.copyOf()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(cccd, payload) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = payload
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                },
            )
            val accepted = gate.trySubmitTyped(
                key = operationKey,
                label = "setNotificationSubscription ${targetCharacteristic.uuid} enabled=$enabled",
                action = action::submit,
                onComplete = { outcome ->
                    if (continuation.isActive) {
                        continuation.resume(report(outcome.toSubscriptionResult(enabled)))
                    }
                },
            )
            if (!accepted) {
                continuation.resume(
                    report(
                        if (gate.isPoisoned) {
                            NotificationSubscriptionResult.Disconnected
                        } else {
                            NotificationSubscriptionResult.Failed(
                                IllegalStateException(
                                    "Another Android GATT operation is already in progress"
                                )
                            )
                        }
                    )
                )
            } else {
                continuation.invokeOnCancellation {
                    gate.abandon(operationKey)
                }
            }
        }
    }
    
    override suspend fun notifyCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        setCharacteristicNotification(
            peripheral,
            characteristic,
            notify,
            if (notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        )
    }
    
    override suspend fun indicateCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        setCharacteristicNotification(
            peripheral,
            characteristic,
            indicate,
            if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        )
    }
    
    override suspend fun readDescriptor(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        descriptor: BluetoothCharacteristicDescriptor
    ) {
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        val androidDesc = (descriptor as? AndroidBluetoothCharacteristicDescriptor)?.descriptor ?: return
        gattCallback.gattsForDevice(device).forEach { gatt ->
            gattCallback.enqueueOperation(
                gatt,
                CentralGattOperationType.ReadDescriptor,
                "readDescriptor ${androidDesc.uuid}",
                identity = descriptorOperationIdentity(
                    androidDesc.characteristic.service?.uuid?.toString(),
                    androidDesc.characteristic.uuid.toString(),
                    androidDesc.uuid.toString(),
                )
            ) {
                it.readDescriptor(androidDesc)
            }
        }
        logger?.debug("readDescriptor -> ${descriptor.uuid}")
    }

    override suspend fun writeDescriptor(
        peripheral: BluetoothPeripheral,
        descriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {
        logger?.debug("writeDescriptor ${descriptor.uuid} value: $value")
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        val androidDesc = (descriptor as? AndroidBluetoothCharacteristicDescriptor)?.descriptor ?: return

        // Snapshot the payload so a caller that reuses/mutates its array after this call cannot change
        // the bytes that get written when the queued operation is finally dispatched.
        val payload = value.copyOf()
        gattCallback.gattsForDevice(device).forEach { gatt ->
            gattCallback.enqueueOperation(
                gatt,
                CentralGattOperationType.WriteDescriptor,
                "writeDescriptor ${androidDesc.uuid}",
                identity = descriptorOperationIdentity(
                    androidDesc.characteristic.service?.uuid?.toString(),
                    androidDesc.characteristic.uuid.toString(),
                    androidDesc.uuid.toString(),
                )
            ) {
                androidDesc.value = payload
                it.writeDescriptor(androidDesc)
            }
        }
    }

    override suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int) {
        logger?.debug("changeMTU -> ${peripheral.uuid} mtuSize: $mtuSize")
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        gattCallback.gattsForDevice(device).forEach { gatt ->
            gattCallback.enqueueOperation(gatt, CentralGattOperationType.ChangeMtu, "requestMtu $mtuSize") {
                it.requestMtu(mtuSize)
            }
        }
    }
    
    override fun refreshGattCache(peripheral: BluetoothPeripheral): Boolean {
        logger?.debug("refreshGattCache for ${peripheral.uuid}")
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return false
        var result = false
        gattCallback.gattsForDevice(device).forEach { gatt ->
            try {
                val refreshMethod = gatt.javaClass.getMethod("refresh")
                val refreshed = refreshMethod.invoke(gatt) as Boolean
                logger?.debug("GATT cache refresh: $refreshed")
                result = result || refreshed
            } catch (e: Exception) {
                logger?.error("Failed to refresh GATT cache: ${e.message}", e)
            }
        }
        return result
    }
    
    override suspend fun openL2capChannel(
        peripheral: BluetoothPeripheral,
        psm: Int,
        secure: Boolean
    ): dev.bluefalcon.core.BluetoothSocket {
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device
            ?: throw L2capException("Peripheral must be an AndroidBluetoothPeripheral")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw L2capException("L2CAP channels require Android 10 (API 29) or higher")
        }
        return withContext(Dispatchers.IO) {
            try {
                val socket = if (secure) {
                    device.createL2capChannel(psm)
                } else {
                    device.createInsecureL2capChannel(psm)
                }
                socket.connect()
                logger?.info("L2CAP channel opened on PSM $psm (secure=$secure)")
                L2CapSocket(socket, psm, peripheral, scope)
            } catch (e: L2capException) {
                throw e
            } catch (e: Exception) {
                logger?.error("Failed to open L2Cap channel: ${e.message}")
                throw L2capException("Failed to open L2CAP channel on PSM $psm", e)
            }
        }
    }
    
    override suspend fun createBond(peripheral: BluetoothPeripheral) {
        logger?.debug("createBond ${peripheral.uuid}")
        ensureBondReceiverRegistered()
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        device.createBond()
    }
    
    override suspend fun removeBond(peripheral: BluetoothPeripheral) {
        logger?.debug("removeBond ${peripheral.uuid}")
        ensureBondReceiverRegistered()
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        try {
            device::class.java.getMethod("removeBond").invoke(device)
        } catch (e: NoSuchMethodException) {
            logger?.error("removeBond method not available on this device: ${e.message}")
        } catch (e: Exception) {
            logger?.error("Failed to remove bond: ${e.message}")
        }
    }
    
    private fun ensureBondReceiverRegistered() {
        if (!isBondReceiverRegistered) {
            context.registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            isBondReceiverRegistered = true
        }
    }
    
    private fun setCharacteristicNotification(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        enable: Boolean,
        descriptorValue: ByteArray?
    ) {
        logger?.debug("setCharacteristicNotification for ${characteristic.uuid} enable: $enable")
        val device = (peripheral as? AndroidBluetoothPeripheral)?.device ?: return
        val androidChar = (characteristic as? AndroidBluetoothCharacteristic)?.characteristic ?: return
        
        gattCallback.gattsForDevice(device).forEach { gatt ->
            fetchCharacteristic(androidChar, gatt).forEach { char ->
                // setCharacteristicNotification only toggles local delivery; it issues no GATT
                // transaction and produces no callback, so it is safe to apply immediately. The CCC
                // descriptor write is the actual GATT operation and must be serialized through the
                // queue so it cannot race service discovery (the root cause of the reconnect bug).
                gatt.setCharacteristicNotification(char, enable)
                descriptorValue?.let { rawValue ->
                    val payload = rawValue.copyOf()
                    char.descriptors.forEach { descriptor ->
                        gattCallback.enqueueOperation(
                            gatt,
                            CentralGattOperationType.WriteDescriptor,
                            "writeDescriptor(CCC) ${descriptor.uuid} enable=$enable",
                            identity = descriptorOperationIdentity(
                                char.service?.uuid?.toString(),
                                char.uuid.toString(),
                                descriptor.uuid.toString(),
                            )
                        ) {
                            descriptor.value = payload
                            it.writeDescriptor(descriptor)
                        }
                    }
                }
            }
        }
    }
    
    private fun fetchCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        gatt: BluetoothGatt,
        exactNativeIdentity: Boolean = false,
    ): List<BluetoothGattCharacteristic> {
        val serviceUuid = characteristic.service?.uuid ?: return emptyList()
        val resolved = gatt.getService(serviceUuid)?.getCharacteristic(characteristic.uuid)
            ?: return emptyList()
        val target = if (exactNativeIdentity) {
            exactNativeAttribute(characteristic, resolved)
        } else {
            resolved
        }
        return listOfNotNull(target)
    }
    
    fun destroy() {
        if (isBondReceiverRegistered) {
            context.unregisterReceiver(bondStateReceiver)
            isBondReceiverRegistered = false
        }
        BluetoothStateMonitor.unregister(context, this)
    }
    
    // Scan callback implementation
    private inner class BluetoothScanCallBack : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            addScanResult(result)
        }
        
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { addScanResult(it) }
        }
        
        override fun onScanFailed(errorCode: Int) {
            logger?.error("Failed to scan with code $errorCode")
        }
        
        private fun addScanResult(result: ScanResult?) {
            logger?.debug("addScanResult $result")
            result?.device?.let { device ->
                val advertisedServiceUUIDs = extractServiceUuids(result)
                if (!matchesActiveFilters(advertisedServiceUUIDs)) return
                val bluetoothPeripheral = AndroidBluetoothPeripheral(device)
                val newRssi = result.rssi.toFloat()
                bluetoothPeripheral.rssi = newRssi
                bluetoothPeripheral.manufacturerData = extractManufacturerData(result)
                bluetoothPeripheral.advertisedServiceUUIDs = advertisedServiceUUIDs
                bluetoothPeripheral.isConnectable = extractConnectable(result)
                val existing = _peripherals.value.find { it.uuid == bluetoothPeripheral.uuid }
                if (existing != null) {
                    (existing as? AndroidBluetoothPeripheral)?.rssi = newRssi
                    (existing as? AndroidBluetoothPeripheral)?.manufacturerData =
                        bluetoothPeripheral.manufacturerData
                    (existing as? AndroidBluetoothPeripheral)?.advertisedServiceUUIDs =
                        bluetoothPeripheral.advertisedServiceUUIDs
                    (existing as? AndroidBluetoothPeripheral)?.isConnectable =
                        bluetoothPeripheral.isConnectable
                    _rssiUpdates.tryEmit(bluetoothPeripheral.uuid to newRssi)
                } else {
                    _peripherals.value = _peripherals.value + setOf(bluetoothPeripheral)
                }
            }
        }

        /**
         * Matches [ServiceFilter]s in software against every service UUID we can observe in the
         * advertisement (service UUID list *and* service data), rather than relying on Android's
         * native `ScanFilter`, which only inspects the service UUID list AD structure. See
         * https://github.com/Reedyuk/blue-falcon/issues/222.
         */
        private fun matchesActiveFilters(advertisedServiceUUIDs: List<Uuid>): Boolean {
            val filters = activeScanFilters
            if (filters.isEmpty()) return true
            return filters.any { it.uuid in advertisedServiceUUIDs }
        }

        private fun extractServiceUuids(result: ScanResult): List<Uuid> {
            val scanRecord = result.scanRecord ?: return emptyList()
            val fromServiceUuidList = scanRecord.serviceUuids?.map { it.uuid.toString().toUuid() }
                ?: emptyList()
            val fromServiceData = scanRecord.serviceData?.keys?.map { it.uuid.toString().toUuid() }
                ?: emptyList()
            return (fromServiceUuidList + fromServiceData).distinct()
        }

        private fun extractConnectable(result: ScanResult): Boolean? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.isConnectable else null

        private fun extractManufacturerData(result: ScanResult): Map<Int, ByteArray> {
            val scanRecord = result.scanRecord ?: return emptyMap()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val sparse = scanRecord.manufacturerSpecificData ?: return emptyMap()
                (0 until sparse.size()).associate { i -> sparse.keyAt(i) to sparse.valueAt(i) }
            } else {
                @Suppress("DEPRECATION")
                val raw = scanRecord.bytes ?: return emptyMap()
                parseManufacturerDataLegacy(raw)
            }
        }

        private fun parseManufacturerDataLegacy(raw: ByteArray): Map<Int, ByteArray> {
            var index = 0
            while (index < raw.size) {
                val length = raw[index].toUByte().toInt()
                index += 1
                if (length == 0) break
                if (index + length > raw.size) break
                val type = raw[index].toUByte().toInt()
                if (type == 0xFF && length >= 3) {
                    val companyId = (raw[index + 1].toInt() and 0xFF) or
                            ((raw[index + 2].toInt() and 0xFF) shl 8)
                    val payload = raw.copyOfRange(index + 3, index + length)
                    return mapOf(companyId to payload)
                }
                index += length
            }
            return emptyMap()
        }
    }
    
    // GATT callback implementation
    private inner class GattClientCallback : BluetoothGattCallback() {
        internal val gatts: MutableList<BluetoothGatt> = CopyOnWriteArrayList()
        private val disconnectHandler = Handler(Looper.getMainLooper())
        private val operationHandler = Handler(Looper.getMainLooper())
        private val pendingTimeouts = java.util.concurrent.ConcurrentHashMap<String, Runnable>()
        private val operationGates =
            java.util.concurrent.ConcurrentHashMap<BluetoothGatt, CentralGattOperationGate>()
        private val gattGenerations =
            java.util.concurrent.ConcurrentHashMap<BluetoothGatt, Long>()

        // Guards every compound read-modify-write over [gatts]/[operationGates] and the "is this the
        // last gatt for the address?" reset decision. These run on three different threads — GATT
        // callbacks on a binder thread, the disconnect watchdog on the main thread, and operation
        // enqueues on the caller's thread — so check-then-act sequences must be serialized. Lock order
        // is always gattLock -> queue monitor; no path takes them the other way, so there is no
        // deadlock with [CentralGattOperationGate]'s per-instance synchronization.
        private val gattLock = Any()

        /**
         * Register a freshly issued connectGatt handle before it reaches STATE_CONNECTED, closing any
         * earlier handle for the same address first. This is the in-flight counterpart to [addGatt]
         * (which only runs once a connection is actually established): it guarantees that an orphaned
         * direct-connect — one that never establishes and therefore never produces a callback — is
         * still tracked, so the next connect()/disconnect() can close it. Without it those orphaned
         * initiations accumulate and wedge a single-connection peripheral.
         */
        fun trackConnecting(gatt: BluetoothGatt) = synchronized(gattLock) {
            val address = gatt.device.address
            gatts.filter { it.device.address == address && it !== gatt }
                .forEach { closeAndForget(it) }
            if (gatts.none { it === gatt }) {
                gatts.add(gatt)
            }
        }

        private fun addGatt(gatt: BluetoothGatt) = synchronized(gattLock) {
            // Replace any stale same-address gatt from a previous connection. On a fast reconnect the
            // new connection's STATE_CONNECTED can arrive before the old gatt's STATE_DISCONNECTED (or
            // its force-close timeout); without this, the new gatt would be dropped and every later op
            // would target the dead one. connectGatt always returns a fresh instance, so an existing
            // entry with the same address but different identity is always stale.
            val existing = gatts.firstOrNull { it.device.address == gatt.device.address }
            if (existing != null && existing !== gatt) {
                closeAndForget(existing)
            }
            if (gatts.none { it === gatt }) {
                gatts.add(gatt)
            }
            gattGenerations.computeIfAbsent(gatt) {
                centralWriteState.onConnected(gatt.device.address)
            }
        }

        /**
         * Removes, closes and forgets [gatt], clearing its operation queue and — only when no newer
         * gatt for the same address remains tracked — the reused peripheral's stale connection state.
         * Idempotent, so it is safe if both STATE_DISCONNECTED and the force-close watchdog fire.
         */
        private fun closeAndForget(gatt: BluetoothGatt) = synchronized(gattLock) {
            cancelDisconnectTimeout(gatt.device.address)
            gatts.remove(gatt)
            val generation = gattGenerations.remove(gatt)
            operationGates.remove(gatt)?.disconnect()
            if (generation != null) {
                centralWriteState.onDisconnected(gatt.device.address, generation)
            }
            try {
                gatt.close()
            } catch (e: Exception) {
                logger?.error("Error closing gatt for ${gatt.device.address}: ${e.message}")
            }
            if (gattsForDevice(gatt.device).isEmpty()) {
                resetPeripheralState(gatt.device.address)
            }
        }

        fun gattsForDevice(device: BluetoothDevice): List<BluetoothGatt> =
            gatts.filter { it.device.address == device.address }

        fun activeGattForDevice(device: BluetoothDevice): BluetoothGatt? =
            gattsForDevice(device).firstOrNull { gattGenerations.containsKey(it) }

        fun generationFor(gatt: BluetoothGatt): Long? = gattGenerations[gatt]

        fun operationGateFor(
            gatt: BluetoothGatt,
            generation: Long,
        ): CentralGattOperationGate? = synchronized(gattLock) {
            if (gatts.none { it === gatt } || gattGenerations[gatt] != generation) {
                return@synchronized null
            }
            operationGates.computeIfAbsent(gatt) {
                createOperationGate(gatt, generation)
            }
        }

        private fun createOperationGate(
            gatt: BluetoothGatt,
            generation: Long,
        ): CentralGattOperationGate =
            CentralGattOperationGate(
                timeoutMillis = GATT_OPERATION_TIMEOUT_MS,
                timeoutScheduler = CentralGattTimeoutScheduler { delayMillis, onTimeout ->
                    val timeout = Runnable(onTimeout)
                    operationHandler.postDelayed(timeout, delayMillis)
                    CentralGattTimeoutHandle {
                        operationHandler.removeCallbacks(timeout)
                    }
                },
                onBusy = {
                    centralWriteState.onBusy(gatt.device.address, generation)
                },
                onReady = {
                    centralWriteState.onReady(gatt.device.address, generation)
                },
                onPoisoned = {
                    logger?.warn(
                        "GATT operation timeout for ${gatt.device.address}; " +
                            "disconnecting the poisoned connection"
                    )
                    centralWriteState.onDisconnected(gatt.device.address, generation)
                    try {
                        gatt.disconnect()
                    } catch (failure: Throwable) {
                        logger?.error(
                            "Failed to disconnect poisoned GATT for " +
                                "${gatt.device.address}: ${failure.message}",
                            failure,
                        )
                    } finally {
                        scheduleDisconnectTimeout(gatt)
                    }
                },
            )

        /**
         * Append a GATT operation to this gatt's serialized FIFO queue. Android allows only one GATT
         * operation in flight per connection; a second issued before the first's callback returns is
         * silently dropped. The queue dispatches one at a time and advances from the matching callback
         * so descriptor writes cannot race service discovery / MTU / each other. A timeout poisons and
         * disconnects the connection because Android provides no operation token that could distinguish
         * its late callback from a retry.
         */
        fun enqueueOperation(
            gatt: BluetoothGatt,
            type: CentralGattOperationType,
            label: String,
            identity: String? = null,
            action: (BluetoothGatt) -> Boolean
        ) {
            synchronized(gattLock) {
                // Don't (re)create a queue for a gatt that has already been forgotten; that would both
                // leak the queue and dispatch onto a dead connection.
                if (gatts.none { it === gatt }) {
                    logger?.debug("Skipping GATT op '$label' — gatt for ${gatt.device.address} is no longer tracked")
                    return
                }
                val generation = gattGenerations[gatt] ?: return
                operationGateFor(gatt, generation)?.enqueueLegacy(
                    key = CentralGattOperationKey(generation, type, identity),
                    label = label,
                ) {
                    action(gatt)
                }
            }
        }

        private fun completeOperation(
            gatt: BluetoothGatt,
            type: CentralGattOperationType,
            identity: String? = null,
            status: Int,
        ) {
            val generation = gattGenerations[gatt] ?: return
            operationGates[gatt]?.complete(
                key = CentralGattOperationKey(generation, type, identity),
                status = status,
                successful = status == BluetoothGatt.GATT_SUCCESS,
            )
        }

        fun scheduleDisconnectTimeout(gatt: BluetoothGatt) {
            val address = gatt.device.address
            cancelDisconnectTimeout(address)
            val timeoutRunnable = Runnable {
                pendingTimeouts.remove(address)
                synchronized(gattLock) {
                    if (gatts.contains(gatt)) {
                        logger?.warn("Disconnect timeout for $address — forcing close")
                        closeAndForget(gatt)
                    }
                }
            }
            pendingTimeouts[address] = timeoutRunnable
            disconnectHandler.postDelayed(timeoutRunnable, DISCONNECT_TIMEOUT_MS)
        }

        private fun cancelDisconnectTimeout(address: String) {
            pendingTimeouts.remove(address)?.let { disconnectHandler.removeCallbacks(it) }
        }

        fun disconnectAllOnAdapterOff() = synchronized(gattLock) {
            pendingTimeouts.keys.toList().forEach { cancelDisconnectTimeout(it) }
            gatts.toList().forEach { gatt ->
                logger?.info("Adapter off - forcing disconnect for ${gatt.device.address}")
                closeAndForget(gatt)
            }
        }
        
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            logger?.debug("onConnectionStateChange status: $status newState: $newState")
            gatt?.device?.let { device ->
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    logger?.info("Connected to ${device.address}")
                    addGatt(gatt)
                    peripheralFor(device.address)?.let { peripheral ->
                        _connectionStateUpdates.tryEmit(
                            ConnectionStateUpdate(peripheral, BluetoothPeripheralState.Connected)
                        )
                    }
                    if (autoDiscoverAllServicesAndCharacteristics) {
                        // Serialize the post-connect service discovery and RSSI read; issued back to
                        // back without a queue, the second would race the first and be dropped.
                        // Discovery is enqueued first because it is the critical path consumers gate
                        // subscription work on — the best-effort RSSI read must not sit ahead of it,
                        // or a dropped RSSI callback would stall discovery for the full op watchdog.
                        enqueueOperation(gatt, CentralGattOperationType.DiscoverServices, "discoverServices") {
                            it.discoverServices()
                        }
                        enqueueOperation(gatt, CentralGattOperationType.ReadRssi, "readRemoteRssi") {
                            it.readRemoteRssi()
                        }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    logger?.info("Disconnected from ${device.address}")
                    peripheralFor(device.address)?.let { peripheral ->
                        _connectionStateUpdates.tryEmit(
                            ConnectionStateUpdate(peripheral, BluetoothPeripheralState.Disconnected)
                        )
                    }
                    // closeAndForget removes/closes the gatt, clears its queue, and resets the reused
                    // peripheral's transient state so a later reconnect waits for the new connection's
                    // discovery/MTU instead of reading stale values — but only if no newer gatt for this
                    // address is already tracked (reconnect race).
                    closeAndForget(gatt)
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            logger?.info("onServicesDiscovered status=$status")
            // Advance the queue regardless of status so a failed discovery does not stall it.
            gatt?.let {
                completeOperation(
                    it,
                    CentralGattOperationType.DiscoverServices,
                    status = status,
                )
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger?.error("Service discovery failed with status $status")
                return
            }
            gatt?.device?.let { device ->
                val peripheral = peripheralFor(device.address) ?: return@let
                val services = gatt.services.map { AndroidBluetoothService(it) }
                peripheral._servicesFlow.value = services
                // Android discovers services and characteristics atomically — emit both phases.
                _serviceDiscoveryUpdates.tryEmit(
                    ServiceDiscoveryUpdate(peripheral, ServiceDiscoveryPhase.ServicesDiscovered)
                )
                services.forEach { service ->
                    _serviceDiscoveryUpdates.tryEmit(
                        ServiceDiscoveryUpdate(peripheral, ServiceDiscoveryPhase.CharacteristicsDiscovered, service)
                    )
                }
            }
        }
        
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            logger?.debug("onMtuChanged mtu=$mtu status=$status")
            gatt?.device?.let { device ->
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    peripheralFor(device.address)?.mtuSize = mtu
                }
                gattGenerations[gatt]?.let { generation ->
                    centralWriteState.onMtuChanged(
                        peripheralUuid = device.address,
                        generation = generation,
                        mtu = mtu,
                        successful = status == BluetoothGatt.GATT_SUCCESS,
                    )
                }
            }
            gatt?.let {
                completeOperation(
                    it,
                    CentralGattOperationType.ChangeMtu,
                    status = status,
                )
            }
        }
        
        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            logger?.debug("onReadRemoteRssi $rssi")
            gatt?.let {
                completeOperation(
                    it,
                    CentralGattOperationType.ReadRssi,
                    status = status,
                )
            }
            gatt?.device?.let { device ->
                peripheralFor(device.address)?.rssi = rssi.toFloat()
            }
        }
        
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            logger?.debug("onCharacteristicRead ${characteristic?.uuid} status=$status")
            gatt?.let {
                completeOperation(
                    it,
                    CentralGattOperationType.ReadCharacteristic,
                    characteristicOperationIdentity(
                        characteristic?.service?.uuid?.toString(),
                        characteristic?.uuid?.toString(),
                    ),
                    status,
                )
            }
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            logger?.debug("onCharacteristicChanged ${characteristic?.uuid}")
            if (gatt == null || characteristic == null) return

            val value = characteristic.value?.copyOf() ?: return
            val peripheral = peripheralFor(gatt.device.address) ?: AndroidBluetoothPeripheral(gatt.device)
            val bluetoothCharacteristic = AndroidBluetoothCharacteristic(characteristic)
            bluetoothCharacteristic.emitNotification(value)
            _characteristicNotifications.tryEmit(
                CharacteristicNotification(
                    peripheral = peripheral,
                    characteristic = bluetoothCharacteristic,
                    value = value
                )
            )
        }
        
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            logger?.debug("onCharacteristicWrite ${characteristic?.uuid} status=$status")
            gatt?.let {
                completeOperation(
                    it,
                    CentralGattOperationType.WriteCharacteristic,
                    characteristicOperationIdentity(
                        characteristic?.service?.uuid?.toString(),
                        characteristic?.uuid?.toString(),
                    ),
                    status,
                )
            }
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            logger?.debug("onDescriptorRead ${descriptor?.uuid}")
            gatt?.let {
                completeOperation(
                    it,
                    CentralGattOperationType.ReadDescriptor,
                    descriptorOperationIdentity(
                        descriptor?.characteristic?.service?.uuid?.toString(),
                        descriptor?.characteristic?.uuid?.toString(),
                        descriptor?.uuid?.toString(),
                    ),
                    status,
                )
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            logger?.debug("onDescriptorWrite ${descriptor?.uuid} status=$status")
            gatt?.let {
                completeOperation(
                    it,
                    CentralGattOperationType.WriteDescriptor,
                    descriptorOperationIdentity(
                        descriptor?.characteristic?.service?.uuid?.toString(),
                        descriptor?.characteristic?.uuid?.toString(),
                        descriptor?.uuid?.toString(),
                    ),
                    status,
                )
            }
        }

    }

    companion object {
        private const val DISCONNECT_TIMEOUT_MS = 5_000L
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID =
            java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Watchdog timeout for a single in-flight GATT operation. If its callback never arrives (the
         * stack occasionally drops one), the connection is quarantined and disconnected after this
         * delay. Comfortably longer than any normal read/write/discover/MTU exchange.
         */
        private const val GATT_OPERATION_TIMEOUT_MS = 10_000L
    }
}

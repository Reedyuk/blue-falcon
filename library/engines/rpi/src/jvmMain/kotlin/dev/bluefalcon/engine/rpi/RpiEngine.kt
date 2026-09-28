package dev.bluefalcon.engine.rpi

import com.welie.blessed.*
import com.welie.blessed.BluetoothPeripheral as BlessedPeripheral
import com.welie.blessed.bluez.DbusHelper
import dev.bluefalcon.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder

/**
 * Raspberry Pi implementation of BlueFalconEngine using the Blessed library
 */
class RpiEngine : BlueFalconEngine {
    override val scope = CoroutineScope(Dispatchers.Default)
    
    private val _peripherals = MutableStateFlow<Set<dev.bluefalcon.core.BluetoothPeripheral>>(emptySet())
    override val peripherals: StateFlow<Set<dev.bluefalcon.core.BluetoothPeripheral>> = _peripherals.asStateFlow()
    
    private val _managerState = MutableStateFlow(BluetoothManagerState.Ready)
    override val managerState: StateFlow<BluetoothManagerState> = _managerState.asStateFlow()

    private val _characteristicNotifications = MutableSharedFlow<CharacteristicNotification>(extraBufferCapacity = 64)
    override val characteristicNotifications: SharedFlow<CharacteristicNotification> = _characteristicNotifications

    private val _serviceDiscoveryUpdates = MutableSharedFlow<ServiceDiscoveryUpdate>(extraBufferCapacity = 64)
    override val serviceDiscoveryUpdates: SharedFlow<ServiceDiscoveryUpdate> = _serviceDiscoveryUpdates
    
    override var isScanning: Boolean = false
        private set
    
    private val peripheralMap = mutableMapOf<String, RpiBluetoothPeripheral>()
    private val peripheralCallbacks = mutableMapOf<String, BluetoothPeripheralCallback>()

    // ADR 0014: BluetoothPeripheral.readCharacteristic() from the Blessed library only returns
    // whether the read request was successfully *queued* - the actual value/failure is delivered
    // later, asynchronously, to BluetoothPeripheralCallback.onCharacteristicUpdate(). Track pending
    // reads here, keyed by peripheral address + characteristic UUID, so readCharacteristic() can
    // suspend until that callback actually resolves this specific request.
    private val pendingReads = mutableMapOf<String, CompletableDeferred<ByteArray>>()

    private fun pendingReadKey(peripheralAddress: String, characteristicUuid: String) =
        "$peripheralAddress::$characteristicUuid"

    // Blessed reports a write result only through onCharacteristicWrite, so each write is recorded
    // here before it is queued and matched to its callback (see RpiPendingWrites).
    private val pendingWrites = RpiPendingWrites()

    override val centralCapabilities: CentralCapabilities =
        CentralCapabilities.None.copy(reliableWriteResults = true)
    
    private val bluetoothManagerCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscoveredPeripheral(
            peripheral: BlessedPeripheral,
            scanResult: ScanResult
        ) {
            val address = peripheral.address
            val device = peripheralMap.getOrPut(address) {
                RpiBluetoothPeripheral(peripheral)
            }
            
            device.rssi = scanResult.rssi.toFloat()
            device.manufacturerData = scanResult.manufacturerData ?: emptyMap()
            _peripherals.value = _peripherals.value + device
        }

        override fun onDisconnectedPeripheral(peripheral: BlessedPeripheral, status: BluetoothCommandStatus) {
            // Blessed skips a queued write with no callback when the link is down, so no pending
            // write of this peripheral can complete any more.
            pendingWrites.disconnected(peripheral.address)
        }
    }
    
    private val bluetoothManager: BluetoothCentralManager = run {
        // blessed-bluez 0.64 sorts adapters by getDeviceName() (the last path component) ascending
        // and returns the last one. On systems with /org/bluez/test, "test" > "hci0" so the wrong
        // adapter is chosen. We bypass this by creating the connection ourselves, initialising the
        // BluezSignalHandler singleton (normally done by package-private BluezAdapterProvider), and
        // then calling the package-private BluetoothCentralManager constructor with the correct adapter.
        val connection = DBusConnectionBuilder.forSystemBus().build()

        // Initialise the BluezSignalHandler singleton that the CentralManager requires.
        val signalHandlerClass = Class.forName("com.welie.blessed.BluezSignalHandler")
        val createInstanceMethod = signalHandlerClass.getDeclaredMethod(
            "createInstance",
            org.freedesktop.dbus.connections.impl.DBusConnection::class.java
        )
        createInstanceMethod.isAccessible = true
        createInstanceMethod.invoke(null, connection)

        val hciAdapter = DbusHelper.findBluezAdapters(connection)
            .filter { Regex("/hci\\d+$").containsMatchIn(it.dbusPath) }
            .maxByOrNull { it.dbusPath }
            ?: throw IllegalStateException("No Bluetooth HCI adapter found at /org/bluez/hciX")

        val ctor = BluetoothCentralManager::class.java.declaredConstructors
            .first { it.parameterCount == 3 }
        ctor.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        ctor.newInstance(bluetoothManagerCallback, emptySet<String>(), hciAdapter) as BluetoothCentralManager
    }
    
    override suspend fun scan(filters: List<ServiceFilter>) {
        isScanning = true
        if (filters.isNotEmpty()) {
            val uuids = filters.map { java.util.UUID.fromString(it.uuid.toString()) }.toTypedArray()
            bluetoothManager.scanForPeripheralsWithServices(uuids)
        } else {
            bluetoothManager.scanForPeripherals()
        }
    }
    
    override suspend fun stopScanning() {
        isScanning = false
        bluetoothManager.stopScan()
    }
    
    override fun clearPeripherals() {
        _peripherals.value = emptySet()
        peripheralMap.clear()
    }
    
    override suspend fun connect(peripheral: dev.bluefalcon.core.BluetoothPeripheral, autoConnect: Boolean) {
        val rpiPeripheral = peripheral as? RpiBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an RpiBluetoothPeripheral")
        
        val callback = createPeripheralCallback(rpiPeripheral)
        peripheralCallbacks[peripheral.uuid] = callback
        bluetoothManager.connectPeripheral(rpiPeripheral.nativePeripheral, callback)
    }
    
    override suspend fun disconnect(peripheral: dev.bluefalcon.core.BluetoothPeripheral) {
        val rpiPeripheral = peripheral as? RpiBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an RpiBluetoothPeripheral")
        
        bluetoothManager.cancelConnection(rpiPeripheral.nativePeripheral)
        peripheralCallbacks.remove(peripheral.uuid)
    }
    
    override fun connectionState(peripheral: dev.bluefalcon.core.BluetoothPeripheral): BluetoothPeripheralState {
        val rpiPeripheral = peripheral as? RpiBluetoothPeripheral
            ?: return BluetoothPeripheralState.Unknown
        
        return when (rpiPeripheral.nativePeripheral.state) {
            ConnectionState.CONNECTED -> BluetoothPeripheralState.Connected
            ConnectionState.CONNECTING -> BluetoothPeripheralState.Connecting
            ConnectionState.DISCONNECTED -> BluetoothPeripheralState.Disconnected
            ConnectionState.DISCONNECTING -> BluetoothPeripheralState.Disconnecting
            else -> BluetoothPeripheralState.Unknown
        }
    }
    
    override fun retrievePeripheral(identifier: String): dev.bluefalcon.core.BluetoothPeripheral? {
        return peripheralMap[identifier]
    }
    
    override fun requestConnectionPriority(peripheral: dev.bluefalcon.core.BluetoothPeripheral, priority: ConnectionPriority) {
        // No-op on RPi
    }
    
    override suspend fun discoverServices(peripheral: dev.bluefalcon.core.BluetoothPeripheral, serviceUUIDs: List<Uuid>) {
        // Services are auto-discovered by Blessed on connection
    }
    
    override suspend fun discoverCharacteristics(
        peripheral: dev.bluefalcon.core.BluetoothPeripheral,
        service: dev.bluefalcon.core.BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {
        // Characteristics are auto-discovered by Blessed
    }
    
    override suspend fun readCharacteristic(
        peripheral: dev.bluefalcon.core.BluetoothPeripheral,
        characteristic: dev.bluefalcon.core.BluetoothCharacteristic
    ): ByteArray? {
        val rpiPeripheral = peripheral as? RpiBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an RpiBluetoothPeripheral")
        val rpiCharacteristic = characteristic as? RpiBluetoothCharacteristic
            ?: throw IllegalArgumentException("Characteristic must be an RpiBluetoothCharacteristic")

        val key = pendingReadKey(rpiPeripheral.nativePeripheral.address, rpiCharacteristic.nativeCharacteristic.uuid.toString())
        val deferred = CompletableDeferred<ByteArray>()
        pendingReads[key] = deferred
        try {
            val queued = rpiPeripheral.nativePeripheral.readCharacteristic(rpiCharacteristic.nativeCharacteristic)
            if (!queued) {
                throw BluetoothUnknownException("Failed to queue characteristic read")
            }
            return withTimeout(READ_TIMEOUT_MS) { deferred.await() }
        } catch (timeout: TimeoutCancellationException) {
            throw BluetoothUnknownException("Timed out waiting for characteristic read to complete")
        } finally {
            pendingReads.remove(key)
        }
    }
    
    override suspend fun writeCharacteristic(
        peripheral: dev.bluefalcon.core.BluetoothPeripheral,
        characteristic: dev.bluefalcon.core.BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
        writeCharacteristic(peripheral, characteristic, value.toByteArray(), writeType)
    }
    
    override suspend fun writeCharacteristic(
        peripheral: dev.bluefalcon.core.BluetoothPeripheral,
        characteristic: dev.bluefalcon.core.BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        val rpiPeripheral = peripheral as? RpiBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an RpiBluetoothPeripheral")
        val rpiCharacteristic = characteristic as? RpiBluetoothCharacteristic
            ?: throw IllegalArgumentException("Characteristic must be an RpiBluetoothCharacteristic")
        
        val nativeCharacteristic = rpiCharacteristic.nativeCharacteristic
        val blessedWriteType = resolveWriteType(
            writeType = writeType,
            supportsWithResponse = nativeCharacteristic.supportsWritingWithResponse(),
            supportsWithoutResponse = nativeCharacteristic.supportsWritingWithoutResponse(),
        )

        // Blessed returns false when it refuses the write (not connected, empty value, or a write
        // type the characteristic does not support). Report that, as readCharacteristic does,
        // instead of dropping the write with no signal.
        //
        // This write is recorded although nobody waits for its result, so its callback cannot
        // complete a typed write to the same characteristic.
        val nativePeripheral = rpiPeripheral.nativePeripheral
        pendingWrites.enqueue(nativePeripheral.address, nativeCharacteristic.writeKey()) {
            nativePeripheral.writeCharacteristic(nativeCharacteristic, value, blessedWriteType)
        } ?: throw BluetoothUnknownException("Failed to queue characteristic write ($blessedWriteType)")
    }

    /**
     * Writes [value] and suspends until Blessed reports the result in `onCharacteristicWrite`.
     *
     * For [CharacteristicWriteType.WithResponse] the result is the peripheral's ATT response, so an
     * error such as "write not permitted" returns [CharacteristicWriteResult.Failed]. For
     * [CharacteristicWriteType.WithoutResponse] BlueZ reports only that it sent the write.
     *
     * A write that gets no result within [WRITE_TIMEOUT_MS] returns [CharacteristicWriteResult.Failed].
     * BlueZ gives no signal for when a write without response can be sent, and Blessed does not
     * expose the MTU, so [characteristicWriteCapabilities] stays empty.
     */
    override suspend fun writeCharacteristic(
        peripheral: dev.bluefalcon.core.BluetoothPeripheral,
        characteristic: dev.bluefalcon.core.BluetoothCharacteristic,
        value: ByteArray,
        writeType: CharacteristicWriteType,
    ): CharacteristicWriteResult {
        val rpiPeripheral = peripheral as? RpiBluetoothPeripheral
            ?: return CharacteristicWriteResult.Failed(
                IllegalArgumentException("Peripheral must be an RpiBluetoothPeripheral")
            )
        val rpiCharacteristic = characteristic as? RpiBluetoothCharacteristic
            ?: return CharacteristicWriteResult.Failed(
                IllegalArgumentException("Characteristic must be an RpiBluetoothCharacteristic")
            )
        val nativePeripheral = rpiPeripheral.nativePeripheral
        val nativeCharacteristic = rpiCharacteristic.nativeCharacteristic
        if (nativePeripheral.state != ConnectionState.CONNECTED) {
            return CharacteristicWriteResult.Disconnected
        }
        val blessedWriteType = when (writeType) {
            CharacteristicWriteType.WithResponse -> BluetoothGattCharacteristic.WriteType.WITH_RESPONSE
            CharacteristicWriteType.WithoutResponse -> BluetoothGattCharacteristic.WriteType.WITHOUT_RESPONSE
        }
        if (!nativeCharacteristic.supportsWriteType(blessedWriteType)) {
            return CharacteristicWriteResult.Unsupported
        }
        if (value.isEmpty()) {
            // Blessed refuses an empty value.
            return CharacteristicWriteResult.Failed(IllegalArgumentException("Blessed cannot write an empty value"))
        }

        val pending = pendingWrites.enqueue(nativePeripheral.address, nativeCharacteristic.writeKey()) {
            nativePeripheral.writeCharacteristic(nativeCharacteristic, value, blessedWriteType)
        }
        if (pending == null) {
            return if (nativePeripheral.state != ConnectionState.CONNECTED) {
                CharacteristicWriteResult.Disconnected
            } else {
                CharacteristicWriteResult.Failed(
                    BluetoothUnknownException("Failed to queue characteristic write ($blessedWriteType)")
                )
            }
        }
        // On a timeout or a cancellation the entry stays registered, so that the late callback
        // completes this entry and not the entry of a later write (see RpiPendingWrites).
        val outcome = withTimeoutOrNull(WRITE_TIMEOUT_MS) { pending.outcome.await() }
            ?: return CharacteristicWriteResult.Failed(
                BluetoothUnknownException("Timed out waiting for characteristic write to complete")
            )
        return outcome.toWriteResult()
    }

    private fun BluetoothGattCharacteristic.writeKey(): String =
        RpiPendingWrites.characteristicKey(service?.uuid, uuid)
    
    override suspend fun notifyCharacteristic(
        peripheral: dev.bluefalcon.core.BluetoothPeripheral,
        characteristic: dev.bluefalcon.core.BluetoothCharacteristic,
        notify: Boolean
    ) {
        val rpiPeripheral = peripheral as? RpiBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an RpiBluetoothPeripheral")
        val rpiCharacteristic = characteristic as? RpiBluetoothCharacteristic
            ?: throw IllegalArgumentException("Characteristic must be an RpiBluetoothCharacteristic")
        
        rpiPeripheral.nativePeripheral.setNotify(rpiCharacteristic.nativeCharacteristic, notify)
    }
    
    override suspend fun indicateCharacteristic(
        peripheral: dev.bluefalcon.core.BluetoothPeripheral,
        characteristic: dev.bluefalcon.core.BluetoothCharacteristic,
        indicate: Boolean
    ) {
        // Blessed library handles this through setNotify
        notifyCharacteristic(peripheral, characteristic, indicate)
    }
    
    override suspend fun readDescriptor(
        peripheral: dev.bluefalcon.core.BluetoothPeripheral,
        characteristic: dev.bluefalcon.core.BluetoothCharacteristic,
        descriptor: dev.bluefalcon.core.BluetoothCharacteristicDescriptor
    ) {
        // Not implemented in original RPi code
        throw UnsupportedOperationException("readDescriptor is not supported on RPi")
    }
    
    override suspend fun writeDescriptor(
        peripheral: dev.bluefalcon.core.BluetoothPeripheral,
        descriptor: dev.bluefalcon.core.BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {
        // Not implemented in original RPi code
        throw UnsupportedOperationException("writeDescriptor is not supported on RPi")
    }
    
    override suspend fun changeMTU(peripheral: dev.bluefalcon.core.BluetoothPeripheral, mtuSize: Int) {
        // Not implemented in original RPi code
        throw UnsupportedOperationException("changeMTU is not supported on RPi")
    }
    
    override fun refreshGattCache(peripheral: dev.bluefalcon.core.BluetoothPeripheral): Boolean {
        return false
    }
    
    override suspend fun openL2capChannel(
        peripheral: dev.bluefalcon.core.BluetoothPeripheral,
        psm: Int,
        secure: Boolean
    ): BluetoothSocket {
        val rpiPeripheral = peripheral as? RpiBluetoothPeripheral
            ?: throw L2capException("Peripheral must be an RpiBluetoothPeripheral")

        val address = rpiPeripheral.nativePeripheral.address
        // BlueZ Device1.AddressType — "public" or "random". Default to public if
        // blessed can't surface it (e.g. device object not yet resolved).
        val addressType = runCatching {
            rpiPeripheral.nativePeripheral.device?.addressType
        }.getOrNull() ?: "public"

        return withContext(Dispatchers.IO) {
            try {
                val fd = LinuxL2cap.connect(address, addressType, psm, secure)
                RpiL2CapSocket(fd, psm, rpiPeripheral, scope)
            } catch (e: L2capException) {
                throw e
            } catch (e: Exception) {
                throw L2capException("Failed to open L2CAP channel on PSM $psm", e)
            }
        }
    }
    
    override suspend fun createBond(peripheral: dev.bluefalcon.core.BluetoothPeripheral) {
        val rpiPeripheral = peripheral as? RpiBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an RpiBluetoothPeripheral")
        
        val callback = peripheralCallbacks[peripheral.uuid] 
            ?: throw IllegalStateException("Peripheral must be connected before creating bond")
        
        rpiPeripheral.nativePeripheral.createBond(callback)
    }
    
    override suspend fun removeBond(peripheral: dev.bluefalcon.core.BluetoothPeripheral) {
        throw UnsupportedOperationException("removeBond is not supported on RPi")
    }
    
    private fun createPeripheralCallback(peripheral: RpiBluetoothPeripheral): BluetoothPeripheralCallback {
        return object : BluetoothPeripheralCallback() {
            override fun onServicesDiscovered(
                nativePeripheral: BlessedPeripheral,
                services: MutableList<BluetoothGattService>
            ) {
                val updatedServices = services.map { RpiBluetoothService(it) }
                peripheral.updateServices(updatedServices)
                // Blessed populates services and characteristics atomically — emit both phases.
                _serviceDiscoveryUpdates.tryEmit(
                    ServiceDiscoveryUpdate(peripheral, ServiceDiscoveryPhase.ServicesDiscovered)
                )
                updatedServices.forEach { service ->
                    _serviceDiscoveryUpdates.tryEmit(
                        ServiceDiscoveryUpdate(peripheral, ServiceDiscoveryPhase.CharacteristicsDiscovered, service)
                    )
                }
            }
            
            override fun onCharacteristicUpdate(
                nativePeripheral: BlessedPeripheral,
                value: ByteArray,
                characteristic: BluetoothGattCharacteristic,
                status: BluetoothCommandStatus
            ) {
                peripheral.updateCharacteristicValue(characteristic.uuid.toString(), value)

                // Resolve any solicited read awaiting this exact characteristic (ADR 0014).
                // Blessed funnels both solicited reads and unsolicited notifications through this
                // same callback, so a pending read is completed opportunistically here without
                // otherwise disturbing the notification emission below.
                pendingReads.remove(
                    pendingReadKey(nativePeripheral.address, characteristic.uuid.toString())
                )?.let { deferred ->
                    if (status == BluetoothCommandStatus.COMMAND_SUCCESS) {
                        deferred.complete(value)
                    } else {
                        deferred.completeExceptionally(
                            BluetoothUnknownException("Characteristic read failed with status $status")
                        )
                    }
                }

                peripheral.characteristics
                    .filterIsInstance<RpiBluetoothCharacteristic>()
                    .firstOrNull { it.uuid.toString() == characteristic.uuid.toString() }
                    ?.let { bluetoothCharacteristic ->
                        bluetoothCharacteristic.emitNotification(value)
                        _characteristicNotifications.tryEmit(
                            CharacteristicNotification(
                                peripheral = peripheral,
                                characteristic = bluetoothCharacteristic,
                                value = value.copyOf()
                            )
                        )
                    }
            }
            
            override fun onCharacteristicWrite(
                nativePeripheral: BlessedPeripheral,
                value: ByteArray,
                characteristic: BluetoothGattCharacteristic,
                status: BluetoothCommandStatus
            ) {
                pendingWrites.complete(nativePeripheral.address, characteristic.writeKey(), status)
                peripheral.updateCharacteristicValue(characteristic.uuid.toString(), value)
            }
        }
    }

    companion object {
        private const val READ_TIMEOUT_MS = 10_000L
        private const val WRITE_TIMEOUT_MS = 10_000L

        /** Android's `BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE`, as the other engines read it. */
        private const val WRITE_TYPE_NO_RESPONSE = 1

        /**
         * Maps the legacy `Int?` write type to a Blessed write type, with the meaning that the Android,
         * Windows, Apple and macOS engines give it: 1 (`WRITE_TYPE_NO_RESPONSE`) is a write without
         * response, and any other value is a write with response. 0 stays a write with response, as
         * this engine always read it.
         *
         * A null write type uses the characteristic's own write type: a write with response when the
         * characteristic supports one, and a write without response when that is the only write it
         * supports. Before this mapping, null and 2 (`WRITE_TYPE_DEFAULT`) asked for a write without
         * response on every characteristic. On a characteristic that supports only a write with
         * response, Blessed refused that write and the engine dropped it with no error. On one that
         * supports both, the write went out with no reply, so an ATT error was lost.
         */
        internal fun resolveWriteType(
            writeType: Int?,
            supportsWithResponse: Boolean,
            supportsWithoutResponse: Boolean,
        ): BluetoothGattCharacteristic.WriteType = when {
            writeType == WRITE_TYPE_NO_RESPONSE -> BluetoothGattCharacteristic.WriteType.WITHOUT_RESPONSE
            writeType != null -> BluetoothGattCharacteristic.WriteType.WITH_RESPONSE
            !supportsWithResponse && supportsWithoutResponse ->
                BluetoothGattCharacteristic.WriteType.WITHOUT_RESPONSE
            else -> BluetoothGattCharacteristic.WriteType.WITH_RESPONSE
        }
    }
}

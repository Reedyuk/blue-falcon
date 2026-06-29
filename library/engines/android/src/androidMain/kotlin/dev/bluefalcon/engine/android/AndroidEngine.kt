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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

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
    
    override var isScanning: Boolean = false
        private set
    
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
                
                logger?.debug("Bond state changed for ${device.address}")
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
        
        val scanFilters: List<ScanFilter> = if (filters.isEmpty()) {
            listOf(ScanFilter.Builder().build())
        } else {
            filters.map { filter ->
                val filterBuilder = ScanFilter.Builder()
                val parcelUuid = android.os.ParcelUuid(java.util.UUID.fromString(filter.uuid.toString()))
                filterBuilder.setServiceUuid(parcelUuid)
                filterBuilder.build()
            }
        }
        
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
            gattCallback.enqueueOperation(gatt, GattOperationType.DISCOVER_SERVICES, "discoverServices") {
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
                gattCallback.enqueueOperation(gatt, GattOperationType.DISCOVER_SERVICES, "discoverServices") {
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
                    GattOperationType.READ_CHAR,
                    "readCharacteristic ${char.uuid}",
                    identity = char.uuid.toString()
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
                    GattOperationType.WRITE_CHAR,
                    "writeCharacteristic ${char.uuid}",
                    identity = char.uuid.toString()
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
                GattOperationType.READ_DESC,
                "readDescriptor ${androidDesc.uuid}",
                identity = androidDesc.uuid.toString()
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
                GattOperationType.WRITE_DESC,
                "writeDescriptor ${androidDesc.uuid}",
                identity = androidDesc.uuid.toString()
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
            gattCallback.enqueueOperation(gatt, GattOperationType.MTU, "requestMtu $mtuSize") {
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
                            GattOperationType.WRITE_DESC,
                            "writeDescriptor(CCC) ${descriptor.uuid} enable=$enable",
                            identity = descriptor.uuid.toString()
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
        gatt: BluetoothGatt
    ): List<BluetoothGattCharacteristic> =
        gatt.services
            .flatMap { service ->
                service.characteristics.filter { it.uuid == characteristic.uuid }
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
                val bluetoothPeripheral = AndroidBluetoothPeripheral(device)
                val newRssi = result.rssi.toFloat()
                bluetoothPeripheral.rssi = newRssi
                val existing = _peripherals.value.find { it.uuid == bluetoothPeripheral.uuid }
                if (existing != null) {
                    (existing as? AndroidBluetoothPeripheral)?.rssi = newRssi
                    _rssiUpdates.tryEmit(bluetoothPeripheral.uuid to newRssi)
                } else {
                    _peripherals.value = _peripherals.value + setOf(bluetoothPeripheral)
                }
            }
        }
    }
    
    // GATT callback implementation
    private inner class GattClientCallback : BluetoothGattCallback() {
        internal val gatts: MutableList<BluetoothGatt> = CopyOnWriteArrayList()
        private val disconnectHandler = Handler(Looper.getMainLooper())
        private val pendingTimeouts = java.util.concurrent.ConcurrentHashMap<String, Runnable>()
        private val operationQueues = java.util.concurrent.ConcurrentHashMap<BluetoothGatt, GattOperationQueue>()

        // Guards every compound read-modify-write over [gatts]/[operationQueues] and the "is this the
        // last gatt for the address?" reset decision. These run on three different threads — GATT
        // callbacks on a binder thread, the disconnect watchdog on the main thread, and operation
        // enqueues on the caller's thread — so check-then-act sequences must be serialized. Lock order
        // is always gattLock -> queue monitor; no path takes them the other way, so there is no
        // deadlock with [GattOperationQueue]'s per-instance synchronization.
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
        }

        /**
         * Removes, closes and forgets [gatt], clearing its operation queue and — only when no newer
         * gatt for the same address remains tracked — the reused peripheral's stale connection state.
         * Idempotent, so it is safe if both STATE_DISCONNECTED and the force-close watchdog fire.
         */
        private fun closeAndForget(gatt: BluetoothGatt) = synchronized(gattLock) {
            cancelDisconnectTimeout(gatt.device.address)
            gatts.remove(gatt)
            operationQueues.remove(gatt)?.clear()
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

        /**
         * Append a GATT operation to this gatt's serialized FIFO queue. Android allows only one GATT
         * operation in flight per connection; a second issued before the first's callback returns is
         * silently dropped. The queue dispatches one at a time and advances from the matching callback
         * (or a timeout), so descriptor writes can no longer race service discovery / MTU / each other.
         */
        fun enqueueOperation(
            gatt: BluetoothGatt,
            type: GattOperationType,
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
                operationQueues
                    .computeIfAbsent(gatt) { GattOperationQueue(it) }
                    .enqueue(GattOperation(type, label, identity, action))
            }
        }

        private fun completeOperation(gatt: BluetoothGatt, type: GattOperationType, identity: String? = null) {
            operationQueues[gatt]?.complete(type, identity)
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
                    if (autoDiscoverAllServicesAndCharacteristics) {
                        // Serialize the post-connect service discovery and RSSI read; issued back to
                        // back without a queue, the second would race the first and be dropped.
                        // Discovery is enqueued first because it is the critical path consumers gate
                        // subscription work on — the best-effort RSSI read must not sit ahead of it,
                        // or a dropped RSSI callback would stall discovery for the full op watchdog.
                        enqueueOperation(gatt, GattOperationType.DISCOVER_SERVICES, "discoverServices") {
                            it.discoverServices()
                        }
                        enqueueOperation(gatt, GattOperationType.READ_RSSI, "readRemoteRssi") {
                            it.readRemoteRssi()
                        }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    logger?.info("Disconnected from ${device.address}")
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
            gatt?.let { completeOperation(it, GattOperationType.DISCOVER_SERVICES) }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger?.error("Service discovery failed with status $status")
                return
            }
            gatt?.device?.let { device ->
                peripheralFor(device.address)?._servicesFlow?.value =
                    gatt.services.map { AndroidBluetoothService(it) }
            }
        }
        
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            logger?.debug("onMtuChanged mtu=$mtu status=$status")
            gatt?.let { completeOperation(it, GattOperationType.MTU) }
            gatt?.device?.let { device ->
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    peripheralFor(device.address)?.mtuSize = mtu
                }
            }
        }
        
        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            logger?.debug("onReadRemoteRssi $rssi")
            gatt?.let { completeOperation(it, GattOperationType.READ_RSSI) }
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
            gatt?.let { completeOperation(it, GattOperationType.READ_CHAR, characteristic?.uuid?.toString()) }
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
            gatt?.let { completeOperation(it, GattOperationType.WRITE_CHAR, characteristic?.uuid?.toString()) }
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            logger?.debug("onDescriptorRead ${descriptor?.uuid}")
            gatt?.let { completeOperation(it, GattOperationType.READ_DESC, descriptor?.uuid?.toString()) }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            logger?.debug("onDescriptorWrite ${descriptor?.uuid} status=$status")
            gatt?.let { completeOperation(it, GattOperationType.WRITE_DESC, descriptor?.uuid?.toString()) }
        }

        /**
         * Per-connection FIFO that serializes GATT operations. Android permits only one operation in
         * flight per connection; the queue dispatches one at a time and advances when the matching
         * callback fires (via [complete]) or a watchdog timeout elapses, so an operation can never be
         * silently dropped by colliding with an in-flight one.
         */
        private inner class GattOperationQueue(private val gatt: BluetoothGatt) {
            private val pending = ArrayDeque<GattOperation>()
            private var current: GattOperation? = null
            private val handler = Handler(Looper.getMainLooper())
            private var timeout: Runnable? = null

            @Synchronized
            fun enqueue(operation: GattOperation) {
                pending.addLast(operation)
                if (current == null) dispatchNext()
            }

            @Synchronized
            fun complete(type: GattOperationType, identity: String?) {
                val op = current ?: return
                // Type matching guards against spurious/out-of-band callbacks (e.g. a system-initiated
                // MTU change) advancing the wrong operation; a genuine mismatch simply leaves the
                // current op pending until its own callback or the watchdog fires.
                if (op.type != type) return
                // When both the op and the callback target a specific resource (characteristic/
                // descriptor), require the UUIDs to match too. Without this, a same-type callback that
                // arrives after the watchdog already advanced the queue (e.g. a delayed onDescriptorWrite
                // landing once the next WRITE_DESC is current) would finish the wrong operation and let
                // the one after it be dispatched onto a still-busy connection and silently dropped.
                if (op.identity != null && identity != null && op.identity != identity) return
                finish(op)
            }

            @Synchronized
            fun clear() {
                timeout?.let { handler.removeCallbacks(it) }
                timeout = null
                current = null
                pending.clear()
            }

            @Synchronized
            private fun dispatchNext() {
                if (current != null) return
                while (true) {
                    val op = pending.removeFirstOrNull() ?: return
                    current = op
                    val accepted = try {
                        op.action(gatt)
                    } catch (e: Exception) {
                        logger?.error("GATT operation '${op.label}' threw: ${e.message}", e)
                        false
                    }
                    if (accepted) {
                        val watchdog = Runnable {
                            logger?.warn("GATT operation '${op.label}' timed out after ${GATT_OPERATION_TIMEOUT_MS}ms")
                            onTimeout(op)
                        }
                        timeout = watchdog
                        handler.postDelayed(watchdog, GATT_OPERATION_TIMEOUT_MS)
                        return
                    }
                    // The stack rejected the call outright (no callback will arrive); skip to the next.
                    logger?.warn("GATT operation '${op.label}' was rejected by the stack; skipping")
                    current = null
                }
            }

            @Synchronized
            private fun onTimeout(op: GattOperation) {
                if (current === op) finish(op)
            }

            @Synchronized
            private fun finish(op: GattOperation) {
                if (current !== op) return
                timeout?.let { handler.removeCallbacks(it) }
                timeout = null
                current = null
                dispatchNext()
            }
        }
    }

    companion object {
        private const val DISCONNECT_TIMEOUT_MS = 5_000L

        /**
         * Watchdog timeout for a single in-flight GATT operation. If its callback never arrives (the
         * stack occasionally drops one), the queue advances after this delay rather than stalling
         * forever. Comfortably longer than any normal read/write/discover/MTU exchange.
         */
        private const val GATT_OPERATION_TIMEOUT_MS = 10_000L
    }
}

private enum class GattOperationType {
    DISCOVER_SERVICES,
    MTU,
    READ_RSSI,
    READ_CHAR,
    WRITE_CHAR,
    READ_DESC,
    WRITE_DESC
}

private class GattOperation(
    val type: GattOperationType,
    val label: String,
    /**
     * Resource UUID this operation targets (characteristic/descriptor), or null for connection-wide
     * operations (discover/MTU/RSSI). When both this and a completion callback carry an identity, the
     * queue requires them to match before advancing, so a late or stray same-type callback cannot
     * finish a different operation. See [AndroidEngine.GattClientCallback.GattOperationQueue.complete].
     */
    val identity: String? = null,
    /** Issues the underlying GATT call; returns the stack's accepted/false result. */
    val action: (BluetoothGatt) -> Boolean
)

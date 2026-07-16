package dev.bluefalcon.engine.macos.jvm

import dev.bluefalcon.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * macOS JVM implementation of [BlueFalconEngine] for Compose Desktop.
 * Bridges to CoreBluetooth via JNI — requires libbluefalcon-macos.dylib bundled in resources.
 */
class MacosJvmEngine : BlueFalconEngine {

    override val scope = CoroutineScope(Dispatchers.Default)

    private val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    override val peripherals: StateFlow<Set<BluetoothPeripheral>> = _peripherals.asStateFlow()

    private val _managerState = MutableStateFlow(BluetoothManagerState.NotReady)
    override val managerState: StateFlow<BluetoothManagerState> = _managerState.asStateFlow()

    private val _characteristicNotifications = MutableSharedFlow<CharacteristicNotification>(extraBufferCapacity = 64)
    override val characteristicNotifications: SharedFlow<CharacteristicNotification> = _characteristicNotifications

    private val _connectionStateUpdates = MutableSharedFlow<ConnectionStateUpdate>(extraBufferCapacity = 64)
    override val connectionStateUpdates: SharedFlow<ConnectionStateUpdate> = _connectionStateUpdates

    override var isScanning: Boolean = false
        private set

    private val connections = mutableMapOf<String, MacosJvmBluetoothPeripheral>()

    // L2CAP open is async: native delivers the handle later via onL2capChannelOpened.
    private val l2capOpenDeferreds = ConcurrentHashMap<String, CompletableDeferred<Long>>()
    // Live sockets keyed by native channel handle, so data/close callbacks can route.
    private val l2capSockets = ConcurrentHashMap<Long, MacosJvmL2CapSocket>()

    init {
        try {
            nativeInitialize()
        } catch (e: UnsatisfiedLinkError) {
            _managerState.value = BluetoothManagerState.NotReady
        }
    }

    // -------------------------------------------------------------------------
    // Callbacks invoked from native via JNI (must stay private, not internal)
    // -------------------------------------------------------------------------

    @Suppress("unused")
    private fun onManagerStateChanged(state: Int) {
        // CBManagerStatePoweredOn = 5
        _managerState.value = if (state == 5) BluetoothManagerState.Ready else BluetoothManagerState.NotReady
    }

    @Suppress("unused")
    private fun onDeviceDiscovered(uuid: String, name: String?, rssi: Float, manufacturerDataBytes: ByteArray?) {
        val mfData = parseManufacturerData(manufacturerDataBytes)
        val existing = _peripherals.value.find { it.uuid == uuid } as? MacosJvmBluetoothPeripheral
        if (existing != null) {
            existing.rssi = rssi
            if (mfData.isNotEmpty()) existing.manufacturerData = mfData
        } else {
            val peripheral = MacosJvmBluetoothPeripheral(uuid, name).apply {
                this.rssi = rssi
                this.manufacturerData = mfData
            }
            _peripherals.value = _peripherals.value + peripheral
        }
    }

    private fun parseManufacturerData(raw: ByteArray?): Map<Int, ByteArray> {
        if (raw == null || raw.size < 2) return emptyMap()
        val companyId = (raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8)
        val payload = raw.copyOfRange(2, raw.size)
        return mapOf(companyId to payload)
    }

    @Suppress("unused")
    private fun onConnected(peripheralUuid: String) {
        if (!connections.containsKey(peripheralUuid)) {
            connections[peripheralUuid] = MacosJvmBluetoothPeripheral(peripheralUuid, null)
        }
        val peripheral = connections[peripheralUuid] ?: return
        _connectionStateUpdates.tryEmit(ConnectionStateUpdate(peripheral, BluetoothPeripheralState.Connected))
    }

    @Suppress("unused")
    private fun onDisconnected(peripheralUuid: String) {
        val peripheral = connections[peripheralUuid]
        connections.remove(peripheralUuid)
        if (peripheral != null) {
            _connectionStateUpdates.tryEmit(ConnectionStateUpdate(peripheral, BluetoothPeripheralState.Disconnected))
        }
    }

    @Suppress("unused")
    private fun onServicesDiscovered(peripheralUuid: String, serviceUuids: Array<String>) {
        val peripheral = connections[peripheralUuid] ?: return
        val services = serviceUuids.map { uuidStr ->
            MacosJvmBluetoothService(
                uuid = parseUuid(uuidStr),
                name = null,
                peripheralUuid = peripheralUuid
            )
        }
        peripheral.updateServices(services)
    }

    @Suppress("unused")
    private fun onCharacteristicDiscovered(
        peripheralUuid: String,
        serviceUuid: String,
        characteristicUuid: String,
        properties: Int
    ) {
        val peripheral = connections[peripheralUuid] ?: return
        val service = peripheral.services
            .find { it.uuid.toString().uppercase() == serviceUuid.uppercase() }
            as? MacosJvmBluetoothService ?: return
        val characteristic = MacosJvmBluetoothCharacteristic(
            uuid = parseUuid(characteristicUuid),
            name = null,
            peripheralUuid = peripheralUuid,
            serviceUuid = parseUuid(serviceUuid),
            properties = properties
        )
        service.addCharacteristic(characteristic)
    }

    @Suppress("unused")
    private fun onCharacteristicRead(
        peripheralUuid: String,
        serviceUuid: String,
        characteristicUuid: String,
        value: ByteArray
    ) {
        findCharacteristic(peripheralUuid, serviceUuid, characteristicUuid)?.updateValue(value)
    }

    @Suppress("unused")
    private fun onCharacteristicChanged(
        peripheralUuid: String,
        serviceUuid: String,
        characteristicUuid: String,
        value: ByteArray
    ) {
        val peripheral = connections[peripheralUuid] ?: return
        val characteristic = findCharacteristic(peripheralUuid, serviceUuid, characteristicUuid) ?: return
        characteristic.updateValue(value)
        characteristic.emitNotification(value)
        _characteristicNotifications.tryEmit(
            CharacteristicNotification(
                peripheral = peripheral,
                characteristic = characteristic,
                value = value.copyOf()
            )
        )
    }

    @Suppress("unused")
    private fun onCharacteristicWritten(
        peripheralUuid: String,
        serviceUuid: String,
        characteristicUuid: String,
        success: Boolean
    ) {
        // Write completion is conveyed through the suspend call returning normally
    }

    @Suppress("unused")
    private fun onDescriptorRead(
        peripheralUuid: String,
        serviceUuid: String,
        characteristicUuid: String,
        descriptorUuid: String,
        value: ByteArray
    ) {
        findDescriptor(peripheralUuid, serviceUuid, characteristicUuid, descriptorUuid)?.updateValue(value)
    }

    @Suppress("unused")
    private fun onMtuChanged(peripheralUuid: String, mtu: Int) {
        connections[peripheralUuid]?.mtuSize = mtu
    }

    @Suppress("unused")
    private fun onL2capChannelOpened(peripheralUuid: String, handle: Long, error: String?) {
        val deferred = l2capOpenDeferreds[peripheralUuid] ?: return
        if (error != null || handle < 0) {
            deferred.completeExceptionally(L2capException(error ?: "Failed to open L2CAP channel"))
        } else {
            deferred.complete(handle)
        }
    }

    @Suppress("unused")
    private fun onL2capDataReceived(handle: Long, data: ByteArray) {
        l2capSockets[handle]?.onDataReceived(data)
    }

    @Suppress("unused")
    private fun onL2capChannelClosed(handle: Long) {
        l2capSockets.remove(handle)?.onClosed()
    }

    // -------------------------------------------------------------------------
    // BlueFalconEngine implementation
    // -------------------------------------------------------------------------

    override suspend fun scan(filters: List<ServiceFilter>) {
        isScanning = true
        nativeScan(filters.map { it.uuid.toString() }.toTypedArray())
    }

    override suspend fun stopScanning() {
        isScanning = false
        nativeStopScan()
    }

    override fun clearPeripherals() {
        _peripherals.value = emptySet()
    }

    override suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean) {
        val p = peripheral.asMacos()
        p.updateServices(emptyList())
        connections[p.uuid] = p
        nativeConnect(p.uuid)
    }

    override suspend fun disconnect(peripheral: BluetoothPeripheral) {
        val p = peripheral.asMacos()
        nativeDisconnect(p.uuid)
        connections.remove(p.uuid)
    }

    override fun connectionState(peripheral: BluetoothPeripheral): BluetoothPeripheralState {
        val p = peripheral as? MacosJvmBluetoothPeripheral ?: return BluetoothPeripheralState.Unknown
        return try {
            when (nativeGetConnectionState(p.uuid)) {
                0 -> BluetoothPeripheralState.Disconnected
                1 -> BluetoothPeripheralState.Connecting
                2 -> BluetoothPeripheralState.Connected
                3 -> BluetoothPeripheralState.Disconnecting
                else -> BluetoothPeripheralState.Unknown
            }
        } catch (e: Exception) {
            BluetoothPeripheralState.Unknown
        }
    }

    override fun retrievePeripheral(identifier: String): BluetoothPeripheral? =
        connections[identifier] ?: MacosJvmBluetoothPeripheral(identifier, null)

    override fun requestConnectionPriority(peripheral: BluetoothPeripheral, priority: ConnectionPriority) {
        // No-op on Apple platforms
    }

    override suspend fun discoverServices(peripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid>) {
        nativeDiscoverServices(peripheral.asMacos().uuid)
    }

    override suspend fun discoverCharacteristics(
        peripheral: BluetoothPeripheral,
        service: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {
        nativeDiscoverCharacteristics(peripheral.asMacos().uuid, service.uuid.toString())
    }

    override suspend fun readCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic
    ) {
        val c = characteristic.asMacos()
        nativeReadCharacteristic(peripheral.asMacos().uuid, c.serviceUuid.toString(), c.uuid.toString())
    }

    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
        writeCharacteristic(peripheral, characteristic, value.encodeToByteArray(), writeType)
    }

    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        val c = characteristic.asMacos()
        val withResponse = writeType != 1
        nativeWriteCharacteristic(
            peripheral.asMacos().uuid, c.serviceUuid.toString(), c.uuid.toString(), value, withResponse
        )
    }

    override suspend fun notifyCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        val c = characteristic.asMacos()
        nativeSetNotify(peripheral.asMacos().uuid, c.serviceUuid.toString(), c.uuid.toString(), notify)
    }

    override suspend fun indicateCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        // Notifications and indications share the same API on Apple
        notifyCharacteristic(peripheral, characteristic, indicate)
    }

    override suspend fun readDescriptor(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        descriptor: BluetoothCharacteristicDescriptor
    ) {
        val c = characteristic.asMacos()
        nativeReadDescriptor(
            peripheral.asMacos().uuid, c.serviceUuid.toString(), c.uuid.toString(), descriptor.uuid.toString()
        )
    }

    override suspend fun writeDescriptor(
        peripheral: BluetoothPeripheral,
        descriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {
        val d = descriptor as? MacosJvmBluetoothCharacteristicDescriptor
            ?: throw IllegalArgumentException("Descriptor must be a MacosJvmBluetoothCharacteristicDescriptor")
        nativeWriteDescriptor(
            peripheral.asMacos().uuid,
            d.serviceUuid.toString(),
            d.characteristicUuid.toString(),
            d.uuid.toString(),
            value
        )
    }

    override suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int) {
        nativeChangeMTU(peripheral.asMacos().uuid, mtuSize)
    }

    override fun refreshGattCache(peripheral: BluetoothPeripheral): Boolean = false

    override suspend fun openL2capChannel(
        peripheral: BluetoothPeripheral,
        psm: Int,
        secure: Boolean
    ): BluetoothSocket {
        // CoreBluetooth determines channel security from the peripheral; the
        // `secure` flag has no separate API here (same as the macOS-native engine).
        val p = peripheral.asMacos()
        val deferred = CompletableDeferred<Long>()
        l2capOpenDeferreds[p.uuid] = deferred
        val handle = try {
            nativeOpenL2capChannel(p.uuid, psm)
            deferred.await()
        } finally {
            l2capOpenDeferreds.remove(p.uuid)
        }
        val socket = MacosJvmL2CapSocket(
            handle = handle,
            psm = psm,
            peripheral = p,
            nativeWrite = this::nativeL2capWrite,
            nativeClose = this::nativeL2capClose
        )
        l2capSockets[handle] = socket
        return socket
    }

    override suspend fun createBond(peripheral: BluetoothPeripheral) {
        // Bonding is automatic on Apple; no action required
    }

    override suspend fun removeBond(peripheral: BluetoothPeripheral) {
        // Must be done through System Preferences on macOS
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun findCharacteristic(
        peripheralUuid: String,
        serviceUuid: String,
        characteristicUuid: String
    ): MacosJvmBluetoothCharacteristic? =
        connections[peripheralUuid]?.services
            ?.find { it.uuid.toString().uppercase() == serviceUuid.uppercase() }
            ?.characteristics
            ?.find { it.uuid.toString().uppercase() == characteristicUuid.uppercase() }
            as? MacosJvmBluetoothCharacteristic

    private fun findDescriptor(
        peripheralUuid: String,
        serviceUuid: String,
        characteristicUuid: String,
        descriptorUuid: String
    ): MacosJvmBluetoothCharacteristicDescriptor? =
        findCharacteristic(peripheralUuid, serviceUuid, characteristicUuid)
            ?.descriptors
            ?.find { it.uuid.toString().uppercase() == descriptorUuid.uppercase() }
            as? MacosJvmBluetoothCharacteristicDescriptor

    private fun BluetoothPeripheral.asMacos(): MacosJvmBluetoothPeripheral =
        this as? MacosJvmBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be a MacosJvmBluetoothPeripheral")

    private fun BluetoothCharacteristic.asMacos(): MacosJvmBluetoothCharacteristic =
        this as? MacosJvmBluetoothCharacteristic
            ?: throw IllegalArgumentException("Characteristic must be a MacosJvmBluetoothCharacteristic")

    /**
     * Expands a short Bluetooth SIG UUID (4 or 8 hex chars) to the full 128-bit canonical
     * form before parsing, so `Uuid.parse` never receives a 4-char string like "180A".
     */
    private fun parseUuid(uuidStr: String): Uuid {
        val s = uuidStr.trim()
        return when (s.length) {
            4  -> Uuid.parse("0000${s}-0000-1000-8000-00805F9B34FB")
            8  -> Uuid.parse("${s}-0000-1000-8000-00805F9B34FB")
            else -> Uuid.parse(s)
        }
    }

    // -------------------------------------------------------------------------
    // Native declarations
    // -------------------------------------------------------------------------

    private external fun nativeInitialize()
    private external fun nativeScan(serviceUuids: Array<String>)
    private external fun nativeStopScan()
    private external fun nativeConnect(peripheralUuid: String)
    private external fun nativeDisconnect(peripheralUuid: String)
    private external fun nativeGetConnectionState(peripheralUuid: String): Int
    private external fun nativeDiscoverServices(peripheralUuid: String)
    private external fun nativeDiscoverCharacteristics(peripheralUuid: String, serviceUuid: String)
    private external fun nativeReadCharacteristic(peripheralUuid: String, serviceUuid: String, characteristicUuid: String)
    private external fun nativeWriteCharacteristic(peripheralUuid: String, serviceUuid: String, characteristicUuid: String, value: ByteArray, withResponse: Boolean)
    private external fun nativeSetNotify(peripheralUuid: String, serviceUuid: String, characteristicUuid: String, enable: Boolean)
    private external fun nativeReadDescriptor(peripheralUuid: String, serviceUuid: String, characteristicUuid: String, descriptorUuid: String)
    private external fun nativeWriteDescriptor(peripheralUuid: String, serviceUuid: String, characteristicUuid: String, descriptorUuid: String, value: ByteArray)
    private external fun nativeChangeMTU(peripheralUuid: String, mtuSize: Int)
    private external fun nativeOpenL2capChannel(peripheralUuid: String, psm: Int)
    private external fun nativeL2capWrite(handle: Long, data: ByteArray)
    private external fun nativeL2capClose(handle: Long)

    companion object {
        init {
            NativeLibLoader.load("natives/libbluefalcon-macos.dylib")
        }
    }
}
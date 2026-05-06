package dev.bluefalcon.engine.macos.jvm

import dev.bluefalcon.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    override var isScanning: Boolean = false
        private set

    private val connections = mutableMapOf<String, MacosJvmBluetoothPeripheral>()

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
    private fun onDeviceDiscovered(uuid: String, name: String?, rssi: Float) {
        val peripheral = MacosJvmBluetoothPeripheral(uuid, name).apply { this.rssi = rssi }
        _peripherals.value = _peripherals.value + peripheral
    }

    @Suppress("unused")
    private fun onConnected(peripheralUuid: String) {
        if (!connections.containsKey(peripheralUuid)) {
            connections[peripheralUuid] = MacosJvmBluetoothPeripheral(peripheralUuid, null)
        }
    }

    @Suppress("unused")
    private fun onDisconnected(peripheralUuid: String) {
        connections.remove(peripheralUuid)
    }

    @Suppress("unused")
    private fun onServicesDiscovered(peripheralUuid: String, serviceUuids: Array<String>) {
        val peripheral = connections[peripheralUuid] ?: return
        val services = serviceUuids.map { uuidStr ->
            MacosJvmBluetoothService(
                uuid = Uuid.parse(uuidStr),
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
            uuid = Uuid.parse(characteristicUuid),
            name = null,
            peripheralUuid = peripheralUuid,
            serviceUuid = Uuid.parse(serviceUuid),
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

    override suspend fun openL2capChannel(peripheral: BluetoothPeripheral, psm: Int) {
        throw UnsupportedOperationException("L2CAP is not yet supported in the macOS JVM engine")
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

    companion object {
        init {
            NativeLibLoader.load("natives/libbluefalcon-macos.dylib")
        }
    }
}
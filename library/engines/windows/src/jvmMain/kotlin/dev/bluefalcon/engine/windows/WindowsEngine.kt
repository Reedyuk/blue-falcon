package dev.bluefalcon.engine.windows

import dev.bluefalcon.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Windows implementation of BlueFalconEngine using JNI bridge to WinRT Bluetooth API
 */
class WindowsEngine : BlueFalconEngine {
    override val scope = CoroutineScope(Dispatchers.Default)
    
    private val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    override val peripherals: StateFlow<Set<BluetoothPeripheral>> = _peripherals.asStateFlow()
    
    private val _managerState = MutableStateFlow(BluetoothManagerState.NotReady)
    override val managerState: StateFlow<BluetoothManagerState> = _managerState.asStateFlow()
    
    override var isScanning: Boolean = false
        private set
    
    // Store active connections
    private val connections = mutableMapOf<Long, WindowsBluetoothPeripheral>()
    
    init {
        // Load native library
        try {
            System.loadLibrary("bluefalcon-windows")
            nativeInitialize()
            _managerState.value = BluetoothManagerState.Ready
        } catch (e: UnsatisfiedLinkError) {
            _managerState.value = BluetoothManagerState.NotReady
        } catch (e: Exception) {
            _managerState.value = BluetoothManagerState.NotReady
        }
    }
    
    override suspend fun scan(filters: List<ServiceFilter>) {
        isScanning = true
        
        try {
            val serviceUuids = filters.map { it.uuid.toString() }.toTypedArray()
            
            nativeScan(serviceUuids)
        } catch (e: Exception) {
            isScanning = false
            throw e
        }
    }
    
    override suspend fun stopScanning() {
        isScanning = false
        
        try {
            nativeStopScan()
        } catch (e: Exception) {
            // Ignore errors when stopping scan
        }
    }
    
    override fun clearPeripherals() {
        _peripherals.value = emptySet()
    }
    
    override suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean) {
        val windowsPeripheral = peripheral as? WindowsBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be a WindowsBluetoothPeripheral")
        
        val address = windowsPeripheral.address
        
        try {
            nativeConnect(address)
            connections[address] = windowsPeripheral
        } catch (e: Exception) {
            throw e
        }
    }
    
    override suspend fun disconnect(peripheral: BluetoothPeripheral) {
        val windowsPeripheral = peripheral as? WindowsBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be a WindowsBluetoothPeripheral")
        
        val address = windowsPeripheral.address
        
        try {
            nativeDisconnect(address)
            connections.remove(address)
        } catch (e: Exception) {
            throw e
        }
    }
    
    override fun connectionState(peripheral: BluetoothPeripheral): BluetoothPeripheralState {
        val windowsPeripheral = peripheral as? WindowsBluetoothPeripheral
            ?: return BluetoothPeripheralState.Unknown
        
        val address = windowsPeripheral.address
        
        return try {
            val state = nativeGetConnectionState(address)
            when (state) {
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
    
    override fun retrievePeripheral(identifier: String): BluetoothPeripheral? {
        return try {
            // Parse MAC address
            val address = parseMacAddress(identifier)
            WindowsBluetoothPeripheral(address, null)
        } catch (e: Exception) {
            null
        }
    }
    
    override fun requestConnectionPriority(peripheral: BluetoothPeripheral, priority: ConnectionPriority) {
        // Windows doesn't have a direct equivalent - no-op
    }
    
    override suspend fun discoverServices(peripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid>) {
        val windowsPeripheral = peripheral as? WindowsBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be a WindowsBluetoothPeripheral")
        
        val address = windowsPeripheral.address
        
        try {
            nativeDiscoverServices(address)
        } catch (e: Exception) {
            throw e
        }
    }
    
    override suspend fun discoverCharacteristics(
        peripheral: BluetoothPeripheral,
        service: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {
        val windowsPeripheral = peripheral as? WindowsBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be a WindowsBluetoothPeripheral")
        
        val address = windowsPeripheral.address
        
        try {
            nativeDiscoverCharacteristics(address, service.uuid.toString())
        } catch (e: Exception) {
            throw e
        }
    }
    
    override suspend fun readCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic
    ) {
        val windowsPeripheral = peripheral as? WindowsBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be a WindowsBluetoothPeripheral")
        
        val address = windowsPeripheral.address
        
        try {
            nativeReadCharacteristic(address, characteristic.uuid.toString())
        } catch (e: Exception) {
            throw e
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
    
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        val windowsPeripheral = peripheral as? WindowsBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be a WindowsBluetoothPeripheral")
        
        val address = windowsPeripheral.address
        
        try {
            val withResponse = writeType == null || writeType == WRITE_TYPE_DEFAULT
            nativeWriteCharacteristic(address, characteristic.uuid.toString(), value, withResponse)
        } catch (e: Exception) {
            throw e
        }
    }
    
    override suspend fun notifyCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        val windowsPeripheral = peripheral as? WindowsBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be a WindowsBluetoothPeripheral")
        
        val address = windowsPeripheral.address
        
        try {
            nativeSetNotify(address, characteristic.uuid.toString(), notify)
        } catch (e: Exception) {
            throw e
        }
    }
    
    override suspend fun indicateCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        val windowsPeripheral = peripheral as? WindowsBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be a WindowsBluetoothPeripheral")
        
        val address = windowsPeripheral.address
        
        try {
            nativeSetIndicate(address, characteristic.uuid.toString(), indicate)
        } catch (e: Exception) {
            throw e
        }
    }
    
    override suspend fun readDescriptor(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        descriptor: BluetoothCharacteristicDescriptor
    ) {
        val windowsPeripheral = peripheral as? WindowsBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be a WindowsBluetoothPeripheral")
        
        val address = windowsPeripheral.address
        
        try {
            nativeReadDescriptor(address, descriptor.uuid.toString())
        } catch (e: Exception) {
            throw e
        }
    }
    
    override suspend fun writeDescriptor(
        peripheral: BluetoothPeripheral,
        descriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {
        val windowsPeripheral = peripheral as? WindowsBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be a WindowsBluetoothPeripheral")
        
        val address = windowsPeripheral.address
        
        try {
            nativeWriteDescriptor(address, descriptor.uuid.toString(), value)
        } catch (e: Exception) {
            throw e
        }
    }
    
    override suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int) {
        val windowsPeripheral = peripheral as? WindowsBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be a WindowsBluetoothPeripheral")
        
        val address = windowsPeripheral.address
        
        try {
            nativeChangeMTU(address, mtuSize)
        } catch (e: Exception) {
            throw e
        }
    }
    
    override fun refreshGattCache(peripheral: BluetoothPeripheral): Boolean = false
    
    override suspend fun openL2capChannel(peripheral: BluetoothPeripheral, psm: Int) {
        throw UnsupportedOperationException("L2CAP is not supported on Windows")
    }
    
    override suspend fun createBond(peripheral: BluetoothPeripheral) {
        throw UnsupportedOperationException("Bonding is not supported on this platform")
    }
    
    override suspend fun removeBond(peripheral: BluetoothPeripheral) {
        throw UnsupportedOperationException("Bonding is not supported on this platform")
    }
    
    // Callbacks from native code
    @Suppress("unused")
    private fun onDeviceDiscovered(
        address: Long,
        name: String?,
        rssi: Float,
        isConnectable: Boolean
    ) {
        val peripheral = WindowsBluetoothPeripheral(address, name).apply {
            this.rssi = rssi
        }
        
        _peripherals.value = _peripherals.value + peripheral
    }
    
    @Suppress("unused")
    private fun onServicesDiscovered(address: Long, serviceUuids: Array<String>) {
        val peripheral = connections[address] ?: return
        
        val services = serviceUuids.map { uuidStr ->
            WindowsBluetoothService(
                uuid = Uuid.parse(uuidStr),
                name = null,
                address = address
            )
        }
        
        peripheral.updateServices(services)
    }
    
    @Suppress("unused")
    private fun onCharacteristicDiscovered(
        address: Long,
        serviceUuid: String,
        characteristicUuid: String,
        properties: Int
    ) {
        val peripheral = connections[address] ?: return
        
        val service = peripheral.services.find { it.uuid.toString() == serviceUuid }
            as? WindowsBluetoothService ?: return
        
        val characteristic = WindowsBluetoothCharacteristic(
            uuid = Uuid.parse(characteristicUuid),
            name = null,
            address = address,
            serviceUuid = Uuid.parse(serviceUuid),
            properties = properties
        )
        
        service.addCharacteristic(characteristic)
    }
    
    @Suppress("unused")
    private fun onCharacteristicRead(
        address: Long,
        characteristicUuid: String,
        value: ByteArray
    ) {
        val peripheral = connections[address] ?: return
        
        peripheral.services.forEach { service ->
            (service as? WindowsBluetoothService)?.characteristics?.forEach { char ->
                val windowsChar = char as? WindowsBluetoothCharacteristic
                if (windowsChar?.uuid.toString() == characteristicUuid) {
                    windowsChar?.updateValue(value)
                }
            }
        }
    }
    
    @Suppress("unused")
    private fun onCharacteristicChanged(
        address: Long,
        characteristicUuid: String,
        value: ByteArray
    ) {
        onCharacteristicRead(address, characteristicUuid, value)
    }
    
    @Suppress("unused")
    private fun onCharacteristicWritten(
        address: Long,
        characteristicUuid: String,
        success: Boolean
    ) {
        // Callback handled via suspend functions
    }
    
    @Suppress("unused")
    private fun onDescriptorRead(
        address: Long,
        descriptorUuid: String,
        value: ByteArray
    ) {
        val peripheral = connections[address] ?: return
        
        peripheral.services.forEach { service ->
            (service as? WindowsBluetoothService)?.characteristics?.forEach { char ->
                (char as? WindowsBluetoothCharacteristic)?.descriptors?.forEach { desc ->
                    val windowsDesc = desc as? WindowsBluetoothCharacteristicDescriptor
                    if (windowsDesc?.uuid.toString() == descriptorUuid) {
                        windowsDesc?.updateValue(value)
                    }
                }
            }
        }
    }
    
    @Suppress("unused")
    private fun onDescriptorWritten(
        address: Long,
        descriptorUuid: String
    ) {
        // Callback handled via suspend functions
    }
    
    @Suppress("unused")
    private fun onMtuChanged(address: Long, mtu: Int) {
        val peripheral = connections[address] ?: return
        peripheral.mtuSize = mtu
    }
    
    @Suppress("unused")
    private fun onRssiUpdated(address: Long, rssi: Float) {
        val peripheral = connections[address] ?: return
        peripheral.rssi = rssi
    }
    
    // Helper function to parse MAC address
    private fun parseMacAddress(mac: String): Long {
        val bytes = mac.split(":").map { it.toInt(16).toByte() }
        var address = 0L
        for (i in bytes.indices) {
            address = (address shl 8) or (bytes[i].toLong() and 0xFF)
        }
        return address
    }
    
    // Native method declarations
    private external fun nativeInitialize()
    private external fun nativeScan(serviceUuids: Array<String>)
    private external fun nativeStopScan()
    private external fun nativeConnect(address: Long)
    private external fun nativeDisconnect(address: Long)
    private external fun nativeGetConnectionState(address: Long): Int
    private external fun nativeDiscoverServices(address: Long)
    private external fun nativeDiscoverCharacteristics(address: Long, serviceUuid: String)
    private external fun nativeReadCharacteristic(address: Long, characteristicUuid: String)
    private external fun nativeWriteCharacteristic(address: Long, characteristicUuid: String, value: ByteArray, withResponse: Boolean)
    private external fun nativeSetNotify(address: Long, characteristicUuid: String, enable: Boolean)
    private external fun nativeSetIndicate(address: Long, characteristicUuid: String, enable: Boolean)
    private external fun nativeReadDescriptor(address: Long, descriptorUuid: String)
    private external fun nativeWriteDescriptor(address: Long, descriptorUuid: String, value: ByteArray)
    private external fun nativeChangeMTU(address: Long, mtu: Int)
    
    companion object {
        const val WRITE_TYPE_DEFAULT = 0x02
        const val WRITE_TYPE_NO_RESPONSE = 0x01
    }
}

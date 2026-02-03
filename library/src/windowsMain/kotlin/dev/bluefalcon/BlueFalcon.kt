package dev.bluefalcon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

actual class BlueFalcon actual constructor(
    private val log: Logger?,
    private val context: ApplicationContext,
    private val autoDiscoverAllServicesAndCharacteristics: Boolean
) {
    actual val delegates: MutableSet<BlueFalconDelegate> = mutableSetOf()
    actual var isScanning: Boolean = false
    
    actual val scope = CoroutineScope(Dispatchers.Default)
    internal actual val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    actual val peripherals: NativeFlow<Set<BluetoothPeripheral>> = _peripherals.toNativeType(scope)
    
    private val _managerState = MutableStateFlow(BluetoothManagerState.NotReady)
    actual val managerState: StateFlow<BluetoothManagerState> = _managerState
    
    // Store connections
    private val connections = mutableMapOf<Long, BluetoothPeripheralImpl>()
    
    init {
        // Load native library
        try {
            System.loadLibrary("bluefalcon-windows")
            nativeInitialize()
            _managerState.value = BluetoothManagerState.Ready
            log?.info("Windows Bluetooth initialized successfully")
        } catch (e: UnsatisfiedLinkError) {
            log?.error("Failed to load native library: ${e.message}")
            _managerState.value = BluetoothManagerState.NotReady
        } catch (e: Exception) {
            log?.error("Failed to initialize Windows Bluetooth: ${e.message}")
            _managerState.value = BluetoothManagerState.NotReady
        }
    }
    
    actual fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean) {
        log?.debug("connect ${bluetoothPeripheral.uuid}")
        val address = (bluetoothPeripheral as BluetoothPeripheralImpl).device.address
        
        scope.launch {
            try {
                nativeConnect(address)
                connections[address] = bluetoothPeripheral
                
                if (autoDiscoverAllServicesAndCharacteristics) {
                    discoverServices(bluetoothPeripheral, emptyList())
                }
                
                delegates.forEach { it.didConnect(bluetoothPeripheral) }
            } catch (e: Exception) {
                log?.error("Failed to connect: ${e.message}")
            }
        }
    }
    
    actual fun disconnect(bluetoothPeripheral: BluetoothPeripheral) {
        log?.debug("disconnect ${bluetoothPeripheral.uuid}")
        val address = (bluetoothPeripheral as BluetoothPeripheralImpl).device.address
        
        scope.launch {
            try {
                nativeDisconnect(address)
                connections.remove(address)
                delegates.forEach { it.didDisconnect(bluetoothPeripheral) }
            } catch (e: Exception) {
                log?.error("Failed to disconnect: ${e.message}")
            }
        }
    }
    
    actual fun retrievePeripheral(identifier: String): BluetoothPeripheral? {
        return try {
            // Try to parse as MAC address
            val address = parseMacAddress(identifier)
            val device = NativeBluetoothDevice(address)
            BluetoothPeripheralImpl(device)
        } catch (e: Exception) {
            log?.error("retrievePeripheral error: ${e.message}")
            null
        }
    }
    
    actual fun requestConnectionPriority(
        bluetoothPeripheral: BluetoothPeripheral,
        connectionPriority: ConnectionPriority
    ) {
        log?.debug("requestConnectionPriority - not supported on Windows")
        // Windows doesn't have a direct equivalent
    }
    
    actual fun connectionState(bluetoothPeripheral: BluetoothPeripheral): BluetoothPeripheralState {
        val address = (bluetoothPeripheral as BluetoothPeripheralImpl).device.address
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
    
    actual fun scan(filters: List<ServiceFilter>) {
        log?.info("Scan started with filters: $filters")
        isScanning = true
        
        scope.launch {
            try {
                val serviceUuids = filters.flatMap { it.serviceUuids }
                    .map { it.toString() }
                    .toTypedArray()
                
                nativeScan(serviceUuids)
            } catch (e: Exception) {
                log?.error("Failed to start scan: ${e.message}")
                isScanning = false
            }
        }
    }
    
    actual fun stopScanning() {
        log?.info("Scan stopped")
        isScanning = false
        
        scope.launch {
            try {
                nativeStopScan()
            } catch (e: Exception) {
                log?.error("Failed to stop scan: ${e.message}")
            }
        }
    }
    
    actual fun clearPeripherals() {
        _peripherals.value = emptySet()
    }
    
    actual fun discoverServices(
        bluetoothPeripheral: BluetoothPeripheral,
        serviceUUIDs: List<Uuid>
    ) {
        val address = (bluetoothPeripheral as BluetoothPeripheralImpl).device.address
        
        scope.launch {
            try {
                nativeDiscoverServices(address)
            } catch (e: Exception) {
                log?.error("Failed to discover services: ${e.message}")
            }
        }
    }
    
    actual fun discoverCharacteristics(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothService: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {
        val address = (bluetoothPeripheral as BluetoothPeripheralImpl).device.address
        
        scope.launch {
            try {
                nativeDiscoverCharacteristics(address, bluetoothService.uuid.toString())
            } catch (e: Exception) {
                log?.error("Failed to discover characteristics: ${e.message}")
            }
        }
    }
    
    actual fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        val address = (bluetoothPeripheral as BluetoothPeripheralImpl).device.address
        
        scope.launch {
            try {
                nativeReadCharacteristic(
                    address,
                    bluetoothCharacteristic.uuid.toString()
                )
            } catch (e: Exception) {
                log?.error("Failed to read characteristic: ${e.message}")
            }
        }
    }
    
    actual fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        val address = (bluetoothPeripheral as BluetoothPeripheralImpl).device.address
        
        scope.launch {
            try {
                nativeSetNotify(
                    address,
                    bluetoothCharacteristic.uuid.toString(),
                    notify
                )
            } catch (e: Exception) {
                log?.error("Failed to set notify: ${e.message}")
            }
        }
    }
    
    actual fun indicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        val address = (bluetoothPeripheral as BluetoothPeripheralImpl).device.address
        
        scope.launch {
            try {
                nativeSetIndicate(
                    address,
                    bluetoothCharacteristic.uuid.toString(),
                    indicate
                )
            } catch (e: Exception) {
                log?.error("Failed to set indicate: ${e.message}")
            }
        }
    }
    
    actual fun notifyAndIndicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        enable: Boolean
    ) {
        // Set both notify and indicate
        notifyCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, enable)
        indicateCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, enable)
    }
    
    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
        writeCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, value.encodeToByteArray(), writeType)
    }
    
    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        val address = (bluetoothPeripheral as BluetoothPeripheralImpl).device.address
        
        scope.launch {
            try {
                val withResponse = writeType == null || writeType == WRITE_TYPE_DEFAULT
                nativeWriteCharacteristic(
                    address,
                    bluetoothCharacteristic.uuid.toString(),
                    value,
                    withResponse
                )
            } catch (e: Exception) {
                log?.error("Failed to write characteristic: ${e.message}")
            }
        }
    }
    
    actual fun writeCharacteristicWithoutEncoding(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        writeCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, value, writeType)
    }
    
    actual fun readDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {
        val address = (bluetoothPeripheral as BluetoothPeripheralImpl).device.address
        
        scope.launch {
            try {
                nativeReadDescriptor(
                    address,
                    bluetoothCharacteristicDescriptor.uuid.toString()
                )
            } catch (e: Exception) {
                log?.error("Failed to read descriptor: ${e.message}")
            }
        }
    }
    
    actual fun writeDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {
        val address = (bluetoothPeripheral as BluetoothPeripheralImpl).device.address
        
        scope.launch {
            try {
                nativeWriteDescriptor(
                    address,
                    bluetoothCharacteristicDescriptor.uuid.toString(),
                    value
                )
            } catch (e: Exception) {
                log?.error("Failed to write descriptor: ${e.message}")
            }
        }
    }
    
    actual fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int) {
        log?.debug("changeMTU -> ${bluetoothPeripheral.uuid} mtuSize: $mtuSize")
        // Windows automatically negotiates MTU, but we can try to request
        val address = (bluetoothPeripheral as BluetoothPeripheralImpl).device.address
        
        scope.launch {
            try {
                nativeChangeMTU(address, mtuSize)
            } catch (e: Exception) {
                log?.error("Failed to change MTU: ${e.message}")
            }
        }
    }
    
    // Callbacks from native code
    @Suppress("unused")
    private fun onDeviceDiscovered(
        address: Long,
        name: String?,
        rssi: Float,
        isConnectable: Boolean
    ) {
        val device = NativeBluetoothDevice(address)
        val peripheral = BluetoothPeripheralImpl(device).apply {
            setName(name)
            this.rssi = rssi
        }
        
        _peripherals.tryEmit(_peripherals.value + setOf(peripheral))
        
        val advertisementData = mapOf<AdvertisementDataRetrievalKeys, Any>(
            AdvertisementDataRetrievalKeys.IsConnectable to if (isConnectable) 1 else 0
        ).let { data ->
            name?.let { data + (AdvertisementDataRetrievalKeys.LocalName to it) } ?: data
        }
        
        delegates.forEach { it.didDiscoverDevice(peripheral, advertisementData) }
    }
    
    @Suppress("unused")
    private fun onServicesDiscovered(address: Long, serviceUuids: Array<String>) {
        val peripheral = connections[address] ?: return
        
        val services = serviceUuids.map { uuidStr ->
            val uuid = kotlin.uuid.Uuid.parse(uuidStr)
            val nativeService = NativeBluetoothService(uuid, null, address)
            BluetoothService(nativeService)
        }
        
        peripheral._servicesFlow.tryEmit(services)
        delegates.forEach { it.didDiscoverServices(peripheral) }
    }
    
    @Suppress("unused")
    private fun onCharacteristicDiscovered(
        address: Long,
        serviceUuid: String,
        characteristicUuid: String,
        properties: Int
    ) {
        val peripheral = connections[address] ?: return
        
        delegates.forEach { it.didDiscoverCharacteristics(peripheral) }
    }
    
    @Suppress("unused")
    private fun onCharacteristicRead(
        address: Long,
        characteristicUuid: String,
        value: ByteArray
    ) {
        val peripheral = connections[address] ?: return
        
        peripheral.services.values.forEach { service ->
            service.characteristics
                .filter { it.uuid.toString() == characteristicUuid }
                .forEach { characteristic ->
                    characteristic.characteristic.value = value
                    delegates.forEach { it.didCharacteristcValueChanged(peripheral, characteristic) }
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
        val peripheral = connections[address] ?: return
        
        peripheral.services.values.forEach { service ->
            service.characteristics
                .filter { it.uuid.toString() == characteristicUuid }
                .forEach { characteristic ->
                    delegates.forEach { it.didWriteCharacteristic(peripheral, characteristic, success) }
                }
        }
    }
    
    @Suppress("unused")
    private fun onDescriptorRead(
        address: Long,
        descriptorUuid: String,
        value: ByteArray
    ) {
        val peripheral = connections[address] ?: return
        
        peripheral.services.values.forEach { service ->
            service.characteristics.forEach { characteristic ->
                characteristic.descriptors
                    .filter { it.uuid.toString() == descriptorUuid }
                    .forEach { descriptor ->
                        descriptor.value = value
                        delegates.forEach { it.didReadDescriptor(peripheral, descriptor) }
                    }
            }
        }
    }
    
    @Suppress("unused")
    private fun onDescriptorWritten(
        address: Long,
        descriptorUuid: String
    ) {
        val peripheral = connections[address] ?: return
        
        peripheral.services.values.forEach { service ->
            service.characteristics.forEach { characteristic ->
                characteristic.descriptors
                    .filter { it.uuid.toString() == descriptorUuid }
                    .forEach { descriptor ->
                        delegates.forEach { it.didWriteDescriptor(peripheral, descriptor) }
                        
                        // Check if this is CCCD
                        if (descriptor.uuid == BluetoothCharacteristicDescriptor.CCCD_UUID) {
                            delegates.forEach { it.didUpdateNotificationStateFor(peripheral, characteristic) }
                        }
                    }
            }
        }
    }
    
    @Suppress("unused")
    private fun onMtuChanged(address: Long, mtu: Int) {
        val peripheral = connections[address] ?: return
        peripheral.mtuSize = mtu
        delegates.forEach { it.didUpdateMTU(peripheral, mtu) }
    }
    
    @Suppress("unused")
    private fun onRssiUpdated(address: Long, rssi: Float) {
        val peripheral = connections[address] ?: return
        peripheral.rssi = rssi
        delegates.forEach { it.didRssiUpdate(peripheral) }
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

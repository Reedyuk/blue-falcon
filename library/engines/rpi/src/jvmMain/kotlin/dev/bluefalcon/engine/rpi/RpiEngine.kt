package dev.bluefalcon.engine.rpi

import com.welie.blessed.*
import com.welie.blessed.BluetoothPeripheral as BlessedPeripheral
import dev.bluefalcon.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    
    override var isScanning: Boolean = false
        private set
    
    private val peripheralMap = mutableMapOf<String, RpiBluetoothPeripheral>()
    private val peripheralCallbacks = mutableMapOf<String, BluetoothPeripheralCallback>()
    
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
            _peripherals.value = _peripherals.value + device
        }
    }
    
    private val bluetoothManager = BluetoothCentralManager(bluetoothManagerCallback)
    
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
    ) {
        val rpiPeripheral = peripheral as? RpiBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an RpiBluetoothPeripheral")
        val rpiCharacteristic = characteristic as? RpiBluetoothCharacteristic
            ?: throw IllegalArgumentException("Characteristic must be an RpiBluetoothCharacteristic")
        
        rpiPeripheral.nativePeripheral.readCharacteristic(rpiCharacteristic.nativeCharacteristic)
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
        
        val blessedWriteType = when (writeType) {
            0 -> BluetoothGattCharacteristic.WriteType.WITH_RESPONSE
            1 -> BluetoothGattCharacteristic.WriteType.WITHOUT_RESPONSE
            else -> BluetoothGattCharacteristic.WriteType.WITHOUT_RESPONSE
        }
        
        rpiPeripheral.nativePeripheral.writeCharacteristic(
            rpiCharacteristic.nativeCharacteristic,
            value,
            blessedWriteType
        )
    }
    
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
    
    override suspend fun openL2capChannel(peripheral: dev.bluefalcon.core.BluetoothPeripheral, psm: Int) {
        throw UnsupportedOperationException("openL2capChannel is not supported on RPi")
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
                peripheral.updateServices(services.map { RpiBluetoothService(it) })
            }
            
            override fun onCharacteristicUpdate(
                nativePeripheral: BlessedPeripheral,
                value: ByteArray,
                characteristic: BluetoothGattCharacteristic,
                status: BluetoothCommandStatus
            ) {
                peripheral.updateCharacteristicValue(characteristic.uuid.toString(), value)
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
                peripheral.updateCharacteristicValue(characteristic.uuid.toString(), value)
            }
        }
    }
}

package dev.bluefalcon.example.engine

import dev.bluefalcon.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Example Custom Engine: Mock BLE Engine
 * 
 * This demonstrates how to implement a custom BlueFalconEngine.
 * This is a mock/fake engine useful for:
 * - Testing without real BLE hardware
 * - UI development
 * - Demos and presentations
 * - Integration testing
 */
class MockBLEEngine : BlueFalconEngine {
    
    // Required scope for coroutines
    override val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // StateFlow for discovered peripherals
    private val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    override val peripherals: StateFlow<Set<BluetoothPeripheral>> = _peripherals.asStateFlow()
    
    // StateFlow for manager state
    private val _managerState = MutableStateFlow<BluetoothManagerState>(BluetoothManagerState.Ready)
    override val managerState: StateFlow<BluetoothManagerState> = _managerState.asStateFlow()
    
    // Internal state
    private var isScanning = false
    private val connectedDevices = mutableSetOf<String>()
    
    // Mock devices to "discover"
    private val mockDevices = listOf(
        createMockPeripheral("Heart Rate Monitor", "00:11:22:33:44:55"),
        createMockPeripheral("Fitness Tracker", "AA:BB:CC:DD:EE:FF"),
        createMockPeripheral("Smart Watch", "11:22:33:44:55:66")
    )
    
    /**
     * Start scanning for devices
     */
    override suspend fun scan(serviceFilter: List<ServiceFilter>) {
        println("[MockEngine] Starting scan...")
        isScanning = true
        
        // Simulate discovering devices over time
        scope.launch {
            mockDevices.forEach { device ->
                delay(1000) // 1 second between discoveries
                if (isScanning) {
                    _peripherals.value = _peripherals.value + device
                    println("[MockEngine] Discovered: ${device.name}")
                }
            }
        }
    }
    
    /**
     * Stop scanning
     */
    override suspend fun stopScanning() {
        println("[MockEngine] Stopping scan")
        isScanning = false
    }
    
    /**
     * Connect to a peripheral
     */
    override suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean) {
        println("[MockEngine] Connecting to ${peripheral.name}...")
        delay(500) // Simulate connection time
        
        connectedDevices.add(peripheral.uuid)
        println("[MockEngine] Connected to ${peripheral.name}")
    }
    
    /**
     * Disconnect from a peripheral
     */
    override suspend fun disconnect(peripheral: BluetoothPeripheral) {
        println("[MockEngine] Disconnecting from ${peripheral.name}")
        delay(200)
        connectedDevices.remove(peripheral.uuid)
    }
    
    /**
     * Get connection state
     */
    override suspend fun connectionState(peripheral: BluetoothPeripheral): BluetoothPeripheralState {
        return if (connectedDevices.contains(peripheral.uuid)) {
            BluetoothPeripheralState.Connected
        } else {
            BluetoothPeripheralState.Disconnected
        }
    }
    
    /**
     * Read a characteristic value
     */
    override suspend fun readCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic
    ): ByteArray {
        println("[MockEngine] Reading characteristic")
        delay(100)
        return byteArrayOf(0x01, 0x02, 0x03, 0x04)
    }
    
    /**
     * Write to a characteristic
     */
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ): Boolean {
        println("[MockEngine] Writing ${value.size} bytes")
        delay(100)
        return true
    }
    
    // Helper to create mock peripherals
    private fun createMockPeripheral(name: String, uuid: String): BluetoothPeripheral {
        return object : BluetoothPeripheral {
            override val name: String = name
            override val uuid: String = uuid
            override val rssi: Int = -60
            override var services: List<BluetoothService> = emptyList()
        }
    }
    
    // Minimal implementations for other required methods
    override suspend fun discoverServices(peripheral: BluetoothPeripheral, serviceUUIDs: List<String>) {}
    override suspend fun discoverCharacteristics(peripheral: BluetoothPeripheral, service: BluetoothService, characteristicUUIDs: List<String>) {}
    override suspend fun writeCharacteristic(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic, value: String, writeType: Int?): Boolean = true
    override suspend fun writeCharacteristicWithoutEncoding(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic, value: ByteArray, writeType: Int?): Boolean = true
    override suspend fun readDescriptor(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic, descriptor: BluetoothCharacteristicDescriptor): ByteArray = byteArrayOf()
    override suspend fun writeDescriptor(peripheral: BluetoothPeripheral, descriptor: BluetoothCharacteristicDescriptor, value: ByteArray): Boolean = true
    override suspend fun notifyCharacteristic(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic, notify: Boolean): Boolean = true
    override suspend fun indicateCharacteristic(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic, indicate: Boolean): Boolean = true
    override suspend fun notifyAndIndicateCharacteristic(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic, enable: Boolean): Boolean = true
    override suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int): Int = mtuSize
    override suspend fun readRSSI(peripheral: BluetoothPeripheral): Int = -60
    override suspend fun clearPeripherals() { _peripherals.value = emptySet() }
    override suspend fun openL2capChannel(peripheral: BluetoothPeripheral, psm: Int) {}
    override suspend fun createBond(peripheral: BluetoothPeripheral): Boolean = true
    override suspend fun removeBond(peripheral: BluetoothPeripheral): Boolean = true
    override suspend fun requestConnectionPriority(peripheral: BluetoothPeripheral, priority: ConnectionPriority) {}
    override suspend fun retrievePeripheral(identifier: String): BluetoothPeripheral? = null
}

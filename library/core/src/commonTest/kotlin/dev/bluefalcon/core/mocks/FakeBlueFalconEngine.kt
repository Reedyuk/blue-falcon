package dev.bluefalcon.core.mocks

import dev.bluefalcon.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fake implementation of BlueFalconEngine for testing purposes.
 * Provides configurable behavior for testing various scenarios.
 */
class FakeBlueFalconEngine : BlueFalconEngine {
    
    override val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)
    
    private val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    override val peripherals: StateFlow<Set<BluetoothPeripheral>> = _peripherals
    
    private val _managerState = MutableStateFlow(BluetoothManagerState.PoweredOn)
    override val managerState: StateFlow<BluetoothManagerState> = _managerState
    
    override var isScanning: Boolean = false
        private set
    
    // Test tracking properties
    var scanCalled = false
    var stopScanningCalled = false
    var lastScanFilters: List<ServiceFilter>? = null
    var connectCalled = false
    var disconnectCalled = false
    
    // Configurable behavior
    var shouldFailConnect = false
    var shouldFailRead = false
    var shouldFailWrite = false
    var onConnect: () -> Unit = {}
    var onScan: () -> Unit = {}
    
    override suspend fun scan(filters: List<ServiceFilter>) {
        scanCalled = true
        lastScanFilters = filters
        isScanning = true
        onScan()
    }
    
    override suspend fun stopScanning() {
        stopScanningCalled = true
        isScanning = false
    }
    
    override fun clearPeripherals() {
        _peripherals.value = emptySet()
    }
    
    override suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean) {
        connectCalled = true
        if (shouldFailConnect) {
            throw BluetoothUnknownException()
        }
        onConnect()
    }
    
    override suspend fun disconnect(peripheral: BluetoothPeripheral) {
        disconnectCalled = true
    }
    
    override fun connectionState(peripheral: BluetoothPeripheral): BluetoothPeripheralState {
        return BluetoothPeripheralState.Disconnected
    }
    
    override fun retrievePeripheral(identifier: String): BluetoothPeripheral? {
        return _peripherals.value.firstOrNull { it.uuid == identifier }
    }
    
    override fun requestConnectionPriority(peripheral: BluetoothPeripheral, priority: ConnectionPriority) {
        // No-op for testing
    }
    
    override suspend fun discoverServices(peripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid>) {
        // No-op for testing
    }
    
    override suspend fun discoverCharacteristics(
        peripheral: BluetoothPeripheral,
        service: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {
        // No-op for testing
    }
    
    override suspend fun readCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic
    ) {
        if (shouldFailRead) {
            throw BluetoothUnknownException()
        }
    }
    
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        if (shouldFailWrite) {
            throw BluetoothUnknownException()
        }
    }
    
    override suspend fun notifyCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        // No-op for testing
    }
    
    override suspend fun indicateCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        // No-op for testing
    }
    
    override suspend fun readDescriptor(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        descriptor: BluetoothCharacteristicDescriptor
    ) {
        // No-op for testing
    }
    
    override suspend fun writeDescriptor(
        peripheral: BluetoothPeripheral,
        descriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {
        // No-op for testing
    }
    
    override suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int) {
        // No-op for testing
    }
    
    override fun refreshGattCache(peripheral: BluetoothPeripheral): Boolean {
        return true
    }
    
    override suspend fun openL2capChannel(peripheral: BluetoothPeripheral, psm: Int) {
        // No-op for testing
    }
    
    override suspend fun createBond(peripheral: BluetoothPeripheral) {
        // No-op for testing
    }
    
    override suspend fun removeBond(peripheral: BluetoothPeripheral) {
        // No-op for testing
    }
    
    // Test helper methods
    
    fun addFakePeripheral(name: String): FakePeripheral {
        val peripheral = FakePeripheral(name)
        _peripherals.value = _peripherals.value + peripheral
        return peripheral
    }
    
    fun createFakePeripheral(name: String): FakePeripheral {
        return FakePeripheral(name)
    }
    
    fun setBluetoothState(state: BluetoothManagerState) {
        _managerState.value = state
    }
    
    fun reset() {
        scanCalled = false
        stopScanningCalled = false
        connectCalled = false
        disconnectCalled = false
        lastScanFilters = null
        shouldFailConnect = false
        shouldFailRead = false
        shouldFailWrite = false
        isScanning = false
        _peripherals.value = emptySet()
        _managerState.value = BluetoothManagerState.PoweredOn
    }
}

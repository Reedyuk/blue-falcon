package dev.bluefalcon.core.mocks

import dev.bluefalcon.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
    
    private val _managerState = MutableStateFlow(BluetoothManagerState.Ready)
    override val managerState: StateFlow<BluetoothManagerState> = _managerState

    private val _characteristicNotifications = MutableSharedFlow<CharacteristicNotification>(extraBufferCapacity = 64)
    override val characteristicNotifications: SharedFlow<CharacteristicNotification> = _characteristicNotifications

    private val _connectionStateUpdates = MutableSharedFlow<ConnectionStateUpdate>(extraBufferCapacity = 64)
    override val connectionStateUpdates: SharedFlow<ConnectionStateUpdate> = _connectionStateUpdates

    private val _serviceDiscoveryUpdates = MutableSharedFlow<ServiceDiscoveryUpdate>(extraBufferCapacity = 64)
    override val serviceDiscoveryUpdates: SharedFlow<ServiceDiscoveryUpdate> = _serviceDiscoveryUpdates
    
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

    // Number of remaining calls that should fail before the operation succeeds. Decremented on
    // each invocation while > 0, allowing tests to simulate transient failures that eventually
    // resolve (e.g. to exercise retry plugins).
    var failConnectTimes = 0
    var failReadTimes = 0
    var failWriteTimes = 0
    var connectCallCount = 0
    var readCallCount = 0
    var writeCallCount = 0
    var typedWriteResult: CharacteristicWriteResult = CharacteristicWriteResult.Unsupported
    var typedWriteFailure: Throwable? = null
    var lastTypedWriteValue: ByteArray? = null
    var lastTypedWriteType: CharacteristicWriteType? = null
    var subscriptionResult: NotificationSubscriptionResult =
        NotificationSubscriptionResult.Unsupported
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
        connectCallCount++
        if (shouldFailConnect || failConnectTimes > 0) {
            if (failConnectTimes > 0) failConnectTimes--
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
        readCallCount++
        if (shouldFailRead || failReadTimes > 0) {
            if (failReadTimes > 0) failReadTimes--
            throw BluetoothUnknownException()
        }
    }
    
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        writeCallCount++
        if (shouldFailWrite || failWriteTimes > 0) {
            if (failWriteTimes > 0) failWriteTimes--
            throw BluetoothUnknownException()
        }
    }
    
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
        if (shouldFailWrite) {
            throw BluetoothUnknownException()
        }
    }

    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: CharacteristicWriteType,
    ): CharacteristicWriteResult {
        typedWriteFailure?.let { throw it }
        lastTypedWriteValue = value.copyOf()
        lastTypedWriteType = writeType
        return typedWriteResult
    }

    override suspend fun setNotificationSubscription(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        enabled: Boolean,
    ): NotificationSubscriptionResult = subscriptionResult
    
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
    
    var lastL2capPsm: Int? = null
    var lastL2capSecure: Boolean? = null

    override suspend fun openL2capChannel(
        peripheral: BluetoothPeripheral,
        psm: Int,
        secure: Boolean
    ): BluetoothSocket {
        lastL2capPsm = psm
        lastL2capSecure = secure
        return FakeBluetoothSocket(psm, peripheral)
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
        failConnectTimes = 0
        failReadTimes = 0
        failWriteTimes = 0
        connectCallCount = 0
        readCallCount = 0
        writeCallCount = 0
        typedWriteResult = CharacteristicWriteResult.Unsupported
        typedWriteFailure = null
        lastTypedWriteValue = null
        lastTypedWriteType = null
        subscriptionResult = NotificationSubscriptionResult.Unsupported
        isScanning = false
        _peripherals.value = emptySet()
        _managerState.value = BluetoothManagerState.Ready
    }

    suspend fun emitCharacteristicNotification(notification: CharacteristicNotification) {
        _characteristicNotifications.emit(notification)
    }

    suspend fun emitConnectionStateUpdate(update: ConnectionStateUpdate) {
        _connectionStateUpdates.emit(update)
    }

    suspend fun emitServiceDiscoveryUpdate(update: ServiceDiscoveryUpdate) {
        _serviceDiscoveryUpdates.emit(update)
    }
}

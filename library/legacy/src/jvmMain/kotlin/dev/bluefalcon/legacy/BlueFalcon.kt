package dev.bluefalcon.legacy

import dev.bluefalcon.core.*
import dev.bluefalcon.engine.windows.WindowsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * JVM implementation of legacy BlueFalcon API.
 * Uses WindowsEngine by default, but can use RpiEngine on Linux ARM platforms.
 */
actual class BlueFalcon actual constructor(
    log: Logger?,
    context: ApplicationContext,
    autoDiscoverAllServicesAndCharacteristics: Boolean
) {
    
    actual val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    actual val delegates: MutableSet<BlueFalconDelegate> = mutableSetOf()
    
    private val coreLogger = log?.let { LegacyLoggerAdapter(it) }
    
    // Use WindowsEngine for JVM by default
    // On specific platforms, users can manually instantiate with RpiEngine if needed
    private val engine = WindowsEngine()
    private val client = dev.bluefalcon.core.BlueFalcon(engine)
    
    actual var isScanning: Boolean
        get() = engine.isScanning
        set(_) {} // Read-only in practice
    
    actual val managerState: StateFlow<BluetoothManagerState>
        get() = engine.managerState.toLegacyManagerState(scope)
    
    actual val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    actual val peripherals: NativeFlow<Set<BluetoothPeripheral>> = engine.peripherals.toNativeType(scope)
    
    init {
        // Forward peripherals updates
        scope.launch {
            engine.peripherals.collect { newPeripherals ->
                _peripherals.value = newPeripherals
                newPeripherals.forEach { peripheral ->
                    delegates.forEach { it.didDiscoverDevice(peripheral, emptyMap()) }
                }
            }
        }
    }
    
    actual fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean) {
        scope.launch {
            try {
                client.connect(bluetoothPeripheral, autoConnect)
                delegates.forEach { it.didConnect(bluetoothPeripheral) }
            } catch (e: Exception) {
                delegates.forEach { it.didDisconnect(bluetoothPeripheral) }
            }
        }
    }
    
    actual fun disconnect(bluetoothPeripheral: BluetoothPeripheral) {
        scope.launch {
            client.disconnect(bluetoothPeripheral)
            delegates.forEach { it.didDisconnect(bluetoothPeripheral) }
        }
    }
    
    actual fun retrievePeripheral(identifier: String): BluetoothPeripheral? {
        return engine.retrievePeripheral(identifier)
    }
    
    actual fun requestConnectionPriority(
        bluetoothPeripheral: BluetoothPeripheral,
        connectionPriority: ConnectionPriority
    ) {
        engine.requestConnectionPriority(bluetoothPeripheral, connectionPriority)
    }
    
    actual fun connectionState(bluetoothPeripheral: BluetoothPeripheral): BluetoothPeripheralState {
        return engine.connectionState(bluetoothPeripheral)
    }
    
    actual fun scan(filters: List<ServiceFilter>) {
        scope.launch {
            try {
                client.scan(filters)
            } catch (e: Exception) {
                throw e
            }
        }
    }
    
    actual fun clearPeripherals() {
        engine.clearPeripherals()
    }
    
    actual fun stopScanning() {
        scope.launch {
            client.stopScanning()
        }
    }
    
    actual fun refreshGattCache(bluetoothPeripheral: BluetoothPeripheral): Boolean {
        // Not supported on Windows/RPI
        return false
    }
    
    actual fun discoverServices(bluetoothPeripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid>) {
        scope.launch {
            try {
                client.discoverServices(bluetoothPeripheral, serviceUUIDs)
                delegates.forEach { it.didDiscoverServices(bluetoothPeripheral) }
            } catch (e: Exception) {
                delegates.forEach { it.didFailToDiscoverServices(bluetoothPeripheral, -1) }
            }
        }
    }
    
    actual fun discoverCharacteristics(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothService: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {
        scope.launch {
            client.discoverCharacteristics(bluetoothPeripheral, bluetoothService, characteristicUUIDs)
            delegates.forEach { it.didDiscoverCharacteristics(bluetoothPeripheral) }
        }
    }
    
    actual fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        scope.launch {
            client.readCharacteristic(bluetoothPeripheral, bluetoothCharacteristic)
            delegates.forEach { it.didCharacteristcValueChanged(bluetoothPeripheral, bluetoothCharacteristic) }
        }
    }
    
    actual fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        scope.launch {
            client.notifyCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, notify)
            delegates.forEach { it.didUpdateNotificationStateFor(bluetoothPeripheral, bluetoothCharacteristic) }
        }
    }
    
    actual fun indicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        scope.launch {
            client.indicateCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, indicate)
            delegates.forEach { it.didUpdateNotificationStateFor(bluetoothPeripheral, bluetoothCharacteristic) }
        }
    }
    
    actual fun notifyAndIndicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        enable: Boolean
    ) {
        scope.launch {
            if (enable) {
                client.notifyCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, true)
                client.indicateCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, true)
            } else {
                client.notifyCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, false)
                client.indicateCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, false)
            }
            delegates.forEach { it.didUpdateNotificationStateFor(bluetoothPeripheral, bluetoothCharacteristic) }
        }
    }
    
    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
        scope.launch {
            try {
                client.writeCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, value, writeType)
                delegates.forEach { it.didWriteCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, true) }
            } catch (e: Exception) {
                delegates.forEach { it.didWriteCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, false) }
            }
        }
    }
    
    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        scope.launch {
            try {
                client.writeCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, value, writeType)
                delegates.forEach { it.didWriteCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, true) }
            } catch (e: Exception) {
                delegates.forEach { it.didWriteCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, false) }
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
        scope.launch {
            client.readDescriptor(bluetoothPeripheral, bluetoothCharacteristic, bluetoothCharacteristicDescriptor)
            delegates.forEach { it.didReadDescriptor(bluetoothPeripheral, bluetoothCharacteristicDescriptor) }
        }
    }
    
    actual fun writeDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {
        scope.launch {
            client.writeDescriptor(bluetoothPeripheral, bluetoothCharacteristicDescriptor, value)
            delegates.forEach { it.didWriteDescriptor(bluetoothPeripheral, bluetoothCharacteristicDescriptor) }
        }
    }
    
    actual fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int) {
        scope.launch {
            client.changeMTU(bluetoothPeripheral, mtuSize)
            delegates.forEach { it.didUpdateMTU(bluetoothPeripheral, 0) }
        }
    }
    
    actual fun openL2capChannel(bluetoothPeripheral: BluetoothPeripheral, psm: Int) {
        scope.launch {
            try {
                client.openL2capChannel(bluetoothPeripheral, psm)
                delegates.forEach { it.didOpenL2capChannel(bluetoothPeripheral, null) }
            } catch (e: Exception) {
                delegates.forEach { it.didOpenL2capChannel(bluetoothPeripheral, null) }
            }
        }
    }
    
    actual fun createBond(bluetoothPeripheral: BluetoothPeripheral) {
        scope.launch {
            client.createBond(bluetoothPeripheral)
            delegates.forEach { it.didBondStateChanged(bluetoothPeripheral, BlueFalconBondState.Bonding) }
        }
    }
    
    actual fun removeBond(bluetoothPeripheral: BluetoothPeripheral) {
        scope.launch {
            client.removeBond(bluetoothPeripheral)
            delegates.forEach { it.didBondStateChanged(bluetoothPeripheral, BlueFalconBondState.None) }
        }
    }
    
    actual fun destroy() {
        // Cleanup resources
    }
}

/**
 * Adapter to convert legacy Logger to core Logger
 */
private class LegacyLoggerAdapter(private val legacyLogger: Logger) : dev.bluefalcon.core.Logger {
    override fun debug(message: String, cause: Throwable?) = legacyLogger.log("DEBUG: $message ${cause?.message ?: ""}")
    override fun info(message: String, cause: Throwable?) = legacyLogger.log("INFO: $message ${cause?.message ?: ""}")
    override fun warn(message: String, cause: Throwable?) = legacyLogger.log("WARN: $message ${cause?.message ?: ""}")
    override fun error(message: String, cause: Throwable?) = legacyLogger.log("ERROR: $message ${cause?.message ?: ""}")
}

/**
 * Convert core BluetoothManagerState to legacy BluetoothManagerState
 */
private fun StateFlow<dev.bluefalcon.core.BluetoothManagerState>.toLegacyManagerState(scope: CoroutineScope): StateFlow<BluetoothManagerState> {
    val mutableFlow = MutableStateFlow(
        when (value) {
            dev.bluefalcon.core.BluetoothManagerState.Ready -> BluetoothManagerState.Ready
            dev.bluefalcon.core.BluetoothManagerState.NotReady -> BluetoothManagerState.NotReady
        }
    )
    scope.launch {
        collect { state ->
            mutableFlow.value = when (state) {
                dev.bluefalcon.core.BluetoothManagerState.Ready -> BluetoothManagerState.Ready
                dev.bluefalcon.core.BluetoothManagerState.NotReady -> BluetoothManagerState.NotReady
            }
        }
    }
    return mutableFlow
}

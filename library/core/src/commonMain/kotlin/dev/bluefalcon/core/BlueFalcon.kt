package dev.bluefalcon.core

import dev.bluefalcon.core.plugin.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

/**
 * Main Blue Falcon client that wraps an engine and provides plugin support
 */
class BlueFalcon(
    val engine: BlueFalconEngine
) : BlueFalconClient {

    /**
     * Plugin registry for managing installed plugins
     */
    val plugins: PluginRegistry = PluginRegistry()

    init {
        engine.scope.launch {
            engine.characteristicNotifications.collect { notification ->
                plugins.dispatchNotification(
                    NotificationCall(
                        peripheral = notification.peripheral,
                        characteristic = notification.characteristic,
                        value = notification.value
                    )
                )
            }
        }
    }
    
    /**
     * Delegated properties from engine
     */
    val peripherals: StateFlow<Set<BluetoothPeripheral>> get() = engine.peripherals
    val managerState: StateFlow<BluetoothManagerState> get() = engine.managerState
    val isScanning: Boolean get() = engine.isScanning
    
    /**
     * Scan for BLE devices
     */
    suspend fun scan(filters: List<ServiceFilter> = emptyList()) {
        plugins.interceptScan(ScanCall(filters)) { call ->
            engine.scan(call.filters)
        }
    }
    
    /**
     * Stop scanning
     */
    suspend fun stopScanning() {
        engine.stopScanning()
    }
    
    /**
     * Clear discovered peripherals
     */
    fun clearPeripherals() {
        engine.clearPeripherals()
    }
    
    /**
     * Connect to a peripheral
     */
    suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean = false) {
        plugins.interceptConnect(ConnectCall(peripheral, autoConnect)) { call ->
            runCatching {
                engine.connect(call.peripheral, call.autoConnect)
            }
        }
    }
    
    /**
     * Disconnect from a peripheral
     */
    suspend fun disconnect(peripheral: BluetoothPeripheral) {
        plugins.interceptDisconnect(DisconnectCall(peripheral)) { call ->
            runCatching {
                engine.disconnect(call.peripheral)
            }
        }
    }
    
    /**
     * Get connection state
     */
    fun connectionState(peripheral: BluetoothPeripheral): BluetoothPeripheralState {
        return engine.connectionState(peripheral)
    }
    
    /**
     * Retrieve peripheral by identifier
     */
    fun retrievePeripheral(identifier: String): BluetoothPeripheral? {
        return engine.retrievePeripheral(identifier)
    }
    
    /**
     * Request connection priority
     */
    fun requestConnectionPriority(peripheral: BluetoothPeripheral, priority: ConnectionPriority) {
        engine.requestConnectionPriority(peripheral, priority)
    }
    
    /**
     * Discover services
     */
    suspend fun discoverServices(peripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid> = emptyList()) {
        engine.discoverServices(peripheral, serviceUUIDs)
    }
    
    /**
     * Discover characteristics
     */
    suspend fun discoverCharacteristics(
        peripheral: BluetoothPeripheral,
        service: BluetoothService,
        characteristicUUIDs: List<Uuid> = emptyList()
    ) {
        engine.discoverCharacteristics(peripheral, service, characteristicUUIDs)
    }
    
    /**
     * Read characteristic
     */
    suspend fun readCharacteristic(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic) {
        plugins.interceptRead(ReadCall(peripheral, characteristic)) { call ->
            runCatching {
                engine.readCharacteristic(call.peripheral, call.characteristic)
                call.characteristic.value
            }
        }
    }
    
    /**
     * Write characteristic (string)
     */
    suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int? = null
    ) {
        writeCharacteristic(peripheral, characteristic, value.encodeToByteArray(), writeType)
    }
    
    /**
     * Write characteristic (bytes)
     */
    suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int? = null
    ) {
        plugins.interceptWrite(WriteCall(peripheral, characteristic, value, writeType)) { call ->
            runCatching {
                engine.writeCharacteristic(call.peripheral, call.characteristic, call.value, call.writeType)
            }
        }
    }
    
    /**
     * Enable/disable notifications
     */
    suspend fun notifyCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        engine.notifyCharacteristic(peripheral, characteristic, notify)
    }
    
    /**
     * Enable/disable indications
     */
    suspend fun indicateCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        engine.indicateCharacteristic(peripheral, characteristic, indicate)
    }
    
    /**
     * Read descriptor
     */
    suspend fun readDescriptor(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        descriptor: BluetoothCharacteristicDescriptor
    ) {
        engine.readDescriptor(peripheral, characteristic, descriptor)
    }
    
    /**
     * Write descriptor
     */
    suspend fun writeDescriptor(
        peripheral: BluetoothPeripheral,
        descriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {
        engine.writeDescriptor(peripheral, descriptor, value)
    }
    
    /**
     * Change MTU
     */
    suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int) {
        engine.changeMTU(peripheral, mtuSize)
    }
    
    /**
     * Refresh GATT cache
     */
    fun refreshGattCache(peripheral: BluetoothPeripheral): Boolean {
        return engine.refreshGattCache(peripheral)
    }
    
    /**
     * Open L2CAP channel
     */
    suspend fun openL2capChannel(peripheral: BluetoothPeripheral, psm: Int) {
        engine.openL2capChannel(peripheral, psm)
    }
    
    /**
     * Create bond
     */
    suspend fun createBond(peripheral: BluetoothPeripheral) {
        engine.createBond(peripheral)
    }
    
    /**
     * Remove bond
     */
    suspend fun removeBond(peripheral: BluetoothPeripheral) {
        engine.removeBond(peripheral)
    }
}

/**
 * Configuration class for BlueFalcon DSL
 */
class BlueFalconConfig {
    lateinit var engine: BlueFalconEngine
    internal val pluginConfigs = mutableListOf<Pair<BlueFalconPlugin, PluginConfig.() -> Unit>>()
    
    /**
     * Install a plugin
     */
    fun <T : BlueFalconPlugin> install(plugin: T, configure: PluginConfig.() -> Unit = {}) {
        pluginConfigs.add(plugin to configure)
    }
}

/**
 * DSL function for creating BlueFalcon with configuration
 */
fun BlueFalcon(block: BlueFalconConfig.() -> Unit): BlueFalcon {
    val config = BlueFalconConfig().apply(block)
    val client = BlueFalcon(config.engine)
    
    // Install all configured plugins
    config.pluginConfigs.forEach { (plugin, configure) ->
        plugin.install(client, PluginConfig().apply(configure))
        client.plugins.install(plugin, configure)
    }
    
    return client
}

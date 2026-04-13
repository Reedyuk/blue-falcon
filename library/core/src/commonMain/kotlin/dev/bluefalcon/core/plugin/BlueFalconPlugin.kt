package dev.bluefalcon.core.plugin

import dev.bluefalcon.core.*

/**
 * Base interface for Blue Falcon plugins
 */
interface BlueFalconPlugin {
    /**
     * Install the plugin into the client
     */
    fun install(client: BlueFalconClient, config: PluginConfig)
    
    /**
     * Called before a scan operation
     */
    suspend fun onBeforeScan(call: ScanCall): ScanCall = call
    
    /**
     * Called after a scan operation
     */
    suspend fun onAfterScan(call: ScanCall) {}
    
    /**
     * Called before a connect operation
     */
    suspend fun onBeforeConnect(call: ConnectCall): ConnectCall = call
    
    /**
     * Called after a connect operation
     */
    suspend fun onAfterConnect(call: ConnectCall, result: Result<Unit>) {}
    
    /**
     * Called before a read operation
     */
    suspend fun onBeforeRead(call: ReadCall): ReadCall = call
    
    /**
     * Called after a read operation
     */
    suspend fun onAfterRead(call: ReadCall, result: Result<ByteArray?>) {}
    
    /**
     * Called before a write operation
     */
    suspend fun onBeforeWrite(call: WriteCall): WriteCall = call
    
    /**
     * Called after a write operation
     */
    suspend fun onAfterWrite(call: WriteCall, result: Result<Unit>) {}

    /**
     * Called before a disconnect operation
     */
    suspend fun onBeforeDisconnect(call: DisconnectCall): DisconnectCall = call

    /**
     * Called after a disconnect operation
     */
    suspend fun onAfterDisconnect(call: DisconnectCall, result: Result<Unit>) {}
}

/**
 * Base class for plugin configurations
 */
open class PluginConfig

/**
 * Scan operation call
 */
data class ScanCall(
    val filters: List<ServiceFilter>
)

/**
 * Connect operation call
 */
data class ConnectCall(
    val peripheral: BluetoothPeripheral,
    val autoConnect: Boolean
)

/**
 * Disconnect operation call
 */
data class DisconnectCall(
    val peripheral: BluetoothPeripheral
)

/**
 * Read operation call
 */
data class ReadCall(
    val peripheral: BluetoothPeripheral,
    val characteristic: BluetoothCharacteristic
)

/**
 * Write operation call
 */
data class WriteCall(
    val peripheral: BluetoothPeripheral,
    val characteristic: BluetoothCharacteristic,
    val value: ByteArray,
    val writeType: Int?
)

/**
 * Forward declaration for BlueFalconClient
 */
interface BlueFalconClient

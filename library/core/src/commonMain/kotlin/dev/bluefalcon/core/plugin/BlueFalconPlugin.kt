package dev.bluefalcon.core.plugin

import dev.bluefalcon.core.*
import kotlin.time.Duration

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

    suspend fun onBeforeCentralWrite(call: CentralWriteCall): CentralWriteCall = call

    suspend fun onAfterCentralWrite(
        call: CentralWriteCall,
        result: CharacteristicWriteResult,
    ) {}

    /**
     * Called before a disconnect operation
     */
    suspend fun onBeforeDisconnect(call: DisconnectCall): DisconnectCall = call

    /**
     * Called after a disconnect operation
     */
    suspend fun onAfterDisconnect(call: DisconnectCall, result: Result<Unit>) {}

    /**
     * Called when a characteristic notification/indication payload is received.
     */
    suspend fun onNotificationReceived(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray
    ) {}
}

/**
 * The kind of operation a [RetryCapable] plugin is being asked to retry.
 */
enum class RetryableOperation {
    CONNECT,
    READ,
    WRITE
}

/**
 * Optional capability that a [BlueFalconPlugin] can implement to actually drive retries of
 * failed operations. The [PluginRegistry] consults every installed [RetryCapable] plugin after
 * an operation fails and, if any of them return a non-null delay, re-invokes the underlying
 * platform operation after waiting for that delay.
 */
interface RetryCapable {
    /**
     * Called after [operation] fails with [error].
     *
     * @param attempt zero-based index of the retry being considered (0 for the first retry
     * following the initial failed attempt).
     * @return the [Duration] to wait before retrying, or `null` if this operation should not be
     * retried (either because the error isn't retryable or the retry budget is exhausted).
     */
    suspend fun retryDelay(operation: RetryableOperation, attempt: Int, error: Throwable): Duration?
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

data class CentralWriteCall(
    val peripheral: BluetoothPeripheral,
    val characteristic: BluetoothCharacteristic,
    val value: ByteArray,
    val writeType: CharacteristicWriteType,
)

data class NotificationCall(
    val peripheral: BluetoothPeripheral,
    val characteristic: BluetoothCharacteristic,
    val value: ByteArray
)

/**
 * Forward declaration for BlueFalconClient
 */
interface BlueFalconClient

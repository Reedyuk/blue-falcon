package dev.bluefalcon.plugins.retry

import dev.bluefalcon.core.*
import dev.bluefalcon.core.plugin.*
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Plugin that automatically retries failed BLE operations.
 * 
 * Usage:
 * ```
 * install(RetryPlugin) {
 *     maxRetries = 3
 *     initialDelay = 500.milliseconds
 *     maxDelay = 5.seconds
 *     backoffMultiplier = 2.0
 *     retryOn = { error -> error is BluetoothException }
 * }
 * ```
 */
class RetryPlugin(private val config: Config) : BlueFalconPlugin {
    
    /**
     * Configuration for the retry plugin
     */
    class Config : PluginConfig() {
        /**
         * Maximum number of retry attempts
         */
        var maxRetries: Int = 3
        
        /**
         * Initial delay before first retry
         */
        var initialDelay: Duration = 500.milliseconds
        
        /**
         * Maximum delay between retries
         */
        var maxDelay: Duration = 5.seconds
        
        /**
         * Multiplier for exponential backoff
         */
        var backoffMultiplier: Double = 2.0
        
        /**
         * Predicate to determine if an error should trigger a retry
         */
        var retryOn: (Throwable) -> Boolean = { true }
        
        /**
         * Whether to retry connect operations
         */
        var retryConnect: Boolean = true
        
        /**
         * Whether to retry read operations
         */
        var retryRead: Boolean = true
        
        /**
         * Whether to retry write operations
         */
        var retryWrite: Boolean = true
    }
    
    override fun install(client: BlueFalconClient, config: PluginConfig) {
        // Plugin installed
    }
    
    override suspend fun onBeforeConnect(call: ConnectCall): ConnectCall {
        return call
    }
    
    override suspend fun onAfterConnect(call: ConnectCall, result: Result<Unit>) {
        if (config.retryConnect && result.isFailure) {
            val error = result.exceptionOrNull()
            if (error != null && config.retryOn(error)) {
                retryOperation("connect", config.maxRetries) {
                    // In a real implementation, this would trigger the actual connect operation
                    // For now, this is a placeholder showing the retry logic structure
                    result.getOrThrow()
                }
            }
        }
    }
    
    override suspend fun onBeforeRead(call: ReadCall): ReadCall {
        return call
    }
    
    override suspend fun onAfterRead(call: ReadCall, result: Result<ByteArray?>) {
        if (config.retryRead && result.isFailure) {
            val error = result.exceptionOrNull()
            if (error != null && config.retryOn(error)) {
                retryOperation("read", config.maxRetries) {
                    // Placeholder for actual read retry
                    result.getOrThrow()
                }
            }
        }
    }
    
    override suspend fun onBeforeWrite(call: WriteCall): WriteCall {
        return call
    }
    
    override suspend fun onAfterWrite(call: WriteCall, result: Result<Unit>) {
        if (config.retryWrite && result.isFailure) {
            val error = result.exceptionOrNull()
            if (error != null && config.retryOn(error)) {
                retryOperation("write", config.maxRetries) {
                    // Placeholder for actual write retry
                    result.getOrThrow()
                }
            }
        }
    }
    
    /**
     * Executes an operation with retry logic and exponential backoff
     */
    private suspend fun <T> retryOperation(
        operationName: String,
        maxRetries: Int,
        operation: suspend () -> T
    ): Result<T> {
        var currentDelay = config.initialDelay
        var lastError: Throwable? = null
        
        repeat(maxRetries) { attempt ->
            try {
                val result = operation()
                return Result.success(result)
            } catch (e: Throwable) {
                lastError = e
                
                if (attempt < maxRetries - 1) {
                    // Wait before retrying with exponential backoff
                    delay(currentDelay)
                    
                    // Calculate next delay with backoff
                    currentDelay = minOf(
                        (currentDelay.inWholeMilliseconds * config.backoffMultiplier).toLong().milliseconds,
                        config.maxDelay
                    )
                }
            }
        }
        
        return Result.failure(lastError ?: Exception("Operation failed after $maxRetries retries"))
    }
    
    companion object {
        /**
         * Creates a new RetryPlugin instance with the given configuration
         */
        fun create(configure: Config.() -> Unit = {}): RetryPlugin {
            val config = Config().apply(configure)
            return RetryPlugin(config)
        }
    }
}

/**
 * Exception types that can be retried
 */
sealed class RetryableException : Exception() {
    /**
     * Connection timeout
     */
    class ConnectionTimeout : RetryableException()
    
    /**
     * Device not available
     */
    class DeviceNotAvailable : RetryableException()
    
    /**
     * GATT error
     */
    class GattError(val code: Int) : RetryableException()
}

/**
 * DSL function to install retry plugin
 */
fun installRetry(configure: RetryPlugin.Config.() -> Unit): RetryPlugin {
    return RetryPlugin.create(configure)
}

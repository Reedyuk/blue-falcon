package dev.bluefalcon.plugins.retry

import dev.bluefalcon.core.*
import dev.bluefalcon.core.plugin.*
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Plugin that automatically retries failed BLE operations with exponential backoff.
 *
 * The plugin implements [RetryCapable], which the [PluginRegistry] consults after a connect,
 * read, or write operation fails. When installed, failed operations are genuinely re-invoked
 * against the underlying platform engine (not just re-reported) until they succeed, the retry
 * budget is exhausted, or [retryOn] rejects the error.
 *
 * Usage:
 * ```
 * install(RetryPlugin.create {
 *     maxRetries = 3
 *     initialDelay = 500.milliseconds
 *     maxDelay = 5.seconds
 *     backoffMultiplier = 2.0
 *     retryOn = { error -> error is BluetoothException }
 * })
 * ```
 */
class RetryPlugin(private val config: Config) : BlueFalconPlugin, RetryCapable {

    /**
     * Configuration for the retry plugin
     */
    class Config : PluginConfig() {
        /**
         * Maximum number of attempts made for an operation (the initial attempt plus retries).
         * A value of 3 means the initial attempt followed by up to 2 retries.
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
        // Plugin installed; retry behaviour is driven entirely through RetryCapable.retryDelay,
        // which PluginRegistry invokes directly.
    }

    override suspend fun retryDelay(
        operation: RetryableOperation,
        attempt: Int,
        error: Throwable
    ): Duration? {
        val enabled = when (operation) {
            RetryableOperation.CONNECT -> config.retryConnect
            RetryableOperation.READ -> config.retryRead
            RetryableOperation.WRITE -> config.retryWrite
        }
        if (!enabled) return null
        if (attempt >= config.maxRetries - 1) return null
        if (!config.retryOn(error)) return null

        val scaledDelayMs = config.initialDelay.inWholeMilliseconds *
            config.backoffMultiplier.pow(attempt)
        val delayMs = scaledDelayMs.toLong().coerceAtMost(config.maxDelay.inWholeMilliseconds)
        return delayMs.milliseconds
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

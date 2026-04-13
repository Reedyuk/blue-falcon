package dev.bluefalcon.plugins.logging

import dev.bluefalcon.core.*
import dev.bluefalcon.core.plugin.*

/**
 * Plugin that logs all BLE operations for debugging purposes.
 * 
 * Usage:
 * ```
 * install(LoggingPlugin) {
 *     level = LogLevel.DEBUG
 *     logger = PrintLnLogger
 *     logDiscovery = true
 *     logConnections = true
 *     logGattOperations = true
 *     logErrors = true
 * }
 * ```
 */
class LoggingPlugin(private val config: Config) : BlueFalconPlugin {
    
    /**
     * Configuration for the logging plugin
     */
    class Config : PluginConfig() {
        /**
         * Minimum log level to output
         */
        var level: LogLevel = LogLevel.DEBUG
        
        /**
         * Logger implementation to use
         */
        var logger: Logger = PrintLnLogger
        
        /**
         * Log device discovery operations
         */
        var logDiscovery: Boolean = true
        
        /**
         * Log connection/disconnection operations
         */
        var logConnections: Boolean = true
        
        /**
         * Log GATT read/write operations
         */
        var logGattOperations: Boolean = true
        
        /**
         * Log errors and exceptions
         */
        var logErrors: Boolean = true
    }
    
    override fun install(client: BlueFalconClient, config: PluginConfig) {
        log(LogLevel.INFO, "LoggingPlugin installed")
    }
    
    override suspend fun onBeforeScan(call: ScanCall): ScanCall {
        if (config.logDiscovery) {
            log(LogLevel.DEBUG, "Starting scan with ${call.filters.size} filters")
        }
        return call
    }
    
    override suspend fun onAfterScan(call: ScanCall) {
        if (config.logDiscovery) {
            log(LogLevel.DEBUG, "Scan completed")
        }
    }
    
    override suspend fun onBeforeConnect(call: ConnectCall): ConnectCall {
        if (config.logConnections) {
            log(LogLevel.DEBUG, "Connecting to peripheral: ${call.peripheral.uuid} (autoConnect=${call.autoConnect})")
        }
        return call
    }
    
    override suspend fun onAfterConnect(call: ConnectCall, result: Result<Unit>) {
        if (config.logConnections) {
            result.fold(
                onSuccess = { 
                    log(LogLevel.INFO, "Connected to peripheral: ${call.peripheral.uuid}") 
                },
                onFailure = { error ->
                    if (config.logErrors) {
                        log(LogLevel.ERROR, "Failed to connect to ${call.peripheral.uuid}: ${error.message}")
                    }
                }
            )
        }
    }
    
    override suspend fun onBeforeRead(call: ReadCall): ReadCall {
        if (config.logGattOperations) {
            log(LogLevel.DEBUG, "Reading characteristic ${call.characteristic.uuid} from ${call.peripheral.uuid}")
        }
        return call
    }
    
    override suspend fun onAfterRead(call: ReadCall, result: Result<ByteArray?>) {
        if (config.logGattOperations) {
            result.fold(
                onSuccess = { value ->
                    val size = value?.size ?: 0
                    log(LogLevel.DEBUG, "Read ${size} bytes from ${call.characteristic.uuid}")
                },
                onFailure = { error ->
                    if (config.logErrors) {
                        log(LogLevel.ERROR, "Failed to read ${call.characteristic.uuid}: ${error.message}")
                    }
                }
            )
        }
    }
    
    override suspend fun onBeforeWrite(call: WriteCall): WriteCall {
        if (config.logGattOperations) {
            log(LogLevel.DEBUG, "Writing ${call.value.size} bytes to ${call.characteristic.uuid}")
        }
        return call
    }
    
    override suspend fun onAfterWrite(call: WriteCall, result: Result<Unit>) {
        if (config.logGattOperations) {
            result.fold(
                onSuccess = {
                    log(LogLevel.DEBUG, "Successfully wrote ${call.value.size} bytes to ${call.characteristic.uuid}")
                },
                onFailure = { error ->
                    if (config.logErrors) {
                        log(LogLevel.ERROR, "Failed to write to ${call.characteristic.uuid}: ${error.message}")
                    }
                }
            )
        }
    }
    
    private fun log(level: LogLevel, message: String) {
        if (level.priority >= config.level.priority) {
            config.logger.log(level, message)
        }
    }
    
    companion object : PluginFactory {
        override fun create(config: PluginConfig): BlueFalconPlugin {
            return LoggingPlugin(config as Config)
        }
    }
}

/**
 * Log levels in order of severity
 */
enum class LogLevel(val priority: Int) {
    DEBUG(0),
    INFO(1),
    WARN(2),
    ERROR(3)
}

/**
 * Logger interface for custom implementations
 */
interface Logger {
    fun log(level: LogLevel, message: String)
}

/**
 * Default logger that prints to console/println
 */
object PrintLnLogger : Logger {
    override fun log(level: LogLevel, message: String) {
        println("[BlueFalcon] [${level.name}] $message")
    }
}

/**
 * Factory interface for creating plugins
 */
interface PluginFactory {
    fun create(config: PluginConfig): BlueFalconPlugin
}

/**
 * DSL function to install logging plugin
 */
fun install(factory: PluginFactory, configure: LoggingPlugin.Config.() -> Unit): BlueFalconPlugin {
    val config = LoggingPlugin.Config().apply(configure)
    return factory.create(config)
}

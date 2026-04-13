package dev.bluefalcon.example.plugin

import dev.bluefalcon.core.*
import dev.bluefalcon.core.plugin.BlueFalconPlugin
import kotlinx.coroutines.delay

/**
 * Example custom plugin: Connection Timeout Plugin
 * 
 * This plugin demonstrates how to create a custom Blue Falcon plugin that:
 * - Implements timeout logic for connections
 * - Uses the interceptor pattern
 * - Maintains internal state
 * - Provides configuration options
 */
class ConnectionTimeoutPlugin(private val config: Config) : BlueFalconPlugin {
    
    /**
     * Configuration for the timeout plugin
     */
    data class Config(
        val timeoutMillis: Long = 10_000, // 10 seconds default
        val onTimeout: ((BluetoothPeripheral) -> Unit)? = null
    )
    
    // Internal state to track connection attempts
    private val connectionAttempts = mutableMapOf<String, Long>()
    
    override val name: String = "ConnectionTimeout"
    
    /**
     * Called when the plugin is installed
     */
    override fun install(blueFalcon: BlueFalcon) {
        println("[$name] Plugin installed with ${config.timeoutMillis}ms timeout")
    }
    
    /**
     * Intercept connect operations to add timeout logic
     */
    override suspend fun onBeforeConnect(
        peripheral: BluetoothPeripheral,
        autoConnect: Boolean,
        next: suspend (BluetoothPeripheral, Boolean) -> Unit
    ) {
        // Track when connection started
        connectionAttempts[peripheral.uuid] = System.currentTimeMillis()
        
        println("[$name] Connecting to ${peripheral.name} with timeout...")
        
        // Start timeout checker
        kotlinx.coroutines.GlobalScope.launch {
            delay(config.timeoutMillis)
            
            // Check if still connecting
            connectionAttempts[peripheral.uuid]?.let { startTime ->
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= config.timeoutMillis) {
                    println("[$name] Connection to ${peripheral.name} timed out after ${elapsed}ms")
                    config.onTimeout?.invoke(peripheral)
                    connectionAttempts.remove(peripheral.uuid)
                }
            }
        }
        
        // Continue with actual connection
        next(peripheral, autoConnect)
    }
    
    /**
     * Clean up when connection succeeds
     */
    override suspend fun onAfterConnect(
        peripheral: BluetoothPeripheral,
        autoConnect: Boolean
    ) {
        connectionAttempts.remove(peripheral.uuid)
        println("[$name] Connection to ${peripheral.name} succeeded")
    }
    
    /**
     * Clean up on disconnect
     */
    override suspend fun onAfterDisconnect(peripheral: BluetoothPeripheral) {
        connectionAttempts.remove(peripheral.uuid)
        println("[$name] Cleaned up timeout tracker for ${peripheral.name}")
    }
}

/**
 * DSL function for easy plugin installation
 */
fun BlueFalconConfig.connectionTimeout(
    configure: ConnectionTimeoutPlugin.Config.() -> Unit = {}
) {
    val config = ConnectionTimeoutPlugin.Config().apply(configure)
    install(ConnectionTimeoutPlugin(config))
}

// =====================================================
// Example Usage
// =====================================================

/**
 * Example 1: Basic usage with default timeout
 */
fun example1() {
    val blueFalcon = BlueFalcon {
        engine = null // Set your platform engine here
        
        // Install with defaults (10 second timeout)
        install(ConnectionTimeoutPlugin(ConnectionTimeoutPlugin.Config()))
    }
}

/**
 * Example 2: Custom timeout with callback
 */
fun example2() {
    val blueFalcon = BlueFalcon {
        engine = null // Set your platform engine here
        
        // Install with custom configuration
        install(ConnectionTimeoutPlugin(ConnectionTimeoutPlugin.Config(
            timeoutMillis = 5000, // 5 seconds
            onTimeout = { peripheral ->
                println("Failed to connect to ${peripheral.name}")
                // You could retry, show error UI, etc.
            }
        )))
    }
}

/**
 * Example 3: Using DSL helper function
 */
fun example3() {
    val blueFalcon = BlueFalcon {
        engine = null // Set your platform engine here
        
        // Use DSL helper
        connectionTimeout {
            timeoutMillis = 15000
            onTimeout = { peripheral ->
                println("Timeout connecting to ${peripheral.name}")
            }
        }
    }
}

/**
 * Example 4: Combining with other plugins
 */
fun example4() {
    val blueFalcon = BlueFalcon {
        engine = null // Set your platform engine here
        
        // Multiple plugins work together
        install(LoggingPlugin(LoggingPlugin.Config(
            level = LogLevel.DEBUG
        )))
        
        install(RetryPlugin(RetryPlugin.Config(
            maxAttempts = 3
        )))
        
        connectionTimeout {
            timeoutMillis = 8000
            onTimeout = { peripheral ->
                println("Connection timeout: ${peripheral.name}")
            }
        }
    }
}

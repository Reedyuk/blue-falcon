package dev.bluefalcon.plugins.example

import dev.bluefalcon.plugins.logging.*
import dev.bluefalcon.plugins.retry.*
import dev.bluefalcon.plugins.caching.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Example demonstrating how to use all three core plugins together.
 * 
 * This example shows:
 * 1. Installing multiple plugins
 * 2. Configuring each plugin with specific settings
 * 3. How plugins work together to enhance BLE operations
 */
class PluginUsageExample {
    
    fun createBlueFalconWithPlugins() {
        // Note: This is a conceptual example showing plugin configuration
        // The actual BlueFalcon client initialization would happen in the 
        // platform-specific engine implementations
        
        // Example 1: Install all three plugins with default settings
        val loggingPlugin = LoggingPlugin(LoggingPlugin.Config())
        val retryPlugin = RetryPlugin.create { }
        val cachingPlugin = CachingPlugin.create { }
        
        // Example 2: Install with custom configurations
        val customLogging = LoggingPlugin(LoggingPlugin.Config().apply {
            level = LogLevel.INFO  // Only log INFO and above
            logger = PrintLnLogger
            logDiscovery = false    // Don't log discovery
            logConnections = true   // Log connections
            logGattOperations = true // Log GATT operations
            logErrors = true        // Log errors
        })
        
        val customRetry = RetryPlugin.create {
            maxRetries = 5
            initialDelay = 1000.milliseconds
            maxDelay = 10.seconds
            backoffMultiplier = 1.5
            retryConnect = true
            retryRead = true
            retryWrite = true
            retryOn = { error ->
                // Only retry on specific errors
                error.message?.contains("timeout") == true ||
                error.message?.contains("disconnected") == true
            }
        }
        
        val customCaching = CachingPlugin.create {
            cacheServices = true
            cacheCharacteristics = true
            cacheDuration = 10.minutes
            invalidateOnDisconnect = true
            maxCachedPeripherals = 100
        }
        
        // Example 3: Production configuration
        // Logging: Error-level only for production
        val prodLogging = LoggingPlugin(LoggingPlugin.Config().apply {
            level = LogLevel.ERROR
            logErrors = true
            logConnections = false
            logGattOperations = false
        })
        
        // Retry: Aggressive retry for critical operations
        val prodRetry = RetryPlugin.create {
            maxRetries = 5
            initialDelay = 200.milliseconds
            maxDelay = 3.seconds
            backoffMultiplier = 2.0
        }
        
        // Caching: Long-lived cache
        val prodCaching = CachingPlugin.create {
            cacheDuration = 30.minutes
            maxCachedPeripherals = 200
        }
        
        println("✅ Plugins configured successfully!")
        println("   • Logging: ${customLogging.javaClass.simpleName}")
        println("   • Retry: ${customRetry.javaClass.simpleName}")
        println("   • Caching: ${customCaching.javaClass.simpleName}")
    }
    
    /**
     * Example showing how plugins enhance a typical BLE workflow
     */
    fun demonstratePluginBenefits() {
        println("""
            Plugin Benefits Demonstration
            =============================
            
            Without Plugins:
            ---------------
            1. Connect to device
               → If fails: Operation fails immediately
               → No visibility into what happened
               
            2. Discover services
               → Every time, even for same device
               → No caching of results
               
            3. Read characteristic
               → If fails: Operation fails immediately
               → No automatic retry
            
            
            With All Three Plugins:
            ----------------------
            1. Connect to device
               → [Logging] "Connecting to device XYZ..."
               → [Retry] If fails, automatically retry up to 3 times
               → [Logging] "Connected after 2 attempts"
               → [Caching] Cache initialized for device
               
            2. Discover services
               → [Caching] Check if already cached
               → [Logging] "Using cached services (age: 2 min)"
               → Skip expensive discovery operation!
               
            3. Read characteristic
               → [Caching] Check cache first
               → [Logging] "Reading characteristic ABC..."
               → [Retry] If fails, retry with backoff
               → [Caching] Store result for future reads
               → [Logging] "Read 20 bytes from ABC"
            
            
            Result:
            -------
            ✅ Better reliability (automatic retries)
            ✅ Better performance (caching)
            ✅ Better debugging (logging)
            ✅ Less code (plugins handle cross-cutting concerns)
        """.trimIndent())
    }
    
    /**
     * Example of selective plugin usage
     */
    fun selectivePluginUsage() {
        // Development: Use all plugins for maximum visibility
        val devPlugins = listOf(
            LoggingPlugin(LoggingPlugin.Config().apply {
                level = LogLevel.DEBUG
            }),
            RetryPlugin.create { maxRetries = 2 },
            CachingPlugin.create { cacheDuration = 5.minutes }
        )
        
        // Production: Only retry and caching
        val prodPlugins = listOf(
            RetryPlugin.create { maxRetries = 3 },
            CachingPlugin.create { cacheDuration = 30.minutes }
        )
        
        // Testing: Only logging to verify behavior
        val testPlugins = listOf(
            LoggingPlugin(LoggingPlugin.Config().apply {
                level = LogLevel.DEBUG
                logger = PrintLnLogger
            })
        )
        
        println("Development: ${devPlugins.size} plugins active")
        println("Production: ${prodPlugins.size} plugins active")
        println("Testing: ${testPlugins.size} plugins active")
    }
}

/**
 * Run the examples
 */
fun main() {
    val example = PluginUsageExample()
    
    println("=".repeat(60))
    println("Blue Falcon Plugin System - Usage Examples")
    println("=".repeat(60))
    println()
    
    example.createBlueFalconWithPlugins()
    println()
    
    example.demonstratePluginBenefits()
    println()
    
    example.selectivePluginUsage()
    println()
    
    println("=".repeat(60))
    println("For more details, see PLUGINS_IMPLEMENTATION.md")
    println("=".repeat(60))
}

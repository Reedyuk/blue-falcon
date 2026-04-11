# Blue Falcon Plugin Development Guide

## Table of Contents

1. [Introduction](#introduction)
2. [How the Plugin System Works](#how-the-plugin-system-works)
3. [Creating Your First Plugin](#creating-your-first-plugin)
4. [Plugin Lifecycle](#plugin-lifecycle)
5. [Interceptor Pattern](#interceptor-pattern)
6. [Complete Plugin Examples](#complete-plugin-examples)
7. [Testing Plugins](#testing-plugins)
8. [Publishing Plugins](#publishing-plugins)
9. [Best Practices](#best-practices)
10. [Advanced Topics](#advanced-topics)

---

## Introduction

Blue Falcon 3.0's plugin system allows you to extend BLE functionality without modifying core library code. Plugins use an **interceptor pattern** to wrap BLE operations, enabling cross-cutting concerns like:

- 🔍 **Logging**: Debug BLE operations
- 🔄 **Retry logic**: Automatic retry with backoff
- 💾 **Caching**: Cache GATT services/characteristics
- 📊 **Analytics**: Track BLE usage patterns
- 🔐 **Security**: Encrypt/decrypt BLE data
- ⏱️ **Metrics**: Monitor operation performance
- 🎯 **Custom protocols**: Device-specific behavior

### Why Build a Plugin?

- ✅ **Reusable**: Share functionality across projects
- ✅ **Composable**: Combine multiple plugins
- ✅ **Non-invasive**: No core library modifications needed
- ✅ **Testable**: Easy to unit test in isolation
- ✅ **Shareable**: Publish to Maven for community use

---

## How the Plugin System Works

### Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    Application Code                      │
└─────────────────┬───────────────────────────────────────┘
                  │ call scan(), connect(), read(), etc.
                  ▼
         ┌────────────────────┐
         │   BlueFalcon Core  │
         └────────┬───────────┘
                  │ intercept operations
                  ▼
         ┌────────────────────┐
         │  Plugin Registry   │◄──── Installed Plugins
         │  - LoggingPlugin   │
         │  - RetryPlugin     │
         │  - CachingPlugin   │
         │  - YourPlugin      │
         └────────┬───────────┘
                  │ call chain
                  ▼
         ┌────────────────────┐
         │ BlueFalconEngine   │
         │  (Android, iOS...) │
         └────────────────────┘
```

### Interceptor Chain

When you call `blueFalcon.scan()`, the plugin system creates an interceptor chain:

```
Application → Plugin 1 → Plugin 2 → ... → Engine → Result
               ↓           ↓                  ↓
          onBeforeScan onBeforeScan      actual scan
               ↓           ↓                  ↓
          onAfterScan  onAfterScan      scan completed
```

Each plugin can:
1. **Modify the request** before it reaches the engine
2. **Execute logic** before/after the operation
3. **Transform the response** before returning to the app
4. **Handle errors** and retry failed operations

---

## Creating Your First Plugin

Let's build a simple **performance monitoring plugin** that measures BLE operation duration.

### Step 1: Implement BlueFalconPlugin

```kotlin
package com.example.plugins.performance

import dev.bluefalcon.core.plugin.*
import dev.bluefalcon.core.*
import kotlin.time.TimeSource
import kotlin.time.Duration

class PerformancePlugin(private val config: Config) : BlueFalconPlugin {
    
    class Config : PluginConfig() {
        var onMeasure: (operation: String, duration: Duration) -> Unit = { _, _ -> }
        var logSlowOperations: Boolean = true
        var slowThreshold: Duration = 1.seconds
    }
    
    override fun install(client: BlueFalconClient, config: PluginConfig) {
        println("PerformancePlugin installed")
    }
    
    override suspend fun onBeforeScan(call: ScanCall): ScanCall {
        // Called before scan starts
        return call
    }
    
    override suspend fun onAfterScan(call: ScanCall) {
        // Called after scan completes
    }
    
    override suspend fun onBeforeConnect(call: ConnectCall): ConnectCall {
        // Track start time (store in call metadata if needed)
        return call
    }
    
    override suspend fun onAfterConnect(call: ConnectCall, result: Result<Unit>) {
        // Measure duration and report
    }
    
    override suspend fun onBeforeRead(call: ReadCall): ReadCall {
        return call
    }
    
    override suspend fun onAfterRead(call: ReadCall, result: Result<ByteArray?>) {
        // Measure read duration
    }
    
    override suspend fun onBeforeWrite(call: WriteCall): WriteCall {
        return call
    }
    
    override suspend fun onAfterWrite(call: WriteCall, result: Result<Unit>) {
        // Measure write duration
    }
}
```

### Step 2: Install the Plugin

```kotlin
val blueFalcon = BlueFalcon {
    engine = AndroidBlueFalconEngine(context)
    
    install(PerformancePlugin(PerformancePlugin.Config().apply {
        onMeasure = { operation, duration ->
            println("$operation took ${duration.inWholeMilliseconds}ms")
        }
        slowThreshold = 500.milliseconds
    }))
}
```

### Step 3: Use It!

```kotlin
lifecycleScope.launch {
    blueFalcon.scan() // Plugin logs: "scan took 245ms"
    blueFalcon.connect(peripheral) // Plugin logs: "connect took 1523ms"
}
```

---

## Plugin Lifecycle

### Installation Phase

```kotlin
class MyPlugin : BlueFalconPlugin {
    override fun install(client: BlueFalconClient, config: PluginConfig) {
        // Called once when plugin is installed
        // Use this to:
        // - Validate configuration
        // - Initialize resources
        // - Set up listeners
        println("Plugin installed with config: $config")
    }
}
```

### Operation Interception

Plugins intercept these BLE operations:

| Operation | Before Hook | After Hook |
|-----------|-------------|------------|
| **Scan** | `onBeforeScan(call)` | `onAfterScan(call)` |
| **Connect** | `onBeforeConnect(call)` | `onAfterConnect(call, result)` |
| **Read** | `onBeforeRead(call)` | `onAfterRead(call, result)` |
| **Write** | `onBeforeWrite(call)` | `onAfterWrite(call, result)` |

### Hook Execution Order

```kotlin
// Application calls
blueFalcon.connect(peripheral)

// Execution order:
1. Plugin1.onBeforeConnect(call)
2. Plugin2.onBeforeConnect(call)
3. Engine.connect(peripheral)  ← Actual BLE operation
4. Plugin2.onAfterConnect(call, result)
5. Plugin1.onAfterConnect(call, result)
```

---

## Interceptor Pattern

### Modifying Requests

Change operation parameters before they reach the engine:

```kotlin
class FilterPlugin(private val config: Config) : BlueFalconPlugin {
    class Config : PluginConfig() {
        var allowedDevices: Set<String> = emptySet()
    }
    
    override suspend fun onBeforeConnect(call: ConnectCall): ConnectCall {
        // Only allow connections to whitelisted devices
        if (call.peripheral.name !in config.allowedDevices) {
            throw SecurityException("Device not whitelisted: ${call.peripheral.name}")
        }
        return call
    }
}
```

### Transforming Responses

Modify data after operations complete:

```kotlin
class DecryptionPlugin : BlueFalconPlugin {
    override suspend fun onAfterRead(call: ReadCall, result: Result<ByteArray?>) {
        result.onSuccess { encryptedData ->
            val decrypted = decrypt(encryptedData ?: return@onSuccess)
            // Update characteristic value with decrypted data
            call.characteristic.value = decrypted
        }
    }
    
    private fun decrypt(data: ByteArray): ByteArray {
        // Your decryption logic
        return data // simplified
    }
}
```

### Error Handling

Catch and handle errors:

```kotlin
class ErrorReportingPlugin(private val config: Config) : BlueFalconPlugin {
    class Config : PluginConfig() {
        var errorReporter: (Throwable) -> Unit = { println(it) }
    }
    
    override suspend fun onAfterConnect(call: ConnectCall, result: Result<Unit>) {
        result.onFailure { error ->
            config.errorReporter(error)
            // Optionally send to analytics, crash reporting, etc.
        }
    }
}
```

---

## Complete Plugin Examples

### Example 1: Rate Limiting Plugin

Prevent too many operations in a short time:

```kotlin
package com.example.plugins.ratelimit

import dev.bluefalcon.core.plugin.*
import dev.bluefalcon.core.*
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class RateLimitPlugin(private val config: Config) : BlueFalconPlugin {
    
    class Config : PluginConfig() {
        var minDelay: Duration = 100.milliseconds
        var maxOperationsPerSecond: Int = 10
    }
    
    private var lastOperationTime = TimeSource.Monotonic.markNow()
    private var operationCount = 0
    private var windowStart = TimeSource.Monotonic.markNow()
    
    override fun install(client: BlueFalconClient, config: PluginConfig) {
        println("RateLimitPlugin installed: ${this.config.maxOperationsPerSecond} ops/sec")
    }
    
    override suspend fun onBeforeRead(call: ReadCall): ReadCall {
        enforceRateLimit()
        return call
    }
    
    override suspend fun onBeforeWrite(call: WriteCall): WriteCall {
        enforceRateLimit()
        return call
    }
    
    private suspend fun enforceRateLimit() {
        val now = TimeSource.Monotonic.markNow()
        
        // Reset counter every second
        if ((now - windowStart) > 1.seconds) {
            operationCount = 0
            windowStart = now
        }
        
        // Check rate limit
        if (operationCount >= config.maxOperationsPerSecond) {
            val waitTime = 1.seconds - (now - windowStart)
            delay(waitTime)
            operationCount = 0
            windowStart = TimeSource.Monotonic.markNow()
        }
        
        // Enforce minimum delay between operations
        val timeSinceLastOp = now - lastOperationTime
        if (timeSinceLastOp < config.minDelay) {
            delay(config.minDelay - timeSinceLastOp)
        }
        
        operationCount++
        lastOperationTime = TimeSource.Monotonic.markNow()
    }
}

// Usage
val blueFalcon = BlueFalcon {
    engine = AndroidBlueFalconEngine(context)
    
    install(RateLimitPlugin(RateLimitPlugin.Config().apply {
        minDelay = 50.milliseconds
        maxOperationsPerSecond = 20
    }))
}
```

### Example 2: Encryption Plugin

Encrypt writes, decrypt reads:

```kotlin
package com.example.plugins.encryption

import dev.bluefalcon.core.plugin.*
import dev.bluefalcon.core.*

class EncryptionPlugin(private val config: Config) : BlueFalconPlugin {
    
    class Config : PluginConfig() {
        lateinit var encryptionKey: ByteArray
        var algorithm: String = "AES"
    }
    
    override fun install(client: BlueFalconClient, config: PluginConfig) {
        require(this.config.encryptionKey.isNotEmpty()) {
            "Encryption key must be provided"
        }
    }
    
    override suspend fun onBeforeWrite(call: WriteCall): WriteCall {
        // Encrypt data before writing
        val encrypted = encrypt(call.value)
        return call.copy(value = encrypted)
    }
    
    override suspend fun onAfterRead(call: ReadCall, result: Result<ByteArray?>) {
        // Decrypt data after reading
        result.onSuccess { encryptedData ->
            encryptedData?.let {
                val decrypted = decrypt(it)
                call.characteristic.value = decrypted
            }
        }
    }
    
    private fun encrypt(data: ByteArray): ByteArray {
        // Simple XOR encryption (use proper crypto in production!)
        return data.mapIndexed { i, byte ->
            (byte.toInt() xor config.encryptionKey[i % config.encryptionKey.size].toInt()).toByte()
        }.toByteArray()
    }
    
    private fun decrypt(data: ByteArray): ByteArray {
        // XOR is symmetric
        return encrypt(data)
    }
}

// Usage
val blueFalcon = BlueFalcon {
    engine = AndroidBlueFalconEngine(context)
    
    install(EncryptionPlugin(EncryptionPlugin.Config().apply {
        encryptionKey = "my-secret-key-1234".encodeToByteArray()
    }))
}

// All writes are encrypted, all reads are decrypted automatically!
blueFalcon.writeCharacteristic(peripheral, characteristic, "sensitive data")
```

### Example 3: Analytics Plugin

Track BLE usage for analytics:

```kotlin
package com.example.plugins.analytics

import dev.bluefalcon.core.plugin.*
import dev.bluefalcon.core.*

class AnalyticsPlugin(private val config: Config) : BlueFalconPlugin {
    
    class Config : PluginConfig() {
        var trackScans: Boolean = true
        var trackConnections: Boolean = true
        var trackGattOperations: Boolean = true
        var analyticsEndpoint: String = ""
    }
    
    private var scanCount = 0
    private var connectionCount = 0
    private var readCount = 0
    private var writeCount = 0
    
    override fun install(client: BlueFalconClient, config: PluginConfig) {
        println("AnalyticsPlugin installed")
    }
    
    override suspend fun onBeforeScan(call: ScanCall): ScanCall {
        if (config.trackScans) {
            scanCount++
            trackEvent("scan_started", mapOf("filters" to call.filters.size))
        }
        return call
    }
    
    override suspend fun onAfterConnect(call: ConnectCall, result: Result<Unit>) {
        if (config.trackConnections) {
            connectionCount++
            val status = if (result.isSuccess) "success" else "failure"
            trackEvent("connection_attempt", mapOf(
                "device" to call.peripheral.name,
                "status" to status,
                "auto_connect" to call.autoConnect
            ))
        }
    }
    
    override suspend fun onAfterRead(call: ReadCall, result: Result<ByteArray?>) {
        if (config.trackGattOperations) {
            readCount++
            trackEvent("characteristic_read", mapOf(
                "characteristic" to call.characteristic.uuid.toString(),
                "success" to result.isSuccess,
                "bytes" to (result.getOrNull()?.size ?: 0)
            ))
        }
    }
    
    override suspend fun onAfterWrite(call: WriteCall, result: Result<Unit>) {
        if (config.trackGattOperations) {
            writeCount++
            trackEvent("characteristic_write", mapOf(
                "characteristic" to call.characteristic.uuid.toString(),
                "success" to result.isSuccess,
                "bytes" to call.value.size
            ))
        }
    }
    
    private fun trackEvent(eventName: String, properties: Map<String, Any>) {
        println("📊 Analytics: $eventName - $properties")
        // Send to your analytics service
        // analytics.track(eventName, properties)
    }
    
    fun getStats(): Map<String, Int> = mapOf(
        "scans" to scanCount,
        "connections" to connectionCount,
        "reads" to readCount,
        "writes" to writeCount
    )
}
```

---

## Testing Plugins

### Unit Testing

```kotlin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class PerformancePluginTest {
    
    @Test
    fun `should measure operation duration`() = runTest {
        var measuredOperation: String? = null
        var measuredDuration: Duration? = null
        
        val plugin = PerformancePlugin(PerformancePlugin.Config().apply {
            onMeasure = { op, duration ->
                measuredOperation = op
                measuredDuration = duration
            }
        })
        
        val call = ReadCall(
            peripheral = mockPeripheral(),
            characteristic = mockCharacteristic()
        )
        
        plugin.onBeforeRead(call)
        delay(100) // Simulate operation
        plugin.onAfterRead(call, Result.success(byteArrayOf(0x01)))
        
        assertEquals("read", measuredOperation)
        assertTrue(measuredDuration!! > 90.milliseconds)
    }
}
```

### Integration Testing

```kotlin
class PluginIntegrationTest {
    
    @Test
    fun `should install and use plugin`() = runTest {
        val context = mockContext()
        var loggedMessage: String? = null
        
        val blueFalcon = BlueFalcon {
            engine = AndroidBlueFalconEngine(context)
            
            install(LoggingPlugin(LoggingPlugin.Config().apply {
                logger = object : Logger {
                    override fun log(level: LogLevel, message: String) {
                        loggedMessage = message
                    }
                }
            }))
        }
        
        blueFalcon.scan()
        
        assertNotNull(loggedMessage)
        assertTrue(loggedMessage!!.contains("scan"))
    }
}
```

---

## Publishing Plugins

### Step 1: Create Gradle Module

```kotlin
// library/plugins/my-plugin/build.gradle.kts
plugins {
    kotlin("multiplatform")
    id("maven-publish")
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":core"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
            }
        }
        
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "dev.bluefalcon"
            artifactId = "blue-falcon-plugin-my-plugin"
            version = "3.0.0"
        }
    }
}
```

### Step 2: Document Your Plugin

Create a README.md:

```markdown
# Blue Falcon My Plugin

## Installation

```kotlin
commonMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-plugin-my-plugin:3.0.0")
}
```

## Usage

```kotlin
val blueFalcon = BlueFalcon {
    engine = AndroidBlueFalconEngine(context)
    
    install(MyPlugin(MyPlugin.Config().apply {
        // configuration
    }))
}
```

## Configuration Options

- `option1`: Description
- `option2`: Description
```

### Step 3: Publish to Maven

```bash
./gradlew :plugins:my-plugin:publishToMavenLocal
# or publish to Maven Central
./gradlew :plugins:my-plugin:publish
```

---

## Best Practices

### 1. Keep Plugins Focused

✅ **Good**: Single responsibility

```kotlin
class LoggingPlugin // Only handles logging
class RetryPlugin   // Only handles retries
```

❌ **Bad**: Multiple responsibilities

```kotlin
class LoggingAndRetryPlugin // Does too much
```

### 2. Make Configuration Optional

```kotlin
class MyPlugin(private val config: Config = Config()) : BlueFalconPlugin {
    class Config : PluginConfig() {
        var enabled: Boolean = true  // Sensible defaults
        var level: Int = 1
    }
}
```

### 3. Avoid Blocking Operations

✅ **Good**: Use suspend functions

```kotlin
override suspend fun onBeforeRead(call: ReadCall): ReadCall {
    delay(100) // Non-blocking
    return call
}
```

❌ **Bad**: Block threads

```kotlin
override suspend fun onBeforeRead(call: ReadCall): ReadCall {
    Thread.sleep(100) // Blocks thread!
    return call
}
```

### 4. Handle Errors Gracefully

```kotlin
override suspend fun onAfterRead(call: ReadCall, result: Result<ByteArray?>) {
    result.fold(
        onSuccess = { data -> 
            // Handle success
        },
        onFailure = { error ->
            // Log error, don't throw unless critical
            logger.error("Read failed: ${error.message}")
        }
    )
}
```

### 5. Document Your Plugin

Include:
- Purpose and use cases
- Configuration options
- Code examples
- Performance impact
- Platform compatibility

---

## Advanced Topics

### Custom Plugin Interfaces

Extend `BlueFalconPlugin` for custom hooks:

```kotlin
interface AdvancedPlugin : BlueFalconPlugin {
    suspend fun onServiceDiscovered(service: BluetoothService) {}
    suspend fun onNotificationReceived(characteristic: BluetoothCharacteristic) {}
}
```

### Plugin Dependencies

One plugin can depend on another:

```kotlin
class MetricsPlugin(
    private val loggingPlugin: LoggingPlugin
) : BlueFalconPlugin {
    override fun install(client: BlueFalconClient, config: PluginConfig) {
        // Use loggingPlugin functionality
        loggingPlugin.log("MetricsPlugin installed")
    }
}
```

### Conditional Plugin Loading

```kotlin
val blueFalcon = BlueFalcon {
    engine = AndroidBlueFalconEngine(context)
    
    if (BuildConfig.DEBUG) {
        install(LoggingPlugin(LoggingPlugin.Config().apply {
            level = LogLevel.DEBUG
        }))
    }
    
    install(RetryPlugin(RetryPlugin.Config()))
}
```

### Plugin Communication

Plugins can communicate through shared state:

```kotlin
object PluginState {
    var connectionAttempts = 0
}

class TrackerPlugin : BlueFalconPlugin {
    override suspend fun onBeforeConnect(call: ConnectCall): ConnectCall {
        PluginState.connectionAttempts++
        return call
    }
}

class ReporterPlugin : BlueFalconPlugin {
    override suspend fun onAfterConnect(call: ConnectCall, result: Result<Unit>) {
        println("Total attempts: ${PluginState.connectionAttempts}")
    }
}
```

---

## Resources

- **API Reference**: [API_REFERENCE.md](API_REFERENCE.md)
- **Migration Guide**: [MIGRATION_GUIDE.md](MIGRATION_GUIDE.md)
- **Example Plugins**: `library/plugins/`
- **GitHub Issues**: [Report bugs/request features](https://github.com/Reedyuk/blue-falcon/issues)

---

**Happy Plugin Development!** 🔌

Need help? Open an issue or discussion on GitHub.

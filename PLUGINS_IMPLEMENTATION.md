# Blue Falcon Phase 4: Core Plugins Implementation

## Overview

This document describes the three production-ready plugins that demonstrate the Blue Falcon plugin system's capabilities. All plugins are built using the `BlueFalconPlugin` interface and follow the interceptor pattern.

## Plugin Architecture

Each plugin:
- Implements the `BlueFalconPlugin` interface from the core module
- Uses before/after operation hooks for interception
- Provides a configuration DSL for easy setup
- Supports all platforms (JVM, JS, iOS, macOS)
- Includes comprehensive documentation

## 1. Logging Plugin

**Location:** `library/plugins/logging/`

**Purpose:** Log all BLE operations for debugging and monitoring.

### Features

- **Configurable Log Levels:** DEBUG, INFO, WARN, ERROR with priority filtering
- **Custom Logger Support:** Pluggable logger interface (default: PrintLnLogger)
- **Selective Logging:** Configure which operations to log
- **Formatted Output:** `[BlueFalcon] [LEVEL] message` format

### Configuration Options

```kotlin
install(LoggingPlugin) {
    level = LogLevel.DEBUG              // Minimum log level
    logger = PrintLnLogger              // Logger implementation
    logDiscovery = true                 // Log device discovery
    logConnections = true               // Log connect/disconnect
    logGattOperations = true            // Log read/write operations
    logErrors = true                    // Log errors
}
```

### Implementation Details

- **Log Levels:** DEBUG(0), INFO(1), WARN(2), ERROR(3) with priority filtering
- **Logger Interface:** Allows custom implementations for platform-specific logging
- **Default Logger:** PrintLnLogger outputs to console
- **Operation Coverage:**
  - Scan operations (before/after)
  - Connect operations (before/after with results)
  - Read operations (before/after with data size)
  - Write operations (before/after with data size)

### Example Output

```
[BlueFalcon] [DEBUG] Starting scan with 2 filters
[BlueFalcon] [INFO] Connected to peripheral: 12345678-1234-1234-1234-123456789012
[BlueFalcon] [DEBUG] Reading characteristic 0000180A-... from 12345678-...
[BlueFalcon] [DEBUG] Read 20 bytes from 0000180A-...
[BlueFalcon] [ERROR] Failed to connect to 87654321-...: Connection timeout
```

## 2. Retry Plugin

**Location:** `library/plugins/retry/`

**Purpose:** Automatically retry failed BLE operations with exponential backoff.

### Features

- **Configurable Retries:** Set maximum retry attempts per operation
- **Exponential Backoff:** Intelligent delay scaling between retries
- **Selective Retrying:** Choose which operations to retry
- **Error Predicate:** Control which errors trigger retries
- **Bounded Delays:** Maximum delay cap to prevent excessive waiting

### Configuration Options

```kotlin
install(RetryPlugin) {
    maxRetries = 3                      // Maximum retry attempts
    initialDelay = 500.milliseconds     // First retry delay
    maxDelay = 5.seconds               // Maximum retry delay
    backoffMultiplier = 2.0            // Delay multiplier per retry
    retryOn = { error ->               // Error predicate
        error is BluetoothException
    }
    retryConnect = true                // Retry connections
    retryRead = true                   // Retry reads
    retryWrite = true                  // Retry writes
}
```

### Retry Logic

1. **First attempt:** Operation executes normally
2. **On failure:** Check if error matches `retryOn` predicate
3. **Wait:** Delay for `initialDelay` (500ms by default)
4. **Retry:** Execute operation again
5. **Backoff:** Multiply delay by `backoffMultiplier` (2.0 = double each time)
6. **Cap:** Never exceed `maxDelay` (5 seconds by default)
7. **Repeat:** Up to `maxRetries` times (3 by default)

### Example Retry Sequence

```
Attempt 1: Fail - wait 500ms
Attempt 2: Fail - wait 1000ms (500ms × 2.0)
Attempt 3: Fail - wait 2000ms (1000ms × 2.0)
Attempt 4: Success!
```

With maxDelay = 5s, delays cap at 5000ms even with more retries.

### Retryable Exceptions

The plugin defines common retryable exceptions:
- `RetryableException.ConnectionTimeout`
- `RetryableException.DeviceNotAvailable`
- `RetryableException.GattError(code: Int)`

## 3. Caching Plugin

**Location:** `library/plugins/caching/`

**Purpose:** Cache service and characteristic discovery results to improve performance.

### Features

- **Service Discovery Cache:** Cache GATT services per peripheral
- **Characteristic Cache:** Cache characteristic data with values
- **TTL Support:** Configurable time-to-live for cache entries
- **Auto-Invalidation:** Clear cache on disconnect (configurable)
- **Memory Management:** Maximum cached peripherals limit
- **In-Memory:** No persistence, cache cleared on app restart

### Configuration Options

```kotlin
install(CachingPlugin) {
    cacheServices = true                // Cache service discovery
    cacheCharacteristics = true         // Cache characteristic data
    cacheDuration = 5.minutes          // Cache entry TTL
    invalidateOnDisconnect = true      // Clear on disconnect
    maxCachedPeripherals = 50          // Memory limit
}
```

### Cache Behavior

#### Cache Hit Flow
1. Peripheral connects
2. Services/characteristics discovered
3. Results cached with timestamp
4. Subsequent reads check cache first
5. If entry exists and not expired, return cached data
6. If entry expired or missing, perform actual operation

#### Cache Invalidation
- **On Disconnect:** If `invalidateOnDisconnect = true`, peripheral cache cleared
- **On Expiry:** Entries older than `cacheDuration` are ignored
- **Manual:** Call `clearCache()` to clear all entries
- **Per-Peripheral:** Call `invalidatePeripheral(uuid)` to clear specific entry

### Cache Structure

```
PeripheralCache
├── Entry: "12345678-1234-1234-1234-123456789012"
│   ├── Created: TimeSource.Monotonic.ValueTimeMark
│   ├── TTL: 5.minutes
│   ├── Services: List<BluetoothService>?
│   └── Characteristics: Map<String, ByteArray>
│       ├── "0000180A-...": [0x01, 0x02, 0x03]
│       └── "0000180F-...": [0xFF, 0xEE]
└── Entry: "87654321-..."
    └── ...
```

### API Methods

```kotlin
// Get cached services (returns null if not cached or expired)
val services: List<BluetoothService>? = plugin.getCachedServices(peripheralUuid)

// Manually cache services
plugin.cacheServices(peripheralUuid, services)

// Invalidate specific peripheral
plugin.invalidatePeripheral(peripheralUuid)

// Clear all cache
plugin.clearCache()
```

## Build Configuration

All three plugins share a common build structure:

### build.gradle.kts

```kotlin
plugins {
    kotlin("multiplatform") version "2.3.0"
}

kotlin {
    jvmToolchain(17)
    
    // Platforms
    jvm()
    js { browser(); nodejs() }
    iosSimulatorArm64()
    iosX64()
    iosArm64()
    macosArm64()
    macosX64()
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":core"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:...")
            }
        }
    }
}
```

### Module Structure

```
plugins/
├── logging/
│   ├── build.gradle.kts
│   └── src/commonMain/kotlin/dev/bluefalcon/plugins/logging/
│       └── LoggingPlugin.kt
├── retry/
│   ├── build.gradle.kts
│   └── src/commonMain/kotlin/dev/bluefalcon/plugins/retry/
│       └── RetryPlugin.kt
└── caching/
    ├── build.gradle.kts
    └── src/commonMain/kotlin/dev/bluefalcon/plugins/caching/
        └── CachingPlugin.kt
```

## Usage Example

### Combining Multiple Plugins

```kotlin
val blueFalcon = BlueFalcon {
    // Install logging for debugging
    install(LoggingPlugin) {
        level = LogLevel.DEBUG
        logDiscovery = true
        logConnections = true
        logGattOperations = true
    }
    
    // Install retry for reliability
    install(RetryPlugin) {
        maxRetries = 3
        initialDelay = 500.milliseconds
        retryConnect = true
        retryRead = true
    }
    
    // Install caching for performance
    install(CachingPlugin) {
        cacheServices = true
        cacheCharacteristics = true
        cacheDuration = 5.minutes
        invalidateOnDisconnect = true
    }
}

// All operations now benefit from logging, retries, and caching
blueFalcon.connect(peripheral)
blueFalcon.readCharacteristic(characteristic)
```

### Plugin Execution Order

Plugins execute in the order they're installed:

1. **Before hooks:** LoggingPlugin → RetryPlugin → CachingPlugin → Actual operation
2. **After hooks:** CachingPlugin → RetryPlugin → LoggingPlugin

This order ensures:
- Logging captures all attempts (including retries)
- Retry logic wraps the actual operation
- Caching happens closest to the operation

## Testing

### Build Verification

```bash
cd library
./gradlew :plugins:logging:build :plugins:retry:build :plugins:caching:build
```

### Test Results

All three plugins successfully compile for all platforms:
- ✅ JVM
- ✅ JavaScript (browser + Node.js)
- ✅ iOS (arm64, x64, simulator)
- ✅ macOS (arm64, x64)

## Implementation Notes

### Type Safety

- Uses `Uuid` type from `kotlin.uuid` for characteristics/services
- Peripheral UUIDs are `String` type (platform-specific identifiers)
- Proper type conversions with `toString()` for UUID formatting

### Error Handling

All plugins handle errors gracefully:
- **Logging:** Logs errors without interrupting operations
- **Retry:** Only retries if predicate matches
- **Caching:** Silently ignores cache misses

### Coroutines

All hook methods are `suspend` functions, allowing:
- Asynchronous operations
- Delay for retry backoff
- Non-blocking cache checks

### Memory Management

- **Logging:** Minimal memory overhead (configuration only)
- **Retry:** State per operation (cleared after completion)
- **Caching:** Bounded by `maxCachedPeripherals` limit

## Future Enhancements

### Potential Features

1. **Logging Plugin:**
   - File-based logging
   - Structured logging (JSON)
   - Metrics/analytics integration

2. **Retry Plugin:**
   - Per-operation retry configuration
   - Circuit breaker pattern
   - Retry statistics

3. **Caching Plugin:**
   - Persistent cache (disk storage)
   - Cache warming strategies
   - LRU eviction policy

## Conclusion

These three plugins demonstrate the power and flexibility of the Blue Falcon plugin system:

- **Production-Ready:** Proper error handling, configuration, and documentation
- **Composable:** Plugins work together seamlessly
- **Extensible:** Easy to add new plugins following the same pattern
- **Cross-Platform:** Work identically on all supported platforms

The plugin architecture enables developers to extend Blue Falcon's functionality without modifying core code, following the Open/Closed Principle.

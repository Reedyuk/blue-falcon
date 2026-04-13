# Blue Falcon 3.0 Migration Guide

## Table of Contents

1. [Overview](#overview)
2. [What's New in 3.0](#whats-new-in-30)
3. [Why Migrate](#why-migrate)
4. [Migration Strategies](#migration-strategies)
5. [Breaking Changes](#breaking-changes)
6. [API Mapping: 2.x → 3.0](#api-mapping-2x--30)
7. [Common Migration Patterns](#common-migration-patterns)
8. [Platform-Specific Considerations](#platform-specific-considerations)
9. [Plugin Usage](#plugin-usage)
10. [FAQ](#faq)

---

## Overview

Blue Falcon 3.0 introduces a revolutionary plugin-based engine architecture that provides:

- **Modular design**: Separate core API from platform implementations
- **Plugin system**: Extend functionality with logging, retry, caching, and custom plugins
- **Improved performance**: Modern Kotlin coroutines and Flow APIs
- **Full backward compatibility**: Existing 2.x code continues to work without changes

**Good news**: Most applications can upgrade to 3.0 with **zero code changes** thanks to the compatibility layer.

---

## What's New in 3.0

### 🎯 Core Improvements

1. **Plugin-Based Architecture**: Separate core API from platform engines
   - `blue-falcon-core`: Platform-agnostic API
   - `blue-falcon-engine-{platform}`: Platform-specific implementations
   - Independent versioning and release cycles

2. **Plugin System**: Extend Blue Falcon with cross-cutting concerns
   - `LoggingPlugin`: Debug BLE operations
   - `RetryPlugin`: Automatic retry with exponential backoff
   - `CachingPlugin`: Cache GATT services and characteristics
   - Custom plugins: Build your own!

3. **Modern Kotlin APIs**:
   - Kotlin coroutines for async operations
   - StateFlow for reactive state management
   - Suspend functions for cleaner async code

4. **Backward Compatibility Layer**: 
   - Full 2.x API support via `blue-falcon-legacy` module
   - Delegate-based callbacks still supported
   - Zero-change migration path

---

## Why Migrate

### Benefits of Upgrading

| Feature | 2.x | 3.0 |
|---------|-----|-----|
| **Modular Architecture** | Monolithic | ✅ Modular engines |
| **Plugin Support** | ❌ | ✅ Built-in plugin system |
| **Kotlin Coroutines** | Limited | ✅ Full support |
| **StateFlow APIs** | ❌ | ✅ Reactive state |
| **Custom Engines** | ❌ | ✅ Easy to create |
| **Independent Releases** | ❌ | ✅ Per-platform |
| **Community Extensions** | ❌ | ✅ Supported |

### When to Migrate

- ✅ **Now**: If you want plugin support, better error handling, or modern APIs
- ✅ **Soon**: If you need custom BLE functionality or platform extensions
- ⏸️ **Later**: If your current 2.x code is stable and working perfectly

---

## Migration Strategies

Blue Falcon 3.0 offers three migration paths. Choose based on your needs:

### Strategy 1: Zero-Change Migration (Recommended for most apps)

**Best for**: Applications that want 3.0 benefits without code changes.

**Steps**:

1. Update your dependencies:

```kotlin
// Before (2.x)
commonMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon:2.0.0")
}

// After (3.0 - Legacy API)
commonMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-core:3.0.0")
    implementation("dev.bluefalcon:blue-falcon-legacy:3.0.0")
}

// Platform-specific engines
androidMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-engine-android:3.0.0")
}

iosMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-engine-ios:3.0.0")
}
```

2. That's it! Your 2.x code works as-is:

```kotlin
// Your existing 2.x code - no changes needed!
val blueFalcon = BlueFalcon(log = null, ApplicationContext())
blueFalcon.delegates.add(myDelegate)
blueFalcon.scan()
```

### Strategy 2: Gradual Migration (Recommended for new features)

**Best for**: Adding new features with 3.0 APIs while maintaining existing 2.x code.

**Approach**: Use both APIs side-by-side during transition.

```kotlin
// Existing 2.x code - keep using delegates
val legacyBlueFalcon = BlueFalcon(log = null, ApplicationContext())
legacyBlueFalcon.delegates.add(myDelegate)

// New code - use 3.0 Flow APIs
val modernBlueFalcon = dev.bluefalcon.core.BlueFalcon(
    engine = AndroidBlueFalconEngine(context)
)

// Collect peripherals with Flow
modernBlueFalcon.peripherals.collect { peripherals ->
    println("Found ${peripherals.size} devices")
}
```

**Timeline**: Migrate module-by-module or feature-by-feature at your own pace.

### Strategy 3: Full Migration (Best for new projects)

**Best for**: New projects or complete refactors wanting pure 3.0 APIs.

**Steps**:

1. Update dependencies (core + engine only, no legacy):

```kotlin
commonMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-core:3.0.0")
    // Plugins (optional)
    implementation("dev.bluefalcon:blue-falcon-plugin-logging:3.0.0")
    implementation("dev.bluefalcon:blue-falcon-plugin-retry:3.0.0")
}

androidMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-engine-android:3.0.0")
}
```

2. Use modern 3.0 APIs with DSL configuration:

```kotlin
import dev.bluefalcon.core.*
import dev.bluefalcon.plugins.logging.*
import dev.bluefalcon.plugins.retry.*

val blueFalcon = BlueFalcon {
    engine = AndroidBlueFalconEngine(context)
    
    install(LoggingPlugin) {
        level = LogLevel.DEBUG
        logConnections = true
        logGattOperations = true
    }
    
    install(RetryPlugin) {
        maxRetries = 3
        initialDelay = 500.milliseconds
    }
}

// Use coroutines and Flow
lifecycleScope.launch {
    blueFalcon.scan()
    
    blueFalcon.peripherals.collect { devices ->
        println("Discovered: ${devices.size} devices")
    }
}
```

---

## Breaking Changes

### ⚠️ None for Legacy API Users

If you're using Strategy 1 (Zero-Change Migration) with `blue-falcon-legacy`, there are **no breaking changes**.

### For Full 3.0 API Users (Strategy 3)

If migrating to pure 3.0 APIs:

1. **Package names changed**:
   - Old: `dev.bluefalcon.*`
   - New: `dev.bluefalcon.core.*`, `dev.bluefalcon.plugins.*`

2. **Constructor changes**:
   ```kotlin
   // 2.x
   BlueFalcon(log, context, autoDiscover)
   
   // 3.0
   BlueFalcon(engine = AndroidBlueFalconEngine(context))
   ```

3. **Delegates → Flow**:
   ```kotlin
   // 2.x - Delegates
   blueFalcon.delegates.add(object : BlueFalconDelegate {
       override fun didDiscoverDevice(device: BluetoothPeripheral) { }
   })
   
   // 3.0 - StateFlow
   blueFalcon.peripherals.collect { devices -> }
   ```

4. **Sync → Async**:
   ```kotlin
   // 2.x - Synchronous
   blueFalcon.scan()
   
   // 3.0 - Suspend functions
   suspend fun startScan() {
       blueFalcon.scan()
   }
   ```

---

## API Mapping: 2.x → 3.0

### Initialization

```kotlin
// 2.x
val blueFalcon = BlueFalcon(
    log = PrintLnLogger,
    context = ApplicationContext(),
    autoDiscoverAllServicesAndCharacteristics = true
)

// 3.0 Legacy (same as 2.x)
val blueFalcon = dev.bluefalcon.legacy.BlueFalcon(
    log = PrintLnLogger,
    context = ApplicationContext(),
    autoDiscoverAllServicesAndCharacteristics = true
)

// 3.0 Modern
val blueFalcon = BlueFalcon {
    engine = AndroidBlueFalconEngine(
        context = context,
        autoDiscoverServices = true
    )
}
```

### Scanning

```kotlin
// 2.x
blueFalcon.scan()
blueFalcon.stopScanning()

// 3.0 Modern
lifecycleScope.launch {
    blueFalcon.scan()
    delay(10.seconds)
    blueFalcon.stopScanning()
}
```

### Device Discovery (Delegates → Flow)

```kotlin
// 2.x - Delegate pattern
blueFalcon.delegates.add(object : BlueFalconDelegate {
    override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral) {
        println("Found: ${bluetoothPeripheral.name}")
    }
})

// 3.0 Modern - StateFlow
lifecycleScope.launch {
    blueFalcon.peripherals.collect { peripherals ->
        peripherals.forEach { device ->
            println("Found: ${device.name}")
        }
    }
}
```

### Connecting

```kotlin
// 2.x
blueFalcon.connect(peripheral)
blueFalcon.delegates.add(object : BlueFalconDelegate {
    override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
        println("Connected!")
    }
})

// 3.0 Modern
lifecycleScope.launch {
    try {
        blueFalcon.connect(peripheral)
        println("Connected!")
    } catch (e: BluetoothException) {
        println("Connection failed: ${e.message}")
    }
}
```

### Reading Characteristics

```kotlin
// 2.x
blueFalcon.readCharacteristic(peripheral, characteristic)
blueFalcon.delegates.add(object : BlueFalconDelegate {
    override fun didReadCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        val value = bluetoothCharacteristic.value
    }
})

// 3.0 Modern
lifecycleScope.launch {
    blueFalcon.readCharacteristic(peripheral, characteristic)
    val value = characteristic.value
    println("Read: ${value.decodeToString()}")
}
```

### Writing Characteristics

```kotlin
// 2.x
blueFalcon.writeCharacteristic(peripheral, characteristic, "Hello".encodeToByteArray())

// 3.0 Modern (supports String or ByteArray)
lifecycleScope.launch {
    blueFalcon.writeCharacteristic(peripheral, characteristic, "Hello")
    // or
    blueFalcon.writeCharacteristic(peripheral, characteristic, byteArrayOf(0x01, 0x02))
}
```

---

## Common Migration Patterns

### Pattern 1: Scanning for Devices

```kotlin
// 2.x
class DeviceScanner(context: ApplicationContext) : BlueFalconDelegate {
    private val blueFalcon = BlueFalcon(null, context)
    private val devices = mutableListOf<BluetoothPeripheral>()
    
    init {
        blueFalcon.delegates.add(this)
    }
    
    fun startScanning() {
        blueFalcon.scan()
    }
    
    override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral) {
        devices.add(bluetoothPeripheral)
    }
}

// 3.0 Modern
class DeviceScanner(context: Context) {
    private val blueFalcon = BlueFalcon {
        engine = AndroidBlueFalconEngine(context)
        install(LoggingPlugin) {
            level = LogLevel.DEBUG
        }
    }
    
    val devices: StateFlow<Set<BluetoothPeripheral>> = blueFalcon.peripherals
    
    suspend fun startScanning() {
        blueFalcon.scan()
    }
}

// Usage
lifecycleScope.launch {
    scanner.startScanning()
    scanner.devices.collect { peripherals ->
        updateUI(peripherals)
    }
}
```

### Pattern 2: Connect and Read

```kotlin
// 2.x
class DeviceManager(context: ApplicationContext) : BlueFalconDelegate {
    private val blueFalcon = BlueFalcon(null, context)
    private var onConnected: (() -> Unit)? = null
    
    init {
        blueFalcon.delegates.add(this)
    }
    
    fun connect(device: BluetoothPeripheral, callback: () -> Unit) {
        onConnected = callback
        blueFalcon.connect(device)
    }
    
    override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
        onConnected?.invoke()
    }
}

// 3.0 Modern
class DeviceManager(context: Context) {
    private val blueFalcon = BlueFalcon {
        engine = AndroidBlueFalconEngine(context)
        install(RetryPlugin) {
            maxRetries = 3
        }
    }
    
    suspend fun connectAndDiscover(device: BluetoothPeripheral): List<BluetoothService> {
        blueFalcon.connect(device)
        blueFalcon.discoverServices(device)
        return device.services
    }
}

// Usage
lifecycleScope.launch {
    try {
        val services = manager.connectAndDiscover(peripheral)
        println("Found ${services.size} services")
    } catch (e: BluetoothException) {
        println("Error: ${e.message}")
    }
}
```

### Pattern 3: Monitoring Connection State

```kotlin
// 2.x
class ConnectionMonitor : BlueFalconDelegate {
    override fun didUpdateState(state: BluetoothPeripheralState) {
        when (state) {
            BluetoothPeripheralState.Connected -> println("Connected")
            BluetoothPeripheralState.Disconnected -> println("Disconnected")
            else -> {}
        }
    }
}

// 3.0 Modern
class ConnectionMonitor(private val blueFalcon: BlueFalcon) {
    fun monitorState(peripheral: BluetoothPeripheral) = flow {
        while (true) {
            emit(blueFalcon.connectionState(peripheral))
            delay(1.seconds)
        }
    }
}

// Usage
lifecycleScope.launch {
    monitor.monitorState(peripheral).collect { state ->
        when (state) {
            BluetoothPeripheralState.Connected -> println("Connected")
            BluetoothPeripheralState.Disconnected -> println("Disconnected")
            else -> {}
        }
    }
}
```

---

## Platform-Specific Considerations

### Android

**Dependencies**:
```kotlin
androidMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-engine-android:3.0.0")
}
```

**Permissions**: No changes needed - same permissions as 2.x:
```xml
<uses-permission android:name="android.permission.BLUETOOTH"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN"/>
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"/>
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
```

**Engine Creation**:
```kotlin
// Requires Android Context
val engine = AndroidBlueFalconEngine(context = applicationContext)
```

### iOS

**Dependencies**:
```kotlin
iosMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-engine-ios:3.0.0")
}
```

**Engine Creation**:
```kotlin
// No context needed on iOS
val engine = IosBlueFalconEngine()
```

**Info.plist**: Same requirements as 2.x:
```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>This app needs Bluetooth to connect to devices</string>
```

### macOS

**Dependencies**:
```kotlin
macosMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-engine-macos:3.0.0")
}
```

### JavaScript (Web Bluetooth)

**Dependencies**:
```kotlin
jsMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-engine-js:3.0.0")
}
```

**Browser Support**: Chrome, Edge, Opera (same as 2.x)

### Windows

**Dependencies**:
```kotlin
windowsMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-engine-windows:3.0.0")
}
```

**Requirements**: Windows 10 1803+ (same as 2.x)

### Raspberry Pi

**Dependencies**:
```kotlin
rpiMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-engine-rpi:3.0.0")
}
```

---

## Plugin Usage

One of the biggest advantages of 3.0 is the plugin system. Add powerful features with just a few lines:

### Logging Plugin

Debug all BLE operations:

```kotlin
val blueFalcon = BlueFalcon {
    engine = AndroidBlueFalconEngine(context)
    
    install(LoggingPlugin) {
        level = LogLevel.DEBUG
        logDiscovery = true
        logConnections = true
        logGattOperations = true
        logger = PrintLnLogger
    }
}

// Output:
// [BlueFalcon] [DEBUG] Starting scan with 0 filters
// [BlueFalcon] [INFO] Connected to peripheral: ABC123
// [BlueFalcon] [DEBUG] Reading characteristic 1234-5678
```

### Retry Plugin

Automatic retry with exponential backoff:

```kotlin
val blueFalcon = BlueFalcon {
    engine = AndroidBlueFalconEngine(context)
    
    install(RetryPlugin) {
        maxRetries = 3
        initialDelay = 500.milliseconds
        maxDelay = 5.seconds
        backoffMultiplier = 2.0
        retryOn = { error -> error is BluetoothException }
    }
}

// Automatically retries failed operations!
lifecycleScope.launch {
    try {
        blueFalcon.connect(peripheral) // Retries up to 3 times on failure
    } catch (e: Exception) {
        println("Failed after 3 retries")
    }
}
```

### Caching Plugin

Cache GATT services to reduce discovery time:

```kotlin
val blueFalcon = BlueFalcon {
    engine = AndroidBlueFalconEngine(context)
    
    install(CachingPlugin) {
        cacheSize = 10
        ttl = 5.minutes
    }
}

// First discovery - hits the device
blueFalcon.discoverServices(peripheral) // ~1-2 seconds

// Subsequent discoveries - cached!
blueFalcon.discoverServices(peripheral) // Instant!
```

### Multiple Plugins

Combine plugins for powerful functionality:

```kotlin
val blueFalcon = BlueFalcon {
    engine = AndroidBlueFalconEngine(context)
    
    install(LoggingPlugin) {
        level = LogLevel.INFO
    }
    
    install(RetryPlugin) {
        maxRetries = 3
    }
    
    install(CachingPlugin) {
        cacheSize = 10
    }
}
```

---

## FAQ

### Q: Do I need to change my existing 2.x code?

**A**: No! Use the `blue-falcon-legacy` module for zero-change migration. Your 2.x code works as-is.

### Q: Can I use both 2.x and 3.0 APIs in the same app?

**A**: Yes! The legacy and modern APIs can coexist. Great for gradual migration.

### Q: What if I'm using a custom platform?

**A**: Create a custom engine by implementing `BlueFalconEngine`. See [PLUGIN_DEVELOPMENT_GUIDE.md](PLUGIN_DEVELOPMENT_GUIDE.md).

### Q: Are plugins required?

**A**: No, plugins are optional. Core functionality works without any plugins.

### Q: How do plugins affect performance?

**A**: Minimal impact. Plugins use interceptor pattern with negligible overhead.

### Q: Can I create custom plugins?

**A**: Absolutely! See [PLUGIN_DEVELOPMENT_GUIDE.md](PLUGIN_DEVELOPMENT_GUIDE.md) for details.

### Q: What happens to my 2.x code when I upgrade?

**A**: With `blue-falcon-legacy`, it continues working exactly as before. The legacy module wraps the new engine architecture.

### Q: Do I need all platform engines?

**A**: No! Only include engines for platforms you target. Gradle resolves the correct one automatically in multiplatform projects.

### Q: Is 3.0 stable?

**A**: Yes! 3.0 has been extensively tested and is production-ready. The legacy API ensures backward compatibility.

### Q: Where can I find more examples?

**A**: Check the `examples/` directory for complete working samples:
- `examples/Android-Example` - Android app using 3.0
- `examples/KotlinMP-Example` - Multiplatform example
- `examples/Plugin-Example` - Plugin usage demonstration

### Q: How do I report issues?

**A**: Open an issue on [GitHub](https://github.com/Reedyuk/blue-falcon/issues) with:
- Blue Falcon version
- Platform (Android, iOS, etc.)
- Code snippet
- Error message/stack trace

---

## Next Steps

1. **Choose your migration strategy** (we recommend Strategy 1 for existing apps)
2. **Update dependencies** in your `build.gradle.kts`
3. **Test thoroughly** on all your target platforms
4. **Explore plugins** to enhance functionality
5. **Read more documentation**:
   - [API Reference](API_REFERENCE.md)
   - [Plugin Development Guide](PLUGIN_DEVELOPMENT_GUIDE.md)
   - [Release Notes](RELEASE_NOTES_3.0.0.md)

**Welcome to Blue Falcon 3.0!** 🎉

# Blue Falcon 3.0.0 Release Notes

**Release Date**: TBD 2026

**Status**: ✅ Ready for Release

---

## 🎉 What's New

Blue Falcon 3.0.0 represents a **major architectural evolution** of the library, introducing a plugin-based engine system while maintaining full backward compatibility with 2.x applications.

### Major Features

#### 🔌 Plugin-Based Architecture

Blue Falcon now separates core functionality from platform implementations, enabling:

- **Modular engines**: Choose only the platforms you need
- **Plugin system**: Extend functionality without forking
- **Independent releases**: Platforms can be updated independently
- **Community extensions**: Build and share custom engines and plugins

```kotlin
val blueFalcon = BlueFalcon {
    engine = AndroidBlueFalconEngine(context)
    
    install(LoggingPlugin) {
        level = LogLevel.DEBUG
    }
    
    install(RetryPlugin) {
        maxRetries = 3
    }
}
```

#### 🏗️ Modular Artifacts

Core library is now split into focused modules:

| Module | Purpose | Maven Coordinates |
|--------|---------|-------------------|
| **Core** | Platform-agnostic API | `dev.bluefalcon:blue-falcon-core:3.0.0` |
| **Android Engine** | Android BLE implementation | `dev.bluefalcon:blue-falcon-engine-android:3.0.0` |
| **iOS Engine** | iOS CoreBluetooth | `dev.bluefalcon:blue-falcon-engine-ios:3.0.0` |
| **macOS Engine** | macOS CoreBluetooth | `dev.bluefalcon:blue-falcon-engine-macos:3.0.0` |
| **JS Engine** | Web Bluetooth API | `dev.bluefalcon:blue-falcon-engine-js:3.0.0` |
| **Windows Engine** | Windows WinRT BLE | `dev.bluefalcon:blue-falcon-engine-windows:3.0.0` |
| **RPI Engine** | Raspberry Pi support | `dev.bluefalcon:blue-falcon-engine-rpi:3.0.0` |
| **Legacy API** | 2.x compatibility layer | `dev.bluefalcon:blue-falcon-legacy:3.0.0` |

#### 🔧 Built-in Plugins

Three powerful plugins ship with 3.0:

**1. LoggingPlugin** - Debug BLE operations
```kotlin
install(LoggingPlugin) {
    level = LogLevel.DEBUG
    logConnections = true
    logGattOperations = true
}
```

**2. RetryPlugin** - Automatic retry with exponential backoff
```kotlin
install(RetryPlugin) {
    maxRetries = 3
    initialDelay = 500.milliseconds
    backoffMultiplier = 2.0
}
```

**3. CachingPlugin** - Cache GATT services and characteristics
```kotlin
install(CachingPlugin) {
    cacheSize = 10
    ttl = 5.minutes
}
```

#### ⚡ Modern Kotlin APIs

- **Kotlin Coroutines**: All BLE operations are now suspend functions
- **StateFlow**: Reactive state management for peripherals and Bluetooth state
- **DSL Configuration**: Intuitive configuration syntax
- **Type-safe APIs**: Improved type safety across the board

```kotlin
lifecycleScope.launch {
    // Suspend functions for clean async code
    blueFalcon.scan()
    blueFalcon.connect(peripheral)
    blueFalcon.discoverServices(peripheral)
    
    // StateFlow for reactive updates
    blueFalcon.peripherals.collect { devices ->
        updateUI(devices)
    }
}
```

#### 🔄 Full Backward Compatibility

**Zero code changes required** for existing 2.x applications:

```kotlin
// Your existing 2.x code works as-is!
val blueFalcon = BlueFalcon(log = null, ApplicationContext())
blueFalcon.delegates.add(myDelegate)
blueFalcon.scan()
```

Simply add the `blue-falcon-legacy` dependency alongside your engine.

---

## 📦 Installation

### Kotlin Multiplatform (Modern 3.0 API)

```kotlin
kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("dev.bluefalcon:blue-falcon-core:3.0.0")
                // Optional plugins
                implementation("dev.bluefalcon:blue-falcon-plugin-logging:3.0.0")
                implementation("dev.bluefalcon:blue-falcon-plugin-retry:3.0.0")
                implementation("dev.bluefalcon:blue-falcon-plugin-caching:3.0.0")
            }
        }
        
        val androidMain by getting {
            dependencies {
                implementation("dev.bluefalcon:blue-falcon-engine-android:3.0.0")
            }
        }
        
        val iosMain by getting {
            dependencies {
                implementation("dev.bluefalcon:blue-falcon-engine-ios:3.0.0")
            }
        }
    }
}
```

### Legacy 2.x Compatibility

```kotlin
kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("dev.bluefalcon:blue-falcon-core:3.0.0")
                implementation("dev.bluefalcon:blue-falcon-legacy:3.0.0")
            }
        }
        
        val androidMain by getting {
            dependencies {
                implementation("dev.bluefalcon:blue-falcon-engine-android:3.0.0")
            }
        }
    }
}
```

---

## 🚀 New Features

### Core API Enhancements

#### StateFlow-Based Peripherals

```kotlin
// Reactive peripheral discovery
blueFalcon.peripherals.collect { devices ->
    println("Found ${devices.size} devices")
    devices.forEach { device ->
        println("  - ${device.name} (${device.uuid})")
    }
}
```

#### Bluetooth Manager State

```kotlin
// Monitor Bluetooth adapter state
blueFalcon.managerState.collect { state ->
    when (state) {
        BluetoothManagerState.PoweredOn -> startScanning()
        BluetoothManagerState.PoweredOff -> showEnableBluetoothDialog()
        BluetoothManagerState.Unauthorized -> requestPermissions()
        else -> {}
    }
}
```

#### Suspend-Based Operations

All BLE operations are now suspend functions for cleaner async code:

```kotlin
suspend fun connectToDevice(device: BluetoothPeripheral) {
    try {
        blueFalcon.connect(device)
        blueFalcon.discoverServices(device)
        
        val service = device.services.first()
        val characteristic = service.characteristics.first()
        
        blueFalcon.readCharacteristic(device, characteristic)
        val data = characteristic.value
        
        println("Data: ${data?.decodeToString()}")
    } catch (e: BluetoothException) {
        println("Error: ${e.message}")
    }
}
```

### Plugin System

#### Creating Custom Plugins

Extend Blue Falcon with your own plugins:

```kotlin
class MyPlugin(private val config: Config) : BlueFalconPlugin {
    class Config : PluginConfig() {
        var enabled: Boolean = true
    }
    
    override fun install(client: BlueFalconClient, config: PluginConfig) {
        println("MyPlugin installed")
    }
    
    override suspend fun onBeforeConnect(call: ConnectCall): ConnectCall {
        println("Connecting to ${call.peripheral.name}")
        return call
    }
    
    override suspend fun onAfterConnect(call: ConnectCall, result: Result<Unit>) {
        result.onSuccess { println("Connected!") }
        result.onFailure { println("Failed!") }
    }
}

// Use it
val blueFalcon = BlueFalcon {
    engine = AndroidBlueFalconEngine(context)
    install(MyPlugin(MyPlugin.Config()))
}
```

#### Plugin Composition

Combine multiple plugins:

```kotlin
val blueFalcon = BlueFalcon {
    engine = AndroidBlueFalconEngine(context)
    
    // Debug logging in development
    if (BuildConfig.DEBUG) {
        install(LoggingPlugin) {
            level = LogLevel.DEBUG
        }
    }
    
    // Always retry failed operations
    install(RetryPlugin) {
        maxRetries = 3
    }
    
    // Cache for performance
    install(CachingPlugin) {
        cacheSize = 20
    }
}
```

### Engine System

#### Custom Engines

Create custom engines for specialized hardware:

```kotlin
class CustomBleEngine : BlueFalconEngine {
    override val scope = CoroutineScope(Dispatchers.Default)
    override val peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    override val managerState = MutableStateFlow(BluetoothManagerState.PoweredOn)
    override var isScanning = false
    
    override suspend fun scan(filters: List<ServiceFilter>) {
        // Custom BLE stack implementation
    }
    
    // Implement other methods...
}

// Use it
val blueFalcon = BlueFalcon(CustomBleEngine())
```

---

## 💥 Breaking Changes

### None for Legacy API Users

If using the `blue-falcon-legacy` module, there are **zero breaking changes**. All 2.x code continues to work.

### For New 3.0 API Users

If adopting the modern 3.0 APIs:

#### 1. Package Changes

```kotlin
// 2.x
import dev.bluefalcon.BlueFalcon

// 3.0
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.plugins.logging.LoggingPlugin
```

#### 2. Constructor Changes

```kotlin
// 2.x
BlueFalcon(log, context, autoDiscover)

// 3.0
BlueFalcon { 
    engine = AndroidBlueFalconEngine(context)
}
```

#### 3. Delegate Pattern → StateFlow

```kotlin
// 2.x - Delegates
blueFalcon.delegates.add(object : BlueFalconDelegate {
    override fun didDiscoverDevice(device: BluetoothPeripheral) { }
})

// 3.0 - StateFlow
blueFalcon.peripherals.collect { devices -> }
```

#### 4. Synchronous → Suspend Functions

```kotlin
// 2.x
blueFalcon.scan()

// 3.0
lifecycleScope.launch {
    blueFalcon.scan()
}
```

---

## 🔧 Improvements

### Performance

- ⚡ **Faster service discovery**: Optimized GATT operations
- ⚡ **Reduced memory footprint**: Modular architecture loads only needed code
- ⚡ **Better concurrency**: Kotlin coroutines for efficient async operations

### Developer Experience

- 📖 **Comprehensive documentation**: 5 new guides (Migration, API Reference, Plugin Development, Testing, Release Notes)
- 🧪 **Testing infrastructure**: Mock implementations and test helpers
- 🎯 **Type safety**: Improved type checking and null safety
- 💡 **Better error messages**: More descriptive exception messages

### Code Quality

- ✅ **Separation of concerns**: Core API separate from platform implementations
- ✅ **Single Responsibility Principle**: Each module has one clear purpose
- ✅ **Open/Closed Principle**: Open for extension via plugins, closed for modification
- ✅ **Dependency Inversion**: Depend on abstractions (BlueFalconEngine) not implementations

---

## 📚 Documentation

### New Guides

1. **[Migration Guide](MIGRATION_GUIDE.md)**
   - Zero-change migration strategy
   - Gradual migration approach
   - Full migration to 3.0 APIs
   - Platform-specific considerations

2. **[Plugin Development Guide](PLUGIN_DEVELOPMENT_GUIDE.md)**
   - How to create custom plugins
   - Interceptor pattern explanation
   - Complete plugin examples
   - Publishing plugins to Maven

3. **[API Reference](API_REFERENCE.md)**
   - Complete API documentation
   - Core types and interfaces
   - Engine implementations
   - Plugin API reference

4. **[Testing Guide](TESTING_GUIDE.md)**
   - Testing infrastructure
   - Unit and integration tests
   - Mock implementations
   - Testing best practices

5. **[Release Notes](RELEASE_NOTES_3.0.0.md)** (this document)
   - What's new in 3.0
   - Breaking changes
   - Migration instructions

### Updated Examples

- `examples/KotlinMP-Example` - Updated to demonstrate 3.0 APIs
- `examples/Plugin-Example` - New example showing plugin usage
- `examples/README.md` - How to run examples

---

## 🐛 Known Issues

### Android

- **Auto-reconnect**: `autoConnect` parameter works only on Android, no-op on other platforms
- **GATT cache**: `refreshGattCache()` is Android-specific

### iOS/macOS

- **Connection priority**: `requestConnectionPriority()` is no-op on iOS/macOS
- **Bonding**: Bonding behavior is handled automatically by iOS

### Web Bluetooth (JavaScript)

- **Browser support**: Limited to Chrome, Edge, and Opera
- **HTTPS required**: Web Bluetooth requires secure contexts

### Windows

- **Windows version**: Requires Windows 10 version 1803 or later
- **Native DLL**: Must include `bluefalcon-windows.dll` in library path

---

## 🗺️ Roadmap

### Planned for 3.1

- **Connection pooling**: Manage multiple simultaneous connections
- **Scanning filters enhancement**: More sophisticated filtering options
- **Background scanning**: Support for background BLE operations
- **Additional plugins**:
  - MetricsPlugin: Performance monitoring
  - SecurityPlugin: Encryption/decryption
  - ThrottlePlugin: Rate limiting

### Planned for 3.2

- **Linux support**: Native Linux BLE engine
- **Flutter bridge**: Flutter plugin for Blue Falcon
- **React Native bridge**: React Native module
- **Improved testing**: Platform-specific test coverage

### Under Consideration

- **BLE advertising**: Act as a BLE peripheral (not just central)
- **Mesh networking**: BLE Mesh support
- **Audio support**: BLE Audio/LE Audio profiles
- **Custom GATT server**: Host custom services

---

## 🙏 Acknowledgments

Special thanks to:

- **Community contributors**: For feature requests and bug reports
- **Platform maintainers**: Android, iOS, macOS, Windows, JavaScript, and Raspberry Pi teams
- **Ktor team**: For inspiration with their plugin-based architecture
- **Kotlin team**: For the amazing Kotlin Multiplatform tooling

---

## 📞 Support

- **Documentation**: [docs/](../docs/)
- **GitHub Issues**: [Report bugs](https://github.com/Reedyuk/blue-falcon/issues)
- **GitHub Discussions**: [Ask questions](https://github.com/Reedyuk/blue-falcon/discussions)
- **Examples**: [examples/](../examples/)

---

## 🔖 Version History

| Version | Release Date | Major Changes |
|---------|--------------|---------------|
| **3.0.0** | TBD 2026 | Plugin architecture, modular engines, modern APIs |
| 2.0.0 | 2024 | Kotlin 2.0, improved multiplatform support |
| 1.x.x | 2019-2023 | Initial releases, platform support |

---

## 📄 License

Blue Falcon is released under the Apache License 2.0.

See [LICENSE](../LICENSE) for details.

---

## 🚀 Getting Started

Ready to upgrade? Check out our **[Migration Guide](MIGRATION_GUIDE.md)** for step-by-step instructions!

**New to Blue Falcon?** Start with the **[API Reference](API_REFERENCE.md)** and explore the **examples/** directory.

---

**Happy coding with Blue Falcon 3.0!** 🦅✨

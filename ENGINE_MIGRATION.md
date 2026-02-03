# Blue Falcon Engine Pattern Migration Guide

## Overview

Blue Falcon has been redesigned to follow the Ktor engine pattern, enabling a pluggable architecture where anyone can easily create custom Bluetooth LE engines for different platforms or use cases.

## What Changed?

### Architecture

**Before:**
```
BlueFalcon (expect/actual)
    └─ Platform-specific implementation directly in BlueFalcon class
```

**After:**
```
BlueFalcon (expect/actual wrapper)
    └─ BlueFalconEngine (interface)
           ├─ AndroidBlueFalconEngine
           ├─ NativeBlueFalconEngine  
           ├─ JsBlueFalconEngine
           └─ RpiBlueFalconEngine
```

### Key Components

1. **BlueFalconEngine Interface** (`commonMain`)
   - Defines the contract for all Bluetooth LE operations
   - Must be implemented by all platform engines
   - Located: `src/commonMain/kotlin/dev/bluefalcon/BlueFalconEngine.kt`

2. **Platform Engines** (platform-specific)
   - `AndroidBlueFalconEngine` - Android BLE implementation
   - `NativeBlueFalconEngine` - iOS/macOS CoreBluetooth implementation
   - `JsBlueFalconEngine` - Web Bluetooth API implementation
   - `RpiBlueFalconEngine` - Raspberry Pi blessed-bluez implementation

3. **Engine Factory** (expect/actual)
   - `createDefaultBlueFalconEngine()` - Creates platform-appropriate engine
   - Can be overridden for custom implementations

4. **BlueFalcon Wrapper** (expect/actual)
   - Delegates all operations to the engine
   - Maintains backwards compatible API
   - Supports custom engine injection

## Backwards Compatibility

### ✅ All Existing Code Works Without Changes

```kotlin
// Standard constructor - works exactly as before
val blueFalcon = BlueFalcon(
    log = PrintLnLogger,
    context = ApplicationContext()
)

// All methods work the same
blueFalcon.scan()
blueFalcon.connect(peripheral)
```

### ✅ New Capability - Custom Engines

```kotlin
// Create custom engine
val customEngine = MyCustomBlueFalconEngine(...)

// Inject into BlueFalcon
val blueFalcon = BlueFalcon(engine = customEngine)

// Use normally
blueFalcon.scan()
```

## Benefits

### For Library Users

1. **No Breaking Changes** - Existing code continues to work
2. **Testing Support** - Create mock engines for unit tests
3. **Custom Behavior** - Implement specialized engines
4. **Platform Extensions** - Add new platform support

### For Library Maintainers

1. **Separation of Concerns** - Platform logic isolated in engines
2. **Easier Testing** - Each engine can be tested independently
3. **Clear Contracts** - Interface defines all required operations
4. **Maintainability** - Changes to one platform don't affect others

## Use Cases

### 1. Testing with Mock Engine

```kotlin
class MockBlueFalconEngine : BlueFalconEngine {
    private val mockPeripherals = mutableListOf<BluetoothPeripheral>()
    
    fun addMockPeripheral(peripheral: BluetoothPeripheral) {
        mockPeripherals.add(peripheral)
    }
    
    override fun scan(filters: List<ServiceFilter>) {
        // Simulate discovering mock peripherals
        mockPeripherals.forEach { peripheral ->
            delegates.forEach { it.didDiscoverDevice(peripheral, emptyMap()) }
        }
    }
    
    // ... implement other methods
}

// In tests
val mockEngine = MockBlueFalconEngine()
mockEngine.addMockPeripheral(testPeripheral)
val blueFalcon = BlueFalcon(engine = mockEngine)
```

### 2. Logging Decorator

```kotlin
class LoggingBlueFalconEngine(
    private val innerEngine: BlueFalconEngine,
    private val logger: Logger
) : BlueFalconEngine by innerEngine {
    
    override fun scan(filters: List<ServiceFilter>) {
        logger.info("Starting scan with ${filters.size} filters")
        innerEngine.scan(filters)
    }
    
    override fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean) {
        logger.info("Connecting to ${bluetoothPeripheral.name}")
        innerEngine.connect(bluetoothPeripheral, autoConnect)
    }
}
```

### 3. Custom Platform Support

```kotlin
// Add Windows support
class WindowsBlueFalconEngine(
    private val log: Logger?,
    private val context: ApplicationContext,
    private val autoDiscoverAllServicesAndCharacteristics: Boolean
) : BlueFalconEngine {
    
    // Implement using Windows BLE APIs
    override fun scan(filters: List<ServiceFilter>) {
        // Windows-specific scanning logic
    }
    
    // ... implement all interface methods
}
```

## Migration for Contributors

### Adding a New Platform

1. **Implement BlueFalconEngine**
   ```kotlin
   class MyPlatformBlueFalconEngine(...) : BlueFalconEngine {
       // Implement all interface methods
   }
   ```

2. **Create Factory Function**
   ```kotlin
   // In platform-specific source set
   actual fun createDefaultBlueFalconEngine(...): BlueFalconEngine =
       MyPlatformBlueFalconEngine(...)
   ```

3. **Update BlueFalcon Wrapper**
   ```kotlin
   // In platform-specific source set
   actual class BlueFalcon actual constructor(...) {
       private var engine: BlueFalconEngine = createDefaultBlueFalconEngine(...)
       
       // Delegate all methods to engine
       actual fun scan(filters: List<ServiceFilter>) = engine.scan(filters)
       // ...
   }
   ```

### Modifying Existing Platform

All platform-specific logic is now in the engine classes:
- `AndroidBlueFalconEngine.kt`
- `NativeBlueFalconEngine.kt`
- `JsBlueFalconEngine.kt`
- `RpiBlueFalconEngine.kt`

The `BlueFalcon.kt` files are now lightweight wrappers that simply delegate to the engine.

## Documentation

- **Creating Custom Engines**: See [CUSTOM_ENGINES.md](CUSTOM_ENGINES.md)
- **Architecture Overview**: See README.md
- **Interface Documentation**: See `BlueFalconEngine.kt` KDoc comments

## Questions?

For questions about the engine pattern or creating custom engines:
1. Read [CUSTOM_ENGINES.md](CUSTOM_ENGINES.md) for detailed examples
2. Check existing engine implementations as reference
3. Open a GitHub issue for support

## Design Inspiration

This pattern is inspired by Ktor's pluggable engine architecture, which allows HTTP client/server implementations to be swapped without changing application code. The same principle applies here for Bluetooth LE operations.

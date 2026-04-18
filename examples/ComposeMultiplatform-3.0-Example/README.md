# Compose Multiplatform Example - Blue Falcon 3.0

This example demonstrates Blue Falcon 3.0 with Compose Multiplatform using the new coroutine and Flow-based API.

## ✨ Modern Version

This is the **3.0** version of Blue Falcon featuring:
- Coroutine-based API (suspend functions)
- StateFlow instead of delegates
- Plugin architecture support
- Better error handling
- Type-safe operations

For comparison with the legacy 2.x API, see the [Legacy Example](../ComposeMultiplatform-Legacy-Example/).

## What's Included

This is a complete Compose Multiplatform application showing:
- Device scanning with StateFlow
- Connection management with coroutines
- Service discovery
- Characteristic read/write
- Notifications
- MTU changes
- RSSI updates

## Project Structure

```
ComposeMultiplatform-3.0-Example/
├── shared/
│   ├── src/
│   │   ├── commonMain/kotlin/
│   │   │   ├── ble/
│   │   │   │   ├── data/           # (BleDelegate not needed in 3.0)
│   │   │   │   └── presentation/
│   │   │   │       ├── BluetoothDeviceViewModel.kt  # Flow-based ViewModel
│   │   │   │       └── component/  # UI components
│   │   │   ├── di/
│   │   │   │   └── AppModule.kt    # Engine setup
│   │   │   └── App.kt
│   │   ├── androidMain/            # Android engine
│   │   └── iosMain/                # iOS engine
│   └── build.gradle.kts            # Blue Falcon 3.0 dependencies
├── androidBlueFalconExampleMP/
└── iosBlueFalconExampleMP/
```

## Key Differences from 2.x

### 1. No Delegate - Use StateFlow Instead

**3.0 (This Example):**
```kotlin
// Collect peripherals from StateFlow
blueFalcon.peripherals.collect { devices ->
    // Update UI with discovered devices
}
```

### 2. Suspend Functions Instead of Callbacks

**3.0 (This Example):**
```kotlin
// All operations are suspend functions
CoroutineScope(Dispatchers.IO).launch {
    try {
        blueFalcon.scan()  // Start scanning
        blueFalcon.connect(peripheral)  // Connect
        blueFalcon.discoverServices(peripheral)  // Discover services
        blueFalcon.readCharacteristic(peripheral, characteristic)  // Read
        blueFalcon.writeCharacteristic(peripheral, characteristic, value)  // Write
    } catch (e: Exception) {
        // Handle errors inline
    }
}
```

### 3. Engine-Based Initialization with Plugins

**3.0 (This Example):**
```kotlin
// androidMain
actual class AppModule(context: Context) {
    actual val blueFalcon: BlueFalcon = BlueFalcon(
        engine = AndroidEngine(context)
    ).apply {
        // Install logging plugin for debugging
        plugins.install(LoggingPlugin(LoggingPlugin.Config().apply {
            level = LogLevel.DEBUG
            logDiscovery = true
            logConnections = true
            logGattOperations = true
        })) { }
        
        // Install retry plugin for better reliability
        plugins.install(RetryPlugin(RetryPlugin.Config().apply {
            maxRetries = 3
            initialDelay = Duration.parse("1s")
        })) { }
    }
}

// iosMain
actual class AppModule {
    actual val blueFalcon: BlueFalcon = BlueFalcon(
        engine = IosEngine()
    ).apply {
        // Same plugin configuration...
    }
}
```

## Plugins Used in This Example

### LoggingPlugin
Logs all BLE operations for debugging:
- Device discovery
- Connection/disconnection events
- GATT read/write operations
- Errors

**Configuration:**
```kotlin
plugins.install(LoggingPlugin(LoggingPlugin.Config().apply {
    level = LogLevel.DEBUG  // Minimum log level
    logDiscovery = true     // Log device scanning
    logConnections = true   // Log connect/disconnect
    logGattOperations = true  // Log read/write/notify
})) { }
```

### RetryPlugin
Automatically retries failed operations with exponential backoff:

**Configuration:**
```kotlin
plugins.install(RetryPlugin(RetryPlugin.Config().apply {
    maxRetries = 3  // Maximum retry attempts
    initialDelay = Duration.parse("1s")  // Initial delay
    backoffMultiplier = 2.0  // Exponential backoff
})) { }
```

    }
}
```

### 3. Engine-Based Initialization

**3.0 (This Example):**
```kotlin
// androidMain
actual class AppModule(context: Context) {
    actual val blueFalcon: BlueFalcon = BlueFalcon(
        engine = AndroidEngine(context)
    )
}

// iosMain
actual class AppModule {
    actual val blueFalcon: BlueFalcon = BlueFalcon(
        engine = IosEngine()
    )
}
```

## Running the Example

### Android
```bash
./gradlew :androidBlueFalconExampleMP:installDebug
```

### iOS
1. Build the Kotlin framework:
   ```bash
   cd shared
   ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
   ```

2. Open in Xcode:
   ```bash
   open iosBlueFalconExampleMP/iosApp.xcodeproj
   ```

3. Run the app in Xcode

## Dependencies (build.gradle.kts)

```kotlin
commonMain.dependencies {
    // Blue Falcon 3.0 Core
    implementation("dev.bluefalcon:blue-falcon-core:3.1.0")
    
    // Optional plugins
    implementation("dev.bluefalcon:blue-falcon-plugin-logging:3.1.0")
    implementation("dev.bluefalcon:blue-falcon-plugin-retry:3.1.0")
}

androidMain.dependencies {
    // Android Engine
    implementation("dev.bluefalcon:blue-falcon-engine-android:3.1.0")
}

iosMain.dependencies {
    // iOS Engine
    implementation("dev.bluefalcon:blue-falcon-engine-ios:3.1.0")
}
```

## Migration to 3.0

See [MIGRATION_GUIDE.md](../../docs/MIGRATION_GUIDE.md) for complete migration instructions.

## Features Demonstrated

- ✅ Device scanning
- ✅ Connection/disconnection
- ✅ Service discovery
- ✅ Characteristic read/write
- ✅ Notifications
- ✅ RSSI updates
- ✅ Compose UI integration
- ✅ ViewModel pattern
- ✅ Dependency injection

## Learn More

- [Blue Falcon 3.0 Example](../ComposeMultiplatform-3.0-Example/) - Modern version
- [Migration Guide](../../docs/MIGRATION_GUIDE.md) - Upgrade to 3.0
- [Plugin Examples](../Plugin-Example/) - Custom plugin development
- [Engine Examples](../Engine-Example/) - Custom engine development

## Support

This legacy version is maintained for compatibility but new development should use 3.0.

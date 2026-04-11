# Compose Multiplatform Example - Blue Falcon 2.x (Legacy)

This example demonstrates Blue Falcon 2.x with Compose Multiplatform using the legacy callback-based API.

## ⚠️ Legacy Version

This is the **2.x (legacy)** version of Blue Falcon. For new projects, use the [3.0 version](../ComposeMultiplatform-3.0-Example/) which features:
- Coroutine-based API instead of callbacks
- Flow instead of delegates
- Plugin architecture
- Better error handling

## What's Included

This is a complete Compose Multiplatform application showing:
- Device scanning
- Connection management
- Service discovery
- Characteristic read/write
- Notifications
- RSSI updates

## Project Structure

```
ComposeMultiplatform-Legacy-Example/
├── shared/
│   ├── src/
│   │   ├── commonMain/kotlin/
│   │   │   ├── ble/
│   │   │   │   ├── data/
│   │   │   │   │   ├── BleDelegate.kt       # Callback delegate
│   │   │   │   │   └── DeviceEvent.kt       # Event types
│   │   │   │   └── presentation/
│   │   │   │       ├── BluetoothDeviceViewModel.kt
│   │   │   │       └── component/           # UI components
│   │   │   ├── di/
│   │   │   │   └── AppModule.kt             # DI setup
│   │   │   └── App.kt
│   │   ├── androidMain/
│   │   └── iosMain/
│   └── build.gradle.kts
├── androidBlueFalconExampleMP/
└── iosBlueFalconExampleMP/
```

## Key Files

### BleDelegate.kt
Implements `BlueFalconDelegate` for callback-based event handling:

```kotlin
class BleDelegate: BlueFalconDelegate {
    override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
        // Handle connection
    }
    
    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {
        // Handle service discovery
    }
    
    // ... more callbacks
}
```

### AppModule.kt (expect/actual)
Platform-specific Blue Falcon initialization:

```kotlin
// commonMain
expect class AppModule {
    val blueFalcon: BlueFalcon
}

// androidMain
actual class AppModule(private val context: Context) {
    actual val blueFalcon: BlueFalcon = BlueFalcon(context, delegate)
}

// iosMain
actual class AppModule {
    actual val blueFalcon: BlueFalcon = BlueFalcon(delegate)
}
```

## Running the Example

### Android
```bash
./gradlew :androidBlueFalconExampleMP:installDebug
```

### iOS
Open in Xcode:
```bash
open iosBlueFalconExampleMP/iosBlueFalconExampleMP.xcodeproj
```

## Migration to 3.0

See [MIGRATION_GUIDE.md](../../docs/MIGRATION_GUIDE.md) for step-by-step migration instructions.

### Quick Comparison

#### 2.x (Legacy) - This Example
```kotlin
// Setup with delegate
class BleDelegate: BlueFalconDelegate {
    override fun didConnect(peripheral: BluetoothPeripheral) {
        // Handle connection
    }
}

val blueFalcon = BlueFalcon(context, delegate)

// Scan (no return value)
blueFalcon.scan()

// Connect (callback-based)
blueFalcon.connect(peripheral)
// Result comes via didConnect callback
```

#### 3.0 (New) - See ComposeMultiplatform-3.0-Example
```kotlin
// Setup with DSL
val blueFalcon = BlueFalcon {
    install(LoggingPlugin)
}

// Observe devices with Flow
blueFalcon.peripherals.collect { devices ->
    // Update UI
}

// Scan (suspend function)
blueFalcon.scan()

// Connect (suspend function with result)
try {
    blueFalcon.connect(peripheral)
    // Connected successfully
} catch (e: Exception) {
    // Handle error
}
```

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

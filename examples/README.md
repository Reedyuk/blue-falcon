# Blue Falcon Examples

This directory contains example projects demonstrating Blue Falcon usage across different platforms and use cases.

## 📁 Available Examples

### 1. Android-Example

**Platform**: Android  
**Language**: Kotlin  
**Description**: Native Android app using Blue Falcon 3.0 with modern APIs

**Features**:
- Device scanning with filters
- Connection management
- GATT service discovery
- Read/write operations
- Plugin integration (Logging, Retry)

**Running**:
```bash
cd Android-Example
./gradlew installDebug
```

**Requirements**:
- Android Studio Arctic Fox or later
- Android device or emulator with BLE support
- Android 5.0 (API 21) or higher

---

### 2. KotlinMP-Example

**Platform**: Kotlin Multiplatform (Android + iOS)  
**Language**: Kotlin  
**Description**: Multiplatform example showing shared BLE code

**Features**:
- Shared BLE logic in commonMain
- Platform-specific engines
- StateFlow for reactive updates
- Modern 3.0 API usage

**Running**:
```bash
cd KotlinMP-Example
# Android
./gradlew :androidApp:installDebug

# iOS
cd iosApp
pod install
open iosApp.xcworkspace
```

**Requirements**:
- Kotlin 2.0+
- Android Studio or IntelliJ IDEA
- Xcode (for iOS)

---

### 3. ComposeMultiplatform-Example

**Platform**: Compose Multiplatform (Android + Desktop)  
**Language**: Kotlin  
**Description**: Modern UI with Compose and Blue Falcon 3.0

**Features**:
- Compose UI for device list
- Reactive state management with Flow
- Plugin usage demonstration
- Cross-platform code sharing

**Running**:
```bash
cd ComposeMultiplatform-Example
# Android
./gradlew :androidApp:installDebug

# Desktop
./gradlew :desktopApp:run
```

---

### 4. JS-Example

**Platform**: JavaScript (Web Browser)  
**Language**: Kotlin/JS  
**Description**: Web Bluetooth API example

**Features**:
- Browser-based BLE scanning
- Web Bluetooth API integration
- Reactive UI updates
- Chrome/Edge/Opera support

**Running**:
```bash
cd JS-Example
./gradlew jsBrowserDevelopmentRun
```

Open browser to `http://localhost:8080`

**Requirements**:
- Chrome, Edge, or Opera browser
- HTTPS connection (required by Web Bluetooth)

---

### 5. RPI-Example

**Platform**: Raspberry Pi (Linux)  
**Language**: Kotlin/Native  
**Description**: IoT device example for Raspberry Pi

**Features**:
- BlueZ integration
- Sensor data reading
- Headless operation
- Linux-based BLE

**Running**:
```bash
cd RPI-Example
./gradlew build
# Deploy to Raspberry Pi
scp build/bin/native/releaseExecutable/RPI-Example.kexe pi@raspberrypi:~/
ssh pi@raspberrypi
./RPI-Example.kexe
```

**Requirements**:
- Raspberry Pi 3 or later (with built-in Bluetooth)
- Raspbian OS
- BlueZ installed

---

### 6. ArchitecturePOC (Proof of Concept)

**Platform**: Cross-platform  
**Language**: Kotlin  
**Description**: Architectural patterns and best practices

**Features**:
- Clean Architecture example
- MVVM pattern
- Dependency injection
- Repository pattern
- Testing examples

---

### 7. Plugin-Example ⭐ NEW in 3.0

**Platform**: Cross-platform  
**Language**: Kotlin  
**Description**: Demonstrates Blue Falcon 3.0 plugin system

**Features**:
- Custom plugin creation
- Built-in plugins (Logging, Retry, Caching)
- Plugin composition
- Interceptor pattern examples

**Running**:
```bash
cd Plugin-Example
./gradlew run
```

See [Plugin-Example/README.md](Plugin-Example/README.md) for details.

---

## 🚀 Quick Start

### Using Modern 3.0 API

```kotlin
import dev.bluefalcon.core.*
import dev.bluefalcon.plugins.logging.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

// Initialize with DSL
val blueFalcon = BlueFalcon {
    engine = AndroidBlueFalconEngine(context)
    
    install(LoggingPlugin) {
        level = LogLevel.DEBUG
        logConnections = true
    }
}

// Scan for devices
lifecycleScope.launch {
    blueFalcon.scan()
    
    // Collect discovered devices
    blueFalcon.peripherals.collect { devices ->
        devices.forEach { device ->
            println("Found: ${device.name} (${device.uuid})")
        }
    }
}

// Connect to device
lifecycleScope.launch {
    val device = blueFalcon.peripherals.value.first()
    
    try {
        blueFalcon.connect(device)
        blueFalcon.discoverServices(device)
        
        val service = device.services.first()
        val characteristic = service.characteristics.first()
        
        // Read
        blueFalcon.readCharacteristic(device, characteristic)
        val value = characteristic.value
        
        // Write
        blueFalcon.writeCharacteristic(device, characteristic, "Hello BLE")
        
    } catch (e: BluetoothException) {
        println("Error: ${e.message}")
    }
}
```

### Using Legacy 2.x API

```kotlin
import dev.bluefalcon.legacy.BlueFalcon
import dev.bluefalcon.legacy.BlueFalconDelegate
import dev.bluefalcon.legacy.ApplicationContext

// Your existing 2.x code works as-is!
val blueFalcon = BlueFalcon(log = null, ApplicationContext())

blueFalcon.delegates.add(object : BlueFalconDelegate {
    override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral) {
        println("Found: ${bluetoothPeripheral.name}")
    }
    
    override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
        println("Connected!")
    }
})

blueFalcon.scan()
```

---

## 🔧 Common Setup

### Gradle Dependencies

#### Modern 3.0 API

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("dev.bluefalcon:blue-falcon-core:3.0.0")
                // Optional plugins
                implementation("dev.bluefalcon:blue-falcon-plugin-logging:3.0.0")
                implementation("dev.bluefalcon:blue-falcon-plugin-retry:3.0.0")
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

#### Legacy 2.x Compatibility

```kotlin
dependencies {
    implementation("dev.bluefalcon:blue-falcon-core:3.0.0")
    implementation("dev.bluefalcon:blue-falcon-legacy:3.0.0")
    implementation("dev.bluefalcon:blue-falcon-engine-android:3.0.0")
}
```

### Platform-Specific Setup

#### Android Permissions

Add to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.BLUETOOTH"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN"/>
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"/>
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>

<uses-feature android:name="android.hardware.bluetooth_le" android:required="true"/>
```

#### iOS Info.plist

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>This app needs Bluetooth to connect to BLE devices</string>
<key>NSBluetoothPeripheralUsageDescription</key>
<string>This app needs Bluetooth to connect to BLE devices</string>
```

#### macOS Entitlements

```xml
<key>com.apple.security.device.bluetooth</key>
<true/>
```

---

## 📖 Documentation

- **[Migration Guide](../docs/MIGRATION_GUIDE.md)** - Upgrade from 2.x to 3.0
- **[API Reference](../docs/API_REFERENCE.md)** - Complete API documentation
- **[Plugin Development Guide](../docs/PLUGIN_DEVELOPMENT_GUIDE.md)** - Create custom plugins
- **[Testing Guide](../docs/TESTING_GUIDE.md)** - Testing Blue Falcon apps
- **[Release Notes](../docs/RELEASE_NOTES_3.0.0.md)** - What's new in 3.0

---

## 🐛 Troubleshooting

### Android: Bluetooth permissions denied

**Solution**: Request runtime permissions in Android 12+

```kotlin
ActivityCompat.requestPermissions(
    this,
    arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    ),
    PERMISSION_REQUEST_CODE
)
```

### iOS: Devices not discovered

**Solution**: Ensure Bluetooth is enabled and app has permission. Check Info.plist entries.

### Web: "Bluetooth not available"

**Solution**: 
- Use HTTPS (Web Bluetooth requires secure context)
- Use Chrome, Edge, or Opera
- Enable experimental web platform features (chrome://flags)

### General: Connection fails

**Solution**: 
- Install RetryPlugin for automatic retry
- Check device is in range
- Ensure device is not bonded to another device

```kotlin
install(RetryPlugin) {
    maxRetries = 3
    initialDelay = 500.milliseconds
}
```

---

## 🤝 Contributing

Found a bug or want to improve an example?

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

---

## 📞 Support

- **GitHub Issues**: [Report bugs](https://github.com/Reedyuk/blue-falcon/issues)
- **Discussions**: [Ask questions](https://github.com/Reedyuk/blue-falcon/discussions)
- **Documentation**: [docs/](../docs/)

---

**Happy coding with Blue Falcon!** 🦅

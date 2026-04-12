# ![Blue Falcon](bluefalcon.png) Blue-Falcon 

[![CI](https://github.com/Reedyuk/blue-falcon/actions/workflows/release.yml/badge.svg)](https://github.com/Reedyuk/blue-falcon/actions/workflows/release.yml)
[![Maven Central](https://img.shields.io/maven-central/v/dev.bluefalcon/blue-falcon-core?label=Maven%20Central)](https://central.sonatype.com/search?q=dev.bluefalcon)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.0-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![License](https://img.shields.io/github/license/Reedyuk/blue-falcon)](LICENSE)

[![Android](https://img.shields.io/badge/platform-android-brightgreen.svg?style=flat)](#)
[![iOS](https://img.shields.io/badge/platform-ios-lightgrey.svg?style=flat)](#)
[![macOS](https://img.shields.io/badge/platform-macos-lightgrey.svg?style=flat)](#)
[![Raspberry Pi](https://img.shields.io/badge/platform-rpi-lightgrey.svg?style=flat)](#)
[![JavaScript](https://img.shields.io/badge/platform-js-yellow.svg?style=flat)](#)
[![Windows](https://img.shields.io/badge/platform-windows-blue.svg?style=flat)](#)

**A Bluetooth Low Energy (BLE) Kotlin Multiplatform library for iOS, Android, MacOS, Raspberry Pi, Windows, and JavaScript.**

Blue Falcon provides a unified API for Bluetooth LE operations across all platforms. Each platform implementation compiles to native code, ensuring optimal performance and seamless integration with platform-specific APIs.

> **🎉 Version 3.0** introduces a plugin-based engine architecture inspired by Ktor, enabling extensibility while maintaining 100% backward compatibility with 2.x.

## ✨ Features

- **🔌 Plugin Architecture** - Extensible engine system with official and community plugins
- **📱 Cross-Platform** - Single API for iOS, Android, macOS, JavaScript, Windows, and Raspberry Pi
- **🔄 Backward Compatible** - Drop-in replacement for 2.x users
- **⚡ Native Performance** - Compiles to platform-native code (Obj-C, JVM, JS, etc.)
- **🔧 Flexible APIs** - Choose between Flow-based reactive API or delegate callbacks
- **🎯 Type-Safe** - Full Kotlin type safety across all platforms

## 📦 Installation

### For 2.x Users (Easiest Upgrade Path)

Simply update your version - **no code changes required**:

```kotlin
commonMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon:3.0.0")
}
```

Your existing 2.x code continues to work unchanged! See the [Migration Guide](docs/MIGRATION_GUIDE.md) for details.

### For New Projects (3.0 API)

#### Core + Engine

```kotlin
commonMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-core:3.0.0")
}

// Add platform-specific engines
androidMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-engine-android:3.0.0")
}

iosMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-engine-ios:3.0.0")
}
```

#### Optional Plugins

```kotlin
commonMain.dependencies {
    // Logging support
    implementation("dev.bluefalcon:blue-falcon-plugin-logging:3.0.0")
    
    // Automatic retry with exponential backoff
    implementation("dev.bluefalcon:blue-falcon-plugin-retry:3.0.0")
    
    // Service/characteristic caching
    implementation("dev.bluefalcon:blue-falcon-plugin-caching:3.0.0")
}
```

## 🚀 Quick Start

### Legacy API (2.x Compatible)

```kotlin
// Create instance
val blueFalcon = BlueFalcon(log = null, ApplicationContext())

// Register delegate
blueFalcon.delegates.add(object : BlueFalconDelegate {
    override fun didDiscoverDevice(peripheral: BluetoothPeripheral, advertisementData: Map<AdvertisementDataRetrievalKeys, Any>) {
        println("Found device: ${peripheral.name}")
    }
    
    override fun didConnect(peripheral: BluetoothPeripheral) {
        println("Connected to: ${peripheral.name}")
    }
})

// Start scanning
blueFalcon.scan()
```

### Modern API (3.0)

```kotlin
import dev.bluefalcon.core.*
import dev.bluefalcon.plugins.logging.*

// Configure with DSL
val blueFalcon = BlueFalcon {
    engine = AndroidEngine(context)  // or iOSEngine(), macOSEngine(), etc.
    
    install(LoggingPlugin) {
        level = LogLevel.DEBUG
    }
    
    install(RetryPlugin) {
        maxAttempts = 3
        initialDelay = 500
    }
}

// Reactive Flow API
launch {
    blueFalcon.peripherals.collect { devices ->
        devices.forEach { device ->
            println("Device: ${device.name}")
        }
    }
}

// Start scanning
blueFalcon.scan()
```

## 📚 Documentation

- **[Migration Guide](docs/MIGRATION_GUIDE.md)** - Upgrading from 2.x to 3.0
- **[API Reference](docs/API_REFERENCE.md)** - Complete API documentation
- **[Plugin Development](docs/PLUGIN_DEVELOPMENT_GUIDE.md)** - Creating custom plugins
- **[Testing Guide](docs/TESTING_GUIDE.md)** - Testing your BLE code
- **[Publishing Guide](docs/PUBLISHING.md)** - Release and publishing process

### Architecture

- **[ADR 0001](docs/adr/0001-add-windows-platform-support.md)** - Windows Platform Support
- **[ADR 0002](docs/adr/0002-adopt-plugin-based-engine-architecture.md)** - Plugin-Based Engine Architecture

## 🎯 Platform Support

| Platform | Engine Module | Status | Notes |
|----------|--------------|--------|-------|
| **Android** | `blue-falcon-engine-android` | ✅ Stable | Full BLE support including L2CAP, bonding |
| **iOS** | `blue-falcon-engine-ios` | ✅ Stable | CoreBluetooth wrapper |
| **macOS** | `blue-falcon-engine-macos` | ✅ Stable | CoreBluetooth wrapper |
| **JavaScript** | `blue-falcon-engine-js` | ✅ Stable | Web Bluetooth API |
| **Windows** | `blue-falcon-engine-windows` | ✅ Stable | WinRT via JNI (Windows 10 1803+) |
| **Raspberry Pi** | `blue-falcon-engine-rpi` | ✅ Stable | Blessed library (BlueZ) |

### Platform Requirements

#### Android
- Minimum SDK: 24 (Android 7.0)
- Target SDK: 33 (Android 13)

#### iOS
- Minimum: iOS 13.0+
- Xcode 14.0+

#### macOS
- Minimum: macOS 10.15+

#### JavaScript
- Modern browsers with Web Bluetooth support
- HTTPS required (security policy)

#### Windows
- Windows 10 version 1803 (April 2018 Update) or later
- JDK 11+

#### Raspberry Pi
- Linux with BlueZ 5.0+
- JDK 11+

## 🏗️ Architecture

Blue Falcon 3.0 uses a three-layer architecture:

```
┌─────────────────────────────────────────┐
│         Your Application Code           │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│  Core (Interfaces + Plugin System)      │
│  • BlueFalcon API                        │
│  • Plugin Registry                       │
│  • Type Definitions                      │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│  Platform Engines (6 implementations)   │
│  • AndroidEngine                         │
│  • iOSEngine, macOSEngine               │
│  • JSEngine                              │
│  • WindowsEngine                         │
│  • RPiEngine                             │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│  Native Platform APIs                    │
│  • Android Bluetooth                     │
│  • CoreBluetooth (iOS/macOS)            │
│  • Web Bluetooth                         │
│  • Windows WinRT                         │
│  • BlueZ (Linux)                         │
└─────────────────────────────────────────┘
```

### Official Plugins

- **LoggingPlugin** - Configurable logging with custom loggers
- **RetryPlugin** - Automatic retry with exponential backoff
- **CachingPlugin** - Service/characteristic discovery caching

See the [Plugin Development Guide](docs/PLUGIN_DEVELOPMENT_GUIDE.md) to create your own!

## 🤝 Contributing

We welcome contributions! Blue Falcon follows a structured decision-making process:

### Proposing Major Changes

For significant architectural changes or new features:

1. **Create an Architecture Decision Record (ADR)**
   ```bash
   # Use the ADR template
   cp docs/adr/ADR-TEMPLATE.md docs/adr/XXXX-your-proposal.md
   ```

2. **Let AI help you write it**
   - Use GitHub Copilot or your preferred AI assistant
   - Reference existing ADRs for context
   - See [Contributing Guide](CONTRIBUTING.md) for details

3. **Submit a Pull Request**
   - Link to your ADR
   - Discuss with maintainers
   - Implement once approved

### Quick Contributions

For bug fixes, docs, or small improvements:

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a Pull Request

See [CONTRIBUTING.md](CONTRIBUTING.md) for detailed guidelines.

## 📖 Examples
blueFalcon.clearPeripherals()

// Check scanning state
val scanning: Boolean = blueFalcon.isScanning
```

#### Observing Discovered Devices

```kotlin
// Observe discovered peripherals via StateFlow
blueFalcon.peripherals.collect { peripherals: Set<BluetoothPeripheral> ->
    // update your UI with the discovered devices
}

// Observe Bluetooth manager state (Ready / NotReady)
blueFalcon.managerState.collect { state: BluetoothManagerState ->
    when (state) {
        BluetoothManagerState.Ready -> { /* Bluetooth is available */ }
        BluetoothManagerState.NotReady -> { /* Bluetooth is unavailable */ }
    }
}
```

#### Connection Management

```kotlin
// Connect to a peripheral (autoConnect = false for direct connection)
blueFalcon.connect(bluetoothPeripheral, autoConnect = false)

// Disconnect from a peripheral
blueFalcon.disconnect(bluetoothPeripheral)

// Check current connection state
val state: BluetoothPeripheralState = blueFalcon.connectionState(bluetoothPeripheral)
// Returns: Connecting, Connected, Disconnected, Disconnecting, or Unknown

// Request connection priority (Android-specific, no-op on other platforms)
blueFalcon.requestConnectionPriority(bluetoothPeripheral, ConnectionPriority.High)
// Options: ConnectionPriority.Balanced, ConnectionPriority.High, ConnectionPriority.Low

// Retrieve a previously known peripheral by identifier
val peripheral: BluetoothPeripheral? = blueFalcon.retrievePeripheral("device-identifier")
// Android: MAC address format (e.g., "00:11:22:33:44:55")
// iOS/Native: UUID format (e.g., "XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX")
```

#### Service & Characteristic Discovery

When `autoDiscoverAllServicesAndCharacteristics` is `true` (default), services and characteristics are discovered automatically after connection. You can also trigger discovery manually:

```kotlin
// Discover services (optionally filter by service UUIDs)
blueFalcon.discoverServices(bluetoothPeripheral, serviceUUIDs = emptyList())

// Discover characteristics for a specific service (optionally filter by UUIDs)
blueFalcon.discoverCharacteristics(
    bluetoothPeripheral,
    bluetoothService,
    characteristicUUIDs = emptyList()
)
```

#### Reading & Writing Characteristics

```kotlin
// Read a characteristic value
blueFalcon.readCharacteristic(bluetoothPeripheral, bluetoothCharacteristic)

// Write a string value
blueFalcon.writeCharacteristic(
    bluetoothPeripheral,
```

For the complete API including descriptors, MTU, L2CAP, and bonding, see the [API Reference](docs/API_REFERENCE.md).

## 📄 License

Blue Falcon is released under the [MIT License](LICENSE).

## 🙏 Acknowledgments

- Built with [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html)
- Inspired by the [Ktor](https://ktor.io/) plugin architecture
- Community contributors and plugin developers

## 📞 Support

- **Issues:** [GitHub Issues](https://github.com/Reedyuk/blue-falcon/issues)
- **Discussions:** [GitHub Discussions](https://github.com/Reedyuk/blue-falcon/discussions)
- **Documentation:** [docs/](docs/)

---

**Made with ❤️ by the Blue Falcon community**

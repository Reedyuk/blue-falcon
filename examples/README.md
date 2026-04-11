# Blue Falcon Examples

This directory contains example projects demonstrating Blue Falcon usage across different platforms and architectures.

## 📁 Available Examples

### 1. ArchitecturePOC

**Platform**: Kotlin Multiplatform  
**Language**: Kotlin  
**Description**: Proof-of-concept demonstrating the Blue Falcon 3.0 architecture

**Features**:
- Shows the three-layer architecture (Core → Engine → Platform)
- Example of using the DSL API
- Plugin integration demonstration
- Custom plugin example

**Location**: `ArchitecturePOC/`  
**See**: [ArchitecturePOC/README.md](ArchitecturePOC/README.md) for details

---

### 2. Plugin-Example

**Platform**: Kotlin Multiplatform  
**Language**: Kotlin  
**Description**: Examples of creating custom Blue Falcon plugins

**Features**:
- Custom plugin implementation
- Plugin configuration
- Interceptor pattern usage
- Integration with BlueFalcon instance

**Location**: `Plugin-Example/`  
**See**: [Plugin-Example/README.md](Plugin-Example/README.md) for details

---

### 3. Engine-Example

**Platform**: Kotlin Multiplatform  
**Language**: Kotlin  
**Description**: Example of creating a custom platform engine

**Features**:
- Custom engine implementation
- BlueFalconEngine interface implementation
- Platform-specific BLE integration
- Testing custom engines

**Location**: `Engine-Example/`  
**See**: [Engine-Example/README.md](Engine-Example/README.md) for details

---

### 4. ComposeMultiplatform-Example

**Platform**: Compose Multiplatform (Android + Desktop)  
**Language**: Kotlin  
**Description**: Production-ready example with Compose UI

**Features**:
- Compose UI for device list
- Real BLE scanning and connection
- Reactive state management with Flow
- Cross-platform code sharing

**Location**: `ComposeMultiplatform-Example/`

**Running**:
```bash
cd ComposeMultiplatform-Example
# Android
./gradlew :androidApp:installDebug

# Desktop
./gradlew :desktopApp:run
```

**Requirements**:
- Kotlin 2.3.0+
- Compose Multiplatform
- Android device or Desktop with Bluetooth

---

### 5. JS-Example

**Platform**: JavaScript (Web Browser)  
**Language**: Kotlin/JS  
**Description**: Web Bluetooth API example

**Features**:
- Browser-based BLE scanning
- Web Bluetooth API integration
- Real-time device discovery

**Location**: `JS-Example/`

**Running**:
```bash
cd JS-Example
./gradlew jsBrowserDevelopmentRun
# Open http://localhost:8080
```

**Requirements**:
- Chrome, Edge, or Opera browser
- HTTPS connection (or localhost)
- Web Bluetooth enabled

---

## 🎯 Quick Start Patterns

### Legacy API (2.x - Backward Compatible)

```kotlin
val blueFalcon = BlueFalcon(log = null, ApplicationContext())

blueFalcon.delegates.add(object : BlueFalconDelegate {
    override fun didDiscoverDevice(
        peripheral: BluetoothPeripheral, 
        advertisementData: Map<AdvertisementDataRetrievalKeys, Any>
    ) {
        println("Found: ${peripheral.name}")
    }
})

blueFalcon.scan()
```

### Modern API (3.0)

```kotlin
import dev.bluefalcon.core.*
import dev.bluefalcon.engine.android.*
import dev.bluefalcon.plugins.logging.*

val blueFalcon = BlueFalcon {
    engine = AndroidEngine(context)
    
    install(LoggingPlugin) {
        level = LogLevel.DEBUG
    }
}

launch {
    blueFalcon.peripherals.collect { devices ->
        devices.forEach { println("Device: ${it.name}") }
    }
}

blueFalcon.scan()
```

---

## 📚 Documentation

For complete guides and API documentation:

- **[Migration Guide](../docs/MIGRATION_GUIDE.md)** - Upgrading from 2.x to 3.0
- **[API Reference](../docs/API_REFERENCE.md)** - Complete API documentation
- **[Plugin Development Guide](../docs/PLUGIN_DEVELOPMENT_GUIDE.md)** - Creating plugins
- **[Testing Guide](../docs/TESTING_GUIDE.md)** - Testing your BLE code

---

## 🤝 Contributing Examples

Want to contribute an example? We'd love to have:

- Platform-specific examples (watchOS, tvOS, Linux, etc.)
- Use case examples (heart rate monitor, glucose meter, etc.)
- Advanced patterns (background scanning, multiple connections, etc.)
- Framework integrations (SwiftUI, Jetpack Compose, React, etc.)

**Guidelines:**
1. Follow the existing example structure
2. Include a detailed README.md
3. Provide working, tested code
4. Document platform requirements
5. Submit a Pull Request

See [CONTRIBUTING.md](../CONTRIBUTING.md) for details.

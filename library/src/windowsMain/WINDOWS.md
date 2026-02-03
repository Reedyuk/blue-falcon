# Windows 10 Bluetooth LE Support

This document provides detailed information about the Windows 10 Bluetooth LE implementation for Blue Falcon.

## Overview

The Windows implementation provides full Bluetooth Low Energy support for Windows 10 desktop applications through JNI (Java Native Interface) and Windows Runtime (WinRT) APIs. This implementation does NOT rely on any third-party libraries and uses only the built-in Windows Bluetooth APIs.

## Architecture

The Windows implementation consists of three layers:

### 1. Kotlin/JVM Layer (`windowsMain/kotlin`)
- **BlueFalcon.kt**: Main entry point implementing the Blue Falcon API
- **BluetoothPeripheral.kt/.windows.kt**: Device representation and management
- **BluetoothService.kt**: GATT service representation
- **BluetoothCharacteristic.kt**: GATT characteristic and descriptor management
- **ApplicationContext.kt**: Empty context (Windows doesn't need application context)
- **Uuid.kt**, **ServiceFilter.kt**: Utility classes

### 2. JNI Bridge Layer (`windowsMain/cpp/jni_bridge.cpp`)
- Provides JNI method implementations that bridge Kotlin/Java to C++
- Handles data type conversions between JVM and native code
- Marshals callbacks from C++ back to Java/Kotlin

### 3. Native Windows Layer (`windowsMain/cpp/BluetoothLEManager.cpp/h`)
- **BluetoothLEManager**: Singleton manager for Bluetooth operations
- Uses Windows Runtime (WinRT) Bluetooth APIs:
  - `Windows.Devices.Bluetooth`
  - `Windows.Devices.Bluetooth.Advertisement`
  - `Windows.Devices.Bluetooth.GenericAttributeProfile`
- Implements asynchronous operations using WinRT async patterns
- Handles device connections, service discovery, and GATT operations

## Requirements

### Development Requirements
- **Windows 10** version 1803 (April 2018 Update) or later
- **Visual Studio 2019 or later** with:
  - C++ development tools
  - Windows 10 SDK (version 10.0.17763.0 or later)
- **Java Development Kit (JDK)** 11 or later
- **CMake** 3.14 or later

### Runtime Requirements
- **Windows 10** version 1803 or later
- **Java Runtime Environment (JRE)** 11 or later
- Bluetooth adapter with BLE support

## Building the Native Library

### Step 1: Set up your environment

1. Install Visual Studio 2019 or later with C++ development tools
2. Install Windows 10 SDK from: https://developer.microsoft.com/en-us/windows/downloads/windows-10-sdk/
3. Ensure JAVA_HOME environment variable is set correctly

### Step 2: Build with CMake

```powershell
cd library\src\windowsMain\cpp
mkdir build
cd build
cmake .. -G "Visual Studio 16 2019" -A x64
cmake --build . --config Release
```

The output `bluefalcon-windows.dll` will be in the `Release` directory.

### Step 3: Install the native library

Copy the DLL to one of the following locations:
- Your Java library path
- `library/src/windowsMain/resources/`
- Your application's working directory

## Usage

### Basic Usage

```kotlin
import dev.bluefalcon.*

// Create BlueFalcon instance
val blueFalcon = BlueFalcon(
    log = PrintLnLogger,  // or your custom logger
    context = ApplicationContext(),  // Empty context for Windows
    autoDiscoverAllServicesAndCharacteristics = true
)

// Implement delegate to receive callbacks
blueFalcon.delegates.add(object : BlueFalconDelegate {
    override fun didDiscoverDevice(
        bluetoothPeripheral: BluetoothPeripheral,
        advertisementData: Map<AdvertisementDataRetrievalKeys, Any>
    ) {
        println("Found device: ${bluetoothPeripheral.name} - ${bluetoothPeripheral.uuid}")
    }
    
    override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
        println("Connected to ${bluetoothPeripheral.name}")
    }
    
    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {
        println("Discovered ${bluetoothPeripheral.services.size} services")
    }
    
    override fun didCharacteristcValueChanged(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        println("Characteristic changed: ${bluetoothCharacteristic.uuid}")
        println("Value: ${bluetoothCharacteristic.value?.contentToString()}")
    }
    
    // Implement other delegate methods...
})

// Start scanning
blueFalcon.scan()

// Stop scanning
blueFalcon.stopScanning()

// Connect to a device
blueFalcon.connect(device, autoConnect = false)

// Read a characteristic
blueFalcon.readCharacteristic(device, characteristic)

// Write to a characteristic
blueFalcon.writeCharacteristic(device, characteristic, data)

// Enable notifications
blueFalcon.notifyCharacteristic(device, characteristic, true)
```

### Filtering Devices

```kotlin
// Scan for devices with specific service UUID
val heartRateServiceUuid = kotlin.uuid.Uuid.parse("0000180d-0000-1000-8000-00805f9b34fb")
val filters = listOf(
    ServiceFilter(serviceUuids = listOf(heartRateServiceUuid))
)
blueFalcon.scan(filters)
```

## API Compatibility

The Windows implementation supports all standard Blue Falcon APIs:

### Scanning
- `scan(filters: List<ServiceFilter> = emptyList())`
- `stopScanning()`
- `clearPeripherals()`

### Connection Management
- `connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean = false)`
- `disconnect(bluetoothPeripheral: BluetoothPeripheral)`
- `connectionState(bluetoothPeripheral: BluetoothPeripheral): BluetoothPeripheralState`
- `retrievePeripheral(identifier: String): BluetoothPeripheral?`

### Service & Characteristic Discovery
- `discoverServices(bluetoothPeripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid> = emptyList())`
- `discoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral, bluetoothService: BluetoothService, characteristicUUIDs: List<Uuid> = emptyList())`

### GATT Operations
- `readCharacteristic(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic)`
- `writeCharacteristic(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic, value: ByteArray, writeType: Int?)`
- `notifyCharacteristic(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic, notify: Boolean)`
- `indicateCharacteristic(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic, indicate: Boolean)`

### Descriptor Operations
- `readDescriptor(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic, bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor)`
- `writeDescriptor(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor, value: ByteArray)`

### MTU Management
- `changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int)`

## Windows-Specific Details

### Device Identifiers
- Windows uses Bluetooth addresses (uint64) internally
- Peripherals are identified by MAC address format: `XX:XX:XX:XX:XX:XX`
- `retrievePeripheral()` accepts MAC address strings

### MTU Negotiation
- Windows automatically negotiates MTU during connection
- `changeMTU()` is provided for API compatibility but Windows handles this automatically
- Default MTU is 23 bytes for BLE 4.0, up to 517 bytes for BLE 4.2+

### Connection Priority
- `requestConnectionPriority()` is not directly supported on Windows
- Windows manages connection parameters automatically

### Permissions
Windows 10 version 1803+ requires the following capabilities in your application manifest:
- Bluetooth capability
- If accessing device information, also include relevant device capabilities

### Thread Safety
- All Bluetooth operations are performed asynchronously
- Callbacks are dispatched on background threads
- Use appropriate thread synchronization in your application

## Limitations

1. **Descriptor Operations**: Full descriptor read/write support is partially implemented
2. **Background Operation**: Continuous background scanning may be limited by Windows power management
3. **Pairing**: Explicit pairing is handled by Windows, not directly by this library
4. **Multiple Adapters**: Currently supports single Bluetooth adapter

## Troubleshooting

### "Cannot find bluefalcon-windows.dll"
- Ensure the DLL is in your Java library path or working directory
- Check that you built the correct architecture (x64 vs x86)
- Verify JAVA_HOME is set correctly

### "Bluetooth adapter not found"
- Check Windows Settings > Devices > Bluetooth & other devices
- Ensure Bluetooth is enabled
- Verify your hardware has a Bluetooth adapter

### "Access denied" or "Permission denied"
- Ensure your application has Bluetooth capabilities in its manifest
- Check Windows Privacy Settings > Bluetooth

### Build Errors
- Verify Visual Studio has C++ tools installed
- Ensure Windows 10 SDK is installed (10.0.17763.0 or later)
- Check that CMake can find your JDK installation

## Implementation Notes

### Asynchronous Operations
All Bluetooth operations are asynchronous, using Kotlin coroutines on the Kotlin side and WinRT async patterns on the native side. Callbacks are marshaled back to Java/Kotlin through JNI.

### Memory Management
- JNI global references are used for callback objects to ensure they persist across native calls
- Native resources are cleaned up in the BlueFalcon destructor
- Device connections are tracked in a thread-safe map

### Error Handling
- Exceptions in native code are caught and logged
- Failed operations are reported through delegate callbacks
- Connection failures result in `didDisconnect` callbacks

## Contributing

When contributing to the Windows implementation:

1. Follow the existing code style
2. Test on multiple Windows 10 versions if possible
3. Ensure thread safety in native code
4. Document any Windows-specific behaviors
5. Update this documentation with any changes

## References

- [Windows Bluetooth LE APIs](https://docs.microsoft.com/en-us/windows/uwp/devices-sensors/bluetooth-low-energy-overview)
- [C++/WinRT Documentation](https://docs.microsoft.com/en-us/windows/uwp/cpp-and-winrt-apis/)
- [JNI Specification](https://docs.oracle.com/javase/8/docs/technotes/guides/jni/spec/jniTOC.html)

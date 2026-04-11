# Blue Falcon 3.0 API Reference

## Table of Contents

1. [Core API](#core-api)
2. [Plugin API](#plugin-api)
3. [Data Types](#data-types)
4. [Engine Implementations](#engine-implementations)
5. [Configuration DSL](#configuration-dsl)
6. [Error Handling](#error-handling)
7. [Flow and State Management](#flow-and-state-management)

---

## Core API

### BlueFalcon

The main client class for interacting with Bluetooth Low Energy devices.

#### Constructor

```kotlin
class BlueFalcon(
    val engine: BlueFalconEngine
)
```

#### DSL Constructor

```kotlin
fun BlueFalcon(block: BlueFalconConfig.() -> Unit): BlueFalcon
```

**Example**:

```kotlin
val blueFalcon = BlueFalcon {
    engine = AndroidBlueFalconEngine(context)
    install(LoggingPlugin) { /* config */ }
}
```

#### Properties

| Property | Type | Description |
|----------|------|-------------|
| `engine` | `BlueFalconEngine` | Underlying platform engine |
| `plugins` | `PluginRegistry` | Installed plugins |
| `peripherals` | `StateFlow<Set<BluetoothPeripheral>>` | Discovered devices |
| `managerState` | `StateFlow<BluetoothManagerState>` | Bluetooth adapter state |
| `isScanning` | `Boolean` | Whether currently scanning |

#### Methods

##### Scanning

```kotlin
suspend fun scan(filters: List<ServiceFilter> = emptyList())
```

Start scanning for BLE devices.

**Parameters**:
- `filters`: Optional list of service UUIDs to filter devices

**Example**:

```kotlin
// Scan for all devices
blueFalcon.scan()

// Scan for devices with specific service
blueFalcon.scan(filters = listOf(
    ServiceFilter(Uuid("0000180D-0000-1000-8000-00805F9B34FB"))
))
```

---

```kotlin
suspend fun stopScanning()
```

Stop scanning for devices.

---

```kotlin
fun clearPeripherals()
```

Clear all discovered peripherals from cache.

##### Connection Management

```kotlin
suspend fun connect(
    peripheral: BluetoothPeripheral, 
    autoConnect: Boolean = false
)
```

Connect to a BLE peripheral.

**Parameters**:
- `peripheral`: The device to connect to
- `autoConnect`: Auto-reconnect on disconnection (Android only)

**Throws**:
- `BluetoothException`: If connection fails

**Example**:

```kotlin
try {
    blueFalcon.connect(peripheral, autoConnect = true)
    println("Connected!")
} catch (e: BluetoothException) {
    println("Failed: ${e.message}")
}
```

---

```kotlin
suspend fun disconnect(peripheral: BluetoothPeripheral)
```

Disconnect from a peripheral.

---

```kotlin
fun connectionState(peripheral: BluetoothPeripheral): BluetoothPeripheralState
```

Get current connection state.

**Returns**: `Connected`, `Connecting`, `Disconnected`, `Disconnecting`

---

```kotlin
fun retrievePeripheral(identifier: String): BluetoothPeripheral?
```

Retrieve a peripheral by platform-specific identifier.

**Parameters**:
- `identifier`: MAC address (Android) or UUID string (iOS/macOS)

**Returns**: Peripheral if found, null otherwise

---

```kotlin
fun requestConnectionPriority(
    peripheral: BluetoothPeripheral,
    priority: ConnectionPriority
)
```

Request connection priority change (Android only, no-op on other platforms).

**Priority levels**: `Balanced`, `High`, `LowPower`

##### Service Discovery

```kotlin
suspend fun discoverServices(
    peripheral: BluetoothPeripheral,
    serviceUUIDs: List<Uuid> = emptyList()
)
```

Discover GATT services on a connected peripheral.

**Parameters**:
- `peripheral`: Connected device
- `serviceUUIDs`: Optional filter for specific services

**Example**:

```kotlin
blueFalcon.connect(peripheral)
blueFalcon.discoverServices(peripheral)

peripheral.services.forEach { service ->
    println("Service: ${service.uuid}")
}
```

---

```kotlin
suspend fun discoverCharacteristics(
    peripheral: BluetoothPeripheral,
    service: BluetoothService,
    characteristicUUIDs: List<Uuid> = emptyList()
)
```

Discover characteristics for a service.

##### GATT Operations

```kotlin
suspend fun readCharacteristic(
    peripheral: BluetoothPeripheral,
    characteristic: BluetoothCharacteristic
)
```

Read a characteristic value. Result is stored in `characteristic.value`.

**Example**:

```kotlin
blueFalcon.readCharacteristic(peripheral, characteristic)
val value = characteristic.value
println("Read: ${value?.decodeToString()}")
```

---

```kotlin
suspend fun writeCharacteristic(
    peripheral: BluetoothPeripheral,
    characteristic: BluetoothCharacteristic,
    value: String,
    writeType: Int? = null
)

suspend fun writeCharacteristic(
    peripheral: BluetoothPeripheral,
    characteristic: BluetoothCharacteristic,
    value: ByteArray,
    writeType: Int? = null
)
```

Write to a characteristic.

**Parameters**:
- `value`: String or ByteArray to write
- `writeType`: Optional write type (platform-specific)

**Example**:

```kotlin
// Write string
blueFalcon.writeCharacteristic(peripheral, characteristic, "Hello BLE")

// Write bytes
blueFalcon.writeCharacteristic(peripheral, characteristic, byteArrayOf(0x01, 0x02))
```

---

```kotlin
suspend fun notifyCharacteristic(
    peripheral: BluetoothPeripheral,
    characteristic: BluetoothCharacteristic,
    notify: Boolean
)
```

Enable/disable notifications for a characteristic.

**Example**:

```kotlin
// Enable notifications
blueFalcon.notifyCharacteristic(peripheral, characteristic, notify = true)

// Disable notifications
blueFalcon.notifyCharacteristic(peripheral, characteristic, notify = false)
```

---

```kotlin
suspend fun indicateCharacteristic(
    peripheral: BluetoothPeripheral,
    characteristic: BluetoothCharacteristic,
    indicate: Boolean
)
```

Enable/disable indications for a characteristic.

##### Descriptor Operations

```kotlin
suspend fun readDescriptor(
    peripheral: BluetoothPeripheral,
    characteristic: BluetoothCharacteristic,
    descriptor: BluetoothCharacteristicDescriptor
)
```

Read a characteristic descriptor.

---

```kotlin
suspend fun writeDescriptor(
    peripheral: BluetoothPeripheral,
    descriptor: BluetoothCharacteristicDescriptor,
    value: ByteArray
)
```

Write to a characteristic descriptor.

##### Advanced Operations

```kotlin
suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int)
```

Request MTU (Maximum Transmission Unit) size change.

---

```kotlin
fun refreshGattCache(peripheral: BluetoothPeripheral): Boolean
```

Refresh GATT cache (Android only).

---

```kotlin
suspend fun openL2capChannel(peripheral: BluetoothPeripheral, psm: Int)
```

Open L2CAP channel (platform-dependent).

---

```kotlin
suspend fun createBond(peripheral: BluetoothPeripheral)
```

Create a bond (pair) with the peripheral.

---

```kotlin
suspend fun removeBond(peripheral: BluetoothPeripheral)
```

Remove bond (unpair) with the peripheral.

---

## Plugin API

### BlueFalconPlugin

Base interface for creating plugins.

```kotlin
interface BlueFalconPlugin {
    fun install(client: BlueFalconClient, config: PluginConfig)
    
    suspend fun onBeforeScan(call: ScanCall): ScanCall = call
    suspend fun onAfterScan(call: ScanCall) {}
    
    suspend fun onBeforeConnect(call: ConnectCall): ConnectCall = call
    suspend fun onAfterConnect(call: ConnectCall, result: Result<Unit>) {}
    
    suspend fun onBeforeRead(call: ReadCall): ReadCall = call
    suspend fun onAfterRead(call: ReadCall, result: Result<ByteArray?>) {}
    
    suspend fun onBeforeWrite(call: WriteCall): WriteCall = call
    suspend fun onAfterWrite(call: WriteCall, result: Result<Unit>) {}
}
```

**Hook Methods**:

| Method | Description | When Called |
|--------|-------------|-------------|
| `install()` | Plugin initialization | Once on installation |
| `onBeforeScan()` | Intercept scan | Before scan starts |
| `onAfterScan()` | Post-scan hook | After scan completes |
| `onBeforeConnect()` | Intercept connect | Before connection attempt |
| `onAfterConnect()` | Post-connect hook | After connection result |
| `onBeforeRead()` | Intercept read | Before reading characteristic |
| `onAfterRead()` | Post-read hook | After read completes |
| `onBeforeWrite()` | Intercept write | Before writing characteristic |
| `onAfterWrite()` | Post-write hook | After write completes |

**Example**:

```kotlin
class MyPlugin : BlueFalconPlugin {
    override fun install(client: BlueFalconClient, config: PluginConfig) {
        println("Plugin installed")
    }
    
    override suspend fun onBeforeRead(call: ReadCall): ReadCall {
        println("About to read ${call.characteristic.uuid}")
        return call
    }
    
    override suspend fun onAfterRead(call: ReadCall, result: Result<ByteArray?>) {
        result.onSuccess { data ->
            println("Read ${data?.size ?: 0} bytes")
        }
    }
}
```

### PluginRegistry

Manages installed plugins.

```kotlin
class PluginRegistry {
    fun install(plugin: BlueFalconPlugin, configure: PluginConfig.() -> Unit = {})
    fun uninstall(plugin: BlueFalconPlugin)
}
```

**Example**:

```kotlin
val plugins = blueFalcon.plugins

// Install a plugin
plugins.install(LoggingPlugin(LoggingPlugin.Config()))

// Uninstall a plugin
plugins.uninstall(loggingPlugin)
```

### PluginConfig

Base class for plugin configurations.

```kotlin
open class PluginConfig
```

**Example**:

```kotlin
class MyPluginConfig : PluginConfig() {
    var enabled: Boolean = true
    var timeout: Duration = 5.seconds
}
```

### Operation Call Types

#### ScanCall

```kotlin
data class ScanCall(val filters: List<ServiceFilter>)
```

#### ConnectCall

```kotlin
data class ConnectCall(
    val peripheral: BluetoothPeripheral,
    val autoConnect: Boolean
)
```

#### ReadCall

```kotlin
data class ReadCall(
    val peripheral: BluetoothPeripheral,
    val characteristic: BluetoothCharacteristic
)
```

#### WriteCall

```kotlin
data class WriteCall(
    val peripheral: BluetoothPeripheral,
    val characteristic: BluetoothCharacteristic,
    val value: ByteArray,
    val writeType: Int?
)
```

---

## Data Types

### BluetoothPeripheral

Represents a BLE device.

```kotlin
interface BluetoothPeripheral {
    val name: String?                          // Device name
    val uuid: String                           // Platform-specific ID
    val rssi: Float?                           // Signal strength (dBm)
    val mtuSize: Int?                          // MTU size
    val services: List<BluetoothService>       // GATT services
    val characteristics: List<BluetoothCharacteristic>
}
```

**Example**:

```kotlin
blueFalcon.peripherals.collect { devices ->
    devices.forEach { device ->
        println("${device.name} (${device.uuid})")
        println("RSSI: ${device.rssi} dBm")
    }
}
```

### BluetoothService

Represents a GATT service.

```kotlin
interface BluetoothService {
    val uuid: Uuid                              // Service UUID
    val name: String?                           // Human-readable name
    val characteristics: List<BluetoothCharacteristic>
}
```

**Example**:

```kotlin
peripheral.services.forEach { service ->
    println("Service: ${service.uuid}")
    service.characteristics.forEach { char ->
        println("  Characteristic: ${char.uuid}")
    }
}
```

### BluetoothCharacteristic

Represents a GATT characteristic.

```kotlin
interface BluetoothCharacteristic {
    val uuid: Uuid                              // Characteristic UUID
    val name: String?                           // Human-readable name
    val value: ByteArray?                       // Current value
    val descriptors: List<BluetoothCharacteristicDescriptor>
    val isNotifying: Boolean                    // Notifications enabled?
    val service: BluetoothService?              // Parent service
}
```

**Example**:

```kotlin
blueFalcon.readCharacteristic(peripheral, characteristic)
val text = characteristic.value?.decodeToString()
println("Value: $text")
```

### BluetoothCharacteristicDescriptor

Represents a GATT descriptor.

```kotlin
interface BluetoothCharacteristicDescriptor {
    val uuid: Uuid                              // Descriptor UUID
    val name: String?                           // Human-readable name
    val value: ByteArray?                       // Current value
    val characteristic: BluetoothCharacteristic? // Parent characteristic
}
```

### Uuid

UUID wrapper for BLE UUIDs.

```kotlin
class Uuid(val value: String) {
    override fun toString(): String = value
}
```

**Common UUIDs**:

```kotlin
// Standard service UUIDs
val HEART_RATE_SERVICE = Uuid("0000180D-0000-1000-8000-00805F9B34FB")
val BATTERY_SERVICE = Uuid("0000180F-0000-1000-8000-00805F9B34FB")
val DEVICE_INFO = Uuid("0000180A-0000-1000-8000-00805F9B34FB")

// Standard characteristic UUIDs
val HEART_RATE_MEASUREMENT = Uuid("00002A37-0000-1000-8000-00805F9B34FB")
val BATTERY_LEVEL = Uuid("00002A19-0000-1000-8000-00805F9B34FB")
```

### ServiceFilter

Filter for scanning operations.

```kotlin
class ServiceFilter(val uuid: Uuid)
```

**Example**:

```kotlin
val heartRateFilter = ServiceFilter(
    Uuid("0000180D-0000-1000-8000-00805F9B34FB")
)

blueFalcon.scan(filters = listOf(heartRateFilter))
```

### BluetoothPeripheralState

Connection state enum.

```kotlin
enum class BluetoothPeripheralState {
    Connected,
    Connecting,
    Disconnected,
    Disconnecting
}
```

### BluetoothManagerState

Bluetooth adapter state.

```kotlin
enum class BluetoothManagerState {
    Unknown,
    Resetting,
    Unsupported,
    Unauthorized,
    PoweredOff,
    PoweredOn
}
```

### ConnectionPriority

Connection priority levels (Android).

```kotlin
enum class ConnectionPriority {
    Balanced,
    High,
    LowPower
}
```

---

## Engine Implementations

### BlueFalconEngine

Core interface that all platform engines implement.

```kotlin
interface BlueFalconEngine {
    val scope: CoroutineScope
    val peripherals: StateFlow<Set<BluetoothPeripheral>>
    val managerState: StateFlow<BluetoothManagerState>
    val isScanning: Boolean
    
    suspend fun scan(filters: List<ServiceFilter> = emptyList())
    suspend fun stopScanning()
    // ... all BlueFalcon methods
}
```

### Platform Engines

#### Android

```kotlin
class AndroidBlueFalconEngine(
    context: Context,
    autoDiscoverServices: Boolean = true
) : BlueFalconEngine
```

**Dependencies**:

```kotlin
androidMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-engine-android:3.0.0")
}
```

**Example**:

```kotlin
val engine = AndroidBlueFalconEngine(
    context = applicationContext,
    autoDiscoverServices = true
)
```

#### iOS

```kotlin
class IosBlueFalconEngine : BlueFalconEngine
```

**Dependencies**:

```kotlin
iosMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-engine-ios:3.0.0")
}
```

**Example**:

```kotlin
val engine = IosBlueFalconEngine()
```

#### macOS

```kotlin
class MacOSBlueFalconEngine : BlueFalconEngine
```

**Dependencies**:

```kotlin
macosMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-engine-macos:3.0.0")
}
```

#### JavaScript (Web Bluetooth)

```kotlin
class JsBlueFalconEngine : BlueFalconEngine
```

**Dependencies**:

```kotlin
jsMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-engine-js:3.0.0")
}
```

**Browser support**: Chrome, Edge, Opera

#### Windows

```kotlin
class WindowsBlueFalconEngine : BlueFalconEngine
```

**Dependencies**:

```kotlin
windowsMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-engine-windows:3.0.0")
}
```

**Requirements**: Windows 10 version 1803+

#### Raspberry Pi

```kotlin
class RpiBlueFalconEngine : BlueFalconEngine
```

**Dependencies**:

```kotlin
rpiMain.dependencies {
    implementation("dev.bluefalcon:blue-falcon-engine-rpi:3.0.0")
}
```

---

## Configuration DSL

### BlueFalconConfig

Configuration builder for DSL-based initialization.

```kotlin
class BlueFalconConfig {
    lateinit var engine: BlueFalconEngine
    
    fun <T : BlueFalconPlugin> install(
        plugin: T, 
        configure: PluginConfig.() -> Unit = {}
    )
}
```

**Example**:

```kotlin
val blueFalcon = BlueFalcon {
    engine = AndroidBlueFalconEngine(context)
    
    install(LoggingPlugin) {
        level = LogLevel.DEBUG
        logger = PrintLnLogger
    }
    
    install(RetryPlugin) {
        maxRetries = 3
        initialDelay = 500.milliseconds
    }
}
```

---

## Error Handling

### Exception Hierarchy

```kotlin
sealed class BluetoothException : Exception()

class BluetoothUnknownException : BluetoothException()
class BluetoothResettingException : BluetoothException()
class BluetoothUnsupportedException : BluetoothException()
class BluetoothPermissionException : BluetoothException()
class BluetoothNotEnabledException : BluetoothException()
```

### Handling Errors

```kotlin
try {
    blueFalcon.connect(peripheral)
} catch (e: BluetoothPermissionException) {
    // Request permissions
} catch (e: BluetoothNotEnabledException) {
    // Prompt user to enable Bluetooth
} catch (e: BluetoothException) {
    // Handle other BLE errors
}
```

### Result Type

Plugin hooks receive `Result<T>` for operations:

```kotlin
override suspend fun onAfterConnect(call: ConnectCall, result: Result<Unit>) {
    result.fold(
        onSuccess = { println("Connected!") },
        onFailure = { error -> println("Failed: $error") }
    )
}
```

---

## Flow and State Management

### StateFlow for Peripherals

```kotlin
val peripherals: StateFlow<Set<BluetoothPeripheral>>
```

**Example**:

```kotlin
lifecycleScope.launch {
    blueFalcon.peripherals.collect { devices ->
        println("Found ${devices.size} devices")
        updateUI(devices)
    }
}
```

### StateFlow for Bluetooth State

```kotlin
val managerState: StateFlow<BluetoothManagerState>
```

**Example**:

```kotlin
lifecycleScope.launch {
    blueFalcon.managerState.collect { state ->
        when (state) {
            BluetoothManagerState.PoweredOn -> startScanning()
            BluetoothManagerState.PoweredOff -> showBluetoothDisabledMessage()
            BluetoothManagerState.Unauthorized -> requestPermissions()
            else -> {}
        }
    }
}
```

### Combining Flows

```kotlin
lifecycleScope.launch {
    combine(
        blueFalcon.peripherals,
        blueFalcon.managerState
    ) { devices, state ->
        Pair(devices, state)
    }.collect { (devices, state) ->
        if (state == BluetoothManagerState.PoweredOn) {
            updateDeviceList(devices)
        }
    }
}
```

---

## Quick Reference

### Common Operations

```kotlin
// Initialize
val blueFalcon = BlueFalcon {
    engine = AndroidBlueFalconEngine(context)
}

// Scan
blueFalcon.scan()

// Monitor discovered devices
blueFalcon.peripherals.collect { devices -> }

// Connect
blueFalcon.connect(peripheral)

// Discover services
blueFalcon.discoverServices(peripheral)

// Read characteristic
blueFalcon.readCharacteristic(peripheral, characteristic)
val value = characteristic.value

// Write characteristic
blueFalcon.writeCharacteristic(peripheral, characteristic, "data")

// Enable notifications
blueFalcon.notifyCharacteristic(peripheral, characteristic, true)

// Disconnect
blueFalcon.disconnect(peripheral)
```

### Platform-Specific Setup

| Platform | Engine | Context Required |
|----------|--------|------------------|
| Android | `AndroidBlueFalconEngine` | ✅ Android Context |
| iOS | `IosBlueFalconEngine` | ❌ None |
| macOS | `MacOSBlueFalconEngine` | ❌ None |
| JavaScript | `JsBlueFalconEngine` | ❌ None |
| Windows | `WindowsBlueFalconEngine` | ❌ None |
| Raspberry Pi | `RpiBlueFalconEngine` | ❌ None |

---

## Further Reading

- **Migration Guide**: [MIGRATION_GUIDE.md](MIGRATION_GUIDE.md)
- **Plugin Development**: [PLUGIN_DEVELOPMENT_GUIDE.md](PLUGIN_DEVELOPMENT_GUIDE.md)
- **Testing Guide**: [TESTING_GUIDE.md](TESTING_GUIDE.md)
- **Release Notes**: [RELEASE_NOTES_3.0.0.md](RELEASE_NOTES_3.0.0.md)
- **GitHub Repository**: [https://github.com/Reedyuk/blue-falcon](https://github.com/Reedyuk/blue-falcon)

---

**Need help?** Open an issue on GitHub or check the examples in the `examples/` directory.

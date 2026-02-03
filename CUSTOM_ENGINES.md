# Creating Custom BlueFalcon Engines

Blue Falcon now supports a pluggable engine architecture inspired by Ktor, allowing developers to create custom Bluetooth Low Energy (BLE) engines for different platforms or use cases.

## What is a BlueFalcon Engine?

A BlueFalcon engine is a platform-specific implementation of Bluetooth LE functionality that conforms to the `BlueFalconEngine` interface. Engines handle all the low-level Bluetooth operations while the `BlueFalcon` class provides a consistent, cross-platform API.

## Why Create a Custom Engine?

You might want to create a custom engine for:

- **New Platforms**: Add support for platforms not currently covered (e.g., Windows, Linux, embedded systems)
- **Alternative BLE Stacks**: Use different Bluetooth libraries or APIs on existing platforms
- **Testing**: Create mock engines for unit testing without real Bluetooth hardware
- **Custom Behavior**: Implement specialized behavior like caching, logging, or connection pooling
- **Performance Optimization**: Optimize for specific use cases or hardware

## Engine Interface

All engines must implement the `BlueFalconEngine` interface:

```kotlin
interface BlueFalconEngine {
    val scope: CoroutineScope
    val delegates: MutableSet<BlueFalconDelegate>
    var isScanning: Boolean
    val managerState: StateFlow<BluetoothManagerState>
    val _peripherals: MutableStateFlow<Set<BluetoothPeripheral>>
    val peripherals: NativeFlow<Set<BluetoothPeripheral>>
    
    // Connection management
    fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean = false)
    fun disconnect(bluetoothPeripheral: BluetoothPeripheral)
    fun retrievePeripheral(identifier: String): BluetoothPeripheral?
    fun requestConnectionPriority(bluetoothPeripheral: BluetoothPeripheral, connectionPriority: ConnectionPriority)
    fun connectionState(bluetoothPeripheral: BluetoothPeripheral): BluetoothPeripheralState
    
    // Scanning
    fun scan(filters: List<ServiceFilter> = emptyList())
    fun stopScanning()
    fun clearPeripherals()
    
    // Service discovery
    fun discoverServices(bluetoothPeripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid> = emptyList())
    fun discoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral, bluetoothService: BluetoothService, characteristicUUIDs: List<Uuid> = emptyList())
    
    // Characteristic operations
    fun readCharacteristic(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic)
    fun writeCharacteristic(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic, value: String, writeType: Int?)
    fun writeCharacteristic(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic, value: ByteArray, writeType: Int?)
    fun writeCharacteristicWithoutEncoding(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic, value: ByteArray, writeType: Int?)
    fun notifyCharacteristic(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic, notify: Boolean)
    fun indicateCharacteristic(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic, indicate: Boolean)
    fun notifyAndIndicateCharacteristic(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic, enable: Boolean)
    
    // Descriptor operations
    fun readDescriptor(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic, bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor)
    fun writeDescriptor(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor, value: ByteArray)
    
    // MTU
    fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int)
}
```

## Creating a Custom Engine

### Step 1: Implement the BlueFalconEngine Interface

Create a new class that implements `BlueFalconEngine`:

```kotlin
package com.example.myengine

import dev.bluefalcon.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MyCustomBlueFalconEngine(
    private val log: Logger?,
    private val context: ApplicationContext,
    private val autoDiscoverAllServicesAndCharacteristics: Boolean
) : BlueFalconEngine {
    
    override val scope = CoroutineScope(Dispatchers.Default)
    override val delegates: MutableSet<BlueFalconDelegate> = mutableSetOf()
    override var isScanning: Boolean = false
    override val managerState: StateFlow<BluetoothManagerState> = MutableStateFlow(BluetoothManagerState.Ready)
    override val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    override val peripherals: NativeFlow<Set<BluetoothPeripheral>> = _peripherals.toNativeType(scope)
    
    // TODO: Implement all interface methods with your custom logic
    override fun scan(filters: List<ServiceFilter>) {
        log?.info("Starting scan with filters: $filters")
        isScanning = true
        // Your custom scanning logic here
    }
    
    override fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean) {
        log?.info("Connecting to ${bluetoothPeripheral.uuid}")
        // Your custom connection logic here
    }
    
    // ... implement remaining methods
}
```

### Step 2: Use Your Custom Engine

You can now use your custom engine in two ways:

#### Option 1: Direct Engine Instantiation

```kotlin
// Create your custom engine
val myEngine = MyCustomBlueFalconEngine(
    log = PrintLnLogger,
    context = ApplicationContext(),
    autoDiscoverAllServicesAndCharacteristics = true
)

// Pass it to BlueFalcon
val blueFalcon = BlueFalcon(engine = myEngine)

// Use BlueFalcon as normal
blueFalcon.delegates.add(myDelegate)
blueFalcon.scan()
```

#### Option 2: Factory Function (for platform-specific builds)

Create a factory function that returns your engine:

```kotlin
// In your platform-specific source set
actual fun createDefaultBlueFalconEngine(
    log: Logger?,
    context: ApplicationContext,
    autoDiscoverAllServicesAndCharacteristics: Boolean
): BlueFalconEngine = MyCustomBlueFalconEngine(log, context, autoDiscoverAllServicesAndCharacteristics)
```

Then use BlueFalcon normally:

```kotlin
val blueFalcon = BlueFalcon(
    log = PrintLnLogger,
    context = ApplicationContext()
)
// Your custom engine will be used automatically
```

## Example: Mock Engine for Testing

Here's a complete example of a mock engine for testing:

```kotlin
package com.example.testing

import dev.bluefalcon.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MockBlueFalconEngine : BlueFalconEngine {
    override val scope = CoroutineScope(Dispatchers.Default)
    override val delegates: MutableSet<BlueFalconDelegate> = mutableSetOf()
    override var isScanning: Boolean = false
    override val managerState: StateFlow<BluetoothManagerState> = MutableStateFlow(BluetoothManagerState.Ready)
    override val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    override val peripherals: NativeFlow<Set<BluetoothPeripheral>> = _peripherals.toNativeType(scope)
    
    // Store mock data
    private val mockPeripherals = mutableMapOf<String, BluetoothPeripheral>()
    
    // Add mock peripherals for testing
    fun addMockPeripheral(peripheral: BluetoothPeripheral) {
        mockPeripherals[peripheral.uuid] = peripheral
    }
    
    override fun scan(filters: List<ServiceFilter>) {
        isScanning = true
        scope.launch {
            // Simulate discovering all mock peripherals
            mockPeripherals.values.forEach { peripheral ->
                _peripherals.emit(_peripherals.value + peripheral)
                delegates.forEach { 
                    it.didDiscoverDevice(peripheral, emptyMap()) 
                }
            }
        }
    }
    
    override fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean) {
        scope.launch {
            // Simulate successful connection
            delegates.forEach { it.didConnect(bluetoothPeripheral) }
        }
    }
    
    override fun disconnect(bluetoothPeripheral: BluetoothPeripheral) {
        scope.launch {
            delegates.forEach { it.didDisconnect(bluetoothPeripheral) }
        }
    }
    
    // ... implement other methods with mock behavior
    override fun stopScanning() { isScanning = false }
    override fun clearPeripherals() { _peripherals.value = emptySet() }
    override fun retrievePeripheral(identifier: String) = mockPeripherals[identifier]
    override fun connectionState(bluetoothPeripheral: BluetoothPeripheral) = BluetoothPeripheralState.Connected
    override fun requestConnectionPriority(bluetoothPeripheral: BluetoothPeripheral, connectionPriority: ConnectionPriority) {}
    override fun discoverServices(bluetoothPeripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid>) {}
    override fun discoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral, bluetoothService: BluetoothService, characteristicUUIDs: List<Uuid>) {}
    override fun readCharacteristic(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic) {}
    override fun writeCharacteristic(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic, value: String, writeType: Int?) {}
    override fun writeCharacteristic(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic, value: ByteArray, writeType: Int?) {}
    override fun writeCharacteristicWithoutEncoding(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic, value: ByteArray, writeType: Int?) {}
    override fun notifyCharacteristic(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic, notify: Boolean) {}
    override fun indicateCharacteristic(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic, indicate: Boolean) {}
    override fun notifyAndIndicateCharacteristic(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic, enable: Boolean) {}
    override fun readDescriptor(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic, bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor) {}
    override fun writeDescriptor(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor, value: ByteArray) {}
    override fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int) {}
}
```

Usage in tests:

```kotlin
@Test
fun testBluetoothScanning() {
    val mockEngine = MockBlueFalconEngine()
    mockEngine.addMockPeripheral(createTestPeripheral("device-1"))
    mockEngine.addMockPeripheral(createTestPeripheral("device-2"))
    
    val blueFalcon = BlueFalcon(engine = mockEngine)
    val delegate = TestDelegate()
    blueFalcon.delegates.add(delegate)
    
    blueFalcon.scan()
    
    // Verify mock peripherals were discovered
    assertEquals(2, delegate.discoveredDevices.size)
}
```

## Example: Logging Engine Wrapper

Create a decorator engine that adds logging:

```kotlin
class LoggingBlueFalconEngine(
    private val innerEngine: BlueFalconEngine,
    private val logger: Logger
) : BlueFalconEngine by innerEngine {
    
    override fun scan(filters: List<ServiceFilter>) {
        logger.info("Starting scan with ${filters.size} filters")
        val startTime = System.currentTimeMillis()
        innerEngine.scan(filters)
        logger.info("Scan started in ${System.currentTimeMillis() - startTime}ms")
    }
    
    override fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean) {
        logger.info("Connecting to ${bluetoothPeripheral.name ?: bluetoothPeripheral.uuid}")
        innerEngine.connect(bluetoothPeripheral, autoConnect)
    }
    
    // ... wrap other methods with logging
}
```

Usage:

```kotlin
val baseEngine = AndroidBlueFalconEngine(PrintLnLogger, context, true)
val loggingEngine = LoggingBlueFalconEngine(baseEngine, CustomLogger())
val blueFalcon = BlueFalcon(engine = loggingEngine)
```

## Best Practices

1. **Implement All Methods**: Even if a platform doesn't support certain operations, implement them as no-ops or throw appropriate exceptions

2. **Use Coroutines**: Leverage Kotlin coroutines for asynchronous operations using the provided `scope`

3. **Notify Delegates**: Always call the appropriate delegate methods when events occur (device discovered, connected, etc.)

4. **Update State Flows**: Keep `_peripherals` and `managerState` updated so consumers can observe changes

5. **Handle Errors**: Use appropriate exception handling and notify delegates of failures

6. **Thread Safety**: Ensure your engine is thread-safe, especially when updating shared state

7. **Resource Cleanup**: Properly clean up Bluetooth resources in disconnect and when the engine is destroyed

8. **Documentation**: Document platform-specific behavior and limitations

## Built-in Engines

Blue Falcon provides the following built-in engines:

- **AndroidBlueFalconEngine**: Uses Android BluetoothLE APIs
- **NativeBlueFalconEngine**: Uses iOS/macOS CoreBluetooth framework
- **JsBlueFalconEngine**: Uses Web Bluetooth API
- **RpiBlueFalconEngine**: Uses blessed-bluez for Raspberry Pi

You can use these as reference implementations when creating your own engine.

## Contributing

If you create an engine for a new platform, consider contributing it back to the Blue Falcon project! Open a pull request on the [GitHub repository](https://github.com/Reedyuk/blue-falcon).

# Engine Example

This example demonstrates how to create a custom Blue Falcon engine.

## What's Included

### MockBLEEngine.kt

A complete, working custom engine implementation that simulates BLE operations.

**Features:**
- Implements the `BlueFalconEngine` interface
- Simulates device discovery
- Mock connection/disconnection
- Fake read/write operations
- Useful for testing and development

## Why Create Custom Engines?

Custom engines allow you to:

1. **Support New Platforms** - Add BLE support for platforms not yet covered
2. **Mock for Testing** - Create fake engines for UI development and testing
3. **Add Custom Behavior** - Implement specialized BLE handling
4. **Integrate Custom Hardware** - Work with proprietary BLE stacks

## Engine Requirements

All engines must implement the `BlueFalconEngine` interface:

```kotlin
interface BlueFalconEngine {
    val scope: CoroutineScope
    val peripherals: StateFlow<Set<BluetoothPeripheral>>
    val managerState: StateFlow<BluetoothManagerState>
    
    suspend fun scan(serviceFilter: List<ServiceFilter>)
    suspend fun stopScanning()
    suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean)
    suspend fun disconnect(peripheral: BluetoothPeripheral)
    // ... and more
}
```

## Usage

```kotlin
import dev.bluefalcon.example.engine.MockBLEEngine

val blueFalcon = BlueFalcon {
    engine = MockBLEEngine()
    
    // Can still use plugins!
    install(LoggingPlugin) {
        level = LogLevel.DEBUG
    }
}

// Use normally
blueFalcon.scan()
```

## Key Implementation Points

### 1. Coroutine Scope

Provide a scope for async operations:

```kotlin
override val scope: CoroutineScope = 
    CoroutineScope(Dispatchers.Default + SupervisorJob())
```

### 2. State Management

Use MutableStateFlow for reactive state:

```kotlin
private val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
override val peripherals: StateFlow<Set<BluetoothPeripheral>> = 
    _peripherals.asStateFlow()
```

### 3. Suspend Functions

All BLE operations are suspend functions:

```kotlin
override suspend fun connect(
    peripheral: BluetoothPeripheral, 
    autoConnect: Boolean
) {
    // Your platform-specific connection logic
    delay(500) // Simulate async operation
    connectedDevices.add(peripheral.uuid)
}
```

### 4. Error Handling

Throw appropriate exceptions:

```kotlin
override suspend fun readCharacteristic(...): ByteArray {
    if (!isConnected(peripheral)) {
        throw BluetoothException("Not connected")
    }
    // Read logic
}
```

## Real Engine Examples

### Android Engine

```kotlin
class AndroidEngine(private val context: Context) : BlueFalconEngine {
    private val bluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    
    override suspend fun scan(serviceFilter: List<ServiceFilter>) {
        bluetoothManager.adapter.bluetoothLeScanner.startScan(...)
    }
    // ... implement other methods using android.bluetooth APIs
}
```

### iOS Engine (Kotlin/Native)

```kotlin
class iOSEngine : BlueFalconEngine {
    private val centralManager = CBCentralManager(...)
    
    override suspend fun scan(serviceFilter: List<ServiceFilter>) {
        centralManager.scanForPeripheralsWithServices(...)
    }
    // ... implement using CoreBluetooth
}
```

### JavaScript Engine

```kotlin
class JSEngine : BlueFalconEngine {
    override suspend fun scan(serviceFilter: List<ServiceFilter>) {
        val bluetooth = window.navigator.bluetooth
        bluetooth.requestDevice(...)
    }
    // ... implement using Web Bluetooth API
}
```

## Testing Your Engine

```kotlin
class MyEngineTest {
    @Test
    fun testScanning() = runBlocking {
        val engine = MyCustomEngine()
        val blueFalcon = BlueFalcon { engine = engine }
        
        blueFalcon.scan()
        delay(1000)
        
        assertTrue(blueFalcon.peripherals.value.isNotEmpty())
    }
}
```

## See Also

- **[Official Engines](../../library/engines/)** - Production engine implementations:
  - Android Engine
  - iOS Engine
  - macOS Engine
  - JavaScript Engine
  - Windows Engine
  - Raspberry Pi Engine

- **[Core Module](../../library/core/)** - BlueFalconEngine interface definition

## Platform Integration Guides

### For New Platforms

When creating an engine for a new platform:

1. **Research the Platform BLE API**
   - Find platform-specific BLE documentation
   - Understand the async/callback patterns
   - Check permission requirements

2. **Create Platform Module**
   - Set up Kotlin Multiplatform target
   - Add platform-specific dependencies
   - Configure expect/actual declarations if needed

3. **Implement BlueFalconEngine**
   - Start with core operations (scan, connect, disconnect)
   - Add GATT operations (read, write, notify)
   - Implement advanced features (MTU, bonding, L2CAP)

4. **Test Thoroughly**
   - Test on real hardware
   - Cover edge cases (connection loss, etc.)
   - Performance testing

5. **Document**
   - Platform requirements
   - Setup instructions
   - Known limitations

## Contributing

Want to contribute an engine for a new platform? Great!

See [CONTRIBUTING.md](../../CONTRIBUTING.md) and create an ADR for major platform additions.

# Blue Falcon Testing Guide

## Table of Contents

1. [Overview](#overview)
2. [Testing Philosophy](#testing-philosophy)
3. [Testing Infrastructure](#testing-infrastructure)
4. [Unit Testing](#unit-testing)
5. [Integration Testing](#integration-testing)
6. [Platform-Specific Testing](#platform-specific-testing)
7. [Plugin Testing](#plugin-testing)
8. [Mock Implementations](#mock-implementations)
9. [Testing Best Practices](#testing-best-practices)
10. [Continuous Integration](#continuous-integration)

---

## Overview

Blue Falcon 3.0 provides a comprehensive testing infrastructure for:

- ✅ **Unit tests**: Test individual components in isolation
- ✅ **Integration tests**: Test interactions between components
- ✅ **Plugin tests**: Verify plugin behavior
- ✅ **Platform tests**: Test platform-specific engines
- ✅ **Mock support**: Fake implementations for testing

### Testing Stack

- **Framework**: Kotlin Test (`kotlin.test`)
- **Coroutines**: `kotlinx-coroutines-test`
- **Mocking**: Custom fakes and mocks
- **Assertions**: Built-in Kotlin Test assertions

---

## Testing Philosophy

### What We Test

1. **Core API Behavior**:
   - BlueFalcon client operations
   - State management (StateFlow)
   - Error handling

2. **Plugin System**:
   - Plugin lifecycle
   - Interceptor chain
   - Plugin configuration

3. **Engine Interface**:
   - Contract compliance
   - Platform abstraction

4. **Data Types**:
   - Type conversions
   - UUID handling
   - Data validation

### What We Don't Test

- Platform-specific BLE stack internals (Android BluetoothGatt, iOS CoreBluetooth)
- Operating system behavior
- Bluetooth hardware

---

## Testing Infrastructure

### Project Structure

```
library/
├── core/
│   ├── src/
│   │   ├── commonMain/         # Production code
│   │   └── commonTest/         # Common tests
│   │       ├── kotlin/
│   │       │   └── dev/bluefalcon/core/
│   │       │       ├── BlueFalconTest.kt
│   │       │       ├── PluginTest.kt
│   │       │       └── mocks/
│   │       │           ├── FakeBlueFalconEngine.kt
│   │       │           └── MockPeripheral.kt
│   └── build.gradle.kts
├── plugins/
│   └── logging/
│       └── src/
│           └── commonTest/      # Plugin-specific tests
│               └── kotlin/
│                   └── LoggingPluginTest.kt
└── engines/
    └── android/
        └── src/
            └── androidTest/     # Platform-specific tests
                └── kotlin/
                    └── AndroidEngineTest.kt
```

### Dependencies

```kotlin
// library/core/build.gradle.kts
kotlin {
    sourceSets {
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
            }
        }
    }
}
```

---

## Unit Testing

### Testing BlueFalcon Core

**Example**: Test scan operation

```kotlin
package dev.bluefalcon.core

import dev.bluefalcon.core.mocks.FakeBlueFalconEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class BlueFalconTest {
    
    @Test
    fun `scan should delegate to engine`() = runTest {
        // Given
        val engine = FakeBlueFalconEngine()
        val blueFalcon = BlueFalcon(engine)
        
        // When
        blueFalcon.scan()
        
        // Then
        assertTrue(engine.scanCalled)
        assertTrue(blueFalcon.isScanning)
    }
    
    @Test
    fun `scan with filters should pass filters to engine`() = runTest {
        // Given
        val engine = FakeBlueFalconEngine()
        val blueFalcon = BlueFalcon(engine)
        val filters = listOf(ServiceFilter(Uuid("1234")))
        
        // When
        blueFalcon.scan(filters)
        
        // Then
        assertEquals(filters, engine.lastScanFilters)
    }
    
    @Test
    fun `clearPeripherals should clear engine peripherals`() = runTest {
        // Given
        val engine = FakeBlueFalconEngine()
        val blueFalcon = BlueFalcon(engine)
        engine.addFakePeripheral("Device 1")
        
        // When
        blueFalcon.clearPeripherals()
        
        // Then
        assertEquals(0, blueFalcon.peripherals.value.size)
    }
}
```

### Testing State Management

```kotlin
@Test
fun `peripherals flow should emit discovered devices`() = runTest {
    // Given
    val engine = FakeBlueFalconEngine()
    val blueFalcon = BlueFalcon(engine)
    val devices = mutableListOf<Set<BluetoothPeripheral>>()
    
    // When
    val job = launch {
        blueFalcon.peripherals.collect { devices.add(it) }
    }
    
    engine.addFakePeripheral("Device 1")
    engine.addFakePeripheral("Device 2")
    advanceTimeBy(100)
    job.cancel()
    
    // Then
    assertTrue(devices.any { it.size == 1 })
    assertTrue(devices.any { it.size == 2 })
}
```

### Testing Error Handling

```kotlin
@Test
fun `connect should throw exception when engine fails`() = runTest {
    // Given
    val engine = FakeBlueFalconEngine().apply {
        shouldFailConnect = true
    }
    val blueFalcon = BlueFalcon(engine)
    val peripheral = engine.createFakePeripheral("Device")
    
    // When/Then
    assertFailsWith<BluetoothException> {
        blueFalcon.connect(peripheral)
    }
}
```

---

## Integration Testing

### Testing Plugin Integration

```kotlin
@Test
fun `plugins should intercept operations`() = runTest {
    // Given
    var beforeCalled = false
    var afterCalled = false
    
    val plugin = object : BlueFalconPlugin {
        override fun install(client: BlueFalconClient, config: PluginConfig) {}
        
        override suspend fun onBeforeScan(call: ScanCall): ScanCall {
            beforeCalled = true
            return call
        }
        
        override suspend fun onAfterScan(call: ScanCall) {
            afterCalled = true
        }
    }
    
    val engine = FakeBlueFalconEngine()
    val blueFalcon = BlueFalcon(engine)
    blueFalcon.plugins.install(plugin)
    
    // When
    blueFalcon.scan()
    
    // Then
    assertTrue(beforeCalled)
    assertTrue(afterCalled)
}
```

### Testing Multiple Plugins

```kotlin
@Test
fun `multiple plugins should execute in order`() = runTest {
    // Given
    val executionOrder = mutableListOf<String>()
    
    val plugin1 = createTestPlugin("Plugin1", executionOrder)
    val plugin2 = createTestPlugin("Plugin2", executionOrder)
    
    val engine = FakeBlueFalconEngine()
    val blueFalcon = BlueFalcon(engine)
    blueFalcon.plugins.install(plugin1)
    blueFalcon.plugins.install(plugin2)
    
    // When
    blueFalcon.scan()
    
    // Then
    assertEquals(
        listOf("Plugin1:before", "Plugin2:before", "Plugin2:after", "Plugin1:after"),
        executionOrder
    )
}

private fun createTestPlugin(name: String, order: MutableList<String>) = 
    object : BlueFalconPlugin {
        override fun install(client: BlueFalconClient, config: PluginConfig) {}
        
        override suspend fun onBeforeScan(call: ScanCall): ScanCall {
            order.add("$name:before")
            return call
        }
        
        override suspend fun onAfterScan(call: ScanCall) {
            order.add("$name:after")
        }
    }
```

---

## Platform-Specific Testing

### Android Engine Testing

```kotlin
// library/engines/android/src/androidTest/kotlin/AndroidEngineTest.kt
package dev.bluefalcon.engines.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.test.runTest

@RunWith(AndroidJUnit4::class)
class AndroidEngineTest {
    
    private val context: Context = ApplicationProvider.getApplicationContext()
    
    @Test
    fun `engine should initialize without errors`() {
        val engine = AndroidBlueFalconEngine(context)
        assertNotNull(engine)
    }
    
    @Test
    fun `scan should update peripherals flow`() = runTest {
        val engine = AndroidBlueFalconEngine(context)
        val devices = mutableListOf<Set<BluetoothPeripheral>>()
        
        val job = launch {
            engine.peripherals.collect { devices.add(it) }
        }
        
        // Start scan (requires proper permissions in test environment)
        // engine.scan()
        
        job.cancel()
        // Assertions based on scan results
    }
}
```

### iOS Engine Testing

```kotlin
// library/engines/ios/src/iosTest/kotlin/IosEngineTest.kt
package dev.bluefalcon.engines.ios

import kotlin.test.Test
import kotlin.test.assertNotNull

class IosEngineTest {
    
    @Test
    fun `engine should initialize`() {
        val engine = IosBlueFalconEngine()
        assertNotNull(engine)
    }
}
```

---

## Plugin Testing

### Testing Plugin Lifecycle

```kotlin
@Test
fun `plugin should be installed and configured`() = runTest {
    // Given
    var installed = false
    var configApplied = false
    
    val plugin = object : BlueFalconPlugin {
        override fun install(client: BlueFalconClient, config: PluginConfig) {
            installed = true
            if (config is TestConfig && config.value == "test") {
                configApplied = true
            }
        }
    }
    
    class TestConfig : PluginConfig() {
        var value: String = ""
    }
    
    val engine = FakeBlueFalconEngine()
    val blueFalcon = BlueFalcon {
        this.engine = engine
        install(plugin, TestConfig().apply { value = "test" })
    }
    
    // Then
    assertTrue(installed)
    assertTrue(configApplied)
}
```

### Testing Logging Plugin

```kotlin
@Test
fun `logging plugin should log operations`() = runTest {
    // Given
    val logs = mutableListOf<String>()
    val testLogger = object : Logger {
        override fun log(level: LogLevel, message: String) {
            logs.add(message)
        }
    }
    
    val plugin = LoggingPlugin(LoggingPlugin.Config().apply {
        logger = testLogger
        level = LogLevel.DEBUG
        logDiscovery = true
    })
    
    val engine = FakeBlueFalconEngine()
    val blueFalcon = BlueFalcon(engine)
    blueFalcon.plugins.install(plugin)
    
    // When
    blueFalcon.scan()
    
    // Then
    assertTrue(logs.any { it.contains("scan") })
}
```

### Testing Retry Plugin

```kotlin
@Test
fun `retry plugin should retry failed operations`() = runTest {
    // Given
    var attempts = 0
    val engine = FakeBlueFalconEngine().apply {
        onConnect = {
            attempts++
            if (attempts < 3) throw BluetoothException()
        }
    }
    
    val plugin = RetryPlugin(RetryPlugin.Config().apply {
        maxRetries = 3
        initialDelay = 10.milliseconds
    })
    
    val blueFalcon = BlueFalcon(engine)
    blueFalcon.plugins.install(plugin)
    
    val peripheral = engine.createFakePeripheral("Device")
    
    // When
    blueFalcon.connect(peripheral)
    
    // Then
    assertEquals(3, attempts)
}
```

---

## Mock Implementations

### FakeBlueFalconEngine

```kotlin
package dev.bluefalcon.core.mocks

import dev.bluefalcon.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeBlueFalconEngine : BlueFalconEngine {
    
    override val scope: CoroutineScope = TestScope()
    
    private val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    override val peripherals: StateFlow<Set<BluetoothPeripheral>> = _peripherals
    
    private val _managerState = MutableStateFlow(BluetoothManagerState.PoweredOn)
    override val managerState: StateFlow<BluetoothManagerState> = _managerState
    
    override var isScanning: Boolean = false
        private set
    
    var scanCalled = false
    var lastScanFilters: List<ServiceFilter>? = null
    var shouldFailConnect = false
    var onConnect: () -> Unit = {}
    
    override suspend fun scan(filters: List<ServiceFilter>) {
        scanCalled = true
        lastScanFilters = filters
        isScanning = true
    }
    
    override suspend fun stopScanning() {
        isScanning = false
    }
    
    override fun clearPeripherals() {
        _peripherals.value = emptySet()
    }
    
    override suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean) {
        if (shouldFailConnect) {
            throw BluetoothException()
        }
        onConnect()
    }
    
    override suspend fun disconnect(peripheral: BluetoothPeripheral) {}
    
    override fun connectionState(peripheral: BluetoothPeripheral): BluetoothPeripheralState {
        return BluetoothPeripheralState.Disconnected
    }
    
    override fun retrievePeripheral(identifier: String): BluetoothPeripheral? = null
    
    override fun requestConnectionPriority(peripheral: BluetoothPeripheral, priority: ConnectionPriority) {}
    
    override suspend fun discoverServices(peripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid>) {}
    
    override suspend fun discoverCharacteristics(
        peripheral: BluetoothPeripheral,
        service: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {}
    
    override suspend fun readCharacteristic(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic) {}
    
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {}
    
    override suspend fun notifyCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {}
    
    override suspend fun indicateCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {}
    
    override suspend fun readDescriptor(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        descriptor: BluetoothCharacteristicDescriptor
    ) {}
    
    override suspend fun writeDescriptor(
        peripheral: BluetoothPeripheral,
        descriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {}
    
    override suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int) {}
    
    override fun refreshGattCache(peripheral: BluetoothPeripheral): Boolean = true
    
    override suspend fun openL2capChannel(peripheral: BluetoothPeripheral, psm: Int) {}
    
    override suspend fun createBond(peripheral: BluetoothPeripheral) {}
    
    override suspend fun removeBond(peripheral: BluetoothPeripheral) {}
    
    // Test helpers
    fun addFakePeripheral(name: String) {
        val peripheral = FakePeripheral(name)
        _peripherals.value = _peripherals.value + peripheral
    }
    
    fun createFakePeripheral(name: String): BluetoothPeripheral = FakePeripheral(name)
}
```

### FakePeripheral

```kotlin
package dev.bluefalcon.core.mocks

import dev.bluefalcon.core.*

data class FakePeripheral(
    override val name: String?,
    override val uuid: String = "fake-uuid-${name}",
    override val rssi: Float? = -50f,
    override val mtuSize: Int? = 23,
    override val services: List<BluetoothService> = emptyList(),
    override val characteristics: List<BluetoothCharacteristic> = emptyList()
) : BluetoothPeripheral
```

### FakeCharacteristic

```kotlin
package dev.bluefalcon.core.mocks

import dev.bluefalcon.core.*

data class FakeCharacteristic(
    override val uuid: Uuid,
    override val name: String? = null,
    override var value: ByteArray? = null,
    override val descriptors: List<BluetoothCharacteristicDescriptor> = emptyList(),
    override val isNotifying: Boolean = false,
    override val service: BluetoothService? = null
) : BluetoothCharacteristic
```

---

## Testing Best Practices

### 1. Use Descriptive Test Names

✅ **Good**:

```kotlin
@Test
fun `should retry connection 3 times on failure`() { }
```

❌ **Bad**:

```kotlin
@Test
fun test1() { }
```

### 2. Follow AAA Pattern

```kotlin
@Test
fun `test name`() = runTest {
    // Arrange (Given)
    val engine = FakeBlueFalconEngine()
    val blueFalcon = BlueFalcon(engine)
    
    // Act (When)
    blueFalcon.scan()
    
    // Assert (Then)
    assertTrue(blueFalcon.isScanning)
}
```

### 3. Test One Thing Per Test

✅ **Good**:

```kotlin
@Test
fun `scan should set isScanning to true`()

@Test
fun `scan should call engine scan`()
```

❌ **Bad**:

```kotlin
@Test
fun `scan should do everything`() // Tests multiple behaviors
```

### 4. Use Test Doubles Appropriately

- **Fakes**: Simplified implementations (FakeBlueFalconEngine)
- **Stubs**: Return fixed values
- **Mocks**: Verify interactions

### 5. Clean Up Resources

```kotlin
@Test
fun `test with resources`() = runTest {
    val job = launch {
        blueFalcon.peripherals.collect { }
    }
    
    try {
        // Test code
    } finally {
        job.cancel()
    }
}
```

---

## Continuous Integration

### GitHub Actions Workflow

```yaml
# .github/workflows/test.yml
name: Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up JDK
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
      
      - name: Run tests
        run: ./gradlew test
      
      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v3
        with:
          name: test-results
          path: '**/build/test-results/**/*.xml'
```

### Running Tests Locally

```bash
# Run all tests
./gradlew test

# Run tests for specific module
./gradlew :core:test

# Run tests with coverage
./gradlew test jacocoTestReport

# Run only unit tests (exclude integration)
./gradlew test --tests "*Test"

# Run specific test
./gradlew test --tests "BlueFalconTest.scan should delegate to engine"
```

---

## Summary

Blue Falcon 3.0 provides:

- ✅ Comprehensive testing infrastructure
- ✅ Mock/fake implementations for testing
- ✅ Coroutine test support
- ✅ Platform-specific test capabilities
- ✅ CI/CD integration

### Testing Checklist

- [ ] Write unit tests for core functionality
- [ ] Create integration tests for plugins
- [ ] Add platform-specific tests as needed
- [ ] Use fakes/mocks for dependencies
- [ ] Follow AAA pattern
- [ ] Test error cases
- [ ] Verify StateFlow emissions
- [ ] Clean up resources

---

**Happy Testing!** 🧪

For more information, see:
- [API Reference](API_REFERENCE.md)
- [Plugin Development Guide](PLUGIN_DEVELOPMENT_GUIDE.md)
- [Migration Guide](MIGRATION_GUIDE.md)

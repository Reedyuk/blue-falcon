package dev.bluefalcon.core.plugin

import dev.bluefalcon.core.*
import dev.bluefalcon.core.mocks.FakeBlueFalconEngine
import dev.bluefalcon.core.mocks.FakeCharacteristic
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

/**
 * Tests for the plugin system
 */
class PluginTest {
    
    @Test
    fun `plugin should be installed via DSL`() = runTest {
        // Given
        var installed = false
        val plugin = object : BlueFalconPlugin {
            override fun install(client: BlueFalconClient, config: PluginConfig) {
                installed = true
            }
        }
        
        val engine = FakeBlueFalconEngine()
        
        // When
        val blueFalcon = BlueFalcon {
            this.engine = engine
            install(plugin)
        }
        
        // Then
        assertTrue(installed)
    }
    
    @Test
    fun `plugin should intercept scan operation`() = runTest {
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

    @Test
    fun `notification callback should be dispatched to plugin`() = runTest {
        var notificationCalled = false
        val expectedValue = byteArrayOf(0x01, 0x02, 0x03)
        val engine = FakeBlueFalconEngine()
        val peripheral = engine.createFakePeripheral("Device")
        val characteristic = FakeCharacteristic(uuid = "00002a37-0000-1000-8000-00805f9b34fb".toUuid())
        val plugin = object : BlueFalconPlugin {
            override fun install(client: BlueFalconClient, config: PluginConfig) {}

            override suspend fun onNotificationReceived(
                peripheral: BluetoothPeripheral,
                characteristic: BluetoothCharacteristic,
                value: ByteArray
            ) {
                notificationCalled = true
                assertTrue(value.contentEquals(expectedValue))
            }
        }
        val blueFalcon = BlueFalcon(engine)
        blueFalcon.plugins.install(plugin)

        engine.emitCharacteristicNotification(
            CharacteristicNotification(
                peripheral = peripheral,
                characteristic = characteristic,
                value = expectedValue
            )
        )

        assertTrue(notificationCalled)
    }

    @Test
    fun `typed write plugins transform the call and observe the exact outcome`() = runTest {
        val observedResults = mutableListOf<CharacteristicWriteResult>()
        val engine = FakeBlueFalconEngine().apply {
            typedWriteResult = CharacteristicWriteResult.Disconnected
        }
        val peripheral = engine.createFakePeripheral("Device")
        val characteristic = FakeCharacteristic(
            uuid = "00002a37-0000-1000-8000-00805f9b34fb".toUuid(),
        )
        val plugin = object : BlueFalconPlugin {
            override fun install(client: BlueFalconClient, config: PluginConfig) = Unit

            override suspend fun onBeforeCentralWrite(
                call: CentralWriteCall,
            ): CentralWriteCall = call.copy(value = byteArrayOf(9, 8, 7))

            override suspend fun onAfterCentralWrite(
                call: CentralWriteCall,
                result: CharacteristicWriteResult,
            ) {
                observedResults += result
            }
        }
        val blueFalcon = BlueFalcon(engine)
        blueFalcon.plugins.install(plugin)

        val result = blueFalcon.writeCharacteristic(
            peripheral,
            characteristic,
            byteArrayOf(1),
            CharacteristicWriteType.WithoutResponse,
        )

        assertEquals(CharacteristicWriteResult.Disconnected, result)
        assertTrue(engine.lastTypedWriteValue!!.contentEquals(byteArrayOf(9, 8, 7)))
        assertEquals(
            listOf<CharacteristicWriteResult>(CharacteristicWriteResult.Disconnected),
            observedResults,
        )
    }

    @Test
    fun `typed write converts before hook failure to a typed failure`() = runTest {
        val failure = IllegalStateException("before hook failed")
        val engine = FakeBlueFalconEngine()
        val peripheral = engine.createFakePeripheral("Device")
        val characteristic = FakeCharacteristic(
            uuid = "00002a37-0000-1000-8000-00805f9b34fb".toUuid(),
        )
        val blueFalcon = BlueFalcon(engine)
        blueFalcon.plugins.install(
            object : BlueFalconPlugin {
                override fun install(client: BlueFalconClient, config: PluginConfig) = Unit

                override suspend fun onBeforeCentralWrite(
                    call: CentralWriteCall,
                ): CentralWriteCall = throw failure
            }
        )

        val result = blueFalcon.writeCharacteristic(
            peripheral,
            characteristic,
            byteArrayOf(1),
            CharacteristicWriteType.WithResponse,
        )

        assertSame(failure, assertIs<CharacteristicWriteResult.Failed>(result).cause)
    }

    @Test
    fun `typed write preserves engine outcome when after hook fails`() = runTest {
        val failure = IllegalStateException("after hook failed")
        val expected = CharacteristicWriteResult.PayloadTooLarge(maximumLength = 20)
        val engine = FakeBlueFalconEngine().apply {
            typedWriteResult = expected
        }
        val peripheral = engine.createFakePeripheral("Device")
        val characteristic = FakeCharacteristic(
            uuid = "00002a37-0000-1000-8000-00805f9b34fb".toUuid(),
        )
        val blueFalcon = BlueFalcon(engine)
        blueFalcon.plugins.install(
            object : BlueFalconPlugin {
                override fun install(client: BlueFalconClient, config: PluginConfig) = Unit

                override suspend fun onAfterCentralWrite(
                    call: CentralWriteCall,
                    result: CharacteristicWriteResult,
                ) {
                    throw failure
                }
            }
        )

        assertEquals(
            expected,
            blueFalcon.writeCharacteristic(
                peripheral,
                characteristic,
                byteArrayOf(1),
                CharacteristicWriteType.WithResponse,
            ),
        )
    }
    
    @Test
    fun `retry capable plugin causes connect to be re-invoked on failure`() = runTest {
        // Given
        val engine = FakeBlueFalconEngine().apply {
            failConnectTimes = 2 // fails twice, succeeds on the 3rd attempt
        }
        val peripheral = engine.createFakePeripheral("Device")
        val blueFalcon = BlueFalcon(engine)
        blueFalcon.plugins.install(alwaysRetryPlugin(maxAttempts = 3))

        // When
        blueFalcon.connect(peripheral)

        // Then
        assertEquals(3, engine.connectCallCount)
    }

    @Test
    fun `retry capable plugin gives up once its retry budget is exhausted`() = runTest {
        // Given
        val engine = FakeBlueFalconEngine().apply {
            failConnectTimes = Int.MAX_VALUE // always fails
        }
        val peripheral = engine.createFakePeripheral("Device")
        val blueFalcon = BlueFalcon(engine)
        blueFalcon.plugins.install(alwaysRetryPlugin(maxAttempts = 3))

        // When
        blueFalcon.connect(peripheral)

        // Then - initial attempt + 2 retries = 3 total attempts, then it gives up
        assertEquals(3, engine.connectCallCount)
    }

    @Test
    fun `retry capable plugin causes read to be re-invoked until it succeeds`() = runTest {
        // Given
        val engine = FakeBlueFalconEngine().apply {
            failReadTimes = 1
        }
        val peripheral = engine.createFakePeripheral("Device")
        val characteristic = FakeCharacteristic(uuid = "00002a37-0000-1000-8000-00805f9b34fb".toUuid())
        val blueFalcon = BlueFalcon(engine)
        blueFalcon.plugins.install(alwaysRetryPlugin(maxAttempts = 3))

        // When
        blueFalcon.readCharacteristic(peripheral, characteristic)

        // Then
        assertEquals(2, engine.readCallCount)
    }

    @Test
    fun `retry capable plugin causes write to be re-invoked until it succeeds`() = runTest {
        // Given
        val engine = FakeBlueFalconEngine().apply {
            failWriteTimes = 1
        }
        val peripheral = engine.createFakePeripheral("Device")
        val characteristic = FakeCharacteristic(uuid = "00002a37-0000-1000-8000-00805f9b34fb".toUuid())
        val blueFalcon = BlueFalcon(engine)
        blueFalcon.plugins.install(alwaysRetryPlugin(maxAttempts = 3))

        // When
        blueFalcon.writeCharacteristic(peripheral, characteristic, byteArrayOf(1), null)

        // Then
        assertEquals(2, engine.writeCallCount)
    }

    @Test
    fun `without a retry capable plugin failures are not retried`() = runTest {
        // Given
        val engine = FakeBlueFalconEngine().apply {
            failConnectTimes = 1
        }
        val peripheral = engine.createFakePeripheral("Device")
        val blueFalcon = BlueFalcon(engine)
        // No plugins installed at all

        // When
        blueFalcon.connect(peripheral)

        // Then - only the single, failed attempt was made
        assertEquals(1, engine.connectCallCount)
    }

    /**
     * A minimal [RetryCapable] plugin used purely to exercise [PluginRegistry]'s retry loop
     * without depending on the real `blue-falcon-plugin-retry` module from core tests.
     */
    private fun alwaysRetryPlugin(maxAttempts: Int) = object : BlueFalconPlugin, RetryCapable {
        override fun install(client: BlueFalconClient, config: PluginConfig) {}

        override suspend fun retryDelay(
            operation: RetryableOperation,
            attempt: Int,
            error: Throwable
        ): kotlin.time.Duration? {
            if (attempt >= maxAttempts - 1) return null
            return kotlin.time.Duration.ZERO
        }
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
}

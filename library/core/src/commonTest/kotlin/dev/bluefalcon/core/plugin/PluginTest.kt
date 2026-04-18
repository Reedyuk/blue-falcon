package dev.bluefalcon.core.plugin

import dev.bluefalcon.core.*
import dev.bluefalcon.core.mocks.FakeBlueFalconEngine
import dev.bluefalcon.core.mocks.FakeCharacteristic
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
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

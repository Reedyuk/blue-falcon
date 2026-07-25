package dev.bluefalcon.peripheral

import dev.bluefalcon.peripheral.fake.FakePeripheralBackend
import dev.bluefalcon.peripheral.internal.DefaultBlueFalconPeripheral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PeripheralPluginRegistryTest {

    @Test
    fun installReturnsFactoryHandleAndCloseClosesPluginExactlyOnce() = runTest {
        val peripheral = DefaultBlueFalconPeripheral(FakePeripheralBackend(), coroutineContext)
        val factory = RecordingPluginFactory()

        assertEquals("installed", peripheral.plugins.install(factory) { name = "installed" })
        peripheral.close()
        peripheral.close()

        assertEquals(listOf("installed"), factory.installedNames)
        assertEquals(1, factory.closeCalls)
    }

    @Test
    fun installingSameFactoryTwiceIsRejected() = runTest {
        val peripheral = DefaultBlueFalconPeripheral(FakePeripheralBackend(), coroutineContext)
        val factory = RecordingPluginFactory()

        peripheral.plugins.install(factory) { name = "one" }

        assertFailsWith<IllegalStateException> {
            peripheral.plugins.install(factory) { name = "two" }
        }
        peripheral.close()
    }

    private class RecordingPluginFactory : PeripheralPluginFactory<RecordingConfig, String> {
        val installedNames = mutableListOf<String>()
        var closeCalls = 0

        override fun createConfig() = RecordingConfig()

        override fun create(config: RecordingConfig) = object : PeripheralPlugin<String> {
            override fun install(
                peripheral: BlueFalconPeripheral,
                scope: CoroutineScope,
            ): String {
                installedNames += config.name
                return config.name
            }

            override suspend fun close() {
                closeCalls++
            }
        }
    }

    private class RecordingConfig : PeripheralPluginConfig() {
        var name = "default"
    }
}

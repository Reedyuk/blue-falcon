package dev.bluefalcon.peripheral

import dev.bluefalcon.peripheral.fake.FakePeripheralBackend
import dev.bluefalcon.peripheral.internal.DefaultBlueFalconPeripheral
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun closeCancelsPluginScopeBeforeCallingPluginClose() = runTest {
        val peripheral = DefaultBlueFalconPeripheral(FakePeripheralBackend(), coroutineContext)
        val scopeCancelledAtClose = CompletableDeferred<Boolean>()
        var installedScope: CoroutineScope? = null
        val factory = object : PeripheralPluginFactory<PeripheralPluginConfig, Unit> {
            override fun createConfig() = PeripheralPluginConfig()

            override fun create(config: PeripheralPluginConfig) =
                object : PeripheralPlugin<Unit> {
                    override fun install(
                        peripheral: BlueFalconPeripheral,
                        scope: CoroutineScope,
                    ) {
                        installedScope = scope
                    }

                    override suspend fun close() {
                        scopeCancelledAtClose.complete(
                            installedScope?.coroutineContext?.get(Job)?.isCancelled == true,
                        )
                    }
                }
        }

        peripheral.plugins.install(factory)
        peripheral.close()

        assertTrue(scopeCancelledAtClose.await())
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

    @Test
    fun factoryCreationFailureDoesNotReserveFactorySlot() = runTest {
        val peripheral = DefaultBlueFalconPeripheral(FakePeripheralBackend(), coroutineContext)
        val factory = CreateFailingOnceFactory()

        assertFailsWith<IllegalStateException> {
            peripheral.plugins.install(factory)
        }

        assertEquals("installed", peripheral.plugins.install(factory))
        peripheral.close()
    }

    @Test
    fun distinctFactoriesThatCompareEqualCanBothBeInstalled() = runTest {
        val peripheral = DefaultBlueFalconPeripheral(FakePeripheralBackend(), coroutineContext)

        assertEquals("first", peripheral.plugins.install(EqualFactory("first")))
        assertEquals("second", peripheral.plugins.install(EqualFactory("second")))
        peripheral.close()
    }

    @Test
    fun failedPluginInstallationCancelsItsChildScope() = runTest {
        val peripheral = DefaultBlueFalconPeripheral(FakePeripheralBackend(), coroutineContext)
        val childCancelled = CompletableDeferred<Unit>()
        val factory = LaunchingFailingFactory(childCancelled)

        assertFailsWith<IllegalStateException> {
            peripheral.plugins.install(factory)
        }
        runCurrent()

        assertTrue(childCancelled.isCompleted)
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

    private class CreateFailingOnceFactory :
        PeripheralPluginFactory<PeripheralPluginConfig, String> {

        private var firstCreation = true

        override fun createConfig() = PeripheralPluginConfig()

        override fun create(config: PeripheralPluginConfig): PeripheralPlugin<String> {
            if (firstCreation) {
                firstCreation = false
                error("creation failed")
            }
            return object : PeripheralPlugin<String> {
                override fun install(
                    peripheral: BlueFalconPeripheral,
                    scope: CoroutineScope,
                ) = "installed"

                override suspend fun close() = Unit
            }
        }
    }

    private class EqualFactory(
        private val value: String,
    ) : PeripheralPluginFactory<PeripheralPluginConfig, String> {

        override fun createConfig() = PeripheralPluginConfig()

        override fun create(config: PeripheralPluginConfig) = object : PeripheralPlugin<String> {
            override fun install(
                peripheral: BlueFalconPeripheral,
                scope: CoroutineScope,
            ) = value

            override suspend fun close() = Unit
        }

        override fun equals(other: Any?): Boolean = other is EqualFactory

        override fun hashCode(): Int = 0
    }

    private class LaunchingFailingFactory(
        private val childCancelled: CompletableDeferred<Unit>,
    ) : PeripheralPluginFactory<PeripheralPluginConfig, Unit> {

        override fun createConfig() = PeripheralPluginConfig()

        override fun create(config: PeripheralPluginConfig) = object : PeripheralPlugin<Unit> {
            override fun install(
                peripheral: BlueFalconPeripheral,
                scope: CoroutineScope,
            ) {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        childCancelled.complete(Unit)
                    }
                }
                error("installation failed")
            }

            override suspend fun close() = Unit
        }
    }
}

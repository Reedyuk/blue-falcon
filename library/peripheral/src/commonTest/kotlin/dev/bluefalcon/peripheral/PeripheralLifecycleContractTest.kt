package dev.bluefalcon.peripheral

import dev.bluefalcon.peripheral.fake.FakePeripheralBackend
import dev.bluefalcon.peripheral.internal.DefaultBlueFalconPeripheral
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class PeripheralLifecycleContractTest {

    @Test
    fun startPublishesExactLifecycleAndDelegatesOnce() = runTest {
        val backend = FakePeripheralBackend()
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)
        val states = mutableListOf<PeripheralManagerState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            peripheral.state.toList(states)
        }

        peripheral.start(TestConfig)

        assertEquals(
            listOf(
                PeripheralManagerState.Stopped,
                PeripheralManagerState.Starting,
                PeripheralManagerState.Running,
            ),
            states,
        )
        assertEquals(1, backend.startCalls)
        assertEquals(listOf(TestConfig), backend.startConfigs)
    }

    @Test
    fun startFromRunningIsRejectedWithoutSecondBackendCall() = runTest {
        val backend = FakePeripheralBackend()
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)
        peripheral.start(TestConfig)

        assertFailsWith<PeripheralLifecycleException> {
            peripheral.start(TestConfig)
        }

        assertEquals(1, backend.startCalls)
        assertEquals(PeripheralManagerState.Running, peripheral.state.value)
    }

    @Test
    fun failedStartRollsBackBackendAndPublishesCause() = runTest {
        val failure = IllegalStateException("start failed")
        val backend = FakePeripheralBackend().apply { startFailure = failure }
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)

        assertSame(
            failure,
            assertFailsWith<IllegalStateException> { peripheral.start(TestConfig) },
        )

        val state = assertIs<PeripheralManagerState.Failed>(peripheral.state.value)
        assertSame(failure, state.cause)
        assertEquals(1, backend.startCalls)
        assertEquals(1, backend.stopCalls)
    }

    @Test
    fun stopIsIdempotentAndRecoversFailedState() = runTest {
        val failure = IllegalStateException("start failed")
        val backend = FakePeripheralBackend().apply { startFailure = failure }
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)
        assertFailsWith<IllegalStateException> { peripheral.start(TestConfig) }

        peripheral.stop()
        peripheral.stop()

        assertEquals(PeripheralManagerState.Stopped, peripheral.state.value)
        assertEquals(2, backend.stopCalls)
    }

    @Test
    fun closeFromRunningStopsAndClosesExactlyOnce() = runTest {
        val backend = FakePeripheralBackend()
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)
        peripheral.start(TestConfig)

        peripheral.close()
        peripheral.close()

        assertEquals(PeripheralManagerState.Closed, peripheral.state.value)
        assertEquals(1, backend.stopCalls)
        assertEquals(1, backend.closeCalls)
    }

    @Test
    fun concurrentCloseCallersShareTerminalCompletion() = runTest {
        val backend = FakePeripheralBackend()
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)
        peripheral.start(TestConfig)
        backend.blockStopUntilReleased()

        val firstClose = async(UnconfinedTestDispatcher(testScheduler)) {
            peripheral.close()
        }
        val secondClose = async(UnconfinedTestDispatcher(testScheduler)) {
            peripheral.close()
        }
        runCurrent()

        assertFalse(firstClose.isCompleted)
        assertFalse(secondClose.isCompleted)

        backend.releaseStop()
        firstClose.await()
        secondClose.await()

        assertEquals(PeripheralManagerState.Closed, peripheral.state.value)
        assertEquals(1, backend.stopCalls)
        assertEquals(1, backend.closeCalls)
    }

    @Test
    fun closeFromStoppedClosesWithoutInventingAStop() = runTest {
        val backend = FakePeripheralBackend()
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)

        peripheral.close()

        assertEquals(PeripheralManagerState.Closed, peripheral.state.value)
        assertEquals(0, backend.stopCalls)
        assertEquals(1, backend.closeCalls)
    }

    @Test
    fun closedManagerCannotRestart() = runTest {
        val backend = FakePeripheralBackend()
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)
        peripheral.close()

        assertFailsWith<PeripheralLifecycleException> {
            peripheral.start(TestConfig)
        }

        assertEquals(0, backend.startCalls)
        assertEquals(PeripheralManagerState.Closed, peripheral.state.value)
    }

    @Test
    fun startCopiesNestedAdvertisingAndGattValuesBeforeBackendCall() = runTest {
        val manufacturerValue = byteArrayOf(1, 2)
        val characteristicValue = byteArrayOf(3, 4)
        val descriptorValue = byteArrayOf(5, 6)
        val config = PeripheralConfig(
            advertiseConfig = AdvertiseConfig(
                manufacturerData = mapOf(7 to manufacturerValue),
                services = listOf(
                    GattServiceConfig(
                        uuid = "service",
                        characteristics = listOf(
                            GattCharacteristicConfig(
                                uuid = "characteristic",
                                properties = setOf(CharacteristicProperty.READ),
                                initialValue = characteristicValue,
                                descriptors = listOf(
                                    GattDescriptorConfig(
                                        uuid = "descriptor",
                                        initialValue = descriptorValue,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val backend = FakePeripheralBackend()
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)

        peripheral.start(config)
        manufacturerValue[0] = 99
        characteristicValue[0] = 99
        descriptorValue[0] = 99

        val backendConfig = backend.startConfigs.single().advertiseConfig
        assertContentEquals(byteArrayOf(1, 2), backendConfig.manufacturerData.getValue(7))
        val backendCharacteristic = backendConfig.services.single().characteristics.single()
        assertContentEquals(byteArrayOf(3, 4), backendCharacteristic.initialValue)
        assertContentEquals(
            byteArrayOf(5, 6),
            backendCharacteristic.descriptors.single().initialValue,
        )
    }

    private companion object {
        val TestConfig = PeripheralConfig(advertiseConfig = AdvertiseConfig())
    }
}

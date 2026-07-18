package dev.bluefalcon.peripheral

import dev.bluefalcon.peripheral.fake.FakePeripheralBackend
import dev.bluefalcon.peripheral.internal.DefaultBlueFalconPeripheral
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private companion object {
        val TestConfig = PeripheralConfig(advertiseConfig = AdvertiseConfig())
    }
}

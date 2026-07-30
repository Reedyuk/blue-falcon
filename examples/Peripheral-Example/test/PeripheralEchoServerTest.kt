package dev.bluefalcon.example.peripheral

import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattServerRequest
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.NotificationReadiness
import dev.bluefalcon.peripheral.NotificationReadinessState
import dev.bluefalcon.peripheral.PeripheralCapabilities
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralEvent
import dev.bluefalcon.peripheral.PeripheralManagerState
import dev.bluefalcon.peripheral.PeripheralPluginConfig
import dev.bluefalcon.peripheral.PeripheralPluginFactory
import dev.bluefalcon.peripheral.PeripheralPluginRegistry
import dev.bluefalcon.peripheral.PeripheralSession
import dev.bluefalcon.plugins.queue.PeripheralQueue
import dev.bluefalcon.plugins.queue.QueuePlugin
import dev.bluefalcon.plugins.queue.QueueSendResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class PeripheralEchoServerTest {

    @Test
    fun cancelledCloseStillClosesPeripheral() = runTest {
        val collectorStarted = CompletableDeferred<Unit>()
        val collectorCleanupStarted = CompletableDeferred<Unit>()
        val collectorCleanupGate = CompletableDeferred<Unit>()
        val peripheral = FakePeripheral(
            requests = flow {
                collectorStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        collectorCleanupStarted.complete(Unit)
                        collectorCleanupGate.await()
                    }
                }
            },
        )
        val server = PeripheralEchoServer(peripheral, backgroundScope)
        runCurrent()
        collectorStarted.await()

        val closeJob = launch {
            server.close()
        }
        runCurrent()
        collectorCleanupStarted.await()

        closeJob.cancel()
        collectorCleanupGate.complete(Unit)
        closeJob.join()

        assertEquals(1, peripheral.closeCalls)
    }

    @Test
    fun repeatedCloseRethrowsSameCleanupFailure() = runTest {
        val failure = IllegalStateException("manager close failed")
        val peripheral = FakePeripheral(
            requests = flow { awaitCancellation() },
            closeFailure = failure,
        )
        val server = PeripheralEchoServer(peripheral, backgroundScope)
        runCurrent()

        val firstFailure = assertFailsWith<IllegalStateException> {
            server.close()
        }
        val repeatedFailure = assertFailsWith<IllegalStateException> {
            server.close()
        }

        assertSame(failure, firstFailure)
        assertSame(failure, repeatedFailure)
        assertEquals(1, peripheral.closeCalls)
    }
}

private class FakePeripheral(
    override val requests: Flow<GattServerRequest>,
    private val closeFailure: Throwable? = null,
) : BlueFalconPeripheral {
    private val mutableState = MutableStateFlow<PeripheralManagerState>(
        PeripheralManagerState.Stopped,
    )
    override val state: StateFlow<PeripheralManagerState> = mutableState
    override val capabilities: PeripheralCapabilities =
        PeripheralCapabilities.Unsupported
    override val plugins: PeripheralPluginRegistry = FakePluginRegistry
    override val sessions: StateFlow<Set<PeripheralSession>> =
        MutableStateFlow(emptySet())
    override val events: Flow<PeripheralEvent> = emptyFlow()
    override val notificationReadiness: Flow<NotificationReadiness> = emptyFlow()
    override val notificationReadinessState: StateFlow<NotificationReadinessState> =
        MutableStateFlow(NotificationReadinessState())

    var closeCalls = 0
        private set

    override suspend fun start(config: PeripheralConfig) {
        mutableState.value = PeripheralManagerState.Running
    }

    override suspend fun stop() {
        mutableState.value = PeripheralManagerState.Stopped
    }

    override suspend fun close() {
        closeCalls += 1
        closeFailure?.let { throw it }
        mutableState.value = PeripheralManagerState.Closed
    }
}

private object FakePluginRegistry : PeripheralPluginRegistry {
    override fun <C : PeripheralPluginConfig, T> install(
        factory: PeripheralPluginFactory<C, T>,
        configure: C.() -> Unit,
    ): T {
        check(factory === QueuePlugin)
        configure(factory.createConfig())
        @Suppress("UNCHECKED_CAST")
        return FakeQueue as T
    }
}

private object FakeQueue : PeripheralQueue {
    override suspend fun send(
        session: PeripheralSession,
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode,
    ): QueueSendResult = QueueSendResult.Unsupported
}

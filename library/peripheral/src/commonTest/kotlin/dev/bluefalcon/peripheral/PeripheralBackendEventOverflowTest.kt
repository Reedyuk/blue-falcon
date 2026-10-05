package dev.bluefalcon.peripheral

import dev.bluefalcon.peripheral.fake.FakePeripheralBackend
import dev.bluefalcon.peripheral.internal.DefaultBlueFalconPeripheral
import dev.bluefalcon.peripheral.internal.BackendCharacteristicReadRequest
import dev.bluefalcon.peripheral.internal.BackendGattResponder
import dev.bluefalcon.core.toUuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PeripheralBackendEventOverflowTest {
    @Test
    fun overflowExpiresPendingResponseWithoutCallingClosedResponder() = runTest {
        val backend = FakePeripheralBackend()
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext, backendEventCapacity = 2)
        try {
            peripheral.start(PeripheralConfig(AdvertiseConfig()))
            val request = async(UnconfinedTestDispatcher(testScheduler)) { peripheral.requests.first() }
            var responseCalls = 0
            backend.eventSink.onRequest(BackendCharacteristicReadRequest(
                PeripheralSessionId("peer"),
                GattServiceId("180d".toUuid()),
                GattCharacteristicId("2a37".toUuid()),
                0,
                object : BackendGattResponder {
                    override fun respond(status: GattResponseStatus, value: ByteArray?) {
                        responseCalls++
                        error("Platform responder already closed")
                    }
                },
            ))
            runCurrent()
            val response = request.await().response!!
            repeat(100) { backend.updateMaximumValueLength(PeripheralSessionId("peer"), 20) }
            runCurrent()
            assertIs<PeripheralManagerState.Failed>(peripheral.state.value)
            assertEquals(1, backend.stopCalls)
            assertTrue(peripheral.sessions.value.isEmpty())
            assertEquals(0, responseCalls)
            assertEquals(GattResponseResult.Expired, response.respond(GattResponseStatus.Success))
        } finally {
            peripheral.close()
        }
    }

    @Test
    fun restartAtFailurePublicationHasFullQueueCapacity() = runTest {
        val backend = FakePeripheralBackend()
        val dispatcher = PausedDispatcher()
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext + dispatcher, backendEventCapacity = 2)
        try {
            val config = PeripheralConfig(AdvertiseConfig())
            peripheral.start(config)
            repeat(100) { backend.openSession(PeripheralSessionId("old-$it")) }
            // Run the overflow monitor while the event consumer stays paused.
            dispatcher.runLast()
            assertIs<PeripheralManagerState.Failed>(peripheral.state.value)
            peripheral.stop()
            peripheral.start(config)
            repeat(2) { backend.openSession(PeripheralSessionId("new-$it")) }
            dispatcher.runAll()
            assertEquals(PeripheralManagerState.Running, peripheral.state.value)
            assertEquals(setOf(PeripheralSessionId("new-0"), PeripheralSessionId("new-1")),
                peripheral.sessions.value.map { it.id }.toSet())
        } finally {
            val closing = async(UnconfinedTestDispatcher(testScheduler)) { peripheral.close() }
            dispatcher.runAll()
            closing.await()
        }
    }

    @Test
    fun oldRequestSinkCannotPolluteRestartedEventStream() = runTest {
        val backend = FakePeripheralBackend()
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)
        try {
            val config = PeripheralConfig(AdvertiseConfig())
            peripheral.start(config)
            val oldSink = backend.eventSink
            peripheral.stop()
            peripheral.start(config)
            val events = mutableListOf<PeripheralEvent>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                peripheral.events.collect { events += it }
            }
            var rejected = 0
            repeat(100) {
                oldSink.onRequest(BackendCharacteristicReadRequest(
                    PeripheralSessionId("old"),
                    GattServiceId("180d".toUuid()),
                    GattCharacteristicId("2a37".toUuid()),
                    0,
                    object : BackendGattResponder {
                        override fun respond(status: GattResponseStatus, value: ByteArray?) {
                            assertEquals(GattResponseStatus.UnlikelyError, status)
                            rejected++
                        }
                    },
                ))
            }
            runCurrent()
            assertEquals(100, rejected)
            assertTrue(events.isEmpty())
            collector.cancel()
        } finally {
            peripheral.close()
        }
    }

    @Test
    fun overflowRejectsRequestsAndClosesExistingSessions() = runTest {
        val backend = FakePeripheralBackend()
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext, backendEventCapacity = 2)
        try {
            peripheral.start(PeripheralConfig(AdvertiseConfig()))
            val sessionId = PeripheralSessionId("peer")
            backend.openSession(sessionId, 20)
            runCurrent()
            assertEquals(1, peripheral.sessions.value.size)
            repeat(100) { backend.updateMaximumValueLength(sessionId, 20) }
            val response = backend.emitCharacteristicRead(
                sessionId,
                GattServiceId("180d".toUuid()),
                GattCharacteristicId("2a37".toUuid()),
            )
            assertEquals(GattResponseStatus.UnlikelyError, response.responses.single().status)
            runCurrent()
            assertIs<PeripheralManagerState.Failed>(peripheral.state.value)
            assertTrue(peripheral.sessions.value.isEmpty())
            assertEquals(1, backend.stopCalls)
        } finally {
            peripheral.close()
        }
    }

    @Test
    fun restartIgnoresOldSinkFloodAndProcessesCurrentEvents() = runTest {
        val backend = FakePeripheralBackend()
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext, backendEventCapacity = 2)
        try {
            val config = PeripheralConfig(AdvertiseConfig())
            peripheral.start(config)
            val oldSink = backend.eventSink
            repeat(100) { backend.openSession(PeripheralSessionId("old-$it")) }
            runCurrent()
            assertIs<PeripheralManagerState.Failed>(peripheral.state.value)
            peripheral.stop()
            peripheral.start(config)
            repeat(10_000) { oldSink.onSessionOpened(PeripheralSessionId("stale-$it"), 20) }
            backend.openSession(PeripheralSessionId("current"), 20)
            runCurrent()
            assertEquals(PeripheralManagerState.Running, peripheral.state.value)
            assertEquals(listOf(PeripheralSessionId("current")), peripheral.sessions.value.map { it.id })
        } finally {
            peripheral.close()
        }
    }

    @Test
    fun stalledConsumerFailsObservablyInsteadOfAccumulatingRemoteSessionEvents() = runTest {
        val backend = FakePeripheralBackend()
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)
        try {
            peripheral.start(PeripheralConfig(AdvertiseConfig()))
            // Do not advance the test dispatcher: the single consumer is stalled.
            repeat(10_000) { backend.openSession(PeripheralSessionId("peer-$it"), 20) }
            runCurrent()
            val failed = assertIs<PeripheralManagerState.Failed>(peripheral.state.value)
            assertTrue(failed.cause.message!!.contains("capacity exceeded"))
            assertEquals(1, backend.stopCalls)
            assertTrue(peripheral.sessions.value.isEmpty())
        } finally {
            peripheral.close()
        }
    }

    private class PausedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }
        fun runLast() = tasks.removeLast().run()
        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}

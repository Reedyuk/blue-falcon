package dev.bluefalcon.peripheral

import dev.bluefalcon.peripheral.fake.FakePeripheralBackend
import dev.bluefalcon.peripheral.internal.DefaultBlueFalconPeripheral
import dev.bluefalcon.core.toUuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PeripheralBackendEventOverflowTest {
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
            assertTrue(failed.cause.message!!.contains("Backend event queue"))
            assertEquals(1, backend.stopCalls)
            assertTrue(peripheral.sessions.value.isEmpty())
        } finally {
            peripheral.close()
        }
    }
}

package dev.bluefalcon.peripheral

import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.fake.FakePeripheralBackend
import dev.bluefalcon.peripheral.internal.DefaultBlueFalconPeripheral
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import dev.bluefalcon.peripheral.internal.BackendSessionToken

@OptIn(ExperimentalCoroutinesApi::class)
class PeripheralIngressIncarnationTest {
    @Test fun closedSessionCannotBeReopenedByEarlierReadIngress() = runTest {
        val backend = FakePeripheralBackend()
        val manager = DefaultBlueFalconPeripheral(backend, coroutineContext)
        manager.start(PeripheralConfig(AdvertiseConfig()))
        val id = PeripheralSessionId("old-central")
        backend.openSession(id)
        backend.emitCharacteristicRead(id, GattServiceId("0000180d-0000-1000-8000-00805f9b34fb".toUuid()), GattCharacteristicId("00002a37-0000-1000-8000-00805f9b34fb".toUuid()))
        backend.closeSession(id)
        runCurrent()
        val observed = manager.sessions.value.toList()
        manager.close()
        assertTrue(observed.isEmpty(), "Earlier request resurrected a disconnected session: $observed")
    }
    @Test fun staleQueuedIngressDoesNotPublishDropInReplacementRun() = runTest {
        val backend = FakePeripheralBackend()
        val manager = DefaultBlueFalconPeripheral(backend, coroutineContext, requestCapacity = 1)
        val config = PeripheralConfig(AdvertiseConfig())
        val id = PeripheralSessionId("reused")
        val service = GattServiceId("180d".toUuid())
        val characteristic = GattCharacteristicId("2a37".toUuid())
        val events = mutableListOf<PeripheralEvent>()
        val collector = backgroundScope.launch { manager.events.collect { events += it } }
        manager.start(config)
        backend.openSession(id)
        backend.emitCharacteristicRead(id, service, characteristic)
        // Queue before ingress processing, then reuse the ID in the next run.
        manager.stop()
        manager.start(config)
        backend.openSession(id)
        runCurrent()
        assertEquals(1, manager.sessions.value.size)
        assertTrue(events.none { it is PeripheralEvent.RequestDropped })
        manager.close()
        collector.cancel()
    }

    @Test fun retiredExplicitOpenCannotRetireReplacementAuthority() = runTest {
        val backend = FakePeripheralBackend()
        val manager = DefaultBlueFalconPeripheral(backend, coroutineContext)
        manager.start(PeripheralConfig(AdvertiseConfig()))
        val id = PeripheralSessionId("same")
        val first = BackendSessionToken(id)
        val second = BackendSessionToken(id)
        val sink = backend.eventSink
        sink.onSessionOpened(first, 20)
        runCurrent()
        sink.onSessionClosed(first)
        sink.onSessionOpened(second, 20)
        runCurrent()
        val replacement = manager.sessions.value.single()
        sink.onSessionOpened(first, 20)
        runCurrent()
        assertTrue(second.isCurrent(), "Late retired open revoked the replacement backend token")
        assertEquals(replacement, manager.sessions.value.single())
        assertEquals(NotificationResult.Sent, replacement.notify(GattCharacteristicId("2a37".toUuid()), byteArrayOf(1)))
        manager.close()
    }

}

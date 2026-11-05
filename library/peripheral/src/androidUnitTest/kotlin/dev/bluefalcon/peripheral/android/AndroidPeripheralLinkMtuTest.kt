package dev.bluefalcon.peripheral.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import dev.bluefalcon.core.NoOpLogger
import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.*
import dev.bluefalcon.peripheral.internal.DefaultBlueFalconPeripheral
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Mirrors the framework stack: fenced MTU reports are remembered, connects recall them. */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPeripheralLinkMtuTest {
    @Test fun connectedWithReportedLinkMtuOpensAtThatCapacity() = runTest {
        listOf(23 to 20, 26 to 23, 293 to 290, 517 to 512, Int.MAX_VALUE to 512, 0 to 0).forEach { (mtu, maximum) ->
            val stack = FakeAndroidBluetoothStack()
            val sink = RecordingBackendSink()
            val backend = AndroidPeripheralBackend(stack, NoOpLogger)
            try {
                backend.start(config(), sink)
                stack.emit(AndroidGattEvent.Connected(Id, attMtu = mtu))
                assertEquals(Id to maximum, sink.openedSessions.single(), "MTU $mtu")
            } finally { backend.close() }
        }
    }

    @Test fun connectedWithoutReportedLinkMtuKeepsAttDefault() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val sink = RecordingBackendSink()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        try {
            backend.start(config(), sink)
            stack.emit(AndroidGattEvent.Connected(Id))
            assertEquals(Id to 20, sink.openedSessions.single())
        } finally { backend.close() }
    }

    @Test fun replacementServerOnStillUpLinkSeedsFromRememberedMtu() = runTest {
        val memory = AndroidLinkMtuMemory().also { it.startObserving() }
        cycle(memory) { stack, session ->
            assertEquals(20, session.maximumUpdateValueLength.value)
            stack.emitMtu(memory, Id, Address, 293)
            runCurrent()
            assertEquals(290, session.maximumUpdateValueLength.value)
        }
        cycle(memory) { stack, session ->
            assertEquals(290, session.maximumUpdateValueLength.value)
            subscribe(stack, Id)
            runCurrent()
            assertEquals(NotificationResult.Sent, session.notify(Char, ByteArray(23)))
            assertEquals(23, stack.notifications.single().value.size)
        }
    }

    @Test fun aclDisconnectReturnsTheNextServerToAttDefault() = runTest {
        val memory = AndroidLinkMtuMemory().also { it.startObserving() }
        cycle(memory) { stack, _ -> stack.emitMtu(memory, Id, Address, 293) }
        memory.onBroadcast(BluetoothDevice.ACTION_ACL_DISCONNECTED, Address, BluetoothAdapter.ERROR)
        cycle(memory) { stack, session ->
            assertEquals(20, session.maximumUpdateValueLength.value)
            subscribe(stack, Id)
            runCurrent()
            assertIs<NotificationResult.Failed>(session.notify(Char, ByteArray(23)))
        }
    }

    @Test fun adapterOffReturnsTheNextServerToAttDefault() = runTest {
        val memory = AndroidLinkMtuMemory().also { it.startObserving() }
        cycle(memory) { stack, _ -> stack.emitMtu(memory, Id, Address, 293) }
        memory.onBroadcast(BluetoothAdapter.ACTION_STATE_CHANGED, null, BluetoothAdapter.STATE_OFF)
        cycle(memory) { _, session -> assertEquals(20, session.maximumUpdateValueLength.value) }
    }

    @Test fun rememberedMtuNeverSeedsADifferentDevice() = runTest {
        val memory = AndroidLinkMtuMemory().also { it.startObserving() }
        cycle(memory) { stack, _ -> stack.emitMtu(memory, Id, Address, 293) }
        cycle(memory, id = Other, address = OtherAddress) { _, session ->
            assertEquals(20, session.maximumUpdateValueLength.value)
        }
        cycle(memory) { _, session -> assertEquals(290, session.maximumUpdateValueLength.value) }
    }

    @Test fun staleGenerationConnectedAndMtuCannotSeedTheCurrentSession() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val manager = DefaultBlueFalconPeripheral(AndroidPeripheralBackend(stack, NoOpLogger), coroutineContext)
        try {
            manager.start(config())
            val old = Target()
            stack.emit(AndroidGattEvent.Connected(Id, old))
            manager.stop()
            manager.start(config())
            stack.emitFrom(0, AndroidGattEvent.Connected(Id, old, attMtu = 517))
            runCurrent()
            assertTrue(manager.sessions.value.isEmpty())
            val current = Target()
            stack.emit(AndroidGattEvent.Connected(Id, current))
            stack.emitFrom(0, AndroidGattEvent.MtuChanged(Id, 517, old))
            stack.emit(AndroidGattEvent.MtuChanged(Id, 517, old))
            runCurrent()
            assertEquals(20, manager.sessions.value.single().maximumUpdateValueLength.value)
        } finally { manager.close() }
    }

    /** One peripheral manager lifetime with its own stack and backend, as after a full retire. */
    private suspend fun TestScope.cycle(
        memory: AndroidLinkMtuMemory,
        id: PeripheralSessionId = Id,
        address: String = Address,
        body: suspend (FakeAndroidBluetoothStack, PeripheralSession) -> Unit,
    ) {
        val stack = FakeAndroidBluetoothStack()
        val manager = DefaultBlueFalconPeripheral(AndroidPeripheralBackend(stack, NoOpLogger), coroutineContext)
        try {
            manager.start(config())
            stack.emit(AndroidGattEvent.Connected(id, attMtu = memory.recall(address)))
            runCurrent()
            body(stack, manager.sessions.value.single { it.id == id })
        } finally { manager.close() }
    }

    private fun FakeAndroidBluetoothStack.emitMtu(
        memory: AndroidLinkMtuMemory,
        id: PeripheralSessionId,
        address: String,
        mtu: Int,
    ) {
        memory.remember(address, mtu)
        emit(AndroidGattEvent.MtuChanged(id, mtu))
    }

    private class Target : AndroidSessionTarget {
        var current = true
        override fun isCurrent() = current
        override fun sendResponse(response: AndroidGattResponse) = true
        override fun notify(request: AndroidNotificationRequest) = AndroidNotificationStartResult.Accepted
        override fun disconnect() = true
    }

    private fun config() = PeripheralConfig(AdvertiseConfig(services = listOf(
        GattServiceConfig(Service.uuid.toString(), listOf(
            GattCharacteristicConfig(Char.uuid.toString(), setOf(CharacteristicProperty.NOTIFY)),
        )),
    )))

    private fun subscribe(stack: FakeAndroidBluetoothStack, id: PeripheralSessionId) {
        stack.emit(AndroidGattEvent.DescriptorWrite(id, 1, Service, Char,
            GattDescriptorId("2902".toUuid()), 0, false, false, byteArrayOf(1, 0)))
    }

    private companion object {
        const val Address = "AA:AA:AA:AA:AA:AA"
        const val OtherAddress = "BB:BB:BB:BB:BB:BB"
        val Id = PeripheralSessionId(Address)
        val Other = PeripheralSessionId(OtherAddress)
        val Service = GattServiceId("180d".toUuid())
        val Char = GattCharacteristicId("2a37".toUuid())
    }
}

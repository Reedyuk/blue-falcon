package dev.bluefalcon.peripheral.android

import dev.bluefalcon.core.NoOpLogger
import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.*
import dev.bluefalcon.peripheral.internal.DefaultBlueFalconPeripheral
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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


    private companion object {
        val Id = PeripheralSessionId("AA:AA:AA:AA:AA:AA")
        val Service = GattServiceId("180d".toUuid())
        val Char = GattCharacteristicId("2a37".toUuid())
    }
}

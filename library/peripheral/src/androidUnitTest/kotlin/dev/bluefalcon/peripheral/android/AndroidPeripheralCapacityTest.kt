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
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPeripheralCapacityTest {
    @Test fun mtu517Publishes512ToPublicSession() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val manager = DefaultBlueFalconPeripheral(AndroidPeripheralBackend(stack, NoOpLogger), coroutineContext)
        try {
            manager.start(config())
            stack.emit(AndroidGattEvent.Connected(Id))
            stack.emit(AndroidGattEvent.MtuChanged(Id, 517))
            runCurrent()
            assertEquals(512, manager.sessions.value.single().maximumUpdateValueLength.value)
        } finally { manager.close() }
    }

    @Test fun publicNotificationBoundariesAndPerSessionLimitsReachNativeFake() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val manager = DefaultBlueFalconPeripheral(AndroidPeripheralBackend(stack, NoOpLogger), coroutineContext)
        try {
            manager.start(config())
            stack.emit(AndroidGattEvent.Connected(Id))
            stack.emit(AndroidGattEvent.Connected(Other))
            subscribe(stack, Id)
            subscribe(stack, Other)
            stack.emit(AndroidGattEvent.MtuChanged(Id, 517))
            stack.emit(AndroidGattEvent.MtuChanged(Other, 26))
            runCurrent()
            val session = manager.sessions.value.single { it.id == Id }
            val other = manager.sessions.value.single { it.id == Other }
            assertEquals(512, session.maximumUpdateValueLength.value)
            assertEquals(23, other.maximumUpdateValueLength.value)
            val failure = assertIs<NotificationResult.Failed>(session.notify(Char, ByteArray(513)))
            assertEquals("Android notification value size 513 exceeds the negotiated limit 512", assertIs<AndroidNotificationValueTooLongException>(failure.cause).message)
            assertTrue(stack.notifications.isEmpty())
            assertIs<NotificationResult.Failed>(other.notify(Char, ByteArray(512)))
            assertTrue(stack.notifications.isEmpty())
            assertEquals(NotificationResult.Sent, session.notify(Char, ByteArray(512)))
            assertEquals(512, stack.notifications.single().value.size)
            stack.emit(AndroidGattEvent.NotificationSent(Id, 0))
            stack.emit(AndroidGattEvent.MtuChanged(Id, 26))
            runCurrent()
            assertEquals(23, session.maximumUpdateValueLength.value)
            assertIs<NotificationResult.Failed>(session.notify(Char, ByteArray(512)))
            assertEquals(1, stack.notifications.size)
            stack.emit(AndroidGattEvent.MtuChanged(Id, 517))
            runCurrent()
            assertEquals(512, session.maximumUpdateValueLength.value)
            subscribe(stack, Id, enabled = false)
            runCurrent()
            assertEquals(NotificationResult.Unsupported, session.notify(Char, ByteArray(512)))
            assertEquals(1, stack.notifications.size)
            manager.close()
            stack.emit(AndroidGattEvent.MtuChanged(Id, 517))
            assertEquals(NotificationResult.Disconnected, session.notify(Char, ByteArray(512)))
            assertEquals(1, stack.notifications.size)
        } finally { manager.close() }
    }

    @Test fun backendCapacityBoundariesShrinkGrowAndDoNotOverflow() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val sink = RecordingBackendSink()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        try {
            backend.start(config(), sink)
            stack.emit(AndroidGattEvent.Connected(Id))
            listOf(23 to 20, 26 to 23, 293 to 290, 514 to 511, 515 to 512,
                517 to 512, Int.MAX_VALUE to 512, 26 to 23, 3 to 0, 2 to 0,
                0 to 0, -1 to 0, Int.MIN_VALUE to 0, 517 to 512).forEach { (mtu, maximum) ->
                stack.emit(AndroidGattEvent.MtuChanged(Id, mtu))
                assertEquals(Id to maximum, sink.maximumLengths.last(), "MTU $mtu")
            }
        } finally { backend.close() }
    }

    @Test fun retiredNativeTargetCannotEnlargeSameIdReplacementOrRestart() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val manager = DefaultBlueFalconPeripheral(AndroidPeripheralBackend(stack, NoOpLogger), coroutineContext)
        try {
            manager.start(config())
            val old = Target()
            stack.emit(AndroidGattEvent.Connected(Id, old))
            stack.emit(AndroidGattEvent.MtuChanged(Id, 517, old))
            runCurrent()
            val retiredSession = manager.sessions.value.single()
            old.current = false
            stack.emit(AndroidGattEvent.Disconnected(Id, 0, old))
            val current = Target()
            stack.emit(AndroidGattEvent.Connected(Id, current))
            stack.emit(AndroidGattEvent.MtuChanged(Id, 26, current))
            stack.emit(AndroidGattEvent.MtuChanged(Id, 517, old))
            runCurrent()
            assertEquals(23, manager.sessions.value.single().maximumUpdateValueLength.value)
            assertEquals(NotificationResult.Disconnected, retiredSession.notify(Char, ByteArray(512)))
            manager.stop()
            val before = manager.sessions.value
            stack.emit(AndroidGattEvent.MtuChanged(Id, 517, current))
            runCurrent()
            assertEquals(before, manager.sessions.value)
            manager.start(config())
            val restarted = Target()
            stack.emit(AndroidGattEvent.Connected(Id, restarted))
            stack.emitFrom(0, AndroidGattEvent.MtuChanged(Id, 517, current))
            runCurrent()
            assertEquals(20, manager.sessions.value.single().maximumUpdateValueLength.value)
            assertTrue(stack.notifications.isEmpty())
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
    private fun subscribe(stack: FakeAndroidBluetoothStack, id: PeripheralSessionId, enabled: Boolean = true) {
        stack.emit(AndroidGattEvent.DescriptorWrite(id, 1, Service, Char,
            GattDescriptorId("2902".toUuid()), 0, false, false,
            if (enabled) byteArrayOf(1, 0) else byteArrayOf(0, 0)))
    }
    private companion object {
        val Id = PeripheralSessionId("same")
        val Other = PeripheralSessionId("other")
        val Service = GattServiceId("180d".toUuid())
        val Char = GattCharacteristicId("2a37".toUuid())
    }
}

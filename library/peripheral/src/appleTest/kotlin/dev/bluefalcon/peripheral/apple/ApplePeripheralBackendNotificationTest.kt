package dev.bluefalcon.peripheral.apple

import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.DisconnectResult
import dev.bluefalcon.peripheral.GattCharacteristicConfig
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattServiceConfig
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.NotificationReadiness
import dev.bluefalcon.peripheral.NotificationResult
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralSessionId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ApplePeripheralBackendNotificationTest {

    @Test
    fun disconnectedSessionDoesNotReachStack() = runTest {
        val stack = FakeApplePeripheralStack()
        val backend = startedBackend(stack)

        val result = backend.notify(
            SessionId,
            CharacteristicId,
            byteArrayOf(1),
            NotificationMode.Notification,
        )

        assertEquals(NotificationResult.Disconnected, result)
        assertTrue(stack.notifications.isEmpty())
    }

    @Test
    fun unsupportedModeAndMissingSubscriptionDoNotReachStack() = runTest {
        val stack = FakeApplePeripheralStack()
        val backend = startedBackend(stack)
        stack.emit(AppleGattEvent.Subscribed(SessionId, 20, CharacteristicId))

        assertEquals(
            NotificationResult.Unsupported,
            backend.notify(
                SessionId,
                CharacteristicId,
                byteArrayOf(1),
                NotificationMode.Indication,
            ),
        )

        stack.emit(AppleGattEvent.Unsubscribed(SessionId, 20, CharacteristicId))
        assertEquals(
            NotificationResult.Unsupported,
            backend.notify(
                SessionId,
                CharacteristicId,
                byteArrayOf(1),
                NotificationMode.Notification,
            ),
        )
        assertTrue(stack.notifications.isEmpty())
    }

    @Test
    fun oversizedPayloadFailsBeforePlatformCall() = runTest {
        val stack = FakeApplePeripheralStack()
        val backend = startedBackend(stack)
        stack.emit(AppleGattEvent.Subscribed(SessionId, 2, CharacteristicId))

        val result = backend.notify(
            SessionId,
            CharacteristicId,
            byteArrayOf(1, 2, 3),
            NotificationMode.Notification,
        )

        assertIs<NotificationResult.Failed>(result)
        assertIs<AppleNotificationValueTooLongException>(result.cause)
        assertTrue(stack.notifications.isEmpty())
    }

    @Test
    fun acceptedUpdateIsTargetedAndPayloadIsCopied() = runTest {
        val stack = FakeApplePeripheralStack()
        val backend = startedBackend(stack)
        stack.emit(AppleGattEvent.Subscribed(SessionId, 20, CharacteristicId))
        val value = byteArrayOf(1, 2, 3)

        val result = backend.notify(
            SessionId,
            CharacteristicId,
            value,
            NotificationMode.Notification,
        )
        value[0] = 99

        assertEquals(NotificationResult.Sent, result)
        val request = stack.notifications.single()
        assertEquals(SessionId, request.sessionId)
        assertEquals(CharacteristicId, request.characteristicId)
        assertEquals(NotificationMode.Notification, request.mode)
        assertContentEquals(byteArrayOf(1, 2, 3), request.value)
    }

    @Test
    fun platformResultsMapExactly() = runTest {
        val stack = FakeApplePeripheralStack()
        val backend = startedBackend(stack)
        stack.emit(AppleGattEvent.Subscribed(SessionId, 20, CharacteristicId))

        stack.notificationResult = AppleNotificationStartResult.Busy
        assertEquals(NotificationResult.Busy, notify(backend))

        stack.notificationResult = AppleNotificationStartResult.Disconnected
        assertEquals(NotificationResult.Disconnected, notify(backend))

        val failure = IllegalStateException("rejected")
        stack.notificationResult = AppleNotificationStartResult.Rejected(failure)
        val failed = assertIs<NotificationResult.Failed>(notify(backend))
        assertSame(failure, failed.cause)
    }

    @Test
    fun readinessIsManagerWideAndForcedDisconnectIsUnsupported() = runTest {
        val stack = FakeApplePeripheralStack()
        val sink = RecordingAppleBackendSink()
        val backend = ApplePeripheralBackend(stack, logger = null)
        backend.start(Config, sink)

        stack.emit(AppleGattEvent.NotificationReady)

        assertEquals(
            listOf<NotificationReadiness>(NotificationReadiness.Manager),
            sink.readinessEvents,
        )
        assertEquals(DisconnectResult.Unsupported, backend.disconnect(SessionId))
    }

    private suspend fun startedBackend(
        stack: FakeApplePeripheralStack,
    ): ApplePeripheralBackend = ApplePeripheralBackend(stack, logger = null).also {
        it.start(Config, RecordingAppleBackendSink())
    }

    private suspend fun notify(backend: ApplePeripheralBackend) = backend.notify(
        SessionId,
        CharacteristicId,
        byteArrayOf(1),
        NotificationMode.Notification,
    )

    private companion object {
        const val ServiceUuid = "0000180d-0000-1000-8000-00805f9b34fb"
        const val CharacteristicUuid = "00002a37-0000-1000-8000-00805f9b34fb"
        val CharacteristicId = GattCharacteristicId(CharacteristicUuid.toUuid())
        val SessionId = PeripheralSessionId("central")
        val Config = PeripheralConfig(
            AdvertiseConfig(
                services = listOf(
                    GattServiceConfig(
                        uuid = ServiceUuid,
                        characteristics = listOf(
                            GattCharacteristicConfig(
                                uuid = CharacteristicUuid,
                                properties = setOf(CharacteristicProperty.NOTIFY),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }
}

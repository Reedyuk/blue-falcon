package dev.bluefalcon.peripheral.apple

import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralSessionId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplePeripheralBackendSessionTest {

    @Test
    fun subscribeCreatesSessionAndPublishesCompleteSubscriptionSet() = runTest {
        val stack = FakeApplePeripheralStack()
        val backend = ApplePeripheralBackend(stack, logger = null)
        val sink = RecordingAppleBackendSink()
        backend.start(PeripheralConfig(AdvertiseConfig()), sink)

        stack.emit(
            AppleGattEvent.Subscribed(SessionId, 180, CharacteristicId),
        )
        stack.emit(
            AppleGattEvent.Subscribed(SessionId, 180, OtherCharacteristicId),
        )
        stack.emit(
            AppleGattEvent.Unsubscribed(SessionId, 180, CharacteristicId),
        )
        stack.emit(
            AppleGattEvent.Unsubscribed(SessionId, 180, OtherCharacteristicId),
        )

        assertEquals(listOf<Pair<PeripheralSessionId, Int?>>(SessionId to 180), sink.openedSessions)
        assertEquals(
            listOf(
                SessionId to setOf(CharacteristicId),
                SessionId to setOf(CharacteristicId, OtherCharacteristicId),
                SessionId to setOf(OtherCharacteristicId),
                SessionId to emptySet(),
            ),
            sink.subscriptionUpdates,
        )
        assertEquals(emptyList(), sink.closedSessions)
    }

    @Test
    fun existingSessionPublishesChangedMaximumLength() = runTest {
        val stack = FakeApplePeripheralStack()
        val backend = ApplePeripheralBackend(stack, logger = null)
        val sink = RecordingAppleBackendSink()
        backend.start(PeripheralConfig(AdvertiseConfig()), sink)

        stack.emit(AppleGattEvent.Subscribed(SessionId, 180, CharacteristicId))
        stack.emit(AppleGattEvent.Subscribed(SessionId, 120, OtherCharacteristicId))

        assertEquals(
            listOf<Pair<PeripheralSessionId, Int?>>(SessionId to 120),
            sink.maximumLengthUpdates,
        )
    }

    @Test
    fun restoredSessionsArePublishedBeforeStartReturns() = runTest {
        val stack = FakeApplePeripheralStack().apply {
            openResult = AppleOpenResult(
                restored = true,
                advertising = true,
                restoredSessions = listOf(
                    AppleRestoredSession(
                        sessionId = SessionId,
                        maximumUpdateValueLength = 180,
                        subscriptions = setOf(CharacteristicId, OtherCharacteristicId),
                    ),
                ),
            )
        }
        val backend = ApplePeripheralBackend(stack, logger = null)
        val sink = RecordingAppleBackendSink()

        backend.start(PeripheralConfig(AdvertiseConfig()), sink)

        assertEquals(listOf<Pair<PeripheralSessionId, Int?>>(SessionId to 180), sink.openedSessions)
        assertEquals(
            listOf(SessionId to setOf(CharacteristicId, OtherCharacteristicId)),
            sink.subscriptionUpdates,
        )
    }

    private companion object {
        val SessionId = PeripheralSessionId("central")
        val CharacteristicId =
            GattCharacteristicId("00002a37-0000-1000-8000-00805f9b34fb".toUuid())
        val OtherCharacteristicId =
            GattCharacteristicId("00002a38-0000-1000-8000-00805f9b34fb".toUuid())
    }
}

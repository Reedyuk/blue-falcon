package dev.bluefalcon.peripheral.apple

import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.PeripheralSessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ApplePeripheralCallbackOwnerTest {
    @Test fun lateOldNativeReadinessRequestsAndSubscriptionsCannotReachReplacementListener() {
        val firstListener = Listener()
        val secondListener = Listener()
        val firstManager = Any()
        val secondManager = Any()
        val first = ApplePeripheralCallbackOwner<Any>(firstListener).apply { bind(firstManager) }
        val id = PeripheralSessionId("same")
        val characteristic = GattCharacteristicId("2a37".toUuid())
        val delayedEvents = listOf(
            AppleGattEvent.NotificationReady,
            AppleGattEvent.Subscribed(id, 20, characteristic),
            AppleGattEvent.CharacteristicRead(id, 20, AppleRequestToken(1), GattServiceId("180d".toUuid()), characteristic, 0),
        )
        val delayedCallbacks = delayedEvents.map { event ->
            { first.listenerFor(firstManager)?.onEvent(event) }
        }
        first.retire()
        val second = ApplePeripheralCallbackOwner<Any>(secondListener).apply { bind(secondManager) }
        delayedCallbacks.forEach { it() }
        assertTrue(firstListener.events.isEmpty())
        assertTrue(secondListener.events.isEmpty())
        second.listenerFor(secondManager)?.onEvent(AppleGattEvent.NotificationReady)
        assertEquals(listOf<AppleGattEvent>(AppleGattEvent.NotificationReady), secondListener.events)
    }

    @Test fun nativeManagerIdentityAndOwnerRetirementAreBothRequired() {
        val listener = Listener()
        val manager = Any()
        val owner = ApplePeripheralCallbackOwner<Any>(listener).apply { bind(manager) }
        assertNull(owner.listenerFor(Any()))
        assertFailsWith<IllegalStateException> { owner.bind(Any()) }
        owner.retire()
        val replacement = ApplePeripheralCallbackOwner<Any>(listener).apply { bind(manager) }
        assertNull(owner.listenerFor(manager))
        assertTrue(replacement.listenerFor(manager) === listener)
    }

    private class Listener : ApplePeripheralStackListener {
        val events = mutableListOf<AppleGattEvent>()
        override fun onEvent(event: AppleGattEvent) { events += event }
        override fun onPlatformFailure(cause: Throwable) { throw cause }
    }
}

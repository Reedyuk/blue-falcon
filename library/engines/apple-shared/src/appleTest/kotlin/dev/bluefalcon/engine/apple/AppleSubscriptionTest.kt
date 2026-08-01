package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.CharacteristicWriteType
import dev.bluefalcon.core.NotificationSubscriptionResult
import dev.bluefalcon.core.toUuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class AppleSubscriptionTest {

    @Test
    fun `subscription owns callback before invoking native setNotifyValue`() = runTest {
        val controller = AppleCentralWriteController(backgroundScope)
        val target = FakeNotificationTarget()
        controller.connected(target)
        target.onSetNotify = { enabled ->
            assertEquals(
                enabled,
                controller.pendingSubscriptionTarget(
                    target.peripheralUuid,
                    target.characteristicIdentity,
                )
            )
        }
        val update = async(UnconfinedTestDispatcher(testScheduler)) {
            controller.notificationUpdates.first()
        }

        val result = async {
            controller.setNotificationSubscription(target, enabled = true)
        }
        runCurrent()

        assertFalse(result.isCompleted)
        controller.onNotificationStateUpdated(
            peripheralUuid = target.peripheralUuid,
            characteristicIdentity = target.characteristicIdentity,
            isNotifying = true,
            failure = null,
        )
        assertEquals(NotificationSubscriptionResult.Updated(true), result.await())
        assertEquals(NotificationSubscriptionResult.Updated(true), update.await().result)
        assertEquals(target.characteristicUuid, update.await().characteristicUuid)
    }

    @Test
    fun `callback failure and state mismatch return failed`() = runTest {
        val controller = AppleCentralWriteController(backgroundScope)
        val target = FakeNotificationTarget()
        controller.connected(target)
        val failed = async {
            controller.setNotificationSubscription(target, enabled = true)
        }
        runCurrent()
        controller.onNotificationStateUpdated(
            target.peripheralUuid,
            target.characteristicIdentity,
            isNotifying = false,
            failure = IllegalStateException("native failure"),
        )
        assertIs<NotificationSubscriptionResult.Failed>(failed.await())

        val mismatched = async {
            controller.setNotificationSubscription(target, enabled = true)
        }
        runCurrent()
        controller.onNotificationStateUpdated(
            target.peripheralUuid,
            target.characteristicIdentity,
            isNotifying = false,
            failure = null,
        )
        assertIs<NotificationSubscriptionResult.Failed>(mismatched.await())
    }

    @Test
    fun `disconnect completes subscription and emits exact update`() = runTest {
        val controller = AppleCentralWriteController(backgroundScope)
        val target = FakeNotificationTarget()
        controller.connected(target)
        val update = async(UnconfinedTestDispatcher(testScheduler)) {
            controller.notificationUpdates.first()
        }
        val result = async {
            controller.setNotificationSubscription(target, enabled = true)
        }
        runCurrent()

        controller.disconnected(target.peripheralUuid)

        assertEquals(NotificationSubscriptionResult.Disconnected, result.await())
        assertEquals(target.peripheralUuid, update.await().peripheralUuid)
        assertEquals(target.characteristicUuid, update.await().characteristicUuid)
        assertEquals(NotificationSubscriptionResult.Disconnected, update.await().result)
    }

    @Test
    fun `cancellation retains ownership until callback then permits retry`() = runTest {
        val controller = AppleCentralWriteController(backgroundScope)
        val target = FakeNotificationTarget()
        controller.connected(target)
        val cancelled = async {
            controller.setNotificationSubscription(target, enabled = true)
        }
        runCurrent()
        cancelled.cancelAndJoin()

        assertIs<NotificationSubscriptionResult.Failed>(
            controller.setNotificationSubscription(target, enabled = false)
        )
        assertTrue(
            controller.onNotificationStateUpdated(
                target.peripheralUuid,
                target.characteristicIdentity,
                isNotifying = true,
                failure = null,
            )
        )

        val retry = async {
            controller.setNotificationSubscription(target, enabled = false)
        }
        runCurrent()
        controller.onNotificationStateUpdated(
            target.peripheralUuid,
            target.characteristicIdentity,
            isNotifying = false,
            failure = null,
        )
        assertEquals(NotificationSubscriptionResult.Updated(false), retry.await())
    }

    @Test
    fun `disconnected target returns disconnected without native call`() = runTest {
        val controller = AppleCentralWriteController(backgroundScope)
        val target = FakeNotificationTarget(connected = false)

        val result = controller.setNotificationSubscription(target, enabled = true)

        assertEquals(NotificationSubscriptionResult.Disconnected, result)
        assertTrue(target.requestedStates.isEmpty())
    }

    @Test
    fun `cancellation during native subscription submission is rethrown`() = runTest {
        val controller = AppleCentralWriteController(backgroundScope)
        val target = FakeNotificationTarget()
        controller.connected(target)
        target.onSetNotify = {
            throw CancellationException("cancel submission")
        }

        assertFailsWith<CancellationException> {
            controller.setNotificationSubscription(target, enabled = true)
        }
    }

    private class FakeNotificationTarget(
        override val peripheralUuid: String = "peripheral-a",
        override val characteristicIdentity: String = "180D",
        override var connected: Boolean = true,
    ) : AppleNotificationTarget, AppleCentralWritePeer {
        override val characteristicUuid = characteristicIdentity.toUuid()
        override val canSendWithoutResponse: Boolean = true
        val requestedStates = mutableListOf<Boolean>()
        var onSetNotify: suspend (Boolean) -> Unit = {}

        override fun maximumWriteValueLength(writeType: CharacteristicWriteType): Int = 128

        override suspend fun setNotifyValue(enabled: Boolean) {
            requestedStates += enabled
            onSetNotify(enabled)
        }
    }
}

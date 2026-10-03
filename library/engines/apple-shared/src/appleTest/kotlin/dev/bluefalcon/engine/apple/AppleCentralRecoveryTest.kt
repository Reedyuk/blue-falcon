package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class AppleCentralRecoveryTest {
    @Test fun quarantinedPeerDoesNotBlockIndependentPeer() = runTest {
        val controller = AppleCentralWriteController(backgroundScope)
        val stuck = Target("stuck")
        val independent = Target("independent")
        controller.connected(stuck)
        controller.connected(independent)
        val lost = async { controller.read(stuck) }
        runCurrent(); advanceTimeBy(10_001); runCurrent()
        assertIs<AppleReadOutcome.Failed>(lost.await())
        val healthy = async { controller.read(independent) }
        runCurrent()
        assertTrue(controller.onCharacteristicValueReceived("independent", "characteristic", byteArrayOf(3), null))
        assertContentEquals(byteArrayOf(3), assertIs<AppleReadOutcome.Success>(healthy.await()).value)
    }

    @Test fun cancelledReadAndSubscriptionRetainPhysicalRecovery() = runTest {
        val controller = AppleCentralWriteController(backgroundScope)
        val readTarget = Target("read")
        val subscriptionTarget = Target("subscription")
        controller.connected(readTarget)
        controller.connected(subscriptionTarget)
        val read = launch { controller.read(readTarget) }
        val subscription = launch { controller.setNotificationSubscription(Subscription("subscription"), true) }
        runCurrent()
        read.cancelAndJoin(); subscription.cancelAndJoin()
        advanceTimeBy(10_001); runCurrent()
        assertNull(controller.currentConnection("read"))
        assertNull(controller.currentConnection("subscription"))
    }
    @Test fun lostReadQuarantinesConnection() = runTest {
        val target = Target()
        val controller = AppleCentralWriteController(backgroundScope)
        controller.connected(target)
        val read = async { controller.read(target) }
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        assertIs<AppleReadOutcome.Failed>(read.await())
        assertNull(controller.currentConnection(target.peripheralUuid), "Lost read must quarantine connection")
    }
    @Test fun lostWriteHasBoundedOutcome() = runTest {
        val target = Target()
        val controller = AppleCentralWriteController(backgroundScope)
        controller.connected(target)
        val write = backgroundScope.async { controller.write(target, byteArrayOf(1), CharacteristicWriteType.WithResponse) }
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        assertTrue(write.isCompleted, "Lost write must finish within watchdog bound")
        assertIs<CharacteristicWriteResult.Failed>(write.await())
        assertNull(controller.currentConnection(target.peripheralUuid))
    }
    @Test fun lostSubscriptionHasBoundedOutcome() = runTest {
        val target = Target()
        val controller = AppleCentralWriteController(backgroundScope)
        controller.connected(target)
        val subscription = backgroundScope.async { controller.setNotificationSubscription(Subscription(target.peripheralUuid), true) }
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        assertTrue(subscription.isCompleted, "Lost subscription must finish within watchdog bound")
        assertIs<NotificationSubscriptionResult.Failed>(subscription.await())
        assertNull(controller.currentConnection(target.peripheralUuid))
    }
    @Test fun cancelledCallerStillHasPhysicalWatchdog() = runTest {
        val target = Target()
        val controller = AppleCentralWriteController(backgroundScope)
        controller.connected(target)
        val write = launch { controller.write(target, byteArrayOf(1), CharacteristicWriteType.WithResponse) }
        runCurrent()
        write.cancelAndJoin()
        advanceTimeBy(10_001)
        runCurrent()
        assertNull(controller.currentConnection(target.peripheralUuid), "Caller cancellation must retain bounded recovery")
    }
    @Test fun alreadyExecutingOldTimerCannotQuarantineNextSameKeyRead() = runTest {
        val timers = mutableListOf<suspend () -> Unit>()
        val target = Target()
        val controller = AppleCentralWriteController(backgroundScope, scheduleTimeout = { action -> timers += action; Job() })
        controller.connected(target)
        val first = async { controller.read(target) }
        runCurrent()
        assertTrue(controller.onCharacteristicValueReceived("peer", "characteristic", byteArrayOf(1), null))
        assertIs<AppleReadOutcome.Success>(first.await())
        val second = async { controller.read(target) }
        runCurrent()
        timers.first()() // Models invocation already started before its Job was cancelled.
        assertNotNull(controller.currentConnection("peer"))
        assertFalse(second.isCompleted)
        assertTrue(controller.onCharacteristicValueReceived("peer", "characteristic", byteArrayOf(2), null))
        assertContentEquals(byteArrayOf(2), assertIs<AppleReadOutcome.Success>(second.await()).value)
    }
    @Test fun retiredReadCannotCompleteReplacement() = runTest {
        lateinit var controller: AppleCentralWriteController
        controller = AppleCentralWriteController(backgroundScope, onQuarantine = { controller.disconnected(it) })
        val target = Target()
        val old = controller.connected(target)
        val first = async { controller.read(target) }
        runCurrent(); advanceTimeBy(10_001); runCurrent()
        assertIs<AppleReadOutcome.Failed>(first.await())
        val replacement = controller.connected(target)
        val second = async { controller.read(target) }
        runCurrent()
        assertFalse(controller.onCharacteristicValueReceived(old, "characteristic", byteArrayOf(9), null))
        assertFalse(second.isCompleted)
        assertTrue(controller.onCharacteristicValueReceived(replacement, "characteristic", byteArrayOf(2), null))
        assertContentEquals(byteArrayOf(2), assertIs<AppleReadOutcome.Success>(second.await()).value)
    }
    @Test fun retiredWriteCannotCompleteReplacement() = runTest {
        lateinit var controller: AppleCentralWriteController
        controller = AppleCentralWriteController(backgroundScope, onQuarantine = { controller.disconnected(it) })
        val target = Target()
        val old = controller.connected(target)
        val first = async { controller.write(target, byteArrayOf(1), CharacteristicWriteType.WithResponse) }
        runCurrent(); advanceTimeBy(10_001); runCurrent()
        assertIs<CharacteristicWriteResult.Failed>(first.await())
        val replacement = controller.connected(target)
        val second = async { controller.write(target, byteArrayOf(2), CharacteristicWriteType.WithResponse) }
        runCurrent()
        assertFalse(controller.onCharacteristicWritten(old, "characteristic", null))
        assertFalse(second.isCompleted)
        assertTrue(controller.onCharacteristicWritten(replacement, "characteristic", null))
        assertEquals(CharacteristicWriteResult.Sent, second.await())
    }
    @Test fun retiredSubscriptionCannotCompleteReplacement() = runTest {
        lateinit var controller: AppleCentralWriteController
        controller = AppleCentralWriteController(backgroundScope, onQuarantine = { controller.disconnected(it) })
        val target = Target()
        val old = controller.connected(target)
        val first = async { controller.setNotificationSubscription(Subscription("peer"), true) }
        runCurrent(); advanceTimeBy(10_001); runCurrent()
        assertIs<NotificationSubscriptionResult.Failed>(first.await())
        val replacement = controller.connected(target)
        val second = async { controller.setNotificationSubscription(Subscription("peer"), true) }
        runCurrent()
        assertFalse(controller.onNotificationStateUpdated(old, "characteristic", true, null))
        assertFalse(second.isCompleted)
        assertTrue(controller.onNotificationStateUpdated(replacement, "characteristic", true, null))
        assertEquals(NotificationSubscriptionResult.Updated(true), second.await())
    }
    private class Subscription(override val peripheralUuid: String) : AppleNotificationTarget {
        override val characteristicUuid = Uuid.parse("00000000-0000-0000-0000-000000000001")
        override val characteristicIdentity = "characteristic"
        override val connected = true
        override suspend fun setNotifyValue(enabled: Boolean) = Unit
    }
    private class Target(override val peripheralUuid: String = "peer") : AppleCentralWriteTarget, AppleCentralReadTarget {
        override val characteristicUuid = "characteristic"
        override val connected = true
        override val canSendWithoutResponse = true
        override fun maximumWriteValueLength(writeType: CharacteristicWriteType) = 512
        override fun writeValue(payload: ByteArray, writeType: CharacteristicWriteType) = Unit
        override fun readValue() = Unit
    }
}

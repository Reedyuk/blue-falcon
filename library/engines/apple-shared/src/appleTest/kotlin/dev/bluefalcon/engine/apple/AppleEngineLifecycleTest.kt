package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class AppleEngineLifecycleTest {
    @Test fun successfulCloseStillReportsLateRejectedChannelCleanupFailure() = runTest {
        val owner = AppleEngineLifecycle(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))) { }
        owner.close()
        val failure = IllegalStateException("late rejected channel close failure")
        assertSame(failure, assertFailsWith<IllegalStateException> { owner.trackNativeCleanup { throw failure } })
        assertSame(failure, assertFailsWith<IllegalStateException> { owner.close() })
        assertSame(failure, assertFailsWith<IllegalStateException> { owner.close() })
    }
    @Test fun managerRetirementFailureSurvivesRemovalAndRepeatedFinalClose() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val engine = AppleEngineLifecycle(scope) { }
        val manager = AppleNativeManagerOwner()
        val failure = IllegalStateException("manager close failure")
        var retained = true
        assertSame(failure, assertFailsWith<IllegalStateException> {
            try { engine.trackNativeCleanup { manager.close(listOf({ throw failure })) } }
            finally { retained = false }
        })
        assertFalse(retained)
        assertSame(failure, assertFailsWith<IllegalStateException> { engine.close() })
        assertSame(failure, assertFailsWith<IllegalStateException> { engine.close() })
    }
    @Test fun nativeManagerCloseWakesPoweredOnWaiterAndCachesCleanupFailure() = runTest {
        val owner = AppleNativeManagerOwner()
        val state = kotlinx.coroutines.flow.MutableStateFlow<Int>(0)
        val waiting = async { runCatching { owner.awaitReady(state) { it == 1 } } }
        runCurrent()
        val ran = mutableListOf<Int>()
        assertFailsWith<IllegalStateException> {
            owner.close(listOf({ ran += 1; error("native delegate detach failed") }, { ran += 2 }))
        }
        runCurrent()
        assertTrue(waiting.isCompleted)
        assertTrue(waiting.await().isFailure)
        assertEquals(listOf(1, 2), ran)
        assertFalse(owner.isOpen)
        owner.forward { error("Retired native callback must not forward") }
        assertEquals("native delegate detach failed", assertFailsWith<IllegalStateException> { owner.close(listOf({ ran += 3 })) }.message)
        assertEquals(listOf(1, 2), ran)
    }
    @Test fun channelWaiterMayForgetItselfSynchronouslyDuringClose() = runTest {
        val owner = AppleChannelOwner<Any>()
        val waiter = CompletableDeferred<Any>()
        owner.registerWaiter(waiter)
        val awaiting = launch(UnconfinedTestDispatcher(testScheduler)) {
            try { waiter.await() } catch (_: L2capException) { } finally { owner.forgetWaiter(waiter) }
        }
        owner.close { }
        awaiting.join()
    }

    @Test fun finalChannelOwnerReleasesAllPendingOpensAndUndeliveredChannels() = runTest {
        val owner = AppleChannelOwner<Any>()
        val first = CompletableDeferred<Any>(); val displaced = CompletableDeferred<Any>()
        owner.registerWaiter(first); owner.registerWaiter(displaced)
        val channel = Any()
        assertTrue(owner.retain(channel))
        val closed = mutableListOf<Any>()
        owner.close { closed += it }
        assertEquals(listOf(channel), closed)
        assertTrue(first.isCompleted); assertTrue(displaced.isCompleted)
        assertFailsWith<L2capException> { first.await() }
        assertFailsWith<L2capException> { displaced.await() }
        assertFalse(owner.retain(Any()))
        assertFailsWith<IllegalStateException> { owner.registerWaiter(CompletableDeferred()) }
        owner.close { error("Must not reclose retired channel") }
    }

    @Test fun cancelledChannelWaiterReclaimsCompletedButUnclaimedChannel() = runTest {
        val owner = AppleChannelOwner<Any>()
        val waiter = CompletableDeferred<Any>()
        val channel = Any()
        owner.registerWaiter(waiter)
        assertTrue(owner.retain(channel))
        assertTrue(owner.associate(waiter, channel))
        waiter.complete(channel)
        waiter.cancel()
        assertTrue(owner.reclaim(waiter) === channel)
        owner.forgetWaiter(waiter)
        var closed = false
        owner.close { closed = true }
        assertFalse(closed, "The canceled waiter already reclaimed and closed its channel")
    }

    @Test fun closeReleasesNativeWaiterBeforeJoiningOwnedOperations() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var closes = 0
        val owner = AppleEngineLifecycle(scope) { closes++; release.complete(Unit) }
        val task = launch { owner.operation { entered.complete(Unit); withContext(NonCancellable) { release.await() } } }
        entered.await()
        val closing = async { owner.close() }
        try {
            runCurrent()
            assertEquals(1, closes, "Native IO must be released before joining operations")
            closing.await()
            task.join()
            assertTrue(task.isCancelled)
        } finally { release.complete(Unit) }
        owner.close()
        assertEquals(1, closes)
        assertFailsWith<IllegalStateException> { owner.operation {} }
    }
    @Test fun cancellingCloseWaiterDoesNotAbandonCleanupAndFailuresRemainObservable() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var closes = 0
        val owner = AppleEngineLifecycle(scope) { closes++; entered.complete(Unit); release.await(); error("native cleanup failure") }
        val waiter = launch { owner.close() }
        entered.await(); waiter.cancelAndJoin(); release.complete(Unit)
        assertEquals("native cleanup failure", assertFailsWith<IllegalStateException> { owner.close() }.message)
        assertEquals("native cleanup failure", assertFailsWith<IllegalStateException> { owner.close() }.message)
        assertEquals(1, closes)
    }
    @Test fun publicScopeCancellationConvergesTerminalCleanup() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var closes = 0
        val owner = AppleEngineLifecycle(scope) { closes++ }
        scope.cancel(); runCurrent(); owner.close()
        assertEquals(1, closes)
        assertFailsWith<IllegalStateException> { owner.native {} }
    }
    @Test fun synchronousNativeAdmissionDrainsBeforeFinalResourceSnapshot() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        var done = false; var closes = 0
        val owner = AppleEngineLifecycle(scope) { assertTrue(done); closes++ }
        owner.native { owner.requestClose(); assertEquals(0, closes); done = true }
        owner.close()
        assertEquals(1, closes)
    }
    @Test fun finalControllerCloseResolvesEveryWaiterAndCancelsItsWatchdogs() = runTest {
        val jobs = mutableListOf<Job>()
        val timers = mutableListOf<suspend () -> Unit>()
        val controller = AppleCentralWriteController(backgroundScope, scheduleTimeout = { action -> timers += action; Job().also { jobs += it } })
        val target = Target()
        controller.connected(target)
        val read = async { controller.read(target) }
        val write = async { controller.write(target, byteArrayOf(1), CharacteristicWriteType.WithResponse) }
        val sub = async { controller.setNotificationSubscription(Subscription(), true) }
        runCurrent()
        controller.close()
        assertTrue(read.isCompleted); assertTrue(write.isCompleted); assertTrue(sub.isCompleted)
        assertEquals(AppleReadOutcome.Disconnected, read.await())
        assertEquals(CharacteristicWriteResult.Disconnected, write.await())
        assertEquals(NotificationSubscriptionResult.Disconnected, sub.await())
        assertTrue(jobs.all { it.isCancelled })
        timers.forEach { it() }
        assertNull(controller.currentConnection("peer"))
        assertTrue(controller.capabilities.value.isEmpty())
        assertFailsWith<IllegalStateException> { controller.connected(target) }
        controller.close()
    }
    private class Target : AppleCentralWriteTarget, AppleCentralReadTarget {
        override val peripheralUuid = "peer"
        override val characteristicUuid = "read-write"
        override val connected = true
        override val canSendWithoutResponse = true
        override fun maximumWriteValueLength(writeType: CharacteristicWriteType) = 512
        override fun writeValue(payload: ByteArray, writeType: CharacteristicWriteType) = Unit
        override fun readValue() = Unit
    }
    private class Subscription : AppleNotificationTarget {
        override val peripheralUuid = "peer"
        override val characteristicIdentity = "subscription"
        override val characteristicUuid = Uuid.parse("00000000-0000-0000-0000-000000000001")
        override val connected = true
        override suspend fun setNotifyValue(enabled: Boolean) = Unit
    }
}

package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.uuid.Uuid
import platform.Foundation.NSMutableArray
import platform.Foundation.NSUUID
import platform.darwin.NSObject

@OptIn(ExperimentalCoroutinesApi::class)
class AppleEngineLifecycleTest {
    // Exercises the real lifecycle seam with native ownership components. The injected
    // cleanup follows closeResources' owner ordering; this is not AppleEngine.connect/
    // close integration and cannot detect edits confined to those private methods.
    @Test fun admittedOperationCallerCancellationPreservesOwnerUntilLifecycleClose() = runTest {
        val engineScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val epochs = ApplePeerManagerEpochs<Any>()
        val ownership = AppleNativeConnectionOwnership<NSObject>()
        val native = NSUUID("00000000-0000-0000-0000-000000000001")
        val wrapper = NSMutableArray().apply { addObject(native) }.objectAtIndex(0uL) as NSObject
        val admitted = CompletableDeferred<AppleNativeConnectionToken<NSObject>>()
        val operationCancelled = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        val callbackRelease = CompletableDeferred<Unit>()
        // Keep the worker alive independently to test the ownership fence itself,
        // rather than making engine-scope cancellation discard the callback for us.
        val dispatcher = AppleCentralCallbackDispatcher(backgroundScope)
        var closes = 0
        var staleEvents = 0
        var replacementEvents = 0
        val lifecycle = AppleEngineLifecycle(engineScope) {
            closes++
            val retiringEpochs = epochs.closeAdmission()
            val tokens = ownership.snapshot()
            tokens.forEach { ownership.beginRetirement(it) }
            cleanupEntered.complete(Unit)
            cleanupRelease.await()
            tokens.forEach { ownership.disconnected(it); it.terminated.complete(Unit) }
            retiringEpochs.forEach { epochs.finishRetirement(it) }
        }
        try {
            val caller = launch {
                lifecycle.operation {
                    val token = lifecycle.native {
                        val epoch = epochs.reserve("peer")
                        ownership.connected("peer", native, epoch)
                    }
                    admitted.complete(token)
                    try { awaitCancellation() } finally { operationCancelled.complete(Unit) }
                }
            }
            val token = admitted.await()
            val epoch = epochs.current("peer")!!
            caller.cancelAndJoin()
            operationCancelled.await()
            assertTrue(caller.isCancelled)
            assertTrue(engineScope.isActive, "Caller cancellation must not cancel the engine")
            assertTrue(lifecycle.isOpen)
            assertEquals(0, closes)
            assertSame(token, epochs.capture(epoch, ownership, wrapper))
            assertFalse(token.terminated.isCompleted)

            assertTrue(dispatcher.dispatch { callbackRelease.await() })
            runCurrent() // Park the worker before queuing the old owner's callback.
            assertTrue(dispatcher.dispatchOwned(token, ownership, onRejected = { error("Unexpected rejection") }) {
                staleEvents++
            })
            val closing = lifecycle.requestClose()
            assertFalse(lifecycle.isOpen, "Close must synchronously reject new admission")
            assertFailsWith<IllegalStateException> { lifecycle.native { error("Late native admission") } }
            runCurrent()
            assertTrue(cleanupEntered.isCompleted, "Real lifecycle close must invoke terminal cleanup")
            assertEquals(1, closes)
            assertFalse(closing.isCompleted, "Close must await final owner cleanup")
            assertFalse(ownership.isActive(token))
            assertNull(epochs.capture(epoch, ownership, wrapper))
            assertNull(ownership.capture("peer", wrapper))
            callbackRelease.complete(Unit)
            runCurrent()
            assertEquals(0, staleEvents, "Queued callback must observe the retired owner before removal")

            cleanupRelease.complete(Unit)
            lifecycle.close()
            assertTrue(closing.isCompleted)
            assertTrue(token.terminated.isCompleted)
            assertTrue(epoch.terminated.isCompleted)
            assertNull(epochs.current("peer"))
            assertNull(ownership.current("peer"))
            assertTrue(engineScope.coroutineContext[Job]!!.isCompleted)

            // A fresh manager epoch can own the same native/UUID. Reuse the owner
            // table adversarially to prove stale removal cannot erase a replacement;
            // a closed AppleEngine itself cannot reconnect.
            val replacementEpochs = ApplePeerManagerEpochs<Any>()
            val replacementEpoch = replacementEpochs.reserve("peer")
            val replacement = ownership.connected("peer", native, replacementEpoch)
            assertNull(epochs.capture(epoch, ownership, wrapper))
            assertSame(replacement, replacementEpochs.capture(replacementEpoch, ownership, wrapper))
            assertFalse(ownership.disconnected(token))
            assertFalse(dispatcher.dispatchOwned(token, ownership, onRejected = { error("Stale rejection") }) { staleEvents++ })
            assertTrue(dispatcher.dispatchOwned(replacement, ownership, onRejected = { error("Replacement rejection") }) { replacementEvents++ })
            runCurrent()
            assertEquals(0, staleEvents)
            assertEquals(1, replacementEvents, "Replacement must remain usable")
            assertTrue(ownership.isActive(replacement))
            lifecycle.close()
            assertEquals(1, closes)
        } finally {
            callbackRelease.complete(Unit)
            cleanupRelease.complete(Unit)
            lifecycle.close()
            dispatcher.close()
        }
    }

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

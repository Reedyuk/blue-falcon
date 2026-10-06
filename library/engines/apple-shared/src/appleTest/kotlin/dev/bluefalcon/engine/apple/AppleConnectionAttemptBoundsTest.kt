package dev.bluefalcon.engine.apple

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AppleConnectionAttemptBoundsTest {
    @Test fun uniquePeerCapacityRejectsBeforeAnotherPrivateAttemptIsAllocated() = runTest {
        val coordinator = AppleConnectionAttemptCoordinator(2, 8)
        val release = CompletableDeferred<Unit>()
        val first = backgroundScope.launch { coordinator.withAttempt("a") { release.await() } }
        val second = backgroundScope.launch { coordinator.withAttempt("b") { release.await() } }
        runCurrent()
        var invoked = false
        val overload = backgroundScope.async { runCatching { coordinator.withAttempt("c") { invoked = true } } }
        runCurrent()
        assertTrue(overload.isCompleted)
        assertIs<IllegalStateException>(overload.await().exceptionOrNull())
        assertFalse(invoked)
        assertEquals(2, coordinator.status.value.retainedPeers)
        assertEquals(1L, coordinator.status.value.rejectedAttempts)
        release.complete(Unit); first.join(); second.join()
        assertEquals(0, coordinator.status.value.retainedUsers)
    }
    @Test fun samePeerWaiterCapacityIncludesActiveAndWaitingAttemptsAndCancellationReleasesPermit() = runTest {
        val coordinator = AppleConnectionAttemptCoordinator(2, 3)
        val release = CompletableDeferred<Unit>()
        val active = backgroundScope.launch { coordinator.withAttempt("a") { release.await() } }
        val waiting = (0..1).map { backgroundScope.launch { coordinator.withAttempt("a") {} } }
        runCurrent()
        val overload = backgroundScope.async { runCatching { coordinator.withAttempt("a") {} } }
        runCurrent()
        assertTrue(overload.isCompleted, "Overflow must fail before adding another mutex waiter")
        assertIs<IllegalStateException>(overload.await().exceptionOrNull())
        assertEquals(3, coordinator.status.value.retainedUsers)
        waiting.first().cancelAndJoin()
        var independent = false
        coordinator.withAttempt("b") { independent = true }
        assertTrue(independent)
        release.complete(Unit); active.join(); waiting.last().join()
        assertEquals(0, coordinator.status.value.retainedPeers)
        assertEquals(0, coordinator.status.value.retainedUsers)
    }
}

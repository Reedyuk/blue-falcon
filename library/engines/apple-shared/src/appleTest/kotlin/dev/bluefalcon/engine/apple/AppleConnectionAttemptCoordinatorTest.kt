package dev.bluefalcon.engine.apple

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AppleConnectionAttemptCoordinatorTest {
    @Test
    fun `stalled attempt serializes its own UUID while unrelated devices connect`() = runTest {
        val coordinator = AppleConnectionAttemptCoordinator()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        launch {
            coordinator.withAttempt("first") {
                events += "first-start"
                release.await()
                events += "first-end"
            }
        }
        runCurrent()
        launch { coordinator.withAttempt("first") { events += "same-UUID" } }
        launch { coordinator.withAttempt("second") { events += "other-UUID" } }
        runCurrent()
        assertEquals(listOf("first-start", "other-UUID"), events)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("first-start", "other-UUID", "first-end", "same-UUID"), events)
    }

    @Test
    fun `cancelling a waiter preserves serialization for remaining attempts`() = runTest {
        val coordinator = AppleConnectionAttemptCoordinator()
        val release = CompletableDeferred<Unit>()
        launch { coordinator.withAttempt("peer") { release.await() } }
        runCurrent()
        val cancelled = launch { coordinator.withAttempt("peer") { error("Cancelled waiter ran") } }
        runCurrent()
        cancelled.cancel()
        runCurrent()
        var started = false
        launch { coordinator.withAttempt("peer") { started = true } }
        runCurrent()
        assertEquals(false, started)
        release.complete(Unit)
        runCurrent()
        assertEquals(true, started)
        coordinator.withAttempt("peer") { assertEquals(true, started) }
    }
}

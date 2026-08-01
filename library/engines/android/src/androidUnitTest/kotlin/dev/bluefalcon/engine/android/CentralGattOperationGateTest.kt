package dev.bluefalcon.engine.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CentralGattOperationGateTest {

    @Test
    fun `typed operation completes only from its matching callback`() {
        val scheduler = FakeTimeoutScheduler()
        val outcomes = mutableListOf<CentralGattOperationOutcome>()
        val key = key(generation = 1, identity = "characteristic-a")
        val gate = CentralGattOperationGate(
            timeoutMillis = 10_000,
            timeoutScheduler = scheduler,
        )

        assertTrue(
            gate.trySubmitTyped(
                key = key,
                label = "write",
                action = { true },
                onComplete = outcomes::add,
            ),
        )
        assertTrue(gate.complete(key, status = 0, successful = true))

        assertEquals(
            listOf<CentralGattOperationOutcome>(
                CentralGattOperationOutcome.Success(status = 0)
            ),
            outcomes,
        )
        assertTrue(gate.isIdle)
        assertTrue(scheduler.allCancelled)
    }

    @Test
    fun `typed operation is backpressured while any operation is active`() {
        val ready = mutableListOf<Unit>()
        var secondActionCalled = false
        val firstKey = key(generation = 1, identity = "characteristic-a")
        val secondKey = key(generation = 1, identity = "characteristic-b")
        val gate = CentralGattOperationGate(
            timeoutMillis = 10_000,
            timeoutScheduler = FakeTimeoutScheduler(),
            onReady = { ready += Unit },
        )

        assertTrue(gate.trySubmitTyped(firstKey, "first", { true }) {})
        assertFalse(
            gate.trySubmitTyped(
                secondKey,
                "second",
                action = {
                    secondActionCalled = true
                    true
                },
            ) {},
        )
        assertFalse(secondActionCalled)

        gate.complete(firstKey, status = 0, successful = true)

        assertEquals(1, ready.size)
        assertTrue(gate.trySubmitTyped(secondKey, "second", { true }) {})
    }

    @Test
    fun `gate reports busy only when idle begins physical work`() {
        var busyCount = 0
        val first = key(generation = 1, identity = "first")
        val second = key(generation = 1, identity = "second")
        val gate = CentralGattOperationGate(
            timeoutMillis = 10_000,
            timeoutScheduler = FakeTimeoutScheduler(),
            onBusy = { busyCount += 1 },
        )

        gate.enqueueLegacy(first, "first") { true }
        gate.enqueueLegacy(second, "second") { true }
        assertEquals(1, busyCount)

        gate.complete(first, status = 0, successful = true)
        assertEquals(1, busyCount)

        gate.complete(second, status = 0, successful = true)
        assertTrue(gate.trySubmitTyped(first, "typed", { true }) {})
        assertEquals(2, busyCount)
    }

    @Test
    fun `legacy fifo and typed submissions share one serialization authority`() {
        val calls = mutableListOf<String>()
        val ready = mutableListOf<Unit>()
        val first = key(generation = 2, identity = "first")
        val second = key(generation = 2, identity = "second")
        val typed = key(generation = 2, identity = "typed")
        val gate = CentralGattOperationGate(
            timeoutMillis = 10_000,
            timeoutScheduler = FakeTimeoutScheduler(),
            onReady = { ready += Unit },
        )

        gate.enqueueLegacy(first, "first") {
            calls += "first"
            true
        }
        gate.enqueueLegacy(second, "second") {
            calls += "second"
            true
        }

        assertEquals(listOf("first"), calls)
        assertFalse(gate.trySubmitTyped(typed, "typed", { true }) {})

        gate.complete(first, status = 0, successful = true)
        assertEquals(listOf("first", "second"), calls)
        assertTrue(ready.isEmpty())

        gate.complete(second, status = 0, successful = true)
        assertEquals(1, ready.size)
        assertTrue(gate.isIdle)
    }

    @Test
    fun `mismatched callback cannot advance current operation`() {
        val outcomes = mutableListOf<CentralGattOperationOutcome>()
        val current = key(generation = 3, identity = "expected")
        val gate = CentralGattOperationGate(
            timeoutMillis = 10_000,
            timeoutScheduler = FakeTimeoutScheduler(),
        )
        gate.trySubmitTyped(current, "write", { true }, outcomes::add)

        assertFalse(
            gate.complete(
                current.copy(identity = "other"),
                status = 0,
                successful = true,
            ),
        )
        assertFalse(
            gate.complete(
                current.copy(generation = 2),
                status = 0,
                successful = true,
            ),
        )

        assertTrue(outcomes.isEmpty())
        assertFalse(gate.isIdle)
    }

    @Test
    fun `immediate rejection completes typed operation and releases readiness`() {
        val outcomes = mutableListOf<CentralGattOperationOutcome>()
        var readyCount = 0
        val gate = CentralGattOperationGate(
            timeoutMillis = 10_000,
            timeoutScheduler = FakeTimeoutScheduler(),
            onReady = { readyCount += 1 },
        )

        assertTrue(
            gate.trySubmitTyped(
                key = key(generation = 4),
                label = "rejected",
                action = { false },
                onComplete = outcomes::add,
            ),
        )

        assertEquals(
            listOf<CentralGattOperationOutcome>(
                CentralGattOperationOutcome.Rejected(cause = null)
            ),
            outcomes,
        )
        assertEquals(1, readyCount)
        assertTrue(gate.isIdle)
    }

    @Test
    fun `timeout completes typed operation and poisons physical ownership`() {
        val scheduler = FakeTimeoutScheduler()
        val outcomes = mutableListOf<CentralGattOperationOutcome>()
        var poisonedCount = 0
        var readyCount = 0
        val gate = CentralGattOperationGate(
            timeoutMillis = 10_000,
            timeoutScheduler = scheduler,
            onPoisoned = { poisonedCount += 1 },
            onReady = { readyCount += 1 },
        )
        val timedOut = key(generation = 5)
        gate.trySubmitTyped(timedOut, "write", { true }, outcomes::add)

        scheduler.fireNext()

        assertEquals(
            listOf<CentralGattOperationOutcome>(CentralGattOperationOutcome.TimedOut),
            outcomes,
        )
        assertEquals(1, poisonedCount)
        assertEquals(0, readyCount)
        assertTrue(gate.isPoisoned)
        assertFalse(gate.isIdle)
        assertFalse(gate.complete(timedOut, status = 0, successful = true))
        assertFalse(
            gate.trySubmitTyped(
                timedOut.copy(identity = "retry"),
                "retry",
                { true },
            ) {},
        )
    }

    @Test
    fun `timeout drops queued legacy work instead of dispatching it`() {
        val scheduler = FakeTimeoutScheduler()
        val calls = mutableListOf<String>()
        val first = key(generation = 5, identity = "first")
        val second = key(generation = 5, identity = "second")
        val gate = CentralGattOperationGate(
            timeoutMillis = 10_000,
            timeoutScheduler = scheduler,
        )
        gate.enqueueLegacy(first, "first") {
            calls += "first"
            true
        }
        gate.enqueueLegacy(second, "second") {
            calls += "second"
            true
        }

        scheduler.fireNext()

        assertEquals(listOf("first"), calls)
        assertFalse(gate.complete(first, status = 0, successful = true))
    }

    @Test
    fun `abandoned waiter retains physical ownership until callback`() {
        val outcomes = mutableListOf<CentralGattOperationOutcome>()
        val current = key(generation = 6)
        val next = key(generation = 6, identity = "next")
        val gate = CentralGattOperationGate(
            timeoutMillis = 10_000,
            timeoutScheduler = FakeTimeoutScheduler(),
        )
        gate.trySubmitTyped(current, "write", { true }, outcomes::add)

        assertTrue(gate.abandon(current))
        assertFalse(gate.trySubmitTyped(next, "next", { true }) {})
        assertTrue(gate.complete(current, status = 0, successful = true))

        assertTrue(outcomes.isEmpty())
        assertTrue(gate.trySubmitTyped(next, "next", { true }) {})
    }

    @Test
    fun `disconnect completes typed operation and rejects stale generation callback`() {
        val outcomes = mutableListOf<CentralGattOperationOutcome>()
        val old = key(generation = 7)
        val replacement = key(generation = 8)
        val gate = CentralGattOperationGate(
            timeoutMillis = 10_000,
            timeoutScheduler = FakeTimeoutScheduler(),
        )
        gate.trySubmitTyped(old, "old", { true }, outcomes::add)

        gate.disconnect()
        assertEquals(
            listOf<CentralGattOperationOutcome>(CentralGattOperationOutcome.Disconnected),
            outcomes,
        )

        assertTrue(gate.trySubmitTyped(replacement, "replacement", { true }) {})
        assertFalse(gate.complete(old, status = 0, successful = true))
        assertFalse(gate.isIdle)
    }

    private fun key(
        generation: Long,
        identity: String = "characteristic",
    ) = CentralGattOperationKey(
        generation = generation,
        type = CentralGattOperationType.WriteCharacteristic,
        identity = identity,
    )

    private class FakeTimeoutScheduler : CentralGattTimeoutScheduler {
        private val scheduled = ArrayDeque<Scheduled>()

        val allCancelled: Boolean
            get() = scheduled.all { it.cancelled }

        override fun schedule(
            delayMillis: Long,
            onTimeout: () -> Unit,
        ): CentralGattTimeoutHandle {
            val scheduledTimeout = Scheduled(delayMillis, onTimeout)
            scheduled += scheduledTimeout
            return CentralGattTimeoutHandle {
                scheduledTimeout.cancelled = true
            }
        }

        fun fireNext() {
            val scheduledTimeout = assertNotNull(scheduled.firstOrNull { !it.cancelled })
            assertNull(scheduledTimeout.takeIf { it.cancelled })
            scheduledTimeout.onTimeout()
        }

        private data class Scheduled(
            val delayMillis: Long,
            val onTimeout: () -> Unit,
            var cancelled: Boolean = false,
        )
    }
}

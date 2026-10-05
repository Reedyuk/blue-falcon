package dev.bluefalcon.engine.android

import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.CharacteristicWriteType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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

    @Test
    fun `completion cannot publish ready before an earlier busy publication finishes`() {
        val state = AndroidCentralWriteState()
        val generation = state.onConnected("peer")
        val enteredBusy = CountDownLatch(1)
        val releaseBusy = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val gate = CentralGattOperationGate(
            timeoutMillis = 10_000,
            timeoutScheduler = FakeTimeoutScheduler(),
            onBusy = {
                enteredBusy.countDown()
                check(releaseBusy.await(5, TimeUnit.SECONDS))
                state.onBusy("peer", generation)
            },
            onReady = { state.onReady("peer", generation) },
        )
        val operationKey = key(generation)
        val submitter = Thread {
            try {
                assertTrue(gate.trySubmitTyped(operationKey, "write", { true }) { completed.countDown() })
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }
        val completer = Thread {
            try {
                assertTrue(gate.complete(operationKey, status = 0, successful = true))
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }
        submitter.start()
        try {
            assertTrue(enteredBusy.await(5, TimeUnit.SECONDS))
            completer.start()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertTrue(gate.isIdle)
        } finally {
            releaseBusy.countDown()
            submitter.join(5_000)
            completer.join(5_000)
        }
        assertFalse(submitter.isAlive)
        assertFalse(completer.isAlive)
        failure.get()?.let { throw it }
        assertNull(state.validateWrite("peer", generation, CharacteristicWriteType.WithResponse, 1))
    }

    @Test
    fun `next operation busy publication follows the previous ready publication`() {
        val state = AndroidCentralWriteState()
        val generation = state.onConnected("peer")
        val enteredReady = CountDownLatch(1)
        val releaseReady = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val gate = CentralGattOperationGate(
            timeoutMillis = 10_000,
            timeoutScheduler = FakeTimeoutScheduler(),
            onBusy = { state.onBusy("peer", generation) },
            onReady = {
                enteredReady.countDown()
                check(releaseReady.await(5, TimeUnit.SECONDS))
                state.onReady("peer", generation)
            },
        )
        val first = key(generation, "first")
        val second = key(generation, "second")
        assertTrue(gate.trySubmitTyped(first, "first", { true }) {})
        val completer = Thread {
            try {
                assertTrue(gate.complete(first, status = 0, successful = true))
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }
        val submitter = Thread {
            try {
                assertTrue(gate.trySubmitTyped(second, "second", {
                    secondStarted.countDown()
                    true
                }) {})
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }
        completer.start()
        try {
            assertTrue(enteredReady.await(5, TimeUnit.SECONDS))
            submitter.start()
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFalse(gate.isIdle)
        } finally {
            releaseReady.countDown()
            completer.join(5_000)
            submitter.join(5_000)
        }
        assertFalse(completer.isAlive)
        assertFalse(submitter.isAlive)
        failure.get()?.let { throw it }
        assertEquals(CharacteristicWriteResult.Backpressured,
            state.validateWrite("peer", generation, CharacteristicWriteType.WithResponse, 1))
        assertTrue(gate.complete(second, status = 0, successful = true))
    }

    @Test
    fun `started cancelled watchdog cannot time out a replacement with the same key`() {
        val scheduler = FakeTimeoutScheduler()
        val firstOutcomes = mutableListOf<CentralGattOperationOutcome>()
        val secondOutcomes = mutableListOf<CentralGattOperationOutcome>()
        val gate = CentralGattOperationGate(10_000, scheduler)
        val operationKey = key(generation = 9)
        assertTrue(gate.trySubmitTyped(operationKey, "same", { true }, firstOutcomes::add))
        // Capture an invocation that has started before cancellation. Removing a pending
        // Handler callback cannot revoke this invocation while it waits for the gate monitor.
        val startedTimeout = scheduler.captureNextInvocation()
        assertTrue(gate.complete(operationKey, status = 0, successful = true))
        assertTrue(gate.trySubmitTyped(operationKey, "same", { true }, secondOutcomes::add))

        startedTimeout()

        assertFalse(gate.isPoisoned)
        assertTrue(secondOutcomes.isEmpty())
        assertFalse(gate.isIdle)
        scheduler.fireNext()
        assertTrue(gate.isPoisoned)
        assertEquals(listOf<CentralGattOperationOutcome>(CentralGattOperationOutcome.Success(0)), firstOutcomes)
        assertEquals(listOf<CentralGattOperationOutcome>(CentralGattOperationOutcome.TimedOut), secondOutcomes)
    }

    @Test
    fun `completion callback can reenter submission without old ready overwriting new busy`() {
        val state = AndroidCentralWriteState()
        val generation = state.onConnected("peer")
        val gate = CentralGattOperationGate(
            10_000, FakeTimeoutScheduler(),
            onBusy = { state.onBusy("peer", generation) },
            onReady = { state.onReady("peer", generation) },
        )
        val first = key(generation, "first")
        val second = key(generation, "second")
        assertTrue(gate.trySubmitTyped(first, "first", { true }) {
            assertTrue(gate.trySubmitTyped(second, "second", { true }) {})
        })

        assertTrue(gate.complete(first, status = 0, successful = true))

        assertFalse(gate.isIdle)
        assertEquals(CharacteristicWriteResult.Backpressured,
            state.validateWrite("peer", generation, CharacteristicWriteType.WithResponse, 1))
        assertTrue(gate.complete(second, status = 0, successful = true))
        assertNull(state.validateWrite("peer", generation, CharacteristicWriteType.WithResponse, 1))
    }

    @Test
    fun `disconnect terminalizes waiter while earlier busy publication is pending`() {
        val state = AndroidCentralWriteState()
        val generation = state.onConnected("peer")
        val enteredBusy = CountDownLatch(1)
        val releaseBusy = CountDownLatch(1)
        val outcomes = mutableListOf<CentralGattOperationOutcome>()
        val failure = AtomicReference<Throwable?>()
        val gate = CentralGattOperationGate(
            10_000, FakeTimeoutScheduler(),
            onBusy = {
                enteredBusy.countDown()
                check(releaseBusy.await(5, TimeUnit.SECONDS))
                state.onBusy("peer", generation)
            },
            onReady = { state.onReady("peer", generation) },
        )
        val operationKey = key(generation)
        val submitter = Thread {
            try {
                assertTrue(gate.trySubmitTyped(operationKey, "first", { true }, outcomes::add))
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }
        submitter.start()
        try {
            assertTrue(enteredBusy.await(5, TimeUnit.SECONDS))
            gate.disconnect()
            state.onDisconnected("peer", generation)
            assertEquals(listOf<CentralGattOperationOutcome>(CentralGattOperationOutcome.Disconnected), outcomes)
            assertFalse(gate.complete(operationKey, 0, true))
        } finally {
            releaseBusy.countDown()
            submitter.join(5_000)
        }
        assertFalse(submitter.isAlive)
        failure.get()?.let { throw it }
        assertTrue(state.capabilities.value.isEmpty())
        assertEquals(CharacteristicWriteResult.Disconnected,
            state.validateWrite("peer", generation, CharacteristicWriteType.WithResponse, 1))
    }

    @Test
    fun `synchronous native completion leaves idle gate ready without a watchdog`() {
        val state = AndroidCentralWriteState()
        val generation = state.onConnected("peer")
        val scheduler = FakeTimeoutScheduler()
        val outcomes = mutableListOf<CentralGattOperationOutcome>()
        val gate = CentralGattOperationGate(
            10_000, scheduler,
            onBusy = { state.onBusy("peer", generation) },
            onReady = { state.onReady("peer", generation) },
        )
        val operationKey = key(generation)

        assertTrue(gate.trySubmitTyped(operationKey, "synchronous", {
            assertTrue(gate.complete(operationKey, 0, true))
            true
        }, outcomes::add))

        assertTrue(gate.isIdle)
        assertNull(state.validateWrite("peer", generation, CharacteristicWriteType.WithResponse, 1))
        assertEquals(listOf<CentralGattOperationOutcome>(CentralGattOperationOutcome.Success(0)), outcomes)
        assertEquals(0, scheduler.scheduledCount)
    }

    @Test
    fun `synchronous completion replacement survives original action rejection`() {
        val state = AndroidCentralWriteState()
        val generation = state.onConnected("peer")
        val scheduler = FakeTimeoutScheduler()
        val firstOutcomes = mutableListOf<CentralGattOperationOutcome>()
        val secondOutcomes = mutableListOf<CentralGattOperationOutcome>()
        val gate = CentralGattOperationGate(
            10_000, scheduler,
            onBusy = { state.onBusy("peer", generation) },
            onReady = { state.onReady("peer", generation) },
        )
        val first = key(generation, "first")
        val second = key(generation, "second")

        assertTrue(gate.trySubmitTyped(first, "synchronous", {
            assertTrue(gate.complete(first, 0, true))
            // A synchronous callback is authoritative even if native submission
            // subsequently returns false. It must not clear its replacement.
            false
        }) { outcome ->
            firstOutcomes += outcome
            assertTrue(gate.trySubmitTyped(second, "replacement", { true }, secondOutcomes::add))
        })

        assertFalse(gate.isIdle)
        assertEquals(1, scheduler.scheduledCount)
        assertEquals(listOf<CentralGattOperationOutcome>(CentralGattOperationOutcome.Success(0)), firstOutcomes)
        assertTrue(secondOutcomes.isEmpty())
        assertEquals(CharacteristicWriteResult.Backpressured,
            state.validateWrite("peer", generation, CharacteristicWriteType.WithResponse, 1))
        assertTrue(gate.complete(second, 0, true))
        assertEquals(listOf<CentralGattOperationOutcome>(CentralGattOperationOutcome.Success(0)), secondOutcomes)
        assertNull(state.validateWrite("peer", generation, CharacteristicWriteType.WithResponse, 1))
    }

    @Test
    fun `rejected native action publishes busy then ready before returning`() {
        val publications = mutableListOf<String>()
        val gate = CentralGattOperationGate(
            10_000, FakeTimeoutScheduler(),
            onBusy = { publications += "busy" },
            onReady = { publications += "ready" },
        )
        assertTrue(gate.trySubmitTyped(key(10), "rejected", { false }) {})
        assertTrue(gate.isIdle)
        assertEquals(listOf("busy", "ready"), publications)
    }

    @Test
    fun `legacy native reentry cannot publish busy after nested replacement completes`() {
        val state = AndroidCentralWriteState()
        val generation = state.onConnected("peer")
        val scheduler = FakeTimeoutScheduler()
        val publications = mutableListOf<String>()
        val gate = CentralGattOperationGate(
            10_000, scheduler,
            onBusy = {
                publications += "busy"
                state.onBusy("peer", generation)
            },
            onReady = {
                publications += "ready"
                state.onReady("peer", generation)
            },
        )
        val first = key(generation, "legacy")
        val second = key(generation, "replacement")
        gate.enqueueLegacy(first, "legacy") {
            assertTrue(gate.complete(first, 0, true))
            assertTrue(gate.trySubmitTyped(second, "replacement", { true }) {})
            assertTrue(gate.complete(second, 0, true))
            true
        }

        assertTrue(gate.isIdle)
        assertNull(state.validateWrite("peer", generation, CharacteristicWriteType.WithResponse, 1))
        assertEquals(1, publications.count { it == "busy" })
        assertEquals("ready", publications.last())
        assertEquals(1, scheduler.scheduledCount)
        assertTrue(scheduler.allCancelled)
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

        val scheduledCount: Int get() = scheduled.size

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

        fun captureNextInvocation(): () -> Unit =
            assertNotNull(scheduled.firstOrNull { !it.cancelled }).onTimeout

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

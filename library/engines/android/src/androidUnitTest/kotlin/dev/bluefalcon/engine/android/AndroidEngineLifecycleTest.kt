package dev.bluefalcon.engine.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidEngineLifecycleTest {
    @Test
    fun `destroy before native admission never creates another owner`() {
        val lifetime = AndroidEngineLifecycle(Job())
        lifetime.destroy()
        var creations = 0
        assertNull(lifetime.withOpen { creations++; Any() })
        assertEquals(0, creations)
        assertTrue(lifetime.isClosed)
    }

    @Test
    fun `destroy closes all native owners exactly once despite one failure`() {
        val lifetime = AndroidEngineLifecycle(Job())
        val closed = mutableListOf<String>()
        assertTrue(lifetime.retain(Any()) { closed += "scan" })
        assertTrue(lifetime.retain(Any()) { closed += "gatt"; error("permission revoked") })
        assertTrue(lifetime.retain(Any()) { closed += "receiver" })
        lifetime.destroy()
        lifetime.destroy()
        assertEquals(3, closed.size)
        assertEquals(setOf("scan", "gatt", "receiver"), closed.toSet())
        assertTrue(lifetime.job.isCancelled)
    }

    @Test
    fun `late native owner is closed instead of retained after destroy`() {
        val lifetime = AndroidEngineLifecycle(Job())
        lifetime.destroy()
        var closes = 0
        assertFalse(lifetime.retain(Any()) { closes++ })
        assertEquals(1, closes)
    }

    @Test
    fun `released resource does not survive until engine close`() {
        val lifetime = AndroidEngineLifecycle(Job())
        val owner = Any()
        var closes = 0
        lifetime.retain(owner) { closes++ }
        lifetime.release(owner)
        lifetime.destroy()
        assertEquals(0, closes)
    }

    @Test
    fun `close reports native cleanup failure after all other owners terminate`() = runBlocking {
        val lifetime = AndroidEngineLifecycle(Job())
        var cleaned = false
        lifetime.retain(Any()) { error("receiver cleanup failed") }
        lifetime.retain(Any()) { cleaned = true }
        kotlin.test.assertFailsWith<IllegalStateException> { lifetime.close() }
        assertTrue(cleaned)
        assertTrue(lifetime.job.isCompleted)
        kotlin.test.assertFailsWith<IllegalStateException> { lifetime.close() }
        Unit
    }

    @Test
    fun `cleanup error churn retains first failure and bounded diagnostic samples`() = runBlocking {
        val lifetime = AndroidEngineLifecycle(Job())
        val first = IllegalStateException("first native failure")
        lifetime.recordCleanupFailure(first)
        repeat(9_999) { lifetime.recordCleanupFailure(IllegalStateException("failure $it")) }
        val report = kotlin.test.assertFailsWith<IllegalStateException> { lifetime.close() }
        kotlin.test.assertSame(first, report.cause)
        assertEquals(16, report.suppressed.size)
        assertTrue(report.message.orEmpty().contains("10000"))
        lifetime.recordCleanupFailure(first)
        val repeated = kotlin.test.assertFailsWith<IllegalStateException> { lifetime.close() }
        kotlin.test.assertSame(report, repeated)
        assertEquals(16, repeated.suppressed.size)
        assertTrue(repeated.message.orEmpty().contains("10000"))
    }

    @Test
    fun `destroy terminalizes an active GATT waiter and cancels its watchdog`() {
        val lifetime = AndroidEngineLifecycle(Job())
        var cancelled = false
        val outcomes = mutableListOf<CentralGattOperationOutcome>()
        val gate = CentralGattOperationGate(10_000, CentralGattTimeoutScheduler { _, _ ->
            CentralGattTimeoutHandle { cancelled = true }
        })
        val key = CentralGattOperationKey(1, CentralGattOperationType.WriteCharacteristic, "attribute")
        gate.trySubmitTyped(key, "pending write", { true }, outcomes::add)
        lifetime.retain(gate, gate::disconnect)
        lifetime.destroy()
        assertTrue(cancelled)
        assertEquals(listOf<CentralGattOperationOutcome>(CentralGattOperationOutcome.Disconnected), outcomes)
        assertFalse(gate.complete(key, 0, true))
    }

    @Test
    fun `close from engine worker is cancelled instead of joining itself`() = runBlocking {
        val parent = Job()
        val lifetime = AndroidEngineLifecycle(parent)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val worker = CoroutineScope(parent + Dispatchers.Default).launch {
            entered.complete(Unit)
            try { lifetime.close() } catch (_: CancellationException) { cancelled.complete(Unit) }
        }
        entered.await()
        try {
            withTimeout(1_000) { cancelled.await() }
            withTimeout(1_000) { worker.join(); parent.join() }
        } finally { worker.cancel(); parent.cancel() }
    }

    @Test
    fun `completed close joins cancelled engine workers`() = runTest {
        val parent = Job()
        val lifetime = AndroidEngineLifecycle(parent)
        val terminal = CompletableDeferred<Unit>()
        val worker = CoroutineScope(coroutineContext + parent).launch {
            try { CompletableDeferred<Unit>().await() } finally { terminal.complete(Unit) }
        }
        kotlinx.coroutines.yield()
        lifetime.close()
        assertTrue(worker.isCompleted)
        assertTrue(parent.isCompleted)
        assertTrue(terminal.isCompleted)
    }
}

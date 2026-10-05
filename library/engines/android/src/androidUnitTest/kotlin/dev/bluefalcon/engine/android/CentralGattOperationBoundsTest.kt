package dev.bluefalcon.engine.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CentralGattOperationBoundsTest {
    private fun key(i: Int) = CentralGattOperationKey(1, CentralGattOperationType.WriteDescriptor, "attribute-$i")
    private fun gate(poison: () -> Unit) = CentralGattOperationGate(10_000, CentralGattTimeoutScheduler { _, _ -> CentralGattTimeoutHandle {} }, onPoisoned = poison)

    @Test fun `queued item overflow terminalizes the active waiter and drops retained closures`() {
        var poisoned = 0
        var nativeCalls = 0
        val outcomes = mutableListOf<CentralGattOperationOutcome>()
        var timerCancelled = false
        val gate = CentralGattOperationGate(10_000, CentralGattTimeoutScheduler { _, _ -> CentralGattTimeoutHandle { timerCancelled = true } }, onPoisoned = { poisoned++ })
        assertTrue(gate.trySubmitTyped(key(0), "active", { nativeCalls++; true }, outcomes::add))
        repeat(128) { i -> gate.enqueueLegacy(key(i+1), "queued") { nativeCalls++; true } }
        assertTrue(gate.isPoisoned)
        assertEquals(0, gate.retainedOperationCount)
        assertEquals(0L, gate.retainedPayloadBytes)
        assertEquals(1, poisoned)
        assertEquals(1, nativeCalls)
        assertEquals(1, outcomes.size)
        assertIs<CentralGattOperationOutcome.Rejected>(outcomes.single())
        assertTrue(timerCancelled)
        assertFalse(gate.complete(key(0), 0, true))
        gate.enqueueLegacy(key(999), "after overflow") { nativeCalls++; true }
        assertEquals(1, nativeCalls)
    }

    @Test fun `queued payload bytes include the active legacy owner`() {
        var poisoned = 0
        var nativeCalls = 0
        val gate = gate { poisoned++ }
        gate.enqueueLegacy(key(0), "active", payloadBytes = 512 * 1024) { nativeCalls++; true }
        gate.enqueueLegacy(key(1), "queued", payloadBytes = 512 * 1024) { nativeCalls++; true }
        assertFalse(gate.isPoisoned)
        gate.enqueueLegacy(key(2), "overflow", payloadBytes = 1) { nativeCalls++; true }
        assertTrue(gate.isPoisoned)
        assertEquals(0, gate.retainedOperationCount)
        assertEquals(0L, gate.retainedPayloadBytes)
        assertEquals(1, poisoned)
        assertEquals(1, nativeCalls)
    }

    @Test fun `completion releases payload budget and keeps boundary admission working`() {
        var poisoned = 0
        var nativeCalls = 0
        val gate = gate { poisoned++ }
        gate.enqueueLegacy(key(0), "first", payloadBytes = 1024 * 1024) { nativeCalls++; true }
        assertTrue(gate.complete(key(0), 0, true))
        gate.enqueueLegacy(key(1), "second", payloadBytes = 1024 * 1024) { nativeCalls++; true }
        assertEquals(2, nativeCalls)
        assertEquals(0, poisoned)
        assertTrue(gate.complete(key(1), 0, true))
        assertTrue(gate.isIdle)
    }
}

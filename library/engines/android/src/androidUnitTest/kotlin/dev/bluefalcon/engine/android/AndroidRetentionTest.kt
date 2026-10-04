package dev.bluefalcon.engine.android

import kotlin.test.*

class AndroidRetentionTest {
    @Test fun `generations remain unique through ten thousand departed peers`() {
        val state = AndroidCentralWriteState()
        val generations = mutableSetOf<Long>()
        repeat(10_000) {
            val peer = "peer-$it"
            val generation = state.onConnected(peer)
            assertTrue(generations.add(generation), "Generation must never be reused by another peer")
            state.onDisconnected(peer, generation)
            assertTrue(state.capabilities.value.isEmpty())
        }
        assertTrue(AndroidCentralWriteState::class.java.declaredFields.none { it.name == "lastGenerations" })
    }

    @Test fun `generation exhaustion fails closed without replacing an active generation`() {
        val state = AndroidCentralWriteState(initialGeneration = Long.MAX_VALUE - 1)
        val generation = state.onConnected("peer")
        assertEquals(Long.MAX_VALUE, generation)
        assertFailsWith<IllegalStateException> { state.onConnected("peer") }
        assertEquals(generation, state.currentGeneration("peer"))
        assertFailsWith<IllegalStateException> { state.onConnected("other") }
        assertNull(state.currentGeneration("other"))
    }

    @Test fun `native capacity rejects before allocation but allows exact replacement after retirement`() {
        val ownership = AndroidGattOwnership<Any>(maximumOwners = 2)
        val first = Any()
        val second = Any()
        ownership.open("first", { first }, {})
        ownership.open("second", { second }, {})
        var allocations = 0
        assertFailsWith<IllegalStateException> { ownership.open("third", { allocations++; Any() }, {}) }
        assertEquals(0, allocations)
        val replacement = Any()
        ownership.open("first", {
            assertFalse(ownership.withCurrent(first) {})
            assertEquals(listOf(second), ownership.snapshot())
            replacement
        }, { assertSame(first, it) })
        assertEquals(2, ownership.snapshot().size)
        assertTrue(ownership.withCurrent(replacement) {})
        assertFalse(ownership.withCurrent(first) {})
    }

    @Test fun `failed replacement allocation leaves old owner retired and another peer intact`() {
        val ownership = AndroidGattOwnership<Any>(maximumOwners = 2)
        val first = Any()
        val second = Any()
        var closes = 0
        ownership.open("first", { first }, {})
        ownership.open("second", { second }, {})
        assertNull(ownership.open("first", { null }, { closes++ }))
        assertEquals(1, closes)
        assertEquals(listOf(second), ownership.snapshot())
    }

    @Test fun `ambiguous native retirement permanently fences allocation while another owner remains usable`() {
        val ownership = AndroidGattOwnership<Any>(maximumOwners = 2)
        val first = Any()
        val independent = Any()
        ownership.open("first", { first }, {})
        ownership.open("independent", { independent }, {})
        var allocations = 0
        assertFailsWith<IllegalStateException> { ownership.open("first", { allocations++; Any() }, { error("native close failed") }) }
        assertFailsWith<IllegalStateException> { ownership.open("new", { allocations++; Any() }, {}) }
        assertEquals(0, allocations)
        assertFalse(ownership.withCurrent(first) {})
        assertTrue(ownership.withCurrent(independent) {})
    }

    @Test fun `discovery churn rejects newest and stays within item and byte bounds without subscribers`() {
        val store = AndroidPeripheralRetention<String>(maximumEntries = 3, maximumBytes = 6, maximumEntryBytes = 4)
        repeat(10_000) { store.offer("peer-$it", "value-$it", 2) }
        assertEquals(3, store.snapshot().size)
        assertEquals(6, store.status.value.retainedAdvertisementBytes)
        assertEquals(9_997, store.status.value.rejectedAdvertisements)
        assertFalse(store.offer("peer-0", "oversize", 5))
        assertEquals("value-0", store.snapshot().first())
        assertFalse(store.offer("peer-0", "aggregate oversize", 3))
        assertEquals(6, store.status.value.retainedAdvertisementBytes)
    }

    @Test fun `direct connection evicts only inactive discovery and release removes byte accounting`() {
        val store = AndroidPeripheralRetention<String>(maximumEntries = 2, maximumBytes = 10, maximumEntryBytes = 5)
        store.offer("active", "active", 5)
        store.offer("inactive", "inactive", 5)
        store.ensureActive("new", "new", setOf("active"))
        assertEquals(listOf("active", "new"), store.snapshot())
        assertEquals(5, store.status.value.retainedAdvertisementBytes)
        assertEquals(1, store.status.value.evictedInactivePeers)
        assertFailsWith<IllegalStateException> { store.ensureActive("overflow", "overflow", setOf("active", "new")) }
        store.clearInactive(setOf("active"))
        assertEquals(listOf("active"), store.snapshot())
        store.clear()
        assertEquals(0, store.status.value.retainedAdvertisementBytes)
    }

    @Test fun `callback value retirement handles abandoned unmatched failing and consumed reads`() {
        val values = AndroidReadCallbackValues()
        repeat(10_000) {
            val key = CentralGattOperationKey(it.toLong(), CentralGattOperationType.ReadCharacteristic, "attribute")
            values.withValue(key, byteArrayOf(1)) { /* abandoned or unmatched operation */ }
            assertEquals(0, values.retainedCount)
        }
        val key = CentralGattOperationKey(10_001, CentralGattOperationType.ReadCharacteristic, "attribute")
        assertFailsWith<IllegalStateException> { values.withValue(key, byteArrayOf(1)) { error("completion failed") } }
        assertEquals(0, values.retainedCount)
        values.withValue(key, byteArrayOf(2)) { assertContentEquals(byteArrayOf(2), values.take(key)) }
        assertEquals(0, values.retainedCount)
        values.withValue(key, null) { assertNull(values.take(key)) }
        assertEquals(0, values.retainedCount)
    }
}

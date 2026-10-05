package dev.bluefalcon.engine.apple

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AppleDiscoveryStoreTest {
    private data class Peer(val id: String, val bytes: Int, val label: String = id)
    private fun store(pinned: Set<String> = emptySet()) = AppleDiscoveryStore<Peer>(4, 8, keyOf = { it.id }, payloadSize = { it.bytes.toLong() }, isPinned = { it.id in pinned })
    @Test fun manufacturerPreflightRejectsBeforeNativeCopyAndHandlesShortHeaders() {
        var copies = 0
        assertNull(decodeAppleManufacturerData(4099uL) { copies++; ByteArray(4099) })
        assertEquals(emptyMap(), decodeAppleManufacturerData(0uL) { error("Must not copy empty NSData") })
        assertEquals(emptyMap(), decodeAppleManufacturerData(1uL) { error("Must not copy short NSData") })
        assertEquals(0, copies)
        val decoded = decodeAppleManufacturerData(4098uL) { copies++; ByteArray(4098).apply { this[0] = 0x34; this[1] = 0x12 } }!!
        assertEquals(4096, decoded[0x1234]!!.size)
        assertEquals(1, copies)
    }
    @Test fun discoveryChurnRejectsNewestAndAccountsPayloadBeforeRetention() {
        val store = store()
        repeat(4) { assertTrue(store.put(Peer("$it", 8), 8)) }
        repeat(10_000) { assertFalse(store.put(Peer("new-$it", 8), 8)) }
        assertEquals(4, store.status.retainedPeers)
        assertEquals(32L, store.status.retainedPayloadBytes)
        assertEquals(10_000L, store.status.rejectedDiscoveries)
        assertFalse(store.put(Peer("0", 9, "oversized"), 9))
        assertEquals("0", store.valueFor("0")?.label)
        assertTrue(store.put(Peer("0", 1, "updated"), 1))
        assertEquals(25L, store.status.retainedPayloadBytes)
    }
    @Test fun connectionAdmissionEvictsOnlyInactiveDiscoveryAndPinnedOverflowRejects() {
        val pinned = mutableSetOf("active", "connected", "third")
        val store = AppleDiscoveryStore<Peer>(2, 8, keyOf = { it.id }, payloadSize = { it.bytes.toLong() }, isPinned = { it.id in pinned })
        assertTrue(store.put(Peer("active", 8), 8))
        assertTrue(store.put(Peer("scan", 8), 8))
        assertTrue(store.put(Peer("connected", 8), 8, evictInactive = true))
        assertEquals(setOf("active", "connected"), store.peripherals.value.map { it.id }.toSet())
        assertFalse(store.put(Peer("third", 1), 1, evictInactive = true))
        pinned.remove("active")
        assertTrue(store.put(Peer("third", 1), 1, evictInactive = true))
        assertEquals(setOf("third", "connected"), store.peripherals.value.map { it.id }.toSet())
        store.clear()
        assertEquals(0, store.status.retainedPeers)
        assertEquals(0L, store.status.retainedPayloadBytes)
        assertEquals(emptySet(), store.peripherals.value)
    }
    @Test fun flowCollectsAndCanReenterClearWithoutAnOwnerLock() = runTest {
        val store = store()
        val observed = mutableListOf<Set<String>>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.peripherals.collect { peers ->
                val ids = peers.map { it.id }.toSet()
                observed += ids
                if ("first" in ids) store.clear()
            }
        }
        assertTrue(store.put(Peer("first", 8), 8))
        assertEquals(emptySet(), store.peripherals.value)
        assertEquals(listOf(emptySet(), setOf("first"), emptySet()), observed)
        collector.cancelAndJoin()
    }
}

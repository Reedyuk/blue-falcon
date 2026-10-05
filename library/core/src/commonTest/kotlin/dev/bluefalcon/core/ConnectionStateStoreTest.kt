package dev.bluefalcon.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ConnectionStateStoreTest {
    @Test fun inactivePeerChurnRetainsOnlyRecentHistoryAndPreservesActivePeer() {
        val store = ConnectionStateStore(64)
        assertTrue(store.update("active") { PeripheralConnectionState.Ready })
        repeat(10_000) { i -> assertTrue(store.update("peer-$i") { PeripheralConnectionState.Disconnected() }) }
        assertEquals(64, store.status.retainedKeys)
        assertEquals(PeripheralConnectionState.Ready, store.states.value["active"])
        assertFalse("peer-0" in store.states.value)
        assertTrue("peer-9999" in store.states.value)
        store.clear()
        assertEquals(0, store.status.retainedKeys)
    }
    @Test fun activeCapacityRejectsNewIdentityWithoutEvictingOwnersAndDisconnectMakesRoom() {
        val store = ConnectionStateStore(2)
        assertTrue(store.update("a") { PeripheralConnectionState.Connecting })
        assertTrue(store.update("b") { PeripheralConnectionState.Connected })
        assertFalse(store.update("c") { PeripheralConnectionState.Connecting })
        assertEquals(1L, store.status.rejectedUpdates)
        assertEquals(setOf("a", "b"), store.states.value.keys)
        assertTrue(store.update("a") { PeripheralConnectionState.Disconnected(DisconnectReason.UserInitiated) })
        assertTrue(store.update("c") { PeripheralConnectionState.Connecting })
        assertEquals(setOf("b", "c"), store.states.value.keys)
    }
    @Test fun missingDiscoveryDoesNotAllocateKeysAndTransformRemainsAtomic() = runTest {
        val store = ConnectionStateStore(8)
        repeat(10_000) { i -> assertTrue(store.update("absent-$i") { existing -> existing }) }
        assertEquals(0, store.status.retainedKeys)
        withContext(Dispatchers.Default) {
            (0 until 100).map { i -> launch {
                store.update("peer-${i % 4}") { PeripheralConnectionState.Connected }
                store.update("peer-${i % 4}") { existing -> if (existing == PeripheralConnectionState.Connected) PeripheralConnectionState.Ready else existing }
            } }.joinAll()
        }
        assertEquals(4, store.status.retainedKeys)
        assertTrue(store.states.value.values.all { it == PeripheralConnectionState.Ready })
    }
}

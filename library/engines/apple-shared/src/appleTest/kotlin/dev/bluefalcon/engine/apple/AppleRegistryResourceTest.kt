package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.NotificationSubscriptionResult
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AppleRegistryResourceTest {
    @Test fun inactivePeerChurnRetiresIdentityStorageWithoutReusingOldAuthority() = runTest {
        val registry = AppleCentralOperationRegistry()
        val old = registry.connected("reused")
        registry.disconnect(old)
        repeat(10_000) { registry.disconnect(registry.connected("peer-$it")) }
        assertEquals(0, registry.retainedPeerCount())
        val replacement = registry.connected("reused")
        assertNotEquals(old, replacement)
        val key = AppleCentralOperationKey("reused", replacement.generation, "value")
        assertTrue(registry.registerRead(key) {})
        assertFalse(registry.completeRead(key.copy(generation = old.generation), AppleReadOutcome.Success(byteArrayOf(9))))
        assertTrue(registry.completeRead(key, AppleReadOutcome.Success(byteArrayOf(1))))
    }

    @Test fun peerAdmissionIsFiniteAndDisconnectRestoresCapacity() = runTest {
        val registry = AppleCentralOperationRegistry()
        val peers = (0 until 32).map { registry.connected("peer-$it") }
        var rejected = false
        try { registry.connected("overflow") } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        assertEquals(32, registry.retainedPeerCount())
        registry.disconnect(peers.first())
        registry.connected("replacement")
        assertEquals(32, registry.retainedPeerCount())
    }

    @Test fun subscribedAttributeChurnCannotGrowPrivateIdentityStorage() = runTest {
        val registry = AppleCentralOperationRegistry()
        val peer = registry.connected("peer")
        val keys = (0 until 256).map { AppleCentralOperationKey("peer", peer.generation, "attribute-$it") }
        keys.forEach { key ->
            assertTrue(registry.registerSubscription(key, true) {})
            assertTrue(registry.completeSubscription(key, NotificationSubscriptionResult.Updated(true)))
        }
        val overflow = AppleCentralOperationKey("peer", peer.generation, "overflow")
        assertFalse(registry.registerSubscription(overflow, true) {})
        assertFalse(registry.registerRead(overflow) {})
        // A disable must remain admissible at capacity and release the same attribute slot.
        assertTrue(registry.registerSubscription(keys.first(), false) {})
        assertTrue(registry.completeSubscription(keys.first(), NotificationSubscriptionResult.Updated(false)))
        assertTrue(registry.registerRead(overflow) {})
        assertFalse(registry.registerSubscription(keys.first(), true) {})
        assertTrue(registry.completeRead(overflow, AppleReadOutcome.Success(byteArrayOf())))
        assertTrue(registry.registerSubscription(keys.first(), true) {})
    }
}

package dev.bluefalcon.plugins.mesh

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class DedupCacheTest {

    @Test
    fun addAndContainsNewEntry() = runTest {
        val cache = DedupCache(maxSize = 10, ttl = 30.seconds)
        val id = MeshMessageId.random()

        assertTrue(cache.add(id), "First add should return true")
        assertTrue(cache.contains(id), "Entry should be present after add")
    }

    @Test
    fun addDuplicateReturnsFalse() = runTest {
        val cache = DedupCache(maxSize = 10, ttl = 30.seconds)
        val id = MeshMessageId.random()

        assertTrue(cache.add(id))
        assertFalse(cache.add(id), "Second add of same ID should return false")
    }

    @Test
    fun evictsOldestWhenFull() = runTest {
        val cache = DedupCache(maxSize = 3, ttl = 30.seconds)
        val id1 = MeshMessageId("id-1")
        val id2 = MeshMessageId("id-2")
        val id3 = MeshMessageId("id-3")
        val id4 = MeshMessageId("id-4")

        cache.add(id1)
        cache.add(id2)
        cache.add(id3)
        assertEquals(3, cache.size())

        cache.add(id4) // Should evict id1
        assertEquals(3, cache.size())
        assertFalse(cache.contains(id1), "Oldest entry should be evicted")
        assertTrue(cache.contains(id2))
        assertTrue(cache.contains(id3))
        assertTrue(cache.contains(id4))
    }

    @Test
    fun containsRefreshesLruOrder() = runTest {
        val cache = DedupCache(maxSize = 3, ttl = 30.seconds)
        val id1 = MeshMessageId("id-1")
        val id2 = MeshMessageId("id-2")
        val id3 = MeshMessageId("id-3")
        val id4 = MeshMessageId("id-4")

        cache.add(id1)
        cache.add(id2)
        cache.add(id3)

        // Access id1 to make it most recently used
        cache.contains(id1)

        // Add id4, should evict id2 (now oldest)
        cache.add(id4)
        assertTrue(cache.contains(id1), "Recently accessed entry should not be evicted")
        assertFalse(cache.contains(id2), "Oldest unused entry should be evicted")
        assertTrue(cache.contains(id3))
        assertTrue(cache.contains(id4))
    }

    @Test
    fun expiredEntryNotContained() = runTest {
        val timeSource = TestTimeSource()
        val cache = DedupCache(maxSize = 10, ttl = 100.milliseconds, timeSource = timeSource)
        val id = MeshMessageId.random()

        cache.add(id)
        assertTrue(cache.contains(id))

        // Advance past TTL
        timeSource += 150.milliseconds
        assertFalse(cache.contains(id), "Expired entry should not be contained")
    }

    @Test
    fun pruneRemovesExpiredEntries() = runTest {
        val timeSource = TestTimeSource()
        val cache = DedupCache(maxSize = 10, ttl = 100.milliseconds, timeSource = timeSource)
        val id1 = MeshMessageId("id-1")
        val id2 = MeshMessageId("id-2")

        cache.add(id1)
        timeSource += 50.milliseconds
        cache.add(id2)

        assertEquals(2, cache.size())

        // Advance past id1's TTL but not id2's
        timeSource += 60.milliseconds
        val pruned = cache.prune()

        assertEquals(1, pruned, "Should prune 1 expired entry")
        assertEquals(1, cache.size())
        assertFalse(cache.contains(id1))
        assertTrue(cache.contains(id2))
    }

    @Test
    fun clearRemovesAllEntries() = runTest {
        val cache = DedupCache(maxSize = 10, ttl = 30.seconds)
        cache.add(MeshMessageId("id-1"))
        cache.add(MeshMessageId("id-2"))
        cache.add(MeshMessageId("id-3"))

        assertEquals(3, cache.size())
        cache.clear()
        assertEquals(0, cache.size())
    }
}

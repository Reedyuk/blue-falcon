package dev.bluefalcon.plugins.mesh

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A thread-safe LRU (Least Recently Used) cache for message deduplication.
 *
 * Tracks message IDs that have been seen recently to prevent re-processing
 * and re-relaying the same message multiple times as it propagates through
 * the mesh network.
 *
 * Entries are evicted when:
 * 1. The cache exceeds [maxSize] (oldest entries evicted first)
 * 2. An entry's age exceeds [ttl] (checked during [contains] and [prune])
 *
 * @param maxSize Maximum number of entries to retain
 * @param ttl Time-to-live for cache entries
 * @param timeSource Time source for TTL calculations (injectable for testing)
 */
internal class DedupCache(
    private val maxSize: Int,
    private val ttl: Duration,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    init {
        require(maxSize > 0) { "Cache max size must be positive" }
        require(ttl.isPositive()) { "TTL must be positive" }
    }

    private data class Entry(
        val insertedAt: TimeSource.Monotonic.ValueTimeMark,
    )

    // LinkedHashMap with accessOrder=true provides LRU ordering
    private val cache = linkedMapOf<MeshMessageId, Entry>()
    private val mutex = Mutex()

    /**
     * Check if a message ID is in the cache (not expired).
     *
     * This operation also refreshes the entry's position in the LRU order
     * if it exists, and prunes expired entries.
     *
     * @return true if the message ID is present and not expired
     */
    suspend fun contains(id: MeshMessageId): Boolean = mutex.withLock {
        val entry = cache[id] ?: return false

        // Check if expired
        if (timeSource.markNow() - entry.insertedAt > ttl) {
            cache.remove(id)
            return false
        }

        // Re-insert to move to end (most recently used)
        cache.remove(id)
        cache[id] = entry
        true
    }

    /**
     * Add a message ID to the cache.
     *
     * If the cache is at capacity, the oldest entry is evicted first.
     * If the ID already exists, its timestamp is refreshed.
     *
     * @return true if the ID was newly added, false if it was already present
     */
    suspend fun add(id: MeshMessageId): Boolean = mutex.withLock {
        val existing = cache.remove(id)
        val isNew = existing == null

        // Evict oldest if at capacity
        if (cache.size >= maxSize) {
            val oldestKey = cache.keys.firstOrNull()
            if (oldestKey != null) {
                cache.remove(oldestKey)
            }
        }

        cache[id] = Entry(insertedAt = timeSource.markNow())
        isNew
    }

    /**
     * Prune all expired entries from the cache.
     *
     * Called periodically or before capacity checks to ensure stale entries
     * don't consume space unnecessarily.
     *
     * @return Number of entries pruned
     */
    suspend fun prune(): Int = mutex.withLock {
        val now = timeSource.markNow()
        val toRemove = cache.entries
            .filter { (_, entry) -> now - entry.insertedAt > ttl }
            .map { it.key }

        toRemove.forEach { cache.remove(it) }
        toRemove.size
    }

    /**
     * Current number of entries in the cache (including potentially expired ones).
     */
    suspend fun size(): Int = mutex.withLock { cache.size }

    /**
     * Clear all entries from the cache.
     */
    suspend fun clear() = mutex.withLock { cache.clear() }
}

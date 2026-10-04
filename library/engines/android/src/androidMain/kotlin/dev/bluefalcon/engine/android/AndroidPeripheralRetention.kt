package dev.bluefalcon.engine.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AndroidDiscoveryRetentionStatus(
    val retainedPeers: Int = 0,
    val retainedAdvertisementBytes: Int = 0,
    val rejectedAdvertisements: Long = 0,
    val evictedInactivePeers: Long = 0,
)

/** Scan rejects newest; explicit connection can evict only an inactive discovered peer. */
internal class AndroidPeripheralRetention<T : Any>(
    private val maximumEntries: Int = 256,
    private val maximumBytes: Int = 256 * 29_700,
    private val maximumEntryBytes: Int = 29_700,
    private val lock: Any = Any(),
) {
    init { require(maximumEntries > 0 && maximumBytes >= 0 && maximumEntryBytes >= 0) }
    private data class Entry<T>(val value: T, val bytes: Int)
    private val entries = linkedMapOf<String, Entry<T>>()
    private val state = MutableStateFlow(AndroidDiscoveryRetentionStatus())
    val status: StateFlow<AndroidDiscoveryRetentionStatus> = state.asStateFlow()

    fun reject(): Boolean = synchronized(lock) {
        state.value = state.value.copy(rejectedAdvertisements = increment(state.value.rejectedAdvertisements))
        false
    }

    fun offer(key: String, value: T, bytes: Int): Boolean = synchronized(lock) {
        require(bytes >= 0)
        val existing = entries[key]
        val nextBytes = state.value.retainedAdvertisementBytes.toLong() - (existing?.bytes ?: 0) + bytes
        if (bytes > maximumEntryBytes || nextBytes > maximumBytes || (existing == null && entries.size >= maximumEntries)) return@synchronized reject()
        entries[key] = Entry(value, bytes)
        publish()
        true
    }

    fun ensureActive(key: String, value: T, activeKeys: Set<String>) = synchronized(lock) {
        if (key in entries) return@synchronized
        if (entries.size >= maximumEntries) {
            val inactive = entries.keys.firstOrNull { it !in activeKeys }
                ?: error("Android discovery storage contains only active owners")
            entries.remove(inactive)
            state.value = state.value.copy(evictedInactivePeers = increment(state.value.evictedInactivePeers))
        }
        // No advertisement snapshot is acquired by an externally supplied direct-connect peer.
        entries[key] = Entry(value, 0)
        publish()
    }

    fun snapshot(): List<T> = synchronized(lock) { entries.values.map { it.value } }
    fun clearInactive(activeKeys: Set<String>) = synchronized(lock) { entries.keys.removeAll { it !in activeKeys }; publish() }
    fun clear() = synchronized(lock) { entries.clear(); publish() }
    private fun publish() { state.value = state.value.copy(retainedPeers = entries.size, retainedAdvertisementBytes = entries.values.sumOf { it.bytes }) }
    private fun increment(value: Long) = if (value == Long.MAX_VALUE) value else value + 1
}

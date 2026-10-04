package dev.bluefalcon.engine.apple

import kotlinx.coroutines.flow.*

/** Bounds the library's retained discovery payload independently of native advertising format. */
data class AppleDiscoveryStorageStatus(val retainedPeers: Int, val retainedPayloadBytes: Long, val rejectedDiscoveries: Long)

internal class AppleDiscoveryStore<T : Any>(
    private val maximumPeers: Int = 256,
    private val maximumValueBytes: Int = 4096,
    private val keyOf: (T) -> String,
    private val payloadSize: (T) -> Long,
    private val isPinned: (T) -> Boolean,
) {
    init { require(maximumPeers > 0); require(maximumValueBytes >= 0) }
    private val values = MutableStateFlow<Set<T>>(emptySet())
    private val rejected = MutableStateFlow(0L)
    val peripherals: StateFlow<Set<T>> = values.asStateFlow()
    val status: AppleDiscoveryStorageStatus get() = values.value.let { current ->
        AppleDiscoveryStorageStatus(current.size, current.sumOf(payloadSize), rejected.value)
    }
    fun valueFor(uuid: String): T? = values.value.firstOrNull { keyOf(it) == uuid }
    fun put(value: T, payloadBytes: Long, evictInactive: Boolean = false): Boolean {
        require(payloadBytes >= 0)
        if (payloadBytes > maximumValueBytes) { reject(); return false }
        val uuid = keyOf(value)
        while (true) {
            val current = values.value
            var retained = current.filterNot { keyOf(it) == uuid }.toSet()
            if (retained.size >= maximumPeers) {
                val victim = if (evictInactive) retained.firstOrNull { !isPinned(it) } else null
                if (victim == null) { reject(); return false }
                retained = retained - victim
            }
            val next = retained + value
            if (values.compareAndSet(current, next)) return true
        }
    }
    fun reject() { rejected.update { if (it == Long.MAX_VALUE) it else it + 1 } }
    fun clear() { values.value = emptySet() }
}

/** Length admission occurs before any native NSData copy or payload allocation. */
internal fun decodeAppleManufacturerData(length: ULong, copy: () -> ByteArray): Map<Int, ByteArray>? {
    if (length > 4098uL) return null
    if (length < 2uL) return emptyMap()
    val bytes = copy()
    if (bytes.size < 2) return emptyMap()
    val companyId = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
    return mapOf(companyId to bytes.copyOfRange(2, bytes.size))
}

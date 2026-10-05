package dev.bluefalcon.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Bounded diagnostic history; active connection state is never evicted. */
data class ConnectionStateStorageStatus(val retainedKeys: Int, val rejectedUpdates: Long)

internal class ConnectionStateStore(private val maximumKeys: Int = 256) {
    init { require(maximumKeys > 0) }
    private val values = MutableStateFlow<Map<String, PeripheralConnectionState>>(emptyMap())
    private val rejected = MutableStateFlow(0L)
    val states: StateFlow<Map<String, PeripheralConnectionState>> = values.asStateFlow()
    val status: ConnectionStateStorageStatus get() = ConnectionStateStorageStatus(values.value.size, rejected.value)

    fun update(uuid: String, transform: (PeripheralConnectionState?) -> PeripheralConnectionState?): Boolean {
        while (true) {
            val current = values.value
            val nextValue = transform(current[uuid]) ?: return true
            var retained = current - uuid
            if (uuid !in current && retained.size >= maximumKeys) {
                val inactive = retained.entries.firstOrNull { it.value is PeripheralConnectionState.Disconnected }
                if (inactive == null) {
                    // Retry if another updater released a slot during the decision.
                    if (values.value !== current) continue
                    rejected.update { if (it == Long.MAX_VALUE) it else it + 1 }
                    return false
                }
                retained = retained - inactive.key
            }
            val next = retained + (uuid to nextValue)
            if (values.compareAndSet(current, next)) return true
        }
    }
    fun clear() { values.value = emptyMap() }
}

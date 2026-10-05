package dev.bluefalcon.peripheral.android

import dev.bluefalcon.peripheral.PeripheralSessionId

/** Called under the framework lock. Live retirement quarantines its address until
 * server restart: address-only callbacks cannot prove every old callback drained.
 * Normal native terminal removal releases an unambiguous live owner. */
internal class AndroidNativeSessionOwners<T : Any>(private val maximumSlots: Int = 256) {
    private val retired = mutableSetOf<PeripheralSessionId>()
    private val live = mutableMapOf<PeripheralSessionId, T>()
    fun admit(id: PeripheralSessionId, create: () -> T): T? {
        if (id in retired) return null
        return live[id] ?: if (live.size + retired.size >= maximumSlots) null else create().also { live[id] = it }
    }
    operator fun get(id: PeripheralSessionId): T? = live[id]
    fun remove(id: PeripheralSessionId): T? = live.remove(id)
    fun retire(id: PeripheralSessionId, expected: T): Boolean {
        if (live[id] !== expected) return false
        live.remove(id)
        retired += id
        return true
    }
    fun clear() { live.clear(); retired.clear() }
}

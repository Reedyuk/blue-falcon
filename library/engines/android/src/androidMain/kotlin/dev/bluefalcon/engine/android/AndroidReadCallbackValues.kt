package dev.bluefalcon.engine.android

/** Callback-scoped read snapshots; abandoned/unmatched callbacks retain no completed history. */
internal class AndroidReadCallbackValues {
    private val values = mutableMapOf<CentralGattOperationKey, ByteArray>()
    val retainedCount: Int get() = values.size
    fun take(key: CentralGattOperationKey): ByteArray? = values.remove(key)
    fun <T> withValue(key: CentralGattOperationKey, value: ByteArray?, complete: () -> T): T {
        if (value != null) values[key] = value
        return try { complete() } finally { values.remove(key) }
    }
    fun clear() { values.clear() }
}

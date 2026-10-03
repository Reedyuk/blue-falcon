package dev.bluefalcon.engine.android

/** One native owner per address, including connections that are still being established. */
internal class AndroidGattOwnership<T : Any> {
    val lock = Any()
    private val owners = mutableMapOf<String, T>()

    fun track(address: String, owner: T): T? = synchronized(lock) {
        owners.put(address, owner)?.takeUnless { it === owner }
    }

    fun snapshot(): List<T> = synchronized(lock) { owners.values.toList() }

    fun forget(owner: T): Boolean = synchronized(lock) {
        val address = owners.entries.firstOrNull { it.value === owner }?.key
            ?: return@synchronized false
        owners.remove(address)
        true
    }

    fun withCurrent(owner: T?, callback: (T) -> Unit): Boolean = synchronized(lock) {
        if (owner == null || owners.values.none { it === owner }) return@synchronized false
        callback(owner)
        true
    }
}

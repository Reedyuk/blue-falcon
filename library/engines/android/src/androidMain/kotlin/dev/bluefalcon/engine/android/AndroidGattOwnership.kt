package dev.bluefalcon.engine.android

/** One native owner per address, including connections that are still being established. */
internal class AndroidGattOwnership<T : Any>(val lock: Any = Any(), private val maximumOwners: Int = 32) {
    init { require(maximumOwners > 0) }
    private val owners = mutableMapOf<String, T>()
    private var admissionFailure: Throwable? = null

    fun refuseNewOwners(failure: Throwable) = synchronized(lock) {
        if (admissionFailure == null) admissionFailure = failure
    }

    fun checkCapacity(address: String) = synchronized(lock) {
        admissionFailure?.let { throw IllegalStateException("Android native owner retirement failed; new connection admission is closed", it) }
        check(address in owners || owners.size < maximumOwners) { "Android native connection capacity exceeded ($maximumOwners)" }
    }

    /** Admission precedes allocation; replacement retires its predecessor before allocating. */
    fun open(address: String, create: () -> T?, retire: (T) -> Unit): T? = synchronized(lock) {
        checkCapacity(address)
        owners.remove(address)?.let { owner ->
            try { retire(owner) } catch (failure: Throwable) { refuseNewOwners(failure); throw failure }
        }
        create()?.also { owners[address] = it }
    }

    fun track(address: String, owner: T): T? = synchronized(lock) {
        checkCapacity(address)
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

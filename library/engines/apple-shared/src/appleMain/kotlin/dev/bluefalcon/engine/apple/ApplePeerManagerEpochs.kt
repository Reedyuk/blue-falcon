package dev.bluefalcon.engine.apple

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import platform.Foundation.NSHashTable
import platform.Foundation.NSLock
import platform.Foundation.NSPointerFunctionsObjectPointerPersonality
import platform.Foundation.NSPointerFunctionsWeakMemory
import platform.darwin.NSObject

/** Manager origin is permanent: an old central delegate never captures a replacement owner. */
internal class ApplePeerManagerEpochs<M : Any>(private val maximumPeers: Int = 32) {
    private val admissionClosed = MutableStateFlow(false)
    private val nativeLock = NSLock()
    // Kotlin WeakReference tracks the Kotlin wrapper, not the underlying ObjC lifetime.
    private val retiredObjcNatives = NSHashTable(
        options = NSPointerFunctionsWeakMemory or NSPointerFunctionsObjectPointerPersonality,
        capacity = 256uL,
    )

    fun nativeAllowed(native: NSObject): Boolean {
        if (admissionClosed.value) return false
        nativeLock.lock()
        try { return !admissionClosed.value && !retiredObjcNatives.containsObject(native) }
        finally { nativeLock.unlock() }
    }

    /** Never evict a still-live ambiguous native handle to regain admission. */
    fun quarantineNative(native: NSObject) {
        nativeLock.lock()
        try {
            // Compact dead weak slots too: lifetime churn must not grow private storage.
            val live = retiredObjcNatives.allObjects
            retiredObjcNatives.removeAllObjects()
            live.forEach { retiredObjcNatives.addObject(it!!) }
            if (!retiredObjcNatives.containsObject(native)) {
                if (retiredObjcNatives.count >= 256uL) admissionClosed.value = true
                else retiredObjcNatives.addObject(native)
            }
        } finally { nativeLock.unlock() }
    }
    class Epoch<M : Any>(val uuid: String) {
        var manager: M? = null
        internal val retiring = MutableStateFlow(false)
        val terminated = CompletableDeferred<Unit>()
    }
    private val owners = MutableStateFlow<Map<String, Epoch<M>>>(emptyMap())
    fun current(uuid: String): Epoch<M>? = owners.value[uuid]
    fun isCurrent(epoch: Epoch<M>): Boolean = owners.value[epoch.uuid] === epoch && !epoch.retiring.value
    fun <T : Any> capture(epoch: Epoch<M>, ownership: AppleNativeConnectionOwnership<T>, native: T): AppleNativeConnectionToken<T>? {
        if (!isCurrent(epoch)) return null
        return ownership.capture(epoch.uuid, native)?.takeIf { it.origin === epoch }
    }
    fun reserve(uuid: String): Epoch<M> {
        val epoch = Epoch<M>(uuid)
        while (true) {
            val current = owners.value
            check(uuid !in current) { "Apple peer manager already reserved" }
            check(current.size < maximumPeers) { "Apple central peer manager limit reached ($maximumPeers)" }
            if (owners.compareAndSet(current, current + (uuid to epoch))) return epoch
        }
    }
    fun retire(epoch: Epoch<M>): Boolean {
        if (!beginRetirement(epoch)) return false
        return finishRetirement(epoch)
    }
    /** Reject ingress now, but reserve admission until native close and owner cleanup finish. */
    fun beginRetirement(epoch: Epoch<M>): Boolean =
        owners.value[epoch.uuid] === epoch && epoch.retiring.compareAndSet(false, true)

    fun retireAfterClose(epoch: Epoch<M>, close: () -> Unit, cleanup: () -> Unit): Boolean {
        if (!beginRetirement(epoch)) return false
        try { close() } finally { cleanup() }
        return true
    }

    fun finishRetirement(epoch: Epoch<M>): Boolean {
        if (!epoch.retiring.value) return false
        while (true) {
            val current = owners.value
            if (current[epoch.uuid] !== epoch) return false
            if (owners.compareAndSet(current, current - epoch.uuid)) {
                epoch.terminated.complete(Unit)
                return true
            }
        }
    }
}

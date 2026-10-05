package dev.bluefalcon.engine.apple

import kotlinx.coroutines.flow.MutableSharedFlow
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBPeripheral
import platform.Foundation.*

/** Shared private storage diagnostics. Values exceeding the configured bound are rejected. */
data class AppleNotificationStorageStatus(val retainedKeys: Int, val rejectedValues: Long)

/** Native weak pointer identity prevents forever UUID histories and cross-incarnation aliasing. */
internal class AppleNotificationFlowStore(
    private val maximumKeys: Int = 256,
    private val maximumPayloadBytes: Int = 512,
) {
    private class Entry(characteristic: CBCharacteristic) {
        val native = weakIdentity().apply { addObject(characteristic) }
        val peer = weakIdentity().apply { characteristic.service?.peripheral?.let { addObject(it) } }
        val flow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    }
    private val lock = NSLock()
    private val entries = mutableListOf<Entry>()
    private var rejectedValues = 0L
    init { require(maximumKeys > 0 && maximumPayloadBytes >= 0) }
    val status: AppleNotificationStorageStatus get() = locked {
        compact()
        AppleNotificationStorageStatus(entries.size, rejectedValues)
    }
    fun retainedKeyCount(): Int = status.retainedKeys
    fun flowFor(characteristic: CBCharacteristic): MutableSharedFlow<ByteArray> = locked {
        compact()
        entries.firstOrNull { it.native.containsObject(characteristic) }?.flow ?: run {
            check(entries.size < maximumKeys) { "Apple notification key capacity reached ($maximumKeys)" }
            Entry(characteristic).also(entries::add).flow
        }
    }
    fun emit(characteristic: CBCharacteristic, value: ByteArray): Boolean {
        if (value.size > maximumPayloadBytes) { reject(); return false }
        // Native callbacks without a subscribed wrapper need no private flow allocation.
        val flow = locked {
            compact()
            entries.firstOrNull { it.native.containsObject(characteristic) }?.flow
        } ?: return true
        // tryEmit can resume an unconfined collector synchronously. No native lock crosses it.
        val accepted = flow.tryEmit(value.copyOf())
        if (!accepted) reject()
        return accepted
    }
    fun retirePeripheral(peripheral: CBPeripheral) = locked {
        entries.removeAll { it.peer.containsObject(peripheral) }
        compact()
    }
    fun clear() = locked { entries.clear() }
    private fun reject() = locked { if (rejectedValues < Long.MAX_VALUE) rejectedValues++ }
    private fun compact() { entries.removeAll { it.native.allObjects.isEmpty() } }
    private inline fun <T> locked(action: () -> T): T {
        lock.lock()
        try { return action() } finally { lock.unlock() }
    }
    private companion object {
        fun weakIdentity() = NSHashTable(
            options = NSPointerFunctionsWeakMemory or NSPointerFunctionsObjectPointerPersonality,
            capacity = 1uL,
        )
    }
}

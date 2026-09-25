package dev.bluefalcon.engine.rpi

import com.welie.blessed.BluetoothCommandStatus
import dev.bluefalcon.core.BluetoothUnknownException
import dev.bluefalcon.core.CharacteristicWriteResult
import kotlinx.coroutines.CompletableDeferred

/**
 * The outcome of one characteristic write that Blessed queued: the status from
 * `onCharacteristicWrite`, or [Disconnected] when the peripheral disconnected first.
 */
internal sealed interface RpiWriteOutcome {
    data class Completed(val status: BluetoothCommandStatus) : RpiWriteOutcome

    data object Disconnected : RpiWriteOutcome
}

internal fun RpiWriteOutcome.toWriteResult(): CharacteristicWriteResult = when (this) {
    RpiWriteOutcome.Disconnected -> CharacteristicWriteResult.Disconnected
    is RpiWriteOutcome.Completed ->
        if (status == BluetoothCommandStatus.COMMAND_SUCCESS) {
            CharacteristicWriteResult.Sent
        } else {
            CharacteristicWriteResult.Failed(BluetoothUnknownException("Characteristic write failed: $status"))
        }
}

/**
 * Matches each Blessed `onCharacteristicWrite` callback to the write that caused it.
 *
 * Blessed reports a write result only through `onCharacteristicWrite(peripheral, value,
 * characteristic, status)`, with no request identity. It runs the commands of one peripheral one at
 * a time, in the order they were queued, and it calls back once for each write that it sends, for a
 * write with response and for a write without response. So the first pending write of that
 * peripheral to the same characteristic is the write that the callback belongs to.
 *
 * The callback's `value` is not used. Blessed (0.65) posts the callback to its callback thread and
 * then starts the next command, and the posted callback reads the peripheral's `currentWriteBytes`
 * only when it runs. By then the next write can have replaced it, so the value can belong to a
 * later write. The characteristic and the status are captured when the callback is posted.
 *
 * Rules that keep the match correct:
 * - Queue each write through [enqueue], which records the write and lets Blessed queue it under
 *   one lock. So the order of the records is the order in which Blessed sends the writes, also
 *   when two coroutines write at the same time.
 * - Queue every write through [enqueue], also one that nobody waits for. Otherwise its callback
 *   completes the next write to the same characteristic.
 * - Do not remove a write when its caller stops waiting (a timeout or a cancellation). Its
 *   callback still comes, and the entry must take that callback, not a later write's entry.
 * - Blessed skips a queued write with no callback only when the peripheral is not connected, so
 *   [disconnected] completes every pending write of that peripheral. The central manager gives its
 *   own callback handler to each peripheral, so the write callbacks and `onDisconnectedPeripheral`
 *   run in order on one thread, and a write callback from an old connection runs before the flush.
 *
 * Limit: Blessed gives a callback no request identity, so the match depends on one callback for
 * each queued write. If Blessed ever went on to the next command with no callback while the link
 * stays up, each later write to that characteristic would get the result of the write before it,
 * until the next disconnect. Blessed 0.65 has no such path.
 *
 * A timeout or a cancellation keeps the record, as a later callback must not complete a newer
 * write. That is the reason this engine does not release the slot of a cancelled call.
 *
 * Blessed calls back on its own thread, so every method that reads or changes the records takes
 * [lock]. [enqueue] also takes [orderLock]; the callback thread never takes it.
 */
internal class RpiPendingWrites {

    /** One queued write. [outcome] completes when its callback arrives or the peripheral disconnects. */
    class Entry internal constructor(internal val characteristicKey: String) {
        val outcome: CompletableDeferred<RpiWriteOutcome> = CompletableDeferred()
    }

    private val lock = Any()
    private val orderLock = Any()
    private val byPeripheral = mutableMapOf<String, MutableList<Entry>>()

    /**
     * Records a write to [characteristicKey] and runs [queue], which must ask Blessed to queue the
     * write and return what Blessed returned. Returns the record, or null when Blessed refused the
     * write (then no callback comes, and no record stays).
     *
     * The write is recorded before [queue] runs, because the callback can arrive before Blessed's
     * `writeCharacteristic` returns. [queue] must not suspend or wait for a callback.
     */
    fun enqueue(peripheralAddress: String, characteristicKey: String, queue: () -> Boolean): Entry? =
        synchronized(orderLock) {
            val entry = Entry(characteristicKey)
            synchronized(lock) { byPeripheral.getOrPut(peripheralAddress) { mutableListOf() } += entry }
            if (queue()) {
                entry
            } else {
                remove(peripheralAddress, entry)
                null
            }
        }

    private fun remove(peripheralAddress: String, entry: Entry) {
        synchronized(lock) {
            byPeripheral[peripheralAddress]?.let { entries ->
                entries.remove(entry)
                if (entries.isEmpty()) byPeripheral.remove(peripheralAddress)
            }
        }
    }

    /**
     * Completes the first pending write of [peripheralAddress] to [characteristicKey]. Returns false
     * when no write matches.
     */
    fun complete(peripheralAddress: String, characteristicKey: String, status: BluetoothCommandStatus): Boolean {
        val entry = synchronized(lock) {
            val entries = byPeripheral[peripheralAddress] ?: return false
            val index = entries.indexOfFirst { it.characteristicKey == characteristicKey }
            if (index < 0) return false
            entries.removeAt(index).also {
                if (entries.isEmpty()) byPeripheral.remove(peripheralAddress)
            }
        }
        entry.outcome.complete(RpiWriteOutcome.Completed(status))
        return true
    }

    /** Completes every pending write of [peripheralAddress] as [RpiWriteOutcome.Disconnected]. */
    fun disconnected(peripheralAddress: String) {
        val entries = synchronized(lock) { byPeripheral.remove(peripheralAddress).orEmpty() }
        entries.forEach { it.outcome.complete(RpiWriteOutcome.Disconnected) }
    }

    /** The number of writes of [peripheralAddress] that wait for a callback. */
    fun pendingCount(peripheralAddress: String): Int =
        synchronized(lock) { byPeripheral[peripheralAddress]?.size ?: 0 }

    companion object {
        /** The key of one characteristic: its service UUID and its own UUID. */
        fun characteristicKey(serviceUuid: Any?, characteristicUuid: Any): String =
            "$serviceUuid/$characteristicUuid"
    }
}

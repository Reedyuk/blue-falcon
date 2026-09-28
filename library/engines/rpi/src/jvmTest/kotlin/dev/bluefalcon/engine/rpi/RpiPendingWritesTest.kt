package dev.bluefalcon.engine.rpi

import com.welie.blessed.BluetoothCommandStatus
import dev.bluefalcon.core.CharacteristicWriteResult
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Unit tests for [RpiPendingWrites], which matches each Blessed `onCharacteristicWrite` callback to
 * the write that caused it, and for the mapping of a write outcome to a [CharacteristicWriteResult].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RpiPendingWritesTest {

    private val writes = RpiPendingWrites()

    private fun queued(peripheral: String = PERIPHERAL, characteristic: String = CHAR_A) =
        assertNotNull(writes.enqueue(peripheral, characteristic) { true })

    @Test
    fun `a callback completes the pending write to its characteristic`() {
        val entry = queued()

        assertTrue(writes.complete(PERIPHERAL, CHAR_A, BluetoothCommandStatus.COMMAND_SUCCESS))

        assertEquals(RpiWriteOutcome.Completed(BluetoothCommandStatus.COMMAND_SUCCESS), entry.outcome.getCompleted())
        assertEquals(0, writes.pendingCount(PERIPHERAL))
    }

    @Test
    fun `callbacks complete writes to one characteristic in queue order`() {
        val first = queued()
        val second = queued()

        writes.complete(PERIPHERAL, CHAR_A, BluetoothCommandStatus.WRITE_NOT_PERMITTED)
        assertEquals(
            RpiWriteOutcome.Completed(BluetoothCommandStatus.WRITE_NOT_PERMITTED),
            first.outcome.getCompleted(),
        )
        assertFalse(second.outcome.isCompleted)

        writes.complete(PERIPHERAL, CHAR_A, BluetoothCommandStatus.COMMAND_SUCCESS)
        assertEquals(RpiWriteOutcome.Completed(BluetoothCommandStatus.COMMAND_SUCCESS), second.outcome.getCompleted())
    }

    @Test
    fun `a callback skips writes to another characteristic`() {
        val other = queued(characteristic = CHAR_B)
        val match = queued(characteristic = CHAR_A)

        writes.complete(PERIPHERAL, CHAR_A, BluetoothCommandStatus.COMMAND_SUCCESS)

        assertTrue(match.outcome.isCompleted)
        assertFalse(other.outcome.isCompleted)
    }

    @Test
    fun `a write that nobody waits for still takes its own callback`() {
        // A legacy write is queued through enqueue as well, so its callback cannot complete the
        // typed write to the same characteristic that follows it.
        val legacy = queued()
        val typed = queued()

        writes.complete(PERIPHERAL, CHAR_A, BluetoothCommandStatus.WRITE_NOT_PERMITTED)

        assertTrue(legacy.outcome.isCompleted)
        assertFalse(typed.outcome.isCompleted)
    }

    @Test
    fun `a callback does not complete a write of another peripheral`() {
        val other = queued(peripheral = OTHER_PERIPHERAL)

        assertFalse(writes.complete(PERIPHERAL, CHAR_A, BluetoothCommandStatus.COMMAND_SUCCESS))
        assertFalse(other.outcome.isCompleted)
    }

    @Test
    fun `a write that Blessed refuses leaves no record`() {
        assertNull(writes.enqueue(PERIPHERAL, CHAR_A) { false })

        assertEquals(0, writes.pendingCount(PERIPHERAL))
        assertFalse(writes.complete(PERIPHERAL, CHAR_A, BluetoothCommandStatus.COMMAND_SUCCESS))
    }

    @Test
    fun `a write is recorded before Blessed queues it, so an early callback finds it`() {
        var completedDuringQueue = false
        val entry = writes.enqueue(PERIPHERAL, CHAR_A) {
            completedDuringQueue = writes.complete(PERIPHERAL, CHAR_A, BluetoothCommandStatus.COMMAND_SUCCESS)
            true
        }

        assertTrue(completedDuringQueue)
        assertTrue(assertNotNull(entry).outcome.isCompleted)
    }

    @Test
    fun `records keep the order in which Blessed queued the writes when threads write at once`() {
        val blessedOrder = Collections.synchronizedList(mutableListOf<Int>())
        val entries = arrayOfNulls<RpiPendingWrites.Entry>(THREADS)
        val start = CountDownLatch(1)
        val threads = (0 until THREADS).map { i ->
            thread {
                start.await()
                entries[i] = writes.enqueue(PERIPHERAL, CHAR_A) { blessedOrder.add(i) }
            }
        }
        start.countDown()
        threads.forEach { it.join() }

        // Blessed calls back in its queue order, so each callback must complete the write that
        // Blessed queued at that position.
        blessedOrder.toList().forEach { i ->
            writes.complete(PERIPHERAL, CHAR_A, BluetoothCommandStatus.COMMAND_SUCCESS)
            assertTrue(assertNotNull(entries[i]).outcome.isCompleted, "write $i")
            blessedOrder.toList().dropWhile { it != i }.drop(1).forEach { later ->
                assertFalse(assertNotNull(entries[later]).outcome.isCompleted, "write $later after $i")
            }
        }
    }

    @Test
    fun `a disconnect completes every pending write of that peripheral only`() {
        val first = queued(characteristic = CHAR_A)
        val second = queued(characteristic = CHAR_B)
        val other = queued(peripheral = OTHER_PERIPHERAL)

        writes.disconnected(PERIPHERAL)

        assertEquals(RpiWriteOutcome.Disconnected, first.outcome.getCompleted())
        assertEquals(RpiWriteOutcome.Disconnected, second.outcome.getCompleted())
        assertFalse(other.outcome.isCompleted)
        assertEquals(0, writes.pendingCount(PERIPHERAL))
        assertEquals(1, writes.pendingCount(OTHER_PERIPHERAL))
    }

    @Test
    fun `outcomes map to write results`() {
        assertEquals(
            CharacteristicWriteResult.Sent,
            RpiWriteOutcome.Completed(BluetoothCommandStatus.COMMAND_SUCCESS).toWriteResult(),
        )
        assertIs<CharacteristicWriteResult.Failed>(
            RpiWriteOutcome.Completed(BluetoothCommandStatus.WRITE_NOT_PERMITTED).toWriteResult(),
        )
        assertEquals(CharacteristicWriteResult.Disconnected, RpiWriteOutcome.Disconnected.toWriteResult())
    }

    private companion object {
        const val PERIPHERAL = "AA:BB:CC:DD:EE:01"
        const val OTHER_PERIPHERAL = "AA:BB:CC:DD:EE:02"
        const val THREADS = 16
        val CHAR_A = RpiPendingWrites.characteristicKey("service-1", "char-a")
        val CHAR_B = RpiPendingWrites.characteristicKey("service-1", "char-b")
    }
}

package dev.bluefalcon.core

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.assertNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalBlueFalconApi::class)
class L2capInboundBufferTest {
    @Test
    fun chunksReceivedBeforeTheFirstCollectorAreDeliveredAfterThreeSeconds() = runTest {
        val buffer = L2capInboundBuffer(backgroundScope, capacity = 8)
        repeat(8) { assertTrue(buffer.trySend(byteArrayOf(it.toByte()))) }

        delay(3_000)
        val received = buffer.incoming.take(8).toList()

        assertEquals((0 until 8).toList(), received.map { it.single().toInt() })
    }

    @Test
    fun fullBufferPushesBackOnTheReaderInsteadOfDropping() = runTest {
        val capacity = 4
        val buffer = L2capInboundBuffer(backgroundScope, capacity = capacity)
        var accepted = 0
        while (buffer.trySend(byteArrayOf(accepted.toByte()))) accepted++

        // The forwarder holds one chunk while it waits for a subscriber.
        assertTrue(accepted in capacity..capacity + 1, "accepted $accepted chunks")
        assertFalse(buffer.trySend(byteArrayOf(99)))

        val received = buffer.incoming.take(accepted).toList()
        assertEquals((0 until accepted).toList(), received.map { it.single().toInt() })
    }

    @Test
    fun slowCollectorReceivesEveryChunkInOrderWithBoundedLead() = runTest {
        val capacity = 4
        val total = 500
        val buffer = L2capInboundBuffer(backgroundScope, capacity = capacity)
        var sent = 0
        var received = 0
        var maximumLead = 0
        launch {
            repeat(total) {
                buffer.send(byteArrayOf((it % 251).toByte()))
                sent++
                maximumLead = maxOf(maximumLead, sent - received)
            }
            buffer.finish()
        }

        val chunks = buffer.incoming.take(total).onEach {
            received++
            delay(5)
        }.toList()

        assertEquals((0 until total).map { (it % 251).toByte() }, chunks.map { it.single() })
        // Channel + shared-flow buffer + the chunk held by the forwarder + the one being sent.
        assertTrue(maximumLead <= 2 * capacity + 2, "reader ran $maximumLead chunks ahead")
    }

    @Test
    fun finishDeliversBufferedChunksBeforeReportingEndOfInput() = runTest {
        var finished = false
        val buffer = L2capInboundBuffer(backgroundScope, capacity = 4, onInputFinished = { finished = true })
        buffer.trySend(byteArrayOf(1))
        buffer.trySend(byteArrayOf(2))
        buffer.finish()
        assertFalse(buffer.trySend(byteArrayOf(3)))

        delay(1_000)
        assertFalse(finished, "end of input must wait for the buffered chunks")
        val received = buffer.incoming.take(2).toList()

        assertEquals(listOf(1, 2), received.map { it.single().toInt() })
        delay(1)
        assertTrue(finished)
    }

    @Test
    fun emptyInputFinishesWithoutACollector() = runTest {
        var finished = false
        val buffer = L2capInboundBuffer(backgroundScope, onInputFinished = { finished = true })
        buffer.finish()
        delay(1)
        assertTrue(finished)
    }

    @Test
    fun chunksWaitForTheNextCollectorWhenTheFirstOneCancels() = runTest {
        val buffer = L2capInboundBuffer(backgroundScope, capacity = 4)
        buffer.trySend(byteArrayOf(1))
        assertEquals(listOf(1), buffer.incoming.take(1).toList().map { it.single().toInt() })

        buffer.trySend(byteArrayOf(2))
        buffer.trySend(byteArrayOf(3))
        delay(1_000)
        val second = async { buffer.incoming.take(2).toList() }

        assertEquals(listOf(2, 3), second.await().map { it.single().toInt() })
    }

    @Test
    fun cancelDropsBufferedChunksAndRejectsNewOnes() = runTest {
        var finished = false
        val buffer = L2capInboundBuffer(backgroundScope, capacity = 4, onInputFinished = { finished = true })
        buffer.trySend(byteArrayOf(1))
        buffer.cancel()

        assertFalse(buffer.trySend(byteArrayOf(2)))
        assertFailsWith<Exception> { buffer.send(byteArrayOf(3)) }
        assertNull(withTimeoutOrNull(1_000) { buffer.incoming.first() })
        assertFalse(finished)
    }

    @Test
    fun capacityMustBePositive() {
        assertFailsWith<IllegalArgumentException> { L2capInboundBuffer(CoroutineScope(Job()), capacity = 0) }
    }
}

package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothService
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSInputStream
import platform.Foundation.NSOutputStream
import platform.Foundation.NSStreamEventEndEncountered
import platform.Foundation.NSStreamEventHasBytesAvailable
import platform.Foundation.NSStreamStatusClosed
import platform.Foundation.outputStreamToMemory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #335: `AppleL2CapSocket.drainInput` used `tryEmit` and ignored the result, so chunks were
 * lost before the first collector and whenever a collector fell 64 chunks behind.
 *
 * The streams are real [NSInputStream]s. The test thread is not a running run loop, so the test
 * delivers the stream events itself, and resumed drains run on the forwarder's thread.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleSocketInboundTest {
    @Test fun collectorThatStartsThreeSecondsAfterOpenReceivesEveryByte() = runBlocking {
        val payload = pattern(200 * 4096 + 123)
        val input = NSInputStream(data = payload.toData())
        val socket = AppleL2CapSocket(
            input, NSOutputStream.outputStreamToMemory(), 42, Peer,
            inboundCapacity = 8,
            runOnStreamThread = { it() },
        )
        var closeNotifications = 0
        socket.onClosed = { closeNotifications++ }
        val events = input.delegate!!
        events.stream(input, NSStreamEventHasBytesAvailable)
        assertTrue(input.hasBytesAvailable, "reading must stop while the inbound buffer is full")
        // The remote finishes sending while the app is still busy.
        events.stream(input, NSStreamEventEndEncountered)
        assertTrue(socket.isOpen, "unread bytes must keep the socket open")

        withContext(Dispatchers.Default) { delay(3_000) }
        val received = collectAll(socket, payload.size)

        assertContentEquals(payload, received)
        assertFalse(socket.isOpen)
        assertEquals(NSStreamStatusClosed, input.streamStatus)
        assertEquals(1, closeNotifications)
    }

    @Test fun slowCollectorReceivesEveryByte() = runBlocking {
        val payload = pattern(300 * 4096)
        val input = NSInputStream(data = payload.toData())
        val socket = AppleL2CapSocket(
            input, NSOutputStream.outputStreamToMemory(), 42, Peer,
            runOnStreamThread = { it() },
        )
        input.delegate!!.stream(input, NSStreamEventHasBytesAvailable)

        val received = collectAll(socket, payload.size) { delay(2) }

        assertContentEquals(payload, received)
        socket.close()
    }

    @Test fun closeDropsBufferedChunksAndStopsReading() = runBlocking {
        val input = NSInputStream(data = pattern(100 * 4096).toData())
        val socket = AppleL2CapSocket(
            input, NSOutputStream.outputStreamToMemory(), 42, Peer,
            inboundCapacity = 4,
            runOnStreamThread = { it() },
        )
        val events = input.delegate!!
        events.stream(input, NSStreamEventHasBytesAvailable)
        socket.close()
        events.stream(input, NSStreamEventHasBytesAvailable)
        assertFalse(socket.isOpen)
        assertEquals(NSStreamStatusClosed, input.streamStatus)
    }

    private suspend fun collectAll(
        socket: AppleL2CapSocket,
        expected: Int,
        onChunk: suspend () -> Unit = {},
    ): ByteArray = withContext(Dispatchers.Default) {
        withTimeout(60_000) {
            val out = ArrayList<Byte>(expected)
            socket.incoming
                .onEach { chunk -> chunk.forEach(out::add); onChunk() }
                .takeWhile { out.size < expected }
                .collect {}
            out.toByteArray()
        }
    }

    private fun pattern(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private object Peer : BluetoothPeripheral {
        override val name: String? = "peer"
        override val uuid = "peer"
        override val rssi: Float? = null
        override val mtuSize: Int? = null
        override val services = emptyList<BluetoothService>()
        override val characteristics = emptyList<BluetoothCharacteristic>()
    }
}

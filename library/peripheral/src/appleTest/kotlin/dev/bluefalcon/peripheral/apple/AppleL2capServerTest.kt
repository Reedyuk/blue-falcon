package dev.bluefalcon.peripheral.apple

import dev.bluefalcon.core.L2capException
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.L2capServerSocket
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralSessionId
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSData
import platform.Foundation.NSInputStream
import platform.Foundation.NSOutputStream
import platform.Foundation.NSStream
import platform.Foundation.NSStreamDataWrittenToMemoryStreamKey
import platform.Foundation.NSStreamEventEndEncountered
import platform.Foundation.NSStreamEventErrorOccurred
import platform.Foundation.NSStreamStatusClosed
import platform.Foundation.getBoundStreamsWithBufferSize
import platform.Foundation.outputStreamToMemory
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class AppleL2capServerTest {

    @Test
    fun bytesReadBeforeCollectionAreDelivered() = runBlocking<Unit> {
        val channel = channel(byteArrayOf(1, 2, 3))
        val socket = AppleL2capServerSocket(channel, onClosed = {}, runOnStreamThread = { it() })

        socket.drainInput()
        val chunk = withTimeout(5.seconds) { socket.incoming.first() }

        assertContentEquals(byteArrayOf(1, 2, 3), chunk)
        assertEquals(PeripheralSessionId("central-1"), socket.sessionId)
        assertEquals("central-1", socket.peripheral.uuid)
        socket.close()
    }

    @Test
    fun fullBufferPausesReadingWithoutLosingBytes() = runBlocking<Unit> {
        val chunkSize = 4096
        val chunkCount = 70 // more than the 64-chunk inbound buffer
        val payload = ByteArray(chunkSize * chunkCount) { (it % 251).toByte() }
        val channel = channel(payload)
        val socket = AppleL2capServerSocket(channel, onClosed = {}, runOnStreamThread = { it() })

        socket.drainInput()
        val received = withTimeout(10.seconds) {
            socket.incoming.take(chunkCount).toList()
        }

        val joined = received.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        assertContentEquals(payload, joined)
        socket.close()
    }

    @Test
    fun collectorThatResubscribesLosesNoChunks() = runBlocking<Unit> {
        val (input, remote) = boundStreams()
        val channel = AppleL2capChannel(
            psm = 0x0081,
            sessionId = PeripheralSessionId("central-1"),
            inputStream = input,
            outputStream = NSOutputStream.outputStreamToMemory(),
        )
        val socket = AppleL2capServerSocket(channel, onClosed = {}, runOnStreamThread = { it() })

        remote.writeAll(byteArrayOf(1))
        socket.drainInput()
        val first = withTimeout(5.seconds) { socket.incoming.first() }
        // No collector is subscribed while these arrive.
        delay(100)
        remote.writeAll(byteArrayOf(2, 3))
        socket.drainInput()
        delay(100)
        val later = withTimeout(5.seconds) { socket.incoming.first() }

        assertContentEquals(byteArrayOf(1), first)
        assertContentEquals(byteArrayOf(2, 3), later)
        socket.close()
        remote.close()
    }

    @Test
    fun endOfInputDeliversDataThenClosesOnce() = runBlocking<Unit> {
        val channel = channel(byteArrayOf(9))
        var closed = 0
        val socket = AppleL2capServerSocket(channel, onClosed = { closed++ }, runOnStreamThread = { it() })

        channel.inputStream.delegate!!.stream(channel.inputStream, NSStreamEventEndEncountered)
        val chunk = withTimeout(5.seconds) { socket.incoming.first() }

        assertContentEquals(byteArrayOf(9), chunk)
        withTimeout(5.seconds) { while (socket.isOpen) delay(10) }
        assertEquals(1, closed)
    }

    @Test
    fun writesReachOutputAndCloseDetachesBothStreams() = runBlocking<Unit> {
        val channel = channel(byteArrayOf())
        var closed = 0
        val socket = AppleL2capServerSocket(channel, onClosed = { closed++ }, runOnStreamThread = { it() })

        socket.write(byteArrayOf(4, 5, 6))
        val written = channel.outputStream.propertyForKey(NSStreamDataWrittenToMemoryStreamKey) as NSData
        assertContentEquals(byteArrayOf(4, 5, 6), written.toByteArray())

        socket.close()
        socket.close()
        assertEquals(1, closed)
        assertFalse(socket.isOpen)
        assertNull(channel.inputStream.delegate)
        assertNull(channel.outputStream.delegate)
        assertEquals(NSStreamStatusClosed, channel.inputStream.streamStatus)
        assertEquals(NSStreamStatusClosed, channel.outputStream.streamStatus)
        assertFailsWith<L2capException> { socket.write(byteArrayOf(1)) }
    }

    @Test
    fun streamErrorClosesSocket() {
        val channel = channel(byteArrayOf(1))
        val socket = AppleL2capServerSocket(channel, onClosed = {}, runOnStreamThread = { it() })

        channel.outputStream.delegate!!.stream(channel.outputStream, NSStreamEventErrorOccurred)

        assertFalse(socket.isOpen)
    }

    @Test
    fun backendRoutesChannelsByPsmAndStopUnpublishes() = runBlocking<Unit> {
        val stack = FakeApplePeripheralStack()
        val peripheral = createBlueFalconPeripheral(stack, logger = null, coroutineContext = EmptyCoroutineContext)
        assertTrue(peripheral.capabilities.l2capServer)
        peripheral.start(PeripheralConfig(AdvertiseConfig()))

        val listener = peripheral.publishL2capChannel(secure = true)
        assertEquals(listOf(true), stack.publishedL2capSecurity)

        val unknown = channel(byteArrayOf(), psm = listener.psm + 1)
        unknown.inputStream.open()
        stack.openL2capChannel(unknown)
        assertEquals(NSStreamStatusClosed, unknown.inputStream.streamStatus)

        stack.openL2capChannel(channel(byteArrayOf(), psm = listener.psm))
        val socket: L2capServerSocket = withTimeout(5.seconds) { listener.sockets.first() }
        assertEquals(listener.psm, socket.psm)

        peripheral.stop()
        assertEquals(listOf(listener.psm), stack.unpublishedPsms)
        assertFalse(listener.isOpen)
        assertFalse(socket.isOpen)
        peripheral.close()
    }

    /** An input stream fed by writes to the returned output stream, like a central sending bytes. */
    private fun boundStreams(): Pair<NSInputStream, NSOutputStream> = memScoped {
        val input = alloc<ObjCObjectVar<NSInputStream?>>()
        val output = alloc<ObjCObjectVar<NSOutputStream?>>()
        NSStream.getBoundStreamsWithBufferSize(BoundBufferSize, input.ptr, output.ptr)
        val remote = output.value!!
        remote.open()
        input.value!! to remote
    }

    private fun NSOutputStream.writeAll(bytes: ByteArray) {
        val written = bytes.usePinned { write(it.addressOf(0).reinterpret(), bytes.size.toULong()) }
        assertEquals(bytes.size.toLong(), written)
    }

    private fun channel(bytes: ByteArray, psm: Int = 0x0081) = AppleL2capChannel(
        psm = psm,
        sessionId = PeripheralSessionId("central-1"),
        inputStream = NSInputStream(data = bytes.toData()),
        outputStream = NSOutputStream.outputStreamToMemory(),
    )

    private companion object {
        val BoundBufferSize: ULong = (64 * 1024).toULong()
    }
}

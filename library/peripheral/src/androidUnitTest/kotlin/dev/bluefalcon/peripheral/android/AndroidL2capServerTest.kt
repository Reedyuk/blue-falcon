package dev.bluefalcon.peripheral.android

import dev.bluefalcon.core.BluetoothPermissionException
import dev.bluefalcon.core.L2capException
import dev.bluefalcon.core.NoOpLogger
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.internal.DefaultL2capListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class AndroidL2capServerTest {

    @Test
    fun acceptedSocketsReachTheListenerAndCloseUnblocksAccept() = runBlocking<Unit> {
        val channel = FakeServerChannel(psm = 0x0081)
        val listener = DefaultL2capListener(secure = true)
        val server = AndroidL2capServer(channel, listener, NoOpLogger).also(listener::attach)
        server.start()
        val connection = FakeConnection("AA:BB:CC:DD:EE:FF")

        channel.connect(connection)
        val socket = withTimeout(5.seconds) { listener.sockets.first() }

        assertEquals(0x0081, listener.psm)
        assertEquals(0x0081, socket.psm)
        assertEquals(PeripheralSessionId("AA:BB:CC:DD:EE:FF"), socket.sessionId)
        listener.close()
        assertTrue(channel.closed)
        assertFalse(socket.isOpen)
        assertTrue(connection.closed)
        waitUntil { channel.acceptReturned }
    }

    @Test
    fun bytesSentBeforeCollectionAreNotLost() = runBlocking<Unit> {
        val connection = FakeConnection("central")
        val socket = AndroidL2capServerSocket(connection, psm = 0x0081, onClosed = {})

        connection.remoteWrites(byteArrayOf(1, 2, 3))
        delay(100)
        val first = withTimeout(5.seconds) { socket.incoming.first() }

        assertContentEquals(byteArrayOf(1, 2, 3), first)
        socket.close()
    }

    @Test
    fun endOfStreamDeliversBufferedChunksThenCloses() = runBlocking<Unit> {
        val connection = FakeConnection("central")
        var closedNotifications = 0
        val socket = AndroidL2capServerSocket(connection, psm = 0x0081, onClosed = { closedNotifications++ })

        connection.remoteWrites(byteArrayOf(7))
        connection.remoteCloses()
        delay(100)
        val chunks = withTimeout(5.seconds) { socket.incoming.first() }

        assertContentEquals(byteArrayOf(7), chunks)
        waitUntil { !socket.isOpen }
        assertEquals(1, closedNotifications)
        assertTrue(connection.closed)
    }

    @Test
    fun writesReachTheOutputStreamAndFailAfterClose() = runBlocking<Unit> {
        val connection = FakeConnection("central")
        val socket = AndroidL2capServerSocket(connection, psm = 0x0081, onClosed = {})

        socket.write(byteArrayOf(4, 5))
        assertContentEquals(byteArrayOf(4, 5), connection.output.toByteArray())

        socket.close()
        socket.close()
        assertFailsWith<L2capException> { socket.write(byteArrayOf(6)) }
    }

    @Test
    fun acceptFailureClosesListenerAndFailsSocketStream() = runBlocking<Unit> {
        val channel = FakeServerChannel(psm = 0x0082).apply { acceptFailure = IOException("adapter off") }
        val listener = DefaultL2capListener(secure = false)
        AndroidL2capServer(channel, listener, NoOpLogger).also(listener::attach).start()

        val failure = assertFailsWith<L2capException> {
            withTimeout(5.seconds) { listener.sockets.toList() }
        }

        assertTrue(failure.causes().any { it is IOException })
        assertFalse(listener.isOpen)
        assertTrue(channel.closed)
    }

    @Test
    fun securityExceptionIsReportedAsPermissionFailure() = runBlocking<Unit> {
        val channel = FakeServerChannel(psm = 0x0083).apply { acceptFailure = SecurityException("revoked") }
        val listener = DefaultL2capListener(secure = true)
        AndroidL2capServer(channel, listener, NoOpLogger).also(listener::attach).start()

        val failure = assertFailsWith<L2capException> {
            withTimeout(5.seconds) { listener.sockets.toList() }
        }

        assertTrue(failure.causes().any { it is BluetoothPermissionException })
    }

    @Test
    fun managerPublishesThroughStackAndStopClosesNativeServer() = runBlocking<Unit> {
        val channel = FakeServerChannel(psm = 0x0085)
        val stack = FakeAndroidBluetoothStack().apply {
            capabilities = capabilities.copy(l2capServer = true)
            l2capChannel = channel
        }
        val peripheral = createBlueFalconPeripheral(stack, NoOpLogger, EmptyCoroutineContext)
        assertTrue(peripheral.capabilities.l2capServer)
        peripheral.start(PeripheralConfig(AdvertiseConfig()))

        val listener = peripheral.publishL2capChannel(secure = false)

        assertEquals(listOf(false), stack.l2capListenCalls)
        assertEquals(0x0085, listener.psm)
        peripheral.stop()
        assertFalse(listener.isOpen)
        assertTrue(channel.closed)
        peripheral.close()
    }

    @Test
    fun stackWithoutL2capReportsNoCapability() {
        val peripheral = createBlueFalconPeripheral(FakeAndroidBluetoothStack(), NoOpLogger, EmptyCoroutineContext)
        assertFalse(peripheral.capabilities.l2capServer)
    }

    /** Coroutines stack-trace recovery may wrap the original exception, so search the chain. */
    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(5.seconds) { while (!condition()) delay(10) }
    }

    private class FakeServerChannel(override val psm: Int) : AndroidL2capServerChannel {
        private val pending = LinkedBlockingQueue<AndroidL2capConnection>()
        @Volatile var closed = false
        @Volatile var acceptReturned = false
        @Volatile var acceptFailure: Throwable? = null

        fun connect(connection: AndroidL2capConnection) = pending.put(connection)

        override fun accept(): AndroidL2capConnection {
            try {
                while (true) {
                    acceptFailure?.let { throw it }
                    if (closed) throw IOException("server closed")
                    pending.poll(10, TimeUnit.MILLISECONDS)?.let { return it }
                }
            } finally {
                acceptReturned = true
            }
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeConnection(address: String) : AndroidL2capConnection {
        private val remote = PipedOutputStream()
        private val pipe = PipedInputStream(remote, 64 * 1024)
        val output = ByteArrayOutputStream()
        @Volatile var closed = false

        override val sessionId = PeripheralSessionId(address)
        override val inputStream: InputStream get() = pipe
        override val outputStream: OutputStream get() = output

        fun remoteWrites(bytes: ByteArray) {
            remote.write(bytes)
            remote.flush()
        }

        fun remoteCloses() = remote.close()

        override fun close() {
            closed = true
            runCatching { remote.close() }
            runCatching { pipe.close() }
        }
    }
}

package dev.bluefalcon.engine.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Issue #335: inbound L2CAP bytes must survive a late collector and push back on a slow one. */
class AndroidSocketInboundTest {
    @Test fun `collector that starts 3 s after open receives every byte`() = runBlocking {
        val payload = pattern(1_000_000)
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val remote = RemoteSender(payload)
        val closes = AtomicLong()
        val io = AndroidSocketIo(remote.input, ByteArrayOutputStream(), scope, { closes.incrementAndGet() })
        try {
            delay(3_000)
            assertTrue(remote.written.get() < payload.size, "the remote sender must be pushed back while nobody collects")

            val received = collectAll(io, payload.size)

            assertContentEquals(payload, received)
            withTimeout(5_000) { io.join() }
            assertFalse(io.isOpen)
            assertTrue(closes.get() == 1L)
        } finally { io.close(); remote.stop(); scope.cancel() }
    }

    @Test fun `slow collector receives every byte and the sender stays bounded`() = runBlocking {
        val payload = pattern(512 * 1024)
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val remote = RemoteSender(payload)
        val capacity = 4
        val io = AndroidSocketIo(remote.input, ByteArrayOutputStream(), scope, {}, inboundCapacity = capacity)
        try {
            var maximumLead = 0L
            val received = collectAll(io, payload.size) { total ->
                maximumLead = maxOf(maximumLead, remote.written.get() - total)
                delay(2)
            }

            assertContentEquals(payload, received)
            // Pipe buffer + inbound channel + shared-flow buffer + chunks held in transit.
            val bound = PIPE_SIZE + (2L * capacity + 3) * READ_SIZE
            assertTrue(maximumLead <= bound, "sender ran $maximumLead bytes ahead, bound $bound")
        } finally { io.close(); remote.stop(); scope.cancel() }
    }

    @Test fun `close drops buffered bytes and closes the native socket once`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val remote = RemoteSender(pattern(256 * 1024))
        val closes = AtomicLong()
        val io = AndroidSocketIo(remote.input, ByteArrayOutputStream(), scope, { closes.incrementAndGet() })
        delay(200)
        io.close(); io.close()
        withTimeout(5_000) { io.join() }
        assertFalse(io.isOpen)
        assertTrue(closes.get() == 1L)
        remote.stop(); scope.cancel()
    }

    private suspend fun collectAll(
        io: AndroidSocketIo,
        expected: Int,
        onChunk: suspend (receivedSoFar: Long) -> Unit = {},
    ): ByteArray = withTimeout(60_000) {
        val out = ByteArrayOutputStream(expected)
        var total = 0L
        io.incoming
            .onEach { chunk -> out.write(chunk); total += chunk.size; onChunk(total) }
            .takeWhile { total < expected }
            .collect {}
        out.toByteArray()
    }

    /** Remote side of the channel: writes [payload] as fast as the pipe accepts it, then closes (EOF). */
    private class RemoteSender(payload: ByteArray) {
        val input = PipedInputStream(PIPE_SIZE)
        private val output = PipedOutputStream(input)
        val written = AtomicLong()
        private val worker = thread(isDaemon = true, name = "l2cap-remote-sender") {
            try {
                var offset = 0
                while (offset < payload.size) {
                    val size = minOf(1024, payload.size - offset)
                    output.write(payload, offset, size)
                    offset += size
                    written.set(offset.toLong())
                }
                output.close()
            } catch (_: Exception) { /* The test closed the socket. */ }
        }

        fun stop() {
            runCatching { input.close() }
            worker.join(5_000)
        }
    }

    private companion object {
        const val PIPE_SIZE = 16 * 1024
        const val READ_SIZE = 4096
        fun pattern(size: Int) = ByteArray(size) { (it % 251).toByte() }
    }
}

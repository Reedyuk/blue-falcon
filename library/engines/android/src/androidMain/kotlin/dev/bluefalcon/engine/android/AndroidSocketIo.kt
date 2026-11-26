package dev.bluefalcon.engine.android

import dev.bluefalcon.core.InternalBlueFalconApi
import dev.bluefalcon.core.L2capInboundBuffer
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Native IO closes on EOF, failure and cancellation, including a blocked read.
 *
 * Inbound chunks go through an [L2capInboundBuffer]. Bytes that arrive before the first collector
 * of [incoming] are kept. When the buffer is full the reader stops reading, so L2CAP flow control
 * pushes back on the remote sender. On EOF or a read failure the native socket closes at once,
 * and chunks that are already buffered are still delivered to the collector. [close] drops them.
 */
@OptIn(InternalBlueFalconApi::class)
internal class AndroidSocketIo(
    private val input: InputStream,
    private val output: OutputStream,
    parentScope: CoroutineScope,
    private val closeNative: () -> Unit,
    inboundCapacity: Int = L2capInboundBuffer.DEFAULT_CAPACITY,
) {
    private val socketJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + socketJob + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val writeMutex = Mutex()
    private val inbound = L2capInboundBuffer(
        scope = scope,
        capacity = inboundCapacity,
        onInputFinished = { socketJob.cancel() },
    )
    val incoming: SharedFlow<ByteArray> = inbound.incoming
    val isOpen: Boolean get() = !closed.get()

    // This sibling runs cancellation cleanup even while input.read blocks the reader.
    private val cancellationCloser = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { close() }
    }
    private val reader = scope.launch {
        val buffer = ByteArray(4096)
        try {
            while (isActive) {
                val size = input.read(buffer)
                if (size == -1) break
                if (size > 0) inbound.send(buffer.copyOf(size))
            }
        } catch (_: IOException) {
            // EOF and IO failure have the same native-owner terminal cleanup.
        } finally { endOfInput() }
    }

    suspend fun write(bytes: ByteArray) = writeMutex.withLock {
        check(isOpen) { "L2CAP socket is closed" }
        withContext(Dispatchers.IO) {
            try {
                output.write(bytes)
                output.flush()
            } catch (failure: IOException) {
                close()
                throw failure
            }
        }
    }

    /** Releases the native socket now. Buffered chunks drain to the collector, then the IO job ends. */
    private fun endOfInput() {
        try { releaseNative() } finally { inbound.finish() }
    }

    fun close() {
        try { releaseNative() } finally {
            inbound.cancel()
            socketJob.cancel()
        }
    }

    private fun releaseNative() {
        if (closed.compareAndSet(false, true)) closeNative()
    }

    suspend fun join() = socketJob.join()
}

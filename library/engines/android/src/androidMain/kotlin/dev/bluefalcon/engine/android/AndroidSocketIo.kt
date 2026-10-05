package dev.bluefalcon.engine.android

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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Native IO closes on EOF, failure and cancellation, including a blocked read. */
internal class AndroidSocketIo(
    private val input: InputStream,
    private val output: OutputStream,
    parentScope: CoroutineScope,
    private val closeNative: () -> Unit,
) {
    private val socketJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + socketJob + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val writeMutex = Mutex()
    private val incomingState = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incoming: SharedFlow<ByteArray> = incomingState
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
                if (size > 0) incomingState.emit(buffer.copyOf(size))
            }
        } catch (_: IOException) {
            // EOF and IO failure have the same native-owner terminal cleanup.
        } finally { close() }
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

    fun close() {
        if (closed.compareAndSet(false, true)) {
            try { closeNative() } finally { socketJob.cancel() }
        }
    }

    suspend fun join() = socketJob.join()
}

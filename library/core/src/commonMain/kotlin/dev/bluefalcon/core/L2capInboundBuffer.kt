package dev.bluefalcon.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Bounded, loss-free inbound path for an L2CAP socket's [BluetoothSocket.incoming] flow.
 *
 * The platform reader puts chunks into a bounded channel. One forwarder moves each chunk into
 * [incoming] only while at least one collector is subscribed. So bytes that arrive before the
 * application starts collecting are kept, not dropped as a `SharedFlow(replay = 0)` would drop
 * them. When the buffer is full, [send] suspends and [trySend] returns `false`. The reader then
 * stops reading, and L2CAP credit-based flow control pushes back on the remote sender.
 *
 * Memory is bounded: at most `capacity` chunks wait in the channel and at most `capacity` more
 * wait in the shared flow's buffer for a slow collector.
 *
 * Delivery contract:
 * - The first collector receives every chunk from the start of the stream.
 * - A collector that subscribes later receives the chunks from the time it subscribes (normal
 *   [SharedFlow] semantics).
 * - If every collector cancels, forwarding pauses until a collector subscribes again. A chunk
 *   that is being emitted at the exact moment the last collector cancels can still be lost.
 *
 * Shared by the central engines and the peripheral L2CAP server. It is not application API.
 *
 * @param scope owns the forwarder coroutine. Cancelling it stops delivery.
 * @param capacity number of chunks buffered before the reader is pushed back.
 * @param onChunkDelivered called after each chunk reached the shared flow. Run-loop readers use
 * it to resume reading after [trySend] returned `false`.
 * @param onInputFinished called once, after [finish] and after every buffered chunk was delivered.
 */
@InternalBlueFalconApi
class L2capInboundBuffer(
    scope: CoroutineScope,
    capacity: Int = DEFAULT_CAPACITY,
    private val onChunkDelivered: () -> Unit = {},
    private val onInputFinished: () -> Unit = {},
) {
    init {
        require(capacity > 0) { "L2CAP inbound capacity must be positive" }
    }

    private val chunks = Channel<ByteArray>(capacity)
    private val shared = MutableSharedFlow<ByteArray>(extraBufferCapacity = capacity)

    /** The flow that [BluetoothSocket.incoming] exposes. */
    val incoming: SharedFlow<ByteArray> = shared.asSharedFlow()

    private val forwarder = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        for (chunk in chunks) {
            // With no subscriber, emit would return at once and discard the chunk.
            shared.subscriptionCount.first { it > 0 }
            shared.emit(chunk)
            onChunkDelivered()
        }
        onInputFinished()
    }

    /** Suspends while the buffer is full. For blocking readers (Android, JVM). */
    suspend fun send(chunk: ByteArray) {
        chunks.send(chunk)
    }

    /** Returns `false` while the buffer is full or after [finish] or [cancel]. For run-loop readers (Apple). */
    fun trySend(chunk: ByteArray): Boolean = chunks.trySend(chunk).isSuccess

    /** End of input. Chunks already buffered are still delivered, then `onInputFinished` runs. */
    fun finish() {
        chunks.close()
    }

    /** Drops every buffered chunk and stops delivery. `onInputFinished` does not run. */
    fun cancel() {
        chunks.cancel()
        forwarder.cancel()
    }

    companion object {
        /** Default number of buffered chunks (each chunk is one platform read, usually up to 4 KiB). */
        const val DEFAULT_CAPACITY: Int = 64
    }
}

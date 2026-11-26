package dev.bluefalcon.peripheral.internal

import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.L2capException
import dev.bluefalcon.peripheral.L2capListener
import dev.bluefalcon.peripheral.L2capServerSocket
import dev.bluefalcon.peripheral.PeripheralSessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Native L2CAP server published by a backend for exactly one [DefaultL2capListener]. */
internal interface BackendL2capServer {
    val psm: Int

    /** Unpublishes the native server. Idempotent. Accepted sockets are owned by the listener. */
    fun close()
}

/** Receives native L2CAP server events. Callable from any thread. */
internal interface BackendL2capSink {
    /** Hands over an accepted socket. The sink closes sockets it does not admit. */
    fun onSocketAccepted(socket: L2capServerSocket)

    /** Called once when an accepted socket closes, for any reason. */
    fun onSocketClosed(socket: L2capServerSocket)

    /** The native server stopped accepting for a reason other than [BackendL2capServer.close]. */
    fun onServerFailed(cause: Throwable)
}

/** Remote central of a server-side socket. Only [uuid] is meaningful. */
internal class L2capRemoteCentral(sessionId: PeripheralSessionId) : BluetoothPeripheral {
    override val uuid: String = sessionId.value
    override val name: String? get() = null
    override val rssi: Float? get() = null
    override val mtuSize: Int? get() = null
    override val services: List<BluetoothService> get() = emptyList()
    override val characteristics: List<BluetoothCharacteristic> get() = emptyList()

    override fun equals(other: Any?): Boolean = other is L2capRemoteCentral && other.uuid == uuid
    override fun hashCode(): Int = uuid.hashCode()
    override fun toString(): String = "L2capRemoteCentral($uuid)"
}

/**
 * Bounded, loss-free inbound path shared by the platform server sockets.
 *
 * The platform reader puts chunks into a bounded channel. One forwarder moves them into
 * [incoming] only after the first collector subscribes, so bytes the central sends before the
 * application starts collecting are kept, not dropped. When the channel is full the reader
 * stops reading, and the platform's own flow control pushes back on the remote central.
 */
internal class L2capInboundBuffer(
    scope: CoroutineScope,
    capacity: Int = DefaultL2capChunkCapacity,
    private val onChunkDelivered: () -> Unit = {},
    private val onInputFinished: () -> Unit,
) {
    private val chunks = Channel<ByteArray>(capacity)
    private val shared = MutableSharedFlow<ByteArray>(extraBufferCapacity = capacity)
    val incoming: SharedFlow<ByteArray> = shared.asSharedFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            shared.subscriptionCount.first { it > 0 }
            for (chunk in chunks) {
                shared.emit(chunk)
                onChunkDelivered()
            }
            onInputFinished()
        }
    }

    /** Suspends while the buffer is full. Used by blocking readers (Android). */
    suspend fun send(chunk: ByteArray) {
        chunks.send(chunk)
    }

    /** Returns `false` while the buffer is full. Used by run-loop readers (Apple). */
    fun trySend(chunk: ByteArray): Boolean = chunks.trySend(chunk).isSuccess

    /** End of input: chunks already buffered are still delivered, then [onInputFinished] runs. */
    fun finish() {
        chunks.close()
    }
}

/** Common listener state: bounded accept queue, owned sockets, and idempotent shutdown. */
internal class DefaultL2capListener(
    override val secure: Boolean,
    private val maximumSockets: Int = DefaultL2capSocketCapacity,
    private val onClosed: (DefaultL2capListener) -> Unit = {},
) : L2capListener, BackendL2capSink {
    init {
        require(maximumSockets > 0) { "L2CAP socket capacity must be positive" }
    }

    private val open = MutableStateFlow(true)
    private val server = MutableStateFlow<BackendL2capServer?>(null)
    private val ownedSockets = MutableStateFlow<Set<L2capServerSocket>>(emptySet())
    private val queue = Channel<L2capServerSocket>(maximumSockets)

    override val psm: Int
        get() = checkNotNull(server.value) { "L2CAP listener is not published yet" }.psm

    override val isOpen: Boolean get() = open.value

    /** Sockets currently owned by this listener, queued or delivered. */
    val openSocketCount: Int get() = ownedSockets.value.size

    override val sockets: Flow<L2capServerSocket> = flow {
        for (socket in queue) {
            if (socket.isOpen) emit(socket)
        }
    }

    fun attach(published: BackendL2capServer) {
        server.value = published
        if (!open.value) runCatching { published.close() }
    }

    override fun onSocketAccepted(socket: L2capServerSocket) {
        if (!open.value) return socket.closeQuietly()
        var admitted = false
        ownedSockets.update { current ->
            admitted = current.size < maximumSockets
            if (admitted) current + socket else current
        }
        if (!admitted) return socket.closeQuietly()
        if (!enqueue(socket)) {
            ownedSockets.update { it - socket }
            return socket.closeQuietly()
        }
        // Close raced with admission, or the socket closed before it was tracked.
        if (!open.value) socket.closeQuietly()
        if (!socket.isOpen) ownedSockets.update { it - socket }
    }

    /**
     * Sockets that closed while still queued keep their queue slot. When the queue is full,
     * drop them so the slot bound matches the open-socket bound. Accept callbacks are serial
     * per listener, so only a consumer can race here, and it only removes items.
     */
    private fun enqueue(socket: L2capServerSocket): Boolean {
        if (queue.trySend(socket).isSuccess) return true
        val retained = buildList {
            while (true) {
                val queued = queue.tryReceive().getOrNull() ?: break
                if (queued.isOpen) add(queued)
            }
        }
        retained.forEach { if (!queue.trySend(it).isSuccess) it.closeQuietly() }
        return queue.trySend(socket).isSuccess
    }

    override fun onSocketClosed(socket: L2capServerSocket) {
        ownedSockets.update { it - socket }
    }

    override fun onServerFailed(cause: Throwable) {
        shutdown(
            if (cause is L2capException) cause
            else L2capException("L2CAP server stopped accepting channels", cause),
        )
    }

    override fun close() = shutdown(null)

    private fun shutdown(cause: Throwable?) {
        if (!open.compareAndSet(expect = true, update = false)) return
        server.value?.let { runCatching { it.close() } }
        queue.close(cause)
        while (true) {
            val pending = queue.tryReceive().getOrNull() ?: break
            pending.closeQuietly()
        }
        ownedSockets.getAndUpdate { emptySet() }.forEach { it.closeQuietly() }
        onClosed(this)
    }

    private fun L2capServerSocket.closeQuietly() {
        runCatching { close() }
    }
}

internal const val DefaultL2capSocketCapacity = 16
internal const val DefaultL2capChunkCapacity = 64
internal const val MaximumL2capListeners = 8

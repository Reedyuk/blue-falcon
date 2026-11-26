package dev.bluefalcon.peripheral.apple

import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.L2capException
import dev.bluefalcon.peripheral.L2capServerSocket
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.internal.L2capInboundBuffer
import dev.bluefalcon.peripheral.internal.L2capRemoteCentral
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSInputStream
import platform.Foundation.NSOutputStream
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSRunLoop
import platform.Foundation.NSStream
import platform.Foundation.NSStreamDelegateProtocol
import platform.Foundation.NSStreamEvent
import platform.Foundation.NSStreamEventEndEncountered
import platform.Foundation.NSStreamEventErrorOccurred
import platform.Foundation.NSStreamEventHasBytesAvailable
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * A channel opened by a central on a published PSM. [nativeChannel] keeps the
 * `CBL2CAPChannel` alive: Core Bluetooth closes the streams when the channel is released.
 */
internal class AppleL2capChannel(
    val psm: Int,
    val sessionId: PeripheralSessionId,
    val inputStream: NSInputStream,
    val outputStream: NSOutputStream,
    private var nativeChannel: Any? = null,
) {
    fun release() {
        nativeChannel = null
    }

    fun close() {
        runCatching { inputStream.close() }
        runCatching { outputStream.close() }
        release()
    }
}

/**
 * Server-side socket over a `CBL2CAPChannel` stream pair.
 *
 * The streams are scheduled on the main run loop, like the central-side socket. Reads stop while
 * the bounded inbound buffer is full and resume after the collector catches up, so no inbound
 * bytes are dropped (see [L2capInboundBuffer]).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class AppleL2capServerSocket(
    private val channel: AppleL2capChannel,
    private val onClosed: (L2capServerSocket) -> Unit,
    private val runOnStreamThread: (() -> Unit) -> Unit = { block ->
        dispatch_async(dispatch_get_main_queue()) { block() }
    },
) : L2capServerSocket {
    override val psm: Int = channel.psm
    override val sessionId: PeripheralSessionId = channel.sessionId
    override val peripheral: BluetoothPeripheral = L2capRemoteCentral(sessionId)

    private val input = channel.inputStream
    private val output = channel.outputStream
    private val closing = MutableStateFlow(false)
    private val drainScheduled = MutableStateFlow(false)
    private val inputLock = NSRecursiveLock()
    private val writeMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pendingChunk: ByteArray? = null
    private var endOfInput = false
    private val inbound = L2capInboundBuffer(
        scope = scope,
        onChunkDelivered = ::requestDrain,
        onInputFinished = ::close,
    )

    override val incoming: SharedFlow<ByteArray> get() = inbound.incoming
    override val isOpen: Boolean get() = !closing.value

    private val streamDelegate = object : NSObject(), NSStreamDelegateProtocol {
        override fun stream(aStream: NSStream, handleEvent: NSStreamEvent) {
            if (closing.value) return
            when (handleEvent) {
                NSStreamEventHasBytesAvailable -> if (aStream == input) drainInput()
                NSStreamEventEndEncountered -> if (aStream == input) finishInput() else close()
                NSStreamEventErrorOccurred -> close()
                else -> Unit
            }
        }
    }

    init {
        try {
            val runLoop = NSRunLoop.mainRunLoop
            for (stream in listOf(input, output)) {
                stream.delegate = streamDelegate
                stream.scheduleInRunLoop(runLoop, NSDefaultRunLoopMode)
                stream.open()
            }
        } catch (cause: Throwable) {
            close()
            throw cause
        }
    }

    /** Reads until the stream is empty or the inbound buffer is full. */
    internal fun drainInput() {
        var failed = false
        locked {
            if (closing.value) return@locked
            pendingChunk?.let { chunk ->
                if (!inbound.trySend(chunk)) return@locked
                pendingChunk = null
            }
            val buffer = ByteArray(ReadBufferSize)
            while (!closing.value && !endOfInput && input.hasBytesAvailable) {
                val read = buffer.usePinned { pinned ->
                    input.read(pinned.addressOf(0).reinterpret(), ReadBufferSize.toULong())
                }
                if (read < 0L) {
                    failed = true
                    break
                }
                if (read == 0L) break
                val chunk = buffer.copyOf(read.toInt())
                if (!inbound.trySend(chunk)) {
                    pendingChunk = chunk
                    break
                }
            }
            if (endOfInput && pendingChunk == null) inbound.finish()
        }
        if (failed) close()
    }

    private fun finishInput() {
        drainInput()
        locked {
            endOfInput = true
            if (pendingChunk == null) inbound.finish()
        }
    }

    private fun requestDrain() {
        val hasPending = locked { pendingChunk != null }
        if (!hasPending || !drainScheduled.compareAndSet(expect = false, update = true)) return
        runOnStreamThread {
            drainScheduled.value = false
            drainInput()
        }
    }

    override suspend fun write(data: ByteArray) {
        if (closing.value) throw L2capException("L2CAP socket on PSM $psm is closed")
        if (data.isEmpty()) return
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                data.usePinned { pinned ->
                    var offset = 0
                    while (offset < data.size) {
                        if (closing.value) throw L2capException("L2CAP socket on PSM $psm is closed")
                        val written = output.write(
                            pinned.addressOf(offset).reinterpret(),
                            (data.size - offset).toULong(),
                        )
                        if (written <= 0L) {
                            close()
                            throw L2capException("Failed to write to L2CAP socket on PSM $psm")
                        }
                        offset += written.toInt()
                    }
                }
            }
        }
    }

    override fun close() {
        if (!closing.compareAndSet(expect = false, update = true)) return
        // Closing the streams first releases a write blocked in the native stream.
        val runLoop = NSRunLoop.mainRunLoop
        for (stream in listOf(input, output)) {
            runCatching { stream.delegate = null }
            runCatching { stream.close() }
            runCatching { stream.removeFromRunLoop(runLoop, NSDefaultRunLoopMode) }
        }
        locked { pendingChunk = null }
        channel.release()
        scope.cancel()
        onClosed(this)
    }

    private inline fun <T> locked(action: () -> T): T {
        inputLock.lock()
        return try { action() } finally { inputLock.unlock() }
    }

    private companion object {
        const val ReadBufferSize = 4096
    }
}

package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothSocket
import dev.bluefalcon.core.InternalBlueFalconApi
import dev.bluefalcon.core.L2capException
import dev.bluefalcon.core.L2capInboundBuffer
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import platform.CoreBluetooth.CBL2CAPChannel
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSInputStream
import platform.Foundation.NSOutputStream
import platform.Foundation.NSRunLoop
import platform.Foundation.NSStream
import platform.Foundation.NSRecursiveLock
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSStreamDelegateProtocol
import platform.Foundation.NSStreamEvent
import platform.Foundation.NSStreamEventEndEncountered
import platform.Foundation.NSStreamEventErrorOccurred
import platform.Foundation.NSStreamEventHasBytesAvailable
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Apple [BluetoothSocket] backed by a [CBL2CAPChannel].
 *
 * The channel exposes an [NSInputStream]/[NSOutputStream] pair. We schedule both
 * on the main run loop and install an [NSStreamDelegateProtocol] that drains the
 * input stream into [incoming] whenever bytes become available.
 *
 * Inbound chunks go through an [L2capInboundBuffer]. Bytes that arrive before the first
 * collector are kept. When the buffer is full, reading stops and resumes after the collector
 * takes a chunk, so Core Bluetooth's L2CAP flow control pushes back on the remote sender.
 * At end of input the remaining stream bytes are still read and delivered before the socket
 * closes. [close] drops buffered chunks.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, InternalBlueFalconApi::class)
class AppleL2CapSocket internal constructor(
    inputStream: NSInputStream,
    outputStream: NSOutputStream,
    override val psm: Int,
    override val peripheral: BluetoothPeripheral,
    private val closeNativeStream: (NSStream) -> Unit = { it.close() },
    private val writeNative: ((ByteArray, Int) -> Long)? = null,
    private val openNativeStream: (NSStream) -> Unit = { it.open() },
    private val onCleanupFailure: (Throwable) -> Unit = {},
    autoStart: Boolean = true,
    inboundCapacity: Int = L2capInboundBuffer.DEFAULT_CAPACITY,
    /** Runs a resumed drain on the thread whose run loop the streams are scheduled on. */
    private val runOnStreamThread: (() -> Unit) -> Unit = { block -> dispatch_async(dispatch_get_main_queue()) { block() } },
) : BluetoothSocket {
    // The channel's streams keep the CBL2CAPChannel alive, and Core Bluetooth closes the channel
    // over the air only after that object is deallocated (#334). beginClose clears both fields,
    // so a closed socket that the app still references does not keep the channel open.
    private var nativeInput: NSInputStream? = inputStream
    private var nativeOutput: NSOutputStream? = outputStream
    constructor(channel: CBL2CAPChannel, psm: Int, peripheral: BluetoothPeripheral) : this(channel, psm, peripheral, {})
    internal constructor(channel: CBL2CAPChannel, psm: Int, peripheral: BluetoothPeripheral, onCleanupFailure: (Throwable) -> Unit) : this(
        channel.inputStream ?: throw L2capException("L2CAP channel on PSM $psm has no input stream"),
        channel.outputStream ?: throw L2capException("L2CAP channel on PSM $psm has no output stream"),
        psm, peripheral, onCleanupFailure = onCleanupFailure,
    )
    internal constructor(
        channel: CBL2CAPChannel,
        psm: Int,
        peripheral: BluetoothPeripheral,
        onCleanupFailure: (Throwable) -> Unit,
        autoStart: Boolean,
    ) : this(
        channel.inputStream ?: throw L2capException("L2CAP channel on PSM $psm has no input stream"),
        channel.outputStream ?: throw L2capException("L2CAP channel on PSM $psm has no output stream"),
        psm,
        peripheral,
        onCleanupFailure = onCleanupFailure,
        autoStart = autoStart,
    )
    private val inboundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inbound = L2capInboundBuffer(
        scope = inboundScope,
        capacity = inboundCapacity,
        onChunkDelivered = { requestDrain() },
        onInputFinished = { inboundScope.cancel() },
    )
    override val incoming: SharedFlow<ByteArray> get() = inbound.incoming
    private val closing = MutableStateFlow(false)
    private val drainScheduled = MutableStateFlow(false)
    // A chunk read from the stream while the inbound buffer was full. Written with streamLock
    // held; requestDrain reads it without the lock, because a blocked write holds that lock.
    private val pendingChunk = MutableStateFlow<ByteArray?>(null)
    private var endOfInput = false
    private val streamLock = NSRecursiveLock()
    private val closeLock = NSRecursiveLock()
    private var teardownDone = false
    private var retired = false
    private var closeCallbackInvoked = false
    private var closeFailure: Throwable? = null
    private var closeCallback: ((Throwable?) -> Unit)? = null
    internal var onClosed: (Throwable?) -> Unit
        get() = closeCallback ?: {}
        set(value) {
            var notify = false
            closeLock.lock()
            try {
                closeCallback = value
                if (retired && !closeCallbackInvoked) {
                    closeCallbackInvoked = true
                    // The owner's callback captures the CBL2CAPChannel; see close().
                    closeCallback = null
                    notify = true
                }
            } finally { closeLock.unlock() }
            if (notify) value(closeFailure)
        }
    override val isOpen: Boolean get() = !closing.value
    private inline fun <T> locked(action: () -> T): T {
        streamLock.lock()
        return try { action() } finally { streamLock.unlock() }
    }

    private val streamDelegate = object : NSObject(), NSStreamDelegateProtocol {
        override fun stream(aStream: NSStream, handleEvent: NSStreamEvent) {
            if (closing.value) return
            locked {
                if (closing.value) return@locked
                when (handleEvent) {
                    NSStreamEventHasBytesAvailable -> if (aStream == nativeInput) drainInput()
                    NSStreamEventEndEncountered -> if (aStream == nativeInput) finishInput() else close()
                    NSStreamEventErrorOccurred -> close()
                    else -> {}
                }
            }
        }
    }

    init { if (autoStart) start() }

    private var started = false

    internal fun start() = locked {
        if (closing.value) return@locked
        check(!started) { "Apple L2CAP socket streams already started" }
        started = true
        try {
            val runLoop = NSRunLoop.mainRunLoop
            for (stream in listOfNotNull(nativeInput, nativeOutput)) {
                if (closing.value) break
                stream.delegate = streamDelegate
                stream.scheduleInRunLoop(runLoop, NSDefaultRunLoopMode)
                openNativeStream(stream)
            }
        } catch (cause: Throwable) {
            try { close() } catch (_: Throwable) { /* Cleanup failure already handed to the owner. */ }
            throw cause
        }
    }

    /** Reads until the stream is empty or the inbound buffer is full. Called with streamLock held. */
    private fun drainInput() {
        if (closing.value) return
        pendingChunk.value?.let { chunk ->
            if (!inbound.trySend(chunk)) return
            pendingChunk.value = null
        }
        val buffer = ByteArray(READ_BUFFER_SIZE)
        val inputStream = nativeInput ?: return
        while (!closing.value && inputStream.hasBytesAvailable) {
            val read = buffer.usePinned { pinned ->
                inputStream.read(pinned.addressOf(0).reinterpret(), READ_BUFFER_SIZE.toULong())
            }
            if (read <= 0L) {
                if (read < 0L) close()
                break
            }
            val chunk = buffer.copyOf(read.toInt())
            if (!inbound.trySend(chunk)) {
                // Stop reading: unread bytes stay in the native stream and L2CAP credits run out.
                pendingChunk.value = chunk
                return
            }
        }
        if (endOfInput) completeInput()
    }

    /** The remote closed its side. Deliver what is still readable, then close. Called with streamLock held. */
    private fun finishInput() {
        endOfInput = true
        drainInput()
    }

    private fun completeInput() {
        if (closing.value || pendingChunk.value != null || nativeInput?.hasBytesAvailable == true) return
        inbound.finish()
        retire()
    }

    /** The collector took a chunk. Resume a drain that stopped on a full buffer. */
    private fun requestDrain() {
        // If the chunk is set right after this check, the buffer was full at that moment,
        // so a later delivery calls requestDrain again.
        if (closing.value || pendingChunk.value == null) return
        if (!drainScheduled.compareAndSet(expect = false, update = true)) return
        runOnStreamThread {
            drainScheduled.value = false
            locked { drainInput() }
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun write(data: ByteArray) {
        val context = currentCoroutineContext()
        context.ensureActive()
        val callerJob = context[Job]
        val cancellation = callerJob?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) beginClose()
        }
        try {
            locked {
                if (closing.value) throw L2capException("L2CAP socket on PSM $psm is closed")
                if (data.isEmpty()) return@locked
                data.usePinned { pinned ->
                    var offset = 0
                    while (offset < data.size) {
                        if (closing.value) throw L2capException("L2CAP socket on PSM $psm is closed")
                        val outputStream = nativeOutput
                            ?: throw L2capException("L2CAP socket on PSM $psm is closed")
                        val written = writeNative?.invoke(data, offset) ?: outputStream.write(
                            pinned.addressOf(offset).reinterpret(),
                            (data.size - offset).toULong()
                        )
                        if (written <= 0L) {
                            close()
                            throw L2capException("Failed to write to L2CAP socket on PSM $psm")
                        }
                        offset += written.toInt()
                    }
                }
                context.ensureActive()
            }
        } finally {
            cancellation?.dispose()
            if (callerJob?.isActive == false) close()
        }
    }

    private fun beginClose() {
        closing.value = true
        closeLock.lock()
        try {
            if (!teardownDone) {
                var failure: Throwable? = null
                fun attempt(action: () -> Unit) {
                    try { action() } catch (cause: Throwable) {
                        if (failure == null) failure = cause else failure.addSuppressed(cause)
                    }
                }
                val runLoop = NSRunLoop.mainRunLoop
                for (stream in listOfNotNull(nativeInput, nativeOutput)) {
                    attempt { stream.delegate = null }
                    attempt { closeNativeStream(stream) }
                    attempt { stream.removeFromRunLoop(runLoop, NSDefaultRunLoopMode) }
                }
                // A blocked write or read keeps its own local reference until it returns.
                nativeInput = null
                nativeOutput = null
                teardownDone = true
                closeFailure = failure
                failure?.let(onCleanupFailure)
            }
        } finally { closeLock.unlock() }
    }

    override fun close() {
        inbound.cancel()
        inboundScope.cancel()
        retire()
    }

    /** Closes the native streams and notifies the owner. Buffered chunks are left to the collector. */
    private fun retire() {
        beginClose()
        // Native close must release blocked IO before waiting for its admission lock.
        locked { pendingChunk.value = null }
        closeLock.lock()
        try {
            if (!retired) retired = true
            if (!closeCallbackInvoked) {
                closeCallback?.let {
                    closeCallbackInvoked = true
                    // Drop the callback once it ran. The engine's callback captures the
                    // CBL2CAPChannel, and Core Bluetooth closes the channel over the air only
                    // after its last reference is released (#334). An app that keeps the closed
                    // socket must not keep the channel open.
                    closeCallback = null
                    it(closeFailure)
                }
            }
        } finally { closeLock.unlock() }
        closeFailure?.let { throw it }
    }

    companion object {
        private const val READ_BUFFER_SIZE = 4096
    }
}

package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothSocket
import dev.bluefalcon.core.L2capException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

/**
 * Apple [BluetoothSocket] backed by a [CBL2CAPChannel].
 *
 * The channel exposes an [NSInputStream]/[NSOutputStream] pair. We schedule both
 * on the main run loop and install an [NSStreamDelegateProtocol] that drains the
 * input stream into [incoming] whenever bytes become available.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class AppleL2CapSocket internal constructor(
    private val inputStream: NSInputStream,
    private val outputStream: NSOutputStream,
    override val psm: Int,
    override val peripheral: BluetoothPeripheral,
    private val closeNativeStream: (NSStream) -> Unit = { it.close() },
    private val writeNative: ((ByteArray, Int) -> Long)? = null,
    private val openNativeStream: (NSStream) -> Unit = { it.open() },
    private val onCleanupFailure: (Throwable) -> Unit = {},
    autoStart: Boolean = true,
) : BluetoothSocket {
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
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()
    private val closing = MutableStateFlow(false)
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
                    NSStreamEventHasBytesAvailable -> if (aStream == inputStream) drainInput()
                    NSStreamEventEndEncountered, NSStreamEventErrorOccurred -> close()
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
            for (stream in listOf(inputStream, outputStream)) {
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

    private fun drainInput() {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        while (!closing.value && inputStream.hasBytesAvailable) {
            val read = buffer.usePinned { pinned ->
                inputStream.read(pinned.addressOf(0).reinterpret(), READ_BUFFER_SIZE.toULong())
            }
            if (read <= 0L) {
                if (read < 0L) close()
                break
            }
            if (!closing.value) _incoming.tryEmit(buffer.copyOf(read.toInt()))
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
                for (stream in listOf(inputStream, outputStream)) {
                    attempt { stream.delegate = null }
                    attempt { closeNativeStream(stream) }
                    attempt { stream.removeFromRunLoop(runLoop, NSDefaultRunLoopMode) }
                }
                teardownDone = true
                closeFailure = failure
                failure?.let(onCleanupFailure)
            }
        } finally { closeLock.unlock() }
    }

    override fun close() {
        beginClose()
        // Native close must release blocked IO before waiting for its admission lock.
        locked { }
        closeLock.lock()
        try {
            if (!retired) retired = true
            if (!closeCallbackInvoked) {
                closeCallback?.let {
                    closeCallbackInvoked = true
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

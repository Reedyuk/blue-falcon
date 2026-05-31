package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothSocket
import dev.bluefalcon.core.L2capException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.CoreBluetooth.CBL2CAPChannel
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSInputStream
import platform.Foundation.NSOutputStream
import platform.Foundation.NSRunLoop
import platform.Foundation.NSStream
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
class AppleL2CapSocket(
    private val channel: CBL2CAPChannel,
    override val psm: Int,
    override val peripheral: BluetoothPeripheral
) : BluetoothSocket {

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    private var open = true
    override val isOpen: Boolean get() = open

    private val inputStream: NSInputStream = channel.inputStream
        ?: throw L2capException("L2CAP channel on PSM $psm has no input stream")
    private val outputStream: NSOutputStream = channel.outputStream
        ?: throw L2capException("L2CAP channel on PSM $psm has no output stream")

    private val streamDelegate = object : NSObject(), NSStreamDelegateProtocol {
        override fun stream(aStream: NSStream, handleEvent: NSStreamEvent) {
            when (handleEvent) {
                NSStreamEventHasBytesAvailable -> if (aStream == inputStream) drainInput()
                NSStreamEventEndEncountered, NSStreamEventErrorOccurred -> open = false
                else -> {}
            }
        }
    }

    init {
        val runLoop = NSRunLoop.mainRunLoop
        for (stream in listOf(inputStream, outputStream)) {
            stream.delegate = streamDelegate
            stream.scheduleInRunLoop(runLoop, NSDefaultRunLoopMode)
            stream.open()
        }
    }

    private fun drainInput() {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        while (inputStream.hasBytesAvailable) {
            val read = buffer.usePinned { pinned ->
                inputStream.read(pinned.addressOf(0).reinterpret(), READ_BUFFER_SIZE.toULong())
            }
            if (read <= 0L) {
                if (read < 0L) open = false
                break
            }
            _incoming.tryEmit(buffer.copyOf(read.toInt()))
        }
    }

    override suspend fun write(data: ByteArray) {
        if (!open) throw L2capException("L2CAP socket on PSM $psm is closed")
        if (data.isEmpty()) return
        data.usePinned { pinned ->
            var offset = 0
            while (offset < data.size) {
                val written = outputStream.write(
                    pinned.addressOf(offset).reinterpret(),
                    (data.size - offset).toULong()
                )
                if (written <= 0L) {
                    open = false
                    throw L2capException("Failed to write to L2CAP socket on PSM $psm")
                }
                offset += written.toInt()
            }
        }
    }

    override fun close() {
        open = false
        val runLoop = NSRunLoop.mainRunLoop
        for (stream in listOf(inputStream, outputStream)) {
            stream.close()
            stream.removeFromRunLoop(runLoop, NSDefaultRunLoopMode)
            stream.delegate = null
        }
    }

    companion object {
        private const val READ_BUFFER_SIZE = 4096
    }
}
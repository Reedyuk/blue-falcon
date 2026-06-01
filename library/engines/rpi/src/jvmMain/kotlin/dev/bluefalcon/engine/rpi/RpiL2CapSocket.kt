package dev.bluefalcon.engine.rpi

import com.sun.jna.Memory
import com.sun.jna.NativeLong
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothSocket
import dev.bluefalcon.core.L2capException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Linux L2CAP CoC socket backed by a raw `AF_BLUETOOTH` file descriptor.
 *
 * A read loop on [Dispatchers.IO] drains the fd into [incoming]; [write] is
 * serialized through a [Mutex]. [close] closes the fd, which unblocks the
 * blocking `read` and ends the loop.
 */
class RpiL2CapSocket(
    private val fd: Int,
    override val psm: Int,
    override val peripheral: BluetoothPeripheral,
    parentScope: CoroutineScope
) : BluetoothSocket {

    private val clib = CLib.INSTANCE

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    @Volatile
    override var isOpen: Boolean = true
        private set

    private val closed = AtomicBoolean(false)
    private val writeMutex = Mutex()

    private val readJob: Job = parentScope.launch(Dispatchers.IO) {
        Memory(READ_BUFFER_SIZE.toLong()).use { buffer ->
            try {
                while (isActive) {
                    val read = clib.read(fd, buffer, NativeLong(READ_BUFFER_SIZE.toLong())).toLong()
                    if (read <= 0L) break
                    _incoming.emit(buffer.getByteArray(0, read.toInt()))
                }
            } finally {
                isOpen = false
            }
        }
    }

    override suspend fun write(data: ByteArray) {
        if (!isOpen) throw L2capException("L2CAP socket on PSM $psm is closed")
        if (data.isEmpty()) return
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                Memory(data.size.toLong()).use { buffer ->
                    buffer.write(0, data, 0, data.size)
                    var offset = 0
                    while (offset < data.size) {
                        val written = clib.write(
                            fd,
                            buffer.share(offset.toLong()),
                            NativeLong((data.size - offset).toLong())
                        ).toLong()
                        if (written <= 0L) {
                            isOpen = false
                            throw L2capException("Failed to write to L2CAP socket on PSM $psm")
                        }
                        offset += written.toInt()
                    }
                }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        isOpen = false
        readJob.cancel()
        clib.close(fd)
    }

    companion object {
        private const val READ_BUFFER_SIZE = 4096
    }
}
package dev.bluefalcon.engine.android

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
import java.io.IOException

/**
 * Android L2CAP socket implementation.
 *
 * Wraps an already-connected [android.bluetooth.BluetoothSocket]. A read loop
 * runs on [Dispatchers.IO] emitting inbound chunks to [incoming]; [write]
 * serializes outbound writes through a [Mutex].
 */
class L2CapSocket(
    private val socket: android.bluetooth.BluetoothSocket,
    override val psm: Int,
    override val peripheral: BluetoothPeripheral,
    parentScope: CoroutineScope
) : BluetoothSocket {

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    @Volatile
    override var isOpen: Boolean = true
        private set

    private val writeMutex = Mutex()

    private val readJob: Job = parentScope.launch(Dispatchers.IO) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            while (isActive) {
                val read = socket.inputStream.read(buffer)
                if (read == -1) break
                if (read > 0) _incoming.emit(buffer.copyOf(read))
            }
        } catch (_: IOException) {
            // Socket closed or peer dropped — fall through and mark closed.
        } finally {
            isOpen = false
        }
    }

    override suspend fun write(data: ByteArray) {
        if (!isOpen) throw L2capException("L2CAP socket on PSM $psm is closed")
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    socket.outputStream.write(data)
                    socket.outputStream.flush()
                } catch (e: IOException) {
                    isOpen = false
                    throw L2capException("Failed to write to L2CAP socket on PSM $psm", e)
                }
            }
        }
    }

    override fun close() {
        isOpen = false
        readJob.cancel()
        try {
            socket.close()
        } catch (_: IOException) {
            // Already closed.
        }
    }

    companion object {
        private const val READ_BUFFER_SIZE = 4096
    }
}

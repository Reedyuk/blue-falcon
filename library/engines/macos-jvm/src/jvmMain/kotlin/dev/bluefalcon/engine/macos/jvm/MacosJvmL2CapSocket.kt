package dev.bluefalcon.engine.macos.jvm

import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothSocket
import dev.bluefalcon.core.L2capException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * macOS-JVM L2CAP socket.
 *
 * `CBL2CAPChannel` exposes no POSIX fd, so all stream handling stays in the
 * native bridge: inbound chunks arrive via [onDataReceived]; [write] and
 * [close] marshal back through the engine's JNI methods. This mirrors how GATT
 * notifications are already delivered on this engine.
 */
class MacosJvmL2CapSocket internal constructor(
    private val handle: Long,
    override val psm: Int,
    override val peripheral: BluetoothPeripheral,
    private val nativeWrite: (Long, ByteArray) -> Unit,
    private val nativeClose: (Long) -> Unit
) : BluetoothSocket {

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    @Volatile
    override var isOpen: Boolean = true
        private set

    private val closed = AtomicBoolean(false)
    private val writeMutex = Mutex()

    override suspend fun write(data: ByteArray) {
        if (!isOpen) throw L2capException("L2CAP socket on PSM $psm is closed")
        if (data.isEmpty()) return
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                nativeWrite(handle, data)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        isOpen = false
        nativeClose(handle)
    }

    /** Invoked from the native bridge when inbound bytes arrive. */
    internal fun onDataReceived(data: ByteArray) {
        _incoming.tryEmit(data)
    }

    /** Invoked from the native bridge when the channel's streams end or error. */
    internal fun onClosed() {
        isOpen = false
    }
}
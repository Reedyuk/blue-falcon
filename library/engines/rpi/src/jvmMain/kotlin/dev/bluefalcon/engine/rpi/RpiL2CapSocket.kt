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
 *
 * BlueZ surfaces the LE Credit Based Flow Control **SDU-length L-field** (a
 * 2-octet little-endian length) to userspace on this `SOCK_SEQPACKET` socket, so
 * it is stripped from each inbound SDU and prepended to each outbound SDU here
 * (see [frameSdu]/[stripSduLength]). [incoming] and [write] therefore carry raw
 * payload only, matching the other engines and the [BluetoothSocket] contract.
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
                    // One SEQPACKET read == one whole SDU; strip the leading
                    // 2-byte LE SDU-length L-field and emit the raw payload.
                    val payload = stripSduLength(buffer.getByteArray(0, read.toInt())) ?: continue
                    _incoming.emit(payload)
                }
            } finally {
                isOpen = false
            }
        }
    }

    override suspend fun write(data: ByteArray) {
        if (!isOpen) throw L2capException("L2CAP socket on PSM $psm is closed")
        if (data.isEmpty()) return
        // Prepend the 2-byte LE SDU-length L-field and send the whole SDU as a
        // single SEQPACKET message (one write() == one SDU; never loop-split it,
        // or each chunk would be mis-framed as its own SDU).
        val sdu = frameSdu(data)
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                Memory(sdu.size.toLong()).use { buffer ->
                    buffer.write(0, sdu, 0, sdu.size)
                    val written = clib.write(fd, buffer, NativeLong(sdu.size.toLong())).toLong()
                    if (written.toInt() != sdu.size) {
                        isOpen = false
                        throw L2capException(
                            "Short/failed L2CAP write on PSM $psm ($written/${sdu.size})"
                        )
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

        /**
         * Frames [payload] as a BlueZ L2CAP CoC SDU by prepending the 2-octet
         * little-endian SDU-length L-field. The length is the payload length,
         * excluding the 2-byte field itself. Inverse of [stripSduLength].
         */
        internal fun frameSdu(payload: ByteArray): ByteArray {
            val sdu = ByteArray(payload.size + 2)
            sdu[0] = (payload.size and 0xFF).toByte()
            sdu[1] = ((payload.size ushr 8) and 0xFF).toByte()
            payload.copyInto(sdu, destinationOffset = 2)
            return sdu
        }

        /**
         * Strips the leading 2-octet LE SDU-length L-field that the Linux kernel
         * exposes on each inbound CoC SDU, returning the raw payload. Returns
         * `null` for a malformed SDU shorter than the 2-byte field.
         */
        internal fun stripSduLength(sdu: ByteArray): ByteArray? {
            if (sdu.size < 2) return null
            return sdu.copyOfRange(2, sdu.size)
        }
    }
}
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
                    // One SEQPACKET read == one whole SDU; strip the leading 2-byte
                    // LE SDU-length L-field and emit the raw payload. A truncated or
                    // malformed SDU yields null and is skipped rather than emitted.
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
        /** Size of the 2-octet little-endian SDU-length L-field BlueZ frames each CoC SDU with. */
        private const val SDU_LENGTH_FIELD = 2

        /**
         * Maximum L2CAP LE CoC SDU payload. The L-field is 16-bit, so a payload cannot
         * exceed this (Bluetooth Core Spec, Vol 3, Part A, §3.4).
         */
        private const val MAX_SDU_PAYLOAD = 0xFFFF

        /**
         * Inbound read buffer, sized to hold a whole maximum-length SDU (payload + L-field)
         * so a spec-compliant peer's SDU is never truncated by the SEQPACKET `read()`.
         * [stripSduLength] still validates the declared length and drops anything short.
         */
        private const val READ_BUFFER_SIZE = MAX_SDU_PAYLOAD + SDU_LENGTH_FIELD

        /**
         * Frames [payload] as a BlueZ L2CAP CoC SDU by prepending the 2-octet
         * little-endian SDU-length L-field. The length is the payload length,
         * excluding the 2-byte field itself. Inverse of [stripSduLength].
         *
         * @throws L2capException if [payload] exceeds [MAX_SDU_PAYLOAD] — the 16-bit
         *   L-field cannot encode it, so framing it would silently wrap and corrupt the
         *   stream. Fail fast instead.
         */
        internal fun frameSdu(payload: ByteArray): ByteArray {
            if (payload.size > MAX_SDU_PAYLOAD) {
                throw L2capException(
                    "L2CAP SDU payload of ${payload.size} bytes exceeds the maximum of $MAX_SDU_PAYLOAD"
                )
            }
            val sdu = ByteArray(payload.size + SDU_LENGTH_FIELD)
            sdu[0] = (payload.size and 0xFF).toByte()
            sdu[1] = ((payload.size ushr 8) and 0xFF).toByte()
            payload.copyInto(sdu, destinationOffset = SDU_LENGTH_FIELD)
            return sdu
        }

        /**
         * Strips the leading 2-octet LE SDU-length L-field that the Linux kernel
         * exposes on each inbound CoC SDU, returning the raw payload.
         *
         * Returns `null` for an SDU shorter than the 2-byte field, or one whose declared
         * length disagrees with the bytes actually received — the latter signals a
         * truncated (e.g. larger than [READ_BUFFER_SIZE]) or otherwise malformed SDU, so
         * the partial payload is dropped rather than emitted as if it were valid.
         */
        internal fun stripSduLength(sdu: ByteArray): ByteArray? {
            if (sdu.size < SDU_LENGTH_FIELD) return null
            val declaredLength = (sdu[0].toInt() and 0xFF) or ((sdu[1].toInt() and 0xFF) shl 8)
            val payloadLength = sdu.size - SDU_LENGTH_FIELD
            if (declaredLength != payloadLength) return null
            return sdu.copyOfRange(SDU_LENGTH_FIELD, sdu.size)
        }
    }
}
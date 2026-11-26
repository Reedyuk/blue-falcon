package dev.bluefalcon.peripheral.android

import android.annotation.TargetApi
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Build
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothPermissionException
import dev.bluefalcon.core.InternalBlueFalconApi
import dev.bluefalcon.core.L2capException
import dev.bluefalcon.core.L2capInboundBuffer
import dev.bluefalcon.core.Logger
import dev.bluefalcon.peripheral.L2capServerSocket
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.internal.BackendL2capServer
import dev.bluefalcon.peripheral.internal.BackendL2capSink
import dev.bluefalcon.peripheral.internal.L2capRemoteCentral
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Seam over `BluetoothServerSocket` so the accept loop is testable without the framework. */
internal interface AndroidL2capServerChannel {
    val psm: Int

    /** Blocks until a central connects. Throws [IOException] after [close]. */
    fun accept(): AndroidL2capConnection

    fun close()
}

/** Seam over an accepted `BluetoothSocket`. */
internal interface AndroidL2capConnection {
    val sessionId: PeripheralSessionId
    val inputStream: InputStream
    val outputStream: OutputStream

    fun close()
}

/** Runs the blocking accept loop for one published server and hands sockets to [sink]. */
internal class AndroidL2capServer(
    private val native: AndroidL2capServerChannel,
    private val sink: BackendL2capSink,
    private val logger: Logger?,
) : BackendL2capServer {
    override val psm: Int = native.psm
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch { acceptLoop() }
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val connection = try {
                native.accept()
            } catch (cause: Throwable) {
                if (closed.compareAndSet(false, true)) {
                    closeNative()
                    sink.onServerFailed(cause.toL2capFailure())
                }
                return
            }
            if (closed.get()) {
                runCatching { connection.close() }
                return
            }
            sink.onSocketAccepted(AndroidL2capServerSocket(connection, psm, sink::onSocketClosed))
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Closing the native server socket is what unblocks a pending accept().
        closeNative()
        scope.cancel()
    }

    private fun closeNative() {
        runCatching { native.close() }
            .onFailure { logger?.warn("Failed to close Android L2CAP server on PSM $psm", it) }
    }

    private fun Throwable.toL2capFailure(): Throwable = when (this) {
        is SecurityException -> BluetoothPermissionException().also { it.initCause(this) }
        is L2capException -> this
        else -> L2capException("Android L2CAP server on PSM $psm stopped accepting", this)
    }
}

/** Server-side socket. Reads are bounded and loss-free; see [L2capInboundBuffer]. */
@OptIn(InternalBlueFalconApi::class)
internal class AndroidL2capServerSocket(
    private val connection: AndroidL2capConnection,
    override val psm: Int,
    private val onClosed: (L2capServerSocket) -> Unit,
) : L2capServerSocket {
    override val sessionId: PeripheralSessionId = connection.sessionId
    override val peripheral: BluetoothPeripheral = L2capRemoteCentral(sessionId)

    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val inbound = L2capInboundBuffer(scope, onInputFinished = ::close)

    override val incoming: SharedFlow<ByteArray> get() = inbound.incoming
    override val isOpen: Boolean get() = !closed.get()

    init {
        scope.launch {
            val buffer = ByteArray(ReadBufferSize)
            try {
                while (isActive) {
                    val size = connection.inputStream.read(buffer)
                    if (size == -1) break
                    if (size > 0) inbound.send(buffer.copyOf(size))
                }
                inbound.finish()
            } catch (_: IOException) {
                // EOF and IO failure end the socket the same way.
                close()
            }
        }
    }

    override suspend fun write(data: ByteArray) {
        writeMutex.withLock {
            if (!isOpen) throw L2capException("L2CAP socket on PSM $psm is closed")
            withContext(Dispatchers.IO) {
                try {
                    connection.outputStream.write(data)
                    connection.outputStream.flush()
                } catch (cause: IOException) {
                    close()
                    throw L2capException("Failed to write to L2CAP socket on PSM $psm", cause)
                }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            // Closing the native socket also unblocks the reader.
            runCatching { connection.close() }
        } finally {
            // Bytes not yet delivered are dropped; end of stream (finish) delivers them instead.
            inbound.cancel()
            scope.cancel()
            onClosed(this)
        }
    }

    private companion object {
        const val ReadBufferSize = 4096
    }
}

@TargetApi(Build.VERSION_CODES.Q)
internal class FrameworkL2capServerChannel(
    private val server: BluetoothServerSocket,
) : AndroidL2capServerChannel {
    override val psm: Int = server.psm

    override fun accept(): AndroidL2capConnection = FrameworkL2capConnection(server.accept())

    override fun close() = server.close()
}

private class FrameworkL2capConnection(
    private val socket: BluetoothSocket,
) : AndroidL2capConnection {
    override val sessionId = PeripheralSessionId(socket.remoteDevice.address)
    override val inputStream: InputStream get() = socket.inputStream
    override val outputStream: OutputStream get() = socket.outputStream

    override fun close() = socket.close()
}

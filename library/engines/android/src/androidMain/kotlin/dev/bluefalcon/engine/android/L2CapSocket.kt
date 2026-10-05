package dev.bluefalcon.engine.android

import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothSocket
import dev.bluefalcon.core.L2capException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import java.io.IOException

/** Android native socket wrapper; the IO owner closes on every terminal path. */
class L2CapSocket private constructor(
    override val psm: Int,
    override val peripheral: BluetoothPeripheral,
    private val io: AndroidSocketIo,
) : BluetoothSocket {
    constructor(
        socket: android.bluetooth.BluetoothSocket,
        psm: Int,
        peripheral: BluetoothPeripheral,
        parentScope: CoroutineScope,
    ) : this(psm, peripheral, AndroidSocketIo(
        socket.inputStream,
        socket.outputStream,
        parentScope,
        { try { socket.close() } catch (_: IOException) {} },
    ))

    internal constructor(
        owner: AndroidOwnedResource<android.bluetooth.BluetoothSocket>,
        psm: Int,
        peripheral: BluetoothPeripheral,
        parentScope: CoroutineScope,
    ) : this(psm, peripheral, AndroidSocketIo(
        owner.native.inputStream,
        owner.native.outputStream,
        parentScope,
        { try { owner.close() } catch (_: IOException) {} },
    ))

    override val incoming: SharedFlow<ByteArray> get() = io.incoming
    override val isOpen: Boolean get() = io.isOpen

    override suspend fun write(data: ByteArray) {
        if (!isOpen) throw L2capException("L2CAP socket on PSM $psm is closed")
        try {
            io.write(data)
        } catch (failure: IOException) {
            throw L2capException("Failed to write to L2CAP socket on PSM $psm", failure)
        }
    }

    override fun close() = io.close()
}

package dev.bluefalcon.core.mocks

import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothSocket
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-memory [BluetoothSocket] for testing. Bytes passed to [write] are recorded
 * in [written]; tests can push inbound data via [emitIncoming].
 */
class FakeBluetoothSocket(
    override val psm: Int,
    override val peripheral: BluetoothPeripheral
) : BluetoothSocket {

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    override var isOpen: Boolean = true
        private set

    val written = mutableListOf<ByteArray>()

    override suspend fun write(data: ByteArray) {
        written.add(data)
    }

    suspend fun emitIncoming(data: ByteArray) {
        _incoming.emit(data)
    }

    override fun close() {
        isOpen = false
    }
}

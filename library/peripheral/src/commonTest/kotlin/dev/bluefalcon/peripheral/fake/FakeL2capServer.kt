package dev.bluefalcon.peripheral.fake

import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.L2capException
import dev.bluefalcon.peripheral.L2capServerSocket
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.internal.BackendL2capServer
import dev.bluefalcon.peripheral.internal.BackendL2capSink
import dev.bluefalcon.peripheral.internal.L2capRemoteCentral
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

internal class FakeL2capServer(
    override val psm: Int,
    val secure: Boolean,
    private val sink: BackendL2capSink,
) : BackendL2capServer {
    var closeCalls = 0
        private set
    val isClosed: Boolean get() = closeCalls > 0

    override fun close() {
        closeCalls++
    }

    fun accept(sessionId: String): FakeL2capServerSocket =
        FakeL2capServerSocket(PeripheralSessionId(sessionId), psm, sink).also(sink::onSocketAccepted)

    fun fail(cause: Throwable) = sink.onServerFailed(cause)
}

internal class FakeL2capServerSocket(
    override val sessionId: PeripheralSessionId,
    override val psm: Int,
    private val sink: BackendL2capSink,
) : L2capServerSocket {
    override val peripheral: BluetoothPeripheral = L2capRemoteCentral(sessionId)
    override val incoming: SharedFlow<ByteArray> = MutableSharedFlow()
    override var isOpen: Boolean = true
        private set
    var closeCalls = 0
        private set
    val written = mutableListOf<ByteArray>()

    override suspend fun write(data: ByteArray) {
        if (!isOpen) throw L2capException("closed")
        written += data.copyOf()
    }

    override fun close() {
        closeCalls++
        if (!isOpen) return
        isOpen = false
        sink.onSocketClosed(this)
    }
}

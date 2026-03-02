package dev.bluefalcon

import java.io.InputStream
import java.io.OutputStream

class L2CapSocket(private val socket: android.bluetooth.BluetoothSocket) : BluetoothSocket {
    val inputStream: InputStream = socket.inputStream
    val outputStream: OutputStream = socket.outputStream
    override fun close() = socket.close()
}

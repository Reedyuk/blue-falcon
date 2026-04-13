package dev.bluefalcon.engine.android

import dev.bluefalcon.core.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream

/**
 * Android L2CAP socket implementation.
 * Wraps Android's BluetoothSocket for L2CAP communication.
 */
class L2CapSocket(private val socket: android.bluetooth.BluetoothSocket) : BluetoothSocket {
    
    val inputStream: InputStream = socket.inputStream
    val outputStream: OutputStream = socket.outputStream
    
    override fun close() = socket.close()
}

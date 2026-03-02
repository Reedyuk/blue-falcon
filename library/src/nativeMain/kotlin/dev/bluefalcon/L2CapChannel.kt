package dev.bluefalcon

import platform.CoreBluetooth.CBL2CAPChannel
import platform.Foundation.NSInputStream
import platform.Foundation.NSOutputStream

class L2CapChannel(val channel: CBL2CAPChannel) : BluetoothSocket {
    val inputStream: NSInputStream? = channel.inputStream
    val outputStream: NSOutputStream? = channel.outputStream
    override fun close() {
        inputStream?.close()
        outputStream?.close()
    }
}

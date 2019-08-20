package dev.bluefalcon

abstract class AbsBluetooth(private val bluetooth: Bluetooth): Bluetooth {

    override fun connect() {
        this.bluetooth.connect()
    }

    override fun disconnect() {
        this.bluetooth.disconnect()
    }

    override fun scan() {
        this.bluetooth.scan()
    }
}
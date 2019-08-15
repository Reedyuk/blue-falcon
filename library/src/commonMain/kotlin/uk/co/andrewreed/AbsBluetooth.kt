package uk.co.andrewreed

abstract class AbsBluetooth: Bluetooth {

    abstract val bluetooth: Bluetooth

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
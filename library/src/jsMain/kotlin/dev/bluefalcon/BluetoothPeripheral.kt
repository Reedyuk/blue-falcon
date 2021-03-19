package dev.bluefalcon

actual class BluetoothPeripheral(val device: BluetoothDevice) {
    actual val name: String?
        get() = device.name
    actual val services: List<BluetoothService>
        get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
    actual val uuid: String
        get() = device.id
    actual var rssi: Float?
        get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
        set(value) {}
}
package dev.bluefalcon

import dev.bluefalcon.external.BluetoothDevice

actual class BluetoothPeripheral(val device: BluetoothDevice) {
    actual val name: String?
        get() = device.name
    actual val services: List<BluetoothService>
        get() = deviceServices.toList()
    actual val uuid: String
        get() = device.id
    actual var rssi: Float?
        get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
        set(value) {}

    internal var deviceServices: MutableSet<BluetoothService> = mutableSetOf()
    val serviceArray: Array<BluetoothService> get() = services.toTypedArray()
}
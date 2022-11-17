package dev.bluefalcon

import dev.bluefalcon.external.BluetoothDevice
import kotlinx.coroutines.flow.MutableStateFlow

actual class BluetoothPeripheral(val device: BluetoothDevice) {
    actual val name: String?
        get() = device.name
    actual val services: List<BluetoothService>
        get() = _servicesFlow.value
    actual val uuid: String
        get() = device.id
    actual var rssi: Float?
        get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
        set(value) {}

    internal actual val _servicesFlow = MutableStateFlow<List<BluetoothService>>(emptyList())
    val serviceArray: Array<BluetoothService> get() = services.toTypedArray()
}
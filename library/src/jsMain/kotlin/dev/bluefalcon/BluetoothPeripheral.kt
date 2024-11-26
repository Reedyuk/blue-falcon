package dev.bluefalcon

import kotlinx.coroutines.flow.MutableStateFlow

actual class BluetoothPeripheral actual constructor(val device: NativeBluetoothDevice) {
    actual val name: String?
        get() = device.name
    actual val uuid: String
        get() = device.id
    actual var rssi: Float?
        get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
        set(value) {}

    actual var mtuSize: Int?
        get() = TODO("not implemented")
        set(value) {}

    internal actual val _servicesFlow = MutableStateFlow<List<BluetoothService>>(emptyList())
    val serviceArray: Array<BluetoothService> get() = services.values.toTypedArray()
    actual val services: Map<String, BluetoothService>
        get() = _servicesFlow.value.associateBy { it.uuid }

    actual val characteristics: Map<String, BluetoothCharacteristic>
        get() = services.values
            .flatMap { it.characteristics }
            .associateBy { it.uuid }
}
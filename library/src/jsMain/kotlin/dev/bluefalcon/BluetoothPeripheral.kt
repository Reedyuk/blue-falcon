package dev.bluefalcon

import kotlinx.coroutines.flow.MutableStateFlow

actual class BluetoothPeripheralImpl actual constructor(actual override val device: NativeBluetoothDevice): BluetoothPeripheral {
    actual override val name: String?
        get() = device.name
    actual override val uuid: String
        get() = device.id
    actual override var rssi: Float?
        get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
        set(value) {}

    actual override var mtuSize: Int?
        get() = TODO("not implemented")
        set(value) {}

    actual override val _servicesFlow = MutableStateFlow<List<BluetoothService>>(emptyList())
    val serviceArray: Array<BluetoothService> get() = services.values.toTypedArray()
    actual override val services: Map<Uuid, BluetoothService>
        get() = _servicesFlow.value.associateBy { it.uuid }

    actual override val characteristics: Map<Uuid, List<BluetoothCharacteristic>>
        get() = services.values
            .flatMap { service -> service.characteristics }
            .groupBy { characteristic -> characteristic.uuid } // Group by characteristic UUID
            .mapValues { entry -> entry.value }
}
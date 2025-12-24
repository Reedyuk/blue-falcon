package dev.bluefalcon

import kotlinx.coroutines.flow.MutableStateFlow

actual class BluetoothPeripheralImpl actual constructor(actual override val device: NativeBluetoothDevice): BluetoothPeripheral {
    actual override val name: String?
        get() = device.name
    actual override val uuid: String
        get() = device.address

    actual override var rssi: Float? = null
    actual override var mtuSize: Int? = null

    internal actual val _servicesFlow = MutableStateFlow<List<BluetoothService>>(emptyList())
    actual override val services: Map<Uuid, BluetoothService>
        get() = _servicesFlow.value.associateBy { it.uuid }

    actual override val characteristics: Map<Uuid, BluetoothCharacteristic>
        get() = services.values
            .flatMap { service -> service.characteristics }
            .groupBy { characteristic -> characteristic.uuid } // Group by characteristic UUID
            .mapValues { entry -> entry.value }
}
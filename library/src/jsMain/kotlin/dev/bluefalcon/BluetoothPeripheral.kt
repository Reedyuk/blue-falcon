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
    actual val services: Map<Uuid, BluetoothService>
        get() = _servicesFlow.value.associateBy { it.uuid }

    actual val characteristics: Map<Uuid, List<BluetoothCharacteristic>>
        get() = services.values
            .flatMap { service -> // Iterate through each service
                service.characteristics.map { characteristic -> // For each characteristic in the service
                    Pair(service.uuid, characteristic) // Create a pair of (serviceUUID, characteristic)
                }
            }
            .groupBy { (_, characteristic) -> characteristic.uuid } // Group by characteristic UUID
            .mapValues { entry -> // For each entry in the grouped map
                entry.value.map { (_, characteristic) -> characteristic } // Extract only the characteristics
            }
}
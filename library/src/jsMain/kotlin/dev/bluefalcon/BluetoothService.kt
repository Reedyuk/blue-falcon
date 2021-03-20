package dev.bluefalcon

import dev.bluefalcon.external.BluetoothRemoteGATTService

actual class BluetoothService(val service: BluetoothRemoteGATTService) {
    actual val name: String?
        get() = service.uuid
    actual val characteristics: List<BluetoothCharacteristic>
        get() = deviceCharacteristics.toList()

    internal var deviceCharacteristics: MutableSet<BluetoothCharacteristic> = mutableSetOf()

    val characteristicArray: Array<BluetoothCharacteristic> get() = characteristics.toTypedArray()
}
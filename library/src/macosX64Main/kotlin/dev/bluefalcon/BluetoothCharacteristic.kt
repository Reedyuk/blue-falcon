package dev.bluefalcon

import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBDescriptor

actual class BluetoothCharacteristic(val characteristic: CBCharacteristic) {
    actual val name: String?
        get() = characteristic.UUID.description
    actual val value: String?
        get() = characteristic.value?.string()
    actual val descriptors: List<BluetoothCharacteristicDescriptor>
        get() = characteristic.descriptors as List<BluetoothCharacteristicDescriptor>
}

actual typealias  BluetoothCharacteristicDescriptor = CBDescriptor
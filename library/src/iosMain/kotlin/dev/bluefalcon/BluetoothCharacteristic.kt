package dev.bluefalcon

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBDescriptor
import platform.posix.memcpy

actual class BluetoothCharacteristic(val characteristic: CBCharacteristic) {
    actual val name: String?
        get() = characteristic.UUID.UUIDString
    actual val value: ByteArray?
        get() = characteristic.value?.let { data ->
            ByteArray(data.length.toInt()).apply {
                usePinned {
                    memcpy(it.addressOf(0), data.bytes, data.length)
                }
            }
        }
    actual val descriptors: List<BluetoothCharacteristicDescriptor>
        get() = characteristic.descriptors as List<BluetoothCharacteristicDescriptor>
}

actual typealias  BluetoothCharacteristicDescriptor = CBDescriptor

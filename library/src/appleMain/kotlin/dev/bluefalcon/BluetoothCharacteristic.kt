package dev.bluefalcon

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.MutableStateFlow
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBDescriptor
import platform.posix.memcpy

actual class BluetoothCharacteristic(val characteristic: CBCharacteristic) {
    actual val name: String?
        get() = characteristic.UUID.UUIDString
    @OptIn(ExperimentalForeignApi::class)
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

    internal actual val _descriptorsFlow = MutableStateFlow<List<BluetoothCharacteristicDescriptor>>(emptyList())

    actual val uuid: Uuid
        get() = characteristic.UUID

    actual val isNotifying: Boolean
        get() = characteristic.isNotifying
}

actual typealias  BluetoothCharacteristicDescriptor = CBDescriptor

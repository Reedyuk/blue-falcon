package dev.bluefalcon

import platform.CoreBluetooth.CBCharacteristic
import platform.Foundation.NSString
import platform.Foundation.base64Encoding
import platform.Foundation.create

actual class BluetoothCharacteristic(val characteristic: CBCharacteristic) {
    actual val name: String?
        get() = characteristic.UUID.description
    actual val value: String?
        get() = characteristic.value?.string()
}
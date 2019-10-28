package dev.bluefalcon

import platform.CoreBluetooth.CBCharacteristic
import platform.Foundation.NSString
import platform.Foundation.create

actual class BluetoothCharacteristic(val characteristic: CBCharacteristic) {
    actual val name: String?
        get() = characteristic.UUID.description
    actual val value: String?
        get() {
            characteristic.value?.let {
                
            }

            return ""
            //String()
            //characteristic.value
            //TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
        }
}

//String(decoding: characteristicData, as: UTF8.self)
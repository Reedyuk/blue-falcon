package dev.bluefalcon

import com.welie.blessed.BluetoothGattCharacteristic
import com.welie.blessed.BluetoothGattDescriptor

actual class BluetoothCharacteristic(val characteristic: BluetoothGattCharacteristic) {
    actual val name: String?
        get() = characteristic.uuid.toString()
    actual val value: ByteArray?
        get() = mutableVal
    actual val descriptors: List<BluetoothCharacteristicDescriptor>
        get() = characteristic.descriptors

    internal var mutableVal: ByteArray? = null
}

actual typealias BluetoothCharacteristicDescriptor = BluetoothGattDescriptor
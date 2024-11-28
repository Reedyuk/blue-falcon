package dev.bluefalcon

import com.welie.blessed.BluetoothGattCharacteristic
import com.welie.blessed.BluetoothGattDescriptor
import kotlinx.coroutines.flow.MutableStateFlow

actual class BluetoothCharacteristic(val characteristic: BluetoothGattCharacteristic) {
    actual val name: String?
        get() = characteristic.uuid.toString()
    actual val value: ByteArray?
        get() = mutableVal
    actual val descriptors: List<BluetoothCharacteristicDescriptor>
        get() = characteristic.descriptors

    internal var mutableVal: ByteArray? = null
    internal actual val _descriptorsFlow = MutableStateFlow<List<BluetoothCharacteristicDescriptor>>(emptyList())

    actual val uuid: Uuid
        get() = Uuid.parse(characteristic.uuid.toString())
    actual val isNotifying: Boolean
        get() = characteristic.supportsNotifying()
}

actual typealias BluetoothCharacteristicDescriptor = BluetoothGattDescriptor
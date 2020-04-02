package dev.bluefalcon

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import java.nio.charset.Charset

actual class BluetoothCharacteristic(val characteristic: BluetoothGattCharacteristic) {
    actual val name: String?
        get() = characteristic.uuid.toString()
    actual val value: String?
        get() = characteristic.value?.let { data ->
            return String(data, Charset.defaultCharset())
        }
    actual val descriptors: List<BluetoothCharacteristicDescriptor>
        get() = characteristic.descriptors
}

actual typealias BluetoothCharacteristicDescriptor = BluetoothGattDescriptor
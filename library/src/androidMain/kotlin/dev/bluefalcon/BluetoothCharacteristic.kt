package dev.bluefalcon

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.charset.Charset

actual class BluetoothCharacteristic(val characteristic: BluetoothGattCharacteristic) {
    actual val name: String?
        get() = characteristic.uuid.toString()
    actual val value: ByteArray?
        get() = characteristic.value
    actual val descriptors: List<BluetoothCharacteristicDescriptor>
        get() = characteristic.descriptors

    internal actual val _descriptorsFlow = MutableStateFlow<List<BluetoothCharacteristicDescriptor>>(emptyList())
}

actual typealias BluetoothCharacteristicDescriptor = BluetoothGattDescriptor
package dev.bluefalcon

import android.bluetooth.BluetoothGattCharacteristic
import java.nio.charset.Charset

actual class BluetoothCharacteristic(val characteristic: BluetoothGattCharacteristic) {
    actual val name: String?
        get() = characteristic.uuid.toString()
    actual val value: String?
        get() = characteristic.value?.let { data ->
            String(data, Charset.defaultCharset()).let {
                return it
            }
        }
}
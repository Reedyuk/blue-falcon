package com.example.bluefalconcomposemultiplatform.peripheral

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor

actual object PlatformGattPermissions {
    actual val readEncrypted: Int = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
    actual val writeEncrypted: Int = BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
}

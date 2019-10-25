package dev.bluefalcon

import android.bluetooth.BluetoothGattService

actual class BluetoothService(val service: BluetoothGattService) {
    actual val name: String?
        get() = service.uuid.toString()
}
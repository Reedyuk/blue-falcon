package dev.bluefalcon

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattService

actual class BluetoothPeripheral(val bluetoothDevice: BluetoothDevice) {
    var services: List<BluetoothGattService> = emptyList()
    actual val name: String?
        get() = bluetoothDevice.name ?: bluetoothDevice.address
}
package dev.bluefalcon

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattService

actual class BluetoothPeripheral(val bluetoothDevice: BluetoothDevice) {
    val services: List<BluetoothGattService> = emptyList()
}
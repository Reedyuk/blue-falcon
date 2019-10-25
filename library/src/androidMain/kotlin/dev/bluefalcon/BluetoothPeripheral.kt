package dev.bluefalcon

import android.bluetooth.BluetoothDevice

actual class BluetoothPeripheral(val bluetoothDevice: BluetoothDevice) {
    actual val name: String?
        get() = bluetoothDevice.name ?: bluetoothDevice.address
    actual val services: List<BluetoothService>
        get() = deviceServices

    var deviceServices: List<BluetoothService> = listOf()
}
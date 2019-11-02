package dev.bluefalcon

import android.bluetooth.BluetoothDevice

actual class BluetoothPeripheral(val bluetoothDevice: BluetoothDevice) {
    actual val name: String?
        get() = bluetoothDevice.name ?: bluetoothDevice.address
    actual val services: List<BluetoothService>
        get() = deviceServices
    actual val uuid: String
        get() = bluetoothDevice.address

    actual var rssi: Float? = null

    var deviceServices: List<BluetoothService> = listOf()
}
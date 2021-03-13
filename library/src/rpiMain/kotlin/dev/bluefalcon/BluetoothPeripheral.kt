package dev.bluefalcon

import com.welie.blessed.BluetoothPeripheral

actual class BluetoothPeripheral(val bluetoothDevice: BluetoothPeripheral) {
    actual val name: String?
        get() = bluetoothDevice.name
    actual val services: List<BluetoothService>
        get() = deviceServices
    actual val uuid: String
        get() = bluetoothDevice.address

    actual var rssi: Float? = null

    var deviceServices: List<BluetoothService> = listOf()
}
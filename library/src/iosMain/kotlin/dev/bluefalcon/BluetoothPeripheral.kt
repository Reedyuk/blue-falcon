package dev.bluefalcon

import platform.CoreBluetooth.CBPeripheral

actual class BluetoothPeripheral(val bluetoothDevice: CBPeripheral, val rssiValue: Float?) {
    actual val name: String? = bluetoothDevice.name
    actual val rssi: Float? = rssiValue
    actual val services: List<BluetoothService>
        get() = bluetoothDevice.services as List<BluetoothService>
}
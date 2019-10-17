package dev.bluefalcon

import platform.CoreBluetooth.CBPeripheral

actual class BluetoothPeripheral(val bluetoothDevice: CBPeripheral) {
    actual val name: String? = bluetoothDevice.name
}
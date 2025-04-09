package dev.bluefalcon.engine

import dev.bluefalcon.BluetoothDevice

sealed class BluetoothActionResult {
    // data class for device
    data class Scan(val device: BluetoothDevice) : BluetoothActionResult()
    data class Connect(val device: BluetoothDevice) : BluetoothActionResult()
    data class DiscoverServices(val device: BluetoothDevice) : BluetoothActionResult()
    data class DiscoverCharacteristics(val device: BluetoothDevice) : BluetoothActionResult()

    data object Success : BluetoothActionResult()
}

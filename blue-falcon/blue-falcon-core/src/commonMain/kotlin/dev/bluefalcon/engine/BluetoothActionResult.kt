package dev.bluefalcon.engine

import dev.bluefalcon.BTCharacteristic
import dev.bluefalcon.BTService
import dev.bluefalcon.BluetoothDevice

sealed class BluetoothActionResult {
    // data class for device
    data class Scan(val device: BluetoothDevice) : BluetoothActionResult()
    data class Connect(val device: BluetoothDevice) : BluetoothActionResult()
    data class DiscoverServices(val device: BluetoothDevice, val services: List<BTService>) : BluetoothActionResult()
    data class DiscoverCharacteristics(val device: BluetoothDevice, val characteristics: List<BTCharacteristic>) : BluetoothActionResult()

    // standard response for functions with no return.
    data object Success : BluetoothActionResult()
    data object Failure : BluetoothActionResult()
}

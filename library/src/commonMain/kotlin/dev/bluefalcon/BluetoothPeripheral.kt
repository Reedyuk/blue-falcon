package dev.bluefalcon

expect class BluetoothPeripheral {
    val name: String?

    val services: List<BluetoothService>
}
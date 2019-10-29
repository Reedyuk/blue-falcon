package dev.bluefalcon

expect class BluetoothPeripheral {
    val name: String?
    val rssi: Float?
    val services: List<BluetoothService>
}
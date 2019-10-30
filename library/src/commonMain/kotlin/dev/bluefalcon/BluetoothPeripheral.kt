package dev.bluefalcon

expect class BluetoothPeripheral {
    val name: String?
    val uuid: String
    val rssi: Float?
    val services: List<BluetoothService>
}
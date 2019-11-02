package dev.bluefalcon

expect class BluetoothPeripheral {
    val name: String?
    val uuid: String
    var rssi: Float?
    val services: List<BluetoothService>
}
package dev.bluefalcon

import kotlinx.coroutines.flow.MutableStateFlow

expect class BluetoothPeripheral {
    val name: String?
    val uuid: String
    var rssi: Float?
    val services: List<BluetoothService>
    internal val _servicesFlow: MutableStateFlow<List<BluetoothService>>
}

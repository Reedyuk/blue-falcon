package dev.bluefalcon

import kotlinx.coroutines.flow.MutableStateFlow

expect class BluetoothPeripheral {
    val name: String?
    val uuid: String
    var rssi: Float?
    var mtuSize: Int?
    val services: Map<String, BluetoothService>
    internal val _servicesFlow: MutableStateFlow<List<BluetoothService>>

    val characteristics: Map<String, BluetoothCharacteristic>
}

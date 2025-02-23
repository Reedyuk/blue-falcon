package dev.bluefalcon

import kotlinx.coroutines.flow.MutableStateFlow

expect class BluetoothPeripheral(device: NativeBluetoothDevice) {
    val name: String?
    val uuid: String
    var rssi: Float?
    var mtuSize: Int?
    val services: Map<Uuid, BluetoothService>
    internal val _servicesFlow: MutableStateFlow<List<BluetoothService>>

    val characteristics: Map<Uuid, BluetoothCharacteristic>
}

expect class NativeBluetoothDevice
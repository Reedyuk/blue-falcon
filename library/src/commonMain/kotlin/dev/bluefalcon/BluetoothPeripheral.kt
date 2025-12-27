package dev.bluefalcon

import kotlinx.coroutines.flow.MutableStateFlow

interface BluetoothPeripheral {
    val device: NativeBluetoothDevice
    val name: String?
    val uuid: String
    var rssi: Float?
    var mtuSize: Int?
    val services: Map<Uuid, BluetoothService>
    // not a fan of this, but to get it over the line.
    val _servicesFlow: MutableStateFlow<List<BluetoothService>>
    val characteristics: Map<Uuid, List<BluetoothCharacteristic>>
}

expect class BluetoothPeripheralImpl(device: NativeBluetoothDevice): BluetoothPeripheral {
    override val device: NativeBluetoothDevice
    override val name: String?
    override val uuid: String
    override var rssi: Float?
    override var mtuSize: Int?
    override val services: Map<Uuid, BluetoothService>
    override val _servicesFlow: MutableStateFlow<List<BluetoothService>>

    override val characteristics: Map<Uuid, List<BluetoothCharacteristic>>
}

expect class NativeBluetoothDevice

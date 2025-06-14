package dev.bluefalcon

import kotlinx.coroutines.flow.MutableStateFlow

expect class BluetoothCharacteristic {
    val name: String?
    val uuid: Uuid
    val value: ByteArray?
    val descriptors: List<BluetoothCharacteristicDescriptor>
    val isNotifying: Boolean
    internal val _descriptorsFlow: MutableStateFlow<List<BluetoothCharacteristicDescriptor>>
    val service: BluetoothService?
}

expect class BluetoothCharacteristicDescriptor
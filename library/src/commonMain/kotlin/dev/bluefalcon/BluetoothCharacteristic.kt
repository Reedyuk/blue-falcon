package dev.bluefalcon

import kotlinx.coroutines.flow.MutableStateFlow

expect class BluetoothCharacteristic {
    val name: String?
    val uuid: String
    val value: ByteArray?
    val descriptors: List<BluetoothCharacteristicDescriptor>
    val isNotifying: Boolean
    internal val _descriptorsFlow: MutableStateFlow<List<BluetoothCharacteristicDescriptor>>
}

expect class BluetoothCharacteristicDescriptor
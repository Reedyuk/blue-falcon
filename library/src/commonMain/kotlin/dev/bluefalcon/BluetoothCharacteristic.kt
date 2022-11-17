package dev.bluefalcon

import kotlinx.coroutines.flow.MutableStateFlow

expect class BluetoothCharacteristic {
    val name: String?
    val value: ByteArray?
    val descriptors: List<BluetoothCharacteristicDescriptor>
    internal val _descriptorsFlow: MutableStateFlow<List<BluetoothCharacteristicDescriptor>>
}

expect class BluetoothCharacteristicDescriptor
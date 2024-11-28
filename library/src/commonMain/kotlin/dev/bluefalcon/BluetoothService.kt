package dev.bluefalcon

import kotlinx.coroutines.flow.MutableStateFlow

expect class BluetoothService {
    val uuid: Uuid
    val name: String?
    val characteristics: List<BluetoothCharacteristic>
    internal val _characteristicsFlow: MutableStateFlow<List<BluetoothCharacteristic>>
}

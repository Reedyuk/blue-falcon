package dev.bluefalcon

import dev.bluefalcon.external.BluetoothRemoteGATTService
import kotlinx.coroutines.flow.MutableStateFlow

actual class BluetoothService(val service: BluetoothRemoteGATTService) {
    actual val name: String?
        get() = service.uuid
    actual val characteristics: List<BluetoothCharacteristic>
        get() = _characteristicsFlow.value
    val characteristicArray: Array<BluetoothCharacteristic> get() = characteristics.toTypedArray()
    internal actual val _characteristicsFlow = MutableStateFlow<List<BluetoothCharacteristic>>(emptyList())
    actual val uuid: String
        get() = service.uuid.uppercase()
}
package dev.bluefalcon

import kotlinx.coroutines.flow.MutableStateFlow
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBService

actual class BluetoothService(val service: CBService) {
    actual val name: String?
        get() = service.UUID.UUIDString
    actual val characteristics: List<BluetoothCharacteristic>
        get() = service.characteristics?.map {
             BluetoothCharacteristic(it as CBCharacteristic)
        } ?: emptyList()
    internal actual val _characteristicsFlow = MutableStateFlow<List<BluetoothCharacteristic>>(emptyList())
}
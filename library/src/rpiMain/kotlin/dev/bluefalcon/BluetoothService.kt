package dev.bluefalcon

import com.welie.blessed.BluetoothGattService
import kotlinx.coroutines.flow.MutableStateFlow

actual class BluetoothService(val service: BluetoothGattService) {
    actual val name: String?
        get() = service.uuid.toString()
    actual val characteristics: List<BluetoothCharacteristic>
        get() = service.characteristics.map {
            BluetoothCharacteristic(it)
        }
    internal actual val _characteristicsFlow = MutableStateFlow<List<BluetoothCharacteristic>>(emptyList())
    actual val uuid: String
        get() = service.uuid.toString().uppercase()
}
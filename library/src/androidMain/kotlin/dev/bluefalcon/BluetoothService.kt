package dev.bluefalcon

import android.bluetooth.BluetoothGattService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.uuid.toKotlinUuid

actual class BluetoothService(val service: BluetoothGattService) {
    actual val name: String?
        get() = service.uuid.toString()
    actual val characteristics: List<BluetoothCharacteristic>
        get() = service.characteristics.map {
            BluetoothCharacteristic(it)
        }
    internal actual val _characteristicsFlow = MutableStateFlow<List<BluetoothCharacteristic>>(emptyList())
    actual val uuid: Uuid
        get() = service.uuid.toKotlinUuid()
}
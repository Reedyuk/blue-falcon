package dev.bluefalcon

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Native Bluetooth Service wrapper for Windows
 */
class NativeBluetoothService(
    val uuid: Uuid,
    val name: String?,
    val deviceAddress: Long
)

actual class BluetoothService(val service: NativeBluetoothService) {
    actual val uuid: Uuid
        get() = service.uuid
    
    actual val name: String?
        get() = service.name ?: service.uuid.toString()
    
    private val _characteristics = mutableListOf<BluetoothCharacteristic>()
    
    actual val characteristics: List<BluetoothCharacteristic>
        get() = _characteristics
    
    internal actual val _characteristicsFlow = MutableStateFlow<List<BluetoothCharacteristic>>(emptyList())
    
    internal fun addCharacteristic(characteristic: BluetoothCharacteristic) {
        if (!_characteristics.contains(characteristic)) {
            _characteristics.add(characteristic)
            _characteristicsFlow.tryEmit(_characteristics.toList())
        }
    }
}

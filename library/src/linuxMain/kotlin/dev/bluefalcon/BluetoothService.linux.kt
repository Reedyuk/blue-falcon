package dev.bluefalcon

import com.monkopedia.sdbus.ObjectPath
import kotlinx.coroutines.flow.MutableStateFlow

actual class BluetoothService(
    val objectPath: ObjectPath,
    actual val uuid: Uuid,
) {
    actual val name: String?
        get() = uuid.toString()

    private val _characteristics = mutableListOf<BluetoothCharacteristic>()

    actual val characteristics: List<BluetoothCharacteristic>
        get() = _characteristics

    internal actual val _characteristicsFlow = MutableStateFlow<List<BluetoothCharacteristic>>(emptyList())

    internal fun addCharacteristic(characteristic: BluetoothCharacteristic) {
        if (_characteristics.none { it.uuid == characteristic.uuid }) {
            _characteristics.add(characteristic)
            _characteristicsFlow.tryEmit(_characteristics.toList())
        }
    }
}

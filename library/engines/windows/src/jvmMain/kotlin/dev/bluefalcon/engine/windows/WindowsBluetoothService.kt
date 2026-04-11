package dev.bluefalcon.engine.windows

import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.Uuid

/**
 * Windows implementation of BluetoothService
 */
class WindowsBluetoothService(
    override val uuid: Uuid,
    override val name: String?,
    val address: Long
) : BluetoothService {
    
    private val _characteristics = mutableListOf<BluetoothCharacteristic>()
    
    override val characteristics: List<BluetoothCharacteristic>
        get() = _characteristics.toList()
    
    internal fun addCharacteristic(characteristic: BluetoothCharacteristic) {
        if (!_characteristics.contains(characteristic)) {
            _characteristics.add(characteristic)
        }
    }
}

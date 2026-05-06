package dev.bluefalcon.engine.macos.jvm

import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.Uuid

class MacosJvmBluetoothService(
    override val uuid: Uuid,
    override val name: String?,
    val peripheralUuid: String
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
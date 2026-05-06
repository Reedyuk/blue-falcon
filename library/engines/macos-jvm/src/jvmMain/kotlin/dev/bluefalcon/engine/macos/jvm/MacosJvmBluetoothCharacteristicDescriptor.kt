package dev.bluefalcon.engine.macos.jvm

import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothCharacteristicDescriptor
import dev.bluefalcon.core.Uuid

class MacosJvmBluetoothCharacteristicDescriptor(
    override val uuid: Uuid,
    val peripheralUuid: String,
    val serviceUuid: Uuid,
    val characteristicUuid: Uuid
) : BluetoothCharacteristicDescriptor {

    private var _value: ByteArray? = null
    private var _characteristic: BluetoothCharacteristic? = null

    override val value: ByteArray?
        get() = _value

    override val characteristic: BluetoothCharacteristic?
        get() = _characteristic

    internal fun updateValue(value: ByteArray) { _value = value }

    internal fun setCharacteristic(characteristic: BluetoothCharacteristic) {
        _characteristic = characteristic
    }

    override fun equals(other: Any?): Boolean =
        other is MacosJvmBluetoothCharacteristicDescriptor &&
            uuid == other.uuid && characteristicUuid == other.characteristicUuid

    override fun hashCode(): Int = 31 * uuid.hashCode() + characteristicUuid.hashCode()
}
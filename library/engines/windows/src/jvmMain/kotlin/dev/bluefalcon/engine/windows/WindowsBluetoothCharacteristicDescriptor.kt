package dev.bluefalcon.engine.windows

import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothCharacteristicDescriptor
import dev.bluefalcon.core.Uuid

/**
 * Windows implementation of BluetoothCharacteristicDescriptor
 */
class WindowsBluetoothCharacteristicDescriptor(
    override val uuid: Uuid,
    val address: Long,
    val serviceUuid: Uuid,
    val characteristicUuid: Uuid
) : BluetoothCharacteristicDescriptor {
    
    private var _value: ByteArray? = null
    private var _characteristic: BluetoothCharacteristic? = null
    
    override val value: ByteArray?
        get() = _value
    
    override val characteristic: BluetoothCharacteristic?
        get() = _characteristic
    
    internal fun updateValue(value: ByteArray) {
        _value = value
    }
    
    internal fun setCharacteristic(characteristic: BluetoothCharacteristic) {
        _characteristic = characteristic
    }
    
    override fun equals(other: Any?): Boolean =
        if (this === other) true
        else if (other !is WindowsBluetoothCharacteristicDescriptor) false
        else uuid == other.uuid && characteristicUuid == other.characteristicUuid
    
    override fun hashCode(): Int =
        31 * uuid.hashCode() + characteristicUuid.hashCode()
    
    companion object {
        // Standard GATT descriptor UUIDs
        val CCCD_UUID = Uuid.parse("00002902-0000-1000-8000-00805f9b34fb")
        val ENABLE_NOTIFICATION_VALUE = byteArrayOf(0x01, 0x00)
        val ENABLE_INDICATION_VALUE = byteArrayOf(0x02, 0x00)
        val DISABLE_NOTIFICATION_VALUE = byteArrayOf(0x00, 0x00)
    }
}

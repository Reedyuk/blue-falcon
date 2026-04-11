package dev.bluefalcon.engine.windows

import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothCharacteristicDescriptor
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.Uuid

/**
 * Windows implementation of BluetoothCharacteristic
 */
class WindowsBluetoothCharacteristic(
    override val uuid: Uuid,
    override val name: String?,
    val address: Long,
    val serviceUuid: Uuid,
    val properties: Int
) : BluetoothCharacteristic {
    
    private var _value: ByteArray? = null
    private val _descriptors = mutableListOf<BluetoothCharacteristicDescriptor>()
    private var _service: BluetoothService? = null
    
    override val value: ByteArray?
        get() = _value
    
    override val descriptors: List<BluetoothCharacteristicDescriptor>
        get() = _descriptors.toList()
    
    override val isNotifying: Boolean
        get() = (properties and PROPERTY_NOTIFY) == PROPERTY_NOTIFY
    
    override val service: BluetoothService?
        get() = _service
    
    internal fun updateValue(value: ByteArray) {
        _value = value
    }
    
    internal fun setService(service: BluetoothService) {
        _service = service
    }
    
    internal fun addDescriptor(descriptor: BluetoothCharacteristicDescriptor) {
        if (!_descriptors.contains(descriptor)) {
            _descriptors.add(descriptor)
        }
    }
    
    override fun equals(other: Any?): Boolean =
        if (this === other) true
        else if (other !is WindowsBluetoothCharacteristic) false
        else uuid == other.uuid && serviceUuid == other.serviceUuid
    
    override fun hashCode(): Int =
        31 * uuid.hashCode() + serviceUuid.hashCode()
    
    companion object {
        const val PROPERTY_BROADCAST = 0x01
        const val PROPERTY_READ = 0x02
        const val PROPERTY_WRITE_NO_RESPONSE = 0x04
        const val PROPERTY_WRITE = 0x08
        const val PROPERTY_NOTIFY = 0x10
        const val PROPERTY_INDICATE = 0x20
        const val PROPERTY_SIGNED_WRITE = 0x40
        const val PROPERTY_EXTENDED_PROPS = 0x80
    }
}

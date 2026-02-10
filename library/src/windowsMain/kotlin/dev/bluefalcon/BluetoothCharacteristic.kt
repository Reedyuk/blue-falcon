package dev.bluefalcon

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Native Bluetooth Characteristic wrapper for Windows
 */
class NativeBluetoothCharacteristic(
    val uuid: Uuid,
    val name: String?,
    val deviceAddress: Long,
    val serviceUuid: Uuid,
    val properties: Int,
    var value: ByteArray? = null
)

actual class BluetoothCharacteristic(val characteristic: NativeBluetoothCharacteristic) {
    actual val name: String?
        get() = characteristic.name ?: characteristic.uuid.toString()
    
    actual val uuid: Uuid
        get() = characteristic.uuid
    
    actual val value: ByteArray?
        get() = characteristic.value
    
    private val _descriptors = mutableListOf<BluetoothCharacteristicDescriptor>()
    
    actual val descriptors: List<BluetoothCharacteristicDescriptor>
        get() = _descriptors
    
    actual val isNotifying: Boolean
        get() = (characteristic.properties and PROPERTY_NOTIFY) == PROPERTY_NOTIFY
    
    private var _service: BluetoothService? = null
    
    actual val service: BluetoothService?
        get() = _service
    
    internal actual val _descriptorsFlow = MutableStateFlow<List<BluetoothCharacteristicDescriptor>>(emptyList())
    
    internal fun setService(service: BluetoothService) {
        _service = service
    }
    
    internal fun addDescriptor(descriptor: BluetoothCharacteristicDescriptor) {
        if (!_descriptors.contains(descriptor)) {
            _descriptors.add(descriptor)
            _descriptorsFlow.tryEmit(_descriptors.toList())
        }
    }
    
    override fun equals(other: Any?): Boolean =
        if (this === other) true
        else if (other !is BluetoothCharacteristic) false
        else characteristic.uuid == other.characteristic.uuid &&
                characteristic.serviceUuid == other.characteristic.serviceUuid
    
    override fun hashCode(): Int =
        31 * characteristic.uuid.hashCode() + characteristic.serviceUuid.hashCode()
    
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

/**
 * Native Bluetooth Characteristic Descriptor wrapper for Windows
 */
class NativeBluetoothCharacteristicDescriptor(
    val uuid: Uuid,
    val deviceAddress: Long,
    val serviceUuid: Uuid,
    val characteristicUuid: Uuid,
    var value: ByteArray? = null
)

actual class BluetoothCharacteristicDescriptor(val descriptor: NativeBluetoothCharacteristicDescriptor) {
    val uuid: Uuid
        get() = descriptor.uuid
    
    var value: ByteArray?
        get() = descriptor.value
        set(newValue) {
            descriptor.value = newValue
        }
    
    override fun equals(other: Any?): Boolean =
        if (this === other) true
        else if (other !is BluetoothCharacteristicDescriptor) false
        else descriptor.uuid == other.descriptor.uuid &&
                descriptor.characteristicUuid == other.descriptor.characteristicUuid
    
    override fun hashCode(): Int =
        31 * descriptor.uuid.hashCode() + descriptor.characteristicUuid.hashCode()
    
    companion object {
        // Standard GATT descriptor UUIDs
        val CCCD_UUID = kotlin.uuid.Uuid.parse("00002902-0000-1000-8000-00805f9b34fb")
        val ENABLE_NOTIFICATION_VALUE = byteArrayOf(0x01, 0x00)
        val ENABLE_INDICATION_VALUE = byteArrayOf(0x02, 0x00)
        val DISABLE_NOTIFICATION_VALUE = byteArrayOf(0x00, 0x00)
    }
}

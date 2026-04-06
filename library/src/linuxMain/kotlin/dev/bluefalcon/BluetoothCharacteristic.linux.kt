package dev.bluefalcon

import com.monkopedia.sdbus.ObjectPath
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

actual class BluetoothCharacteristic(
    val objectPath: ObjectPath,
    actual val uuid: Uuid,
    private val serviceObjectPath: ObjectPath,
) {
    actual val name: String?
        get() = uuid.toString()

    internal var _value: ByteArray? = null
    actual val value: ByteArray?
        get() = _value

    private val _descriptors = mutableListOf<BluetoothCharacteristicDescriptor>()
    actual val descriptors: List<BluetoothCharacteristicDescriptor>
        get() = _descriptors

    internal var _isNotifying: Boolean = false
    internal var _notifyJob: Job? = null
    actual val isNotifying: Boolean
        get() = _isNotifying

    private var _service: BluetoothService? = null
    actual val service: BluetoothService?
        get() = _service

    internal actual val _descriptorsFlow = MutableStateFlow<List<BluetoothCharacteristicDescriptor>>(emptyList())

    internal fun setService(service: BluetoothService) {
        _service = service
    }

    internal fun addDescriptor(descriptor: BluetoothCharacteristicDescriptor) {
        if (_descriptors.none { it.uuid == descriptor.uuid }) {
            _descriptors.add(descriptor)
            _descriptorsFlow.tryEmit(_descriptors.toList())
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BluetoothCharacteristic) return false
        return objectPath == other.objectPath
    }

    override fun hashCode(): Int = objectPath.hashCode()
}

actual class BluetoothCharacteristicDescriptor(
    val objectPath: ObjectPath,
    val uuid: Uuid,
    val characteristicObjectPath: ObjectPath,
) {
    internal var _value: ByteArray? = null
    var value: ByteArray?
        get() = _value
        set(v) { _value = v }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BluetoothCharacteristicDescriptor) return false
        return objectPath == other.objectPath
    }

    override fun hashCode(): Int = objectPath.hashCode()
}

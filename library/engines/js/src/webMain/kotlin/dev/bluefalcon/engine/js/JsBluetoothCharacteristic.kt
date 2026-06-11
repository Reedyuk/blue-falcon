package dev.bluefalcon.engine.js

import dev.bluefalcon.core.Uuid
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import dev.bluefalcon.core.BluetoothCharacteristic as CoreBluetoothCharacteristic
import dev.bluefalcon.core.BluetoothCharacteristicDescriptor as CoreBluetoothCharacteristicDescriptor

class JsBluetoothCharacteristic internal constructor(
    internal val characteristic: WebCharacteristic,
    override val service: JsBluetoothService?
) : CoreBluetoothCharacteristic {

    override val name: String?
        get() = characteristic.uuid

    override val value: ByteArray?
        get() = characteristic.value

    private val _descriptorsFlow = MutableStateFlow<List<CoreBluetoothCharacteristicDescriptor>>(emptyList())
    private val _notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    override val descriptors: List<CoreBluetoothCharacteristicDescriptor>
        get() = _descriptorsFlow.value

    override val notifications: SharedFlow<ByteArray>
        get() = _notifications.asSharedFlow()

    override val uuid: Uuid
        get() = Uuid.parse(characteristic.uuid)

    override val isNotifying: Boolean
        get() = false // Web Bluetooth API doesn't expose this directly

    val stringValue: String?
        get() = value?.decodeToString()

    internal fun emitNotification(value: ByteArray) {
        _notifications.tryEmit(value.copyOf())
    }
}

package dev.bluefalcon.engine.js

import dev.bluefalcon.core.Uuid
import dev.bluefalcon.engine.js.external.BluetoothRemoteGATTCharacteristic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import dev.bluefalcon.core.BluetoothCharacteristic as CoreBluetoothCharacteristic
import dev.bluefalcon.core.BluetoothCharacteristicDescriptor as CoreBluetoothCharacteristicDescriptor

class JsBluetoothCharacteristic(
    val characteristic: BluetoothRemoteGATTCharacteristic,
    override val service: JsBluetoothService?
) : CoreBluetoothCharacteristic {
    
    override val name: String?
        get() = characteristic.uuid
    
    override val value: ByteArray?
        get() = characteristic.value?.buffer.toByteArray()
    
    private val _descriptorsFlow = MutableStateFlow<List<CoreBluetoothCharacteristicDescriptor>>(emptyList())
    private val descriptorsFlow: StateFlow<List<CoreBluetoothCharacteristicDescriptor>> = _descriptorsFlow.asStateFlow()
    
    override val descriptors: List<CoreBluetoothCharacteristicDescriptor>
        get() = _descriptorsFlow.value
    
    override val uuid: Uuid
        get() = Uuid.parse(characteristic.uuid)
    
    override val isNotifying: Boolean
        get() = false // Web Bluetooth API doesn't expose this directly
    
    val stringValue: String?
        get() = value?.decodeToString()
}

fun ArrayBuffer?.toByteArray(): ByteArray? = this?.run { Int8Array(this).unsafeCast<ByteArray>() }

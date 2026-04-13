package dev.bluefalcon.engine.rpi

import com.welie.blessed.BluetoothGattCharacteristic as BlessedCharacteristic
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothCharacteristicDescriptor
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.Uuid
import kotlin.uuid.toKotlinUuid

/**
 * Raspberry Pi implementation of BluetoothCharacteristic wrapping Blessed library
 */
class RpiBluetoothCharacteristic(
    val nativeCharacteristic: BlessedCharacteristic
) : BluetoothCharacteristic {
    
    override val uuid: Uuid
        get() = nativeCharacteristic.uuid.toKotlinUuid()
    
    override val name: String?
        get() = nativeCharacteristic.uuid.toString()
    
    private var _value: ByteArray? = null
    override val value: ByteArray?
        get() = _value
    
    override val descriptors: List<BluetoothCharacteristicDescriptor>
        get() = nativeCharacteristic.descriptors.map { RpiBluetoothCharacteristicDescriptor(it) }
    
    override val isNotifying: Boolean
        get() = nativeCharacteristic.supportsNotifying()
    
    override val service: BluetoothService?
        get() = nativeCharacteristic.service?.let { RpiBluetoothService(it) }
    
    internal fun updateValue(value: ByteArray) {
        _value = value
    }
}

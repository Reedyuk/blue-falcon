package dev.bluefalcon.engine.rpi

import com.welie.blessed.BluetoothGattDescriptor as BlessedDescriptor
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothCharacteristicDescriptor
import dev.bluefalcon.core.Uuid
import kotlin.uuid.toKotlinUuid

/**
 * Raspberry Pi implementation of BluetoothCharacteristicDescriptor wrapping Blessed library
 */
class RpiBluetoothCharacteristicDescriptor(
    val nativeDescriptor: BlessedDescriptor
) : BluetoothCharacteristicDescriptor {
    
    override val uuid: Uuid
        get() = nativeDescriptor.uuid.toKotlinUuid()
    
    override val value: ByteArray?
        get() = null // Blessed library doesn't expose descriptor values
    
    override val characteristic: BluetoothCharacteristic?
        get() = nativeDescriptor.characteristic?.let { RpiBluetoothCharacteristic(it) }
}

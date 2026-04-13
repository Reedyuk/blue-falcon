package dev.bluefalcon.engine.android

import android.bluetooth.BluetoothGattDescriptor
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothCharacteristicDescriptor
import dev.bluefalcon.core.Uuid
import kotlin.uuid.toKotlinUuid

/**
 * Android implementation of BluetoothCharacteristicDescriptor.
 * Wraps Android's BluetoothGattDescriptor.
 */
class AndroidBluetoothCharacteristicDescriptor(val descriptor: BluetoothGattDescriptor) : 
    BluetoothCharacteristicDescriptor {
    
    override val uuid: Uuid
        get() = descriptor.uuid.toKotlinUuid()
    
    override val value: ByteArray?
        get() = descriptor.value
    
    override val characteristic: BluetoothCharacteristic?
        get() = descriptor.characteristic?.let { AndroidBluetoothCharacteristic(it) }
}

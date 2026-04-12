package dev.bluefalcon.engine.rpi

import com.welie.blessed.BluetoothGattService as BlessedService
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.Uuid
import kotlin.uuid.toKotlinUuid

/**
 * Raspberry Pi implementation of BluetoothService wrapping Blessed library
 */
class RpiBluetoothService(
    val nativeService: BlessedService
) : BluetoothService {
    
    override val uuid: Uuid
        get() = nativeService.uuid.toKotlinUuid()
    
    override val name: String?
        get() = nativeService.uuid.toString()
    
    override val characteristics: List<BluetoothCharacteristic>
        get() = nativeService.characteristics.map { RpiBluetoothCharacteristic(it) }
}

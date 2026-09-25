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
    
    /**
     * One wrapper for each characteristic, made once. A wrapper holds the characteristic's
     * [BluetoothCharacteristic.notifications] flow and its last value, so every caller must get the
     * same object: the engine delivers a notification to the wrapper that it finds here, and a
     * caller collects from the wrapper that it got earlier. A new wrapper on each access sent every
     * notification to an object that nobody collected from.
     *
     * Blessed gives the service with all its characteristics in `onServicesDiscovered`, and a new
     * discovery makes a new [RpiBluetoothService], so the list does not change after construction.
     */
    override val characteristics: List<BluetoothCharacteristic> =
        nativeService.characteristics.map { RpiBluetoothCharacteristic(it) }
}

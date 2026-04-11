package dev.bluefalcon.engine.android

import android.bluetooth.BluetoothGattService
import dev.bluefalcon.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.uuid.toKotlinUuid

/**
 * Android implementation of BluetoothService.
 * Wraps Android's BluetoothGattService.
 */
class AndroidBluetoothService(val service: BluetoothGattService) : BluetoothService {
    
    override val name: String?
        get() = service.uuid.toString()
    
    override val characteristics: List<BluetoothCharacteristic>
        get() = service.characteristics.map { AndroidBluetoothCharacteristic(it) }
    
    override val uuid: Uuid
        get() = service.uuid.toKotlinUuid()
    
    internal val _characteristicsFlow = MutableStateFlow<List<BluetoothCharacteristic>>(emptyList())
}

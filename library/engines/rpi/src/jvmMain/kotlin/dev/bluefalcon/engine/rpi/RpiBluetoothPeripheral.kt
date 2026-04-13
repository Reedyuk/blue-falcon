package dev.bluefalcon.engine.rpi

import com.welie.blessed.BluetoothPeripheral as BlessedPeripheral
import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothService

/**
 * Raspberry Pi implementation of BluetoothPeripheral wrapping Blessed library
 */
class RpiBluetoothPeripheral(
    val nativePeripheral: BlessedPeripheral
) : BluetoothPeripheral {
    
    override val name: String?
        get() = nativePeripheral.name
    
    override val uuid: String
        get() = nativePeripheral.address
    
    override var rssi: Float? = null
    
    override var mtuSize: Int? = null
    
    private var _services = emptyList<BluetoothService>()
    override val services: List<BluetoothService>
        get() = _services
    
    override val characteristics: List<BluetoothCharacteristic>
        get() = _services.flatMap { it.characteristics }
    
    internal fun updateServices(services: List<BluetoothService>) {
        _services = services
    }
    
    internal fun updateCharacteristicValue(characteristicUuid: String, value: ByteArray) {
        _services.forEach { service ->
            service.characteristics.filterIsInstance<RpiBluetoothCharacteristic>()
                .filter { it.uuid.toString() == characteristicUuid }
                .forEach { it.updateValue(value) }
        }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RpiBluetoothPeripheral) return false
        return uuid == other.uuid
    }
    
    override fun hashCode(): Int {
        return uuid.hashCode()
    }
}


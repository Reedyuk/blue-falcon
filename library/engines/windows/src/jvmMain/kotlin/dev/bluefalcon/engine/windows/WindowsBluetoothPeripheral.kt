package dev.bluefalcon.engine.windows

import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothService

/**
 * Windows implementation of BluetoothPeripheral
 * Wraps a Windows Bluetooth LE device address (UINT64)
 */
class WindowsBluetoothPeripheral(
    val address: Long,
    private var deviceName: String?
) : BluetoothPeripheral {
    
    private var _rssi: Float? = null
    private var _mtuSize: Int? = null
    private val _services = mutableListOf<BluetoothService>()
    
    override val name: String?
        get() = deviceName ?: uuid
    
    override val uuid: String
        get() = formatMacAddress(address)
    
    override var rssi: Float?
        get() = _rssi
        set(value) { _rssi = value }
    
    override var mtuSize: Int?
        get() = _mtuSize
        set(value) { _mtuSize = value }
    
    override val services: List<BluetoothService>
        get() = _services.toList()
    
    override val characteristics: List<BluetoothCharacteristic>
        get() = _services.flatMap { it.characteristics }
    
    internal fun updateServices(services: List<BluetoothService>) {
        _services.clear()
        _services.addAll(services)
    }
    
    private fun formatMacAddress(address: Long): String {
        return String.format(
            "%02X:%02X:%02X:%02X:%02X:%02X",
            (address shr 40) and 0xFF,
            (address shr 32) and 0xFF,
            (address shr 24) and 0xFF,
            (address shr 16) and 0xFF,
            (address shr 8) and 0xFF,
            address and 0xFF
        )
    }
    
    override fun toString(): String = uuid
    
    override fun hashCode(): Int = uuid.hashCode()
    
    override fun equals(other: Any?): Boolean {
        if (other == null || other !is WindowsBluetoothPeripheral) return false
        return other.uuid == uuid
    }
}

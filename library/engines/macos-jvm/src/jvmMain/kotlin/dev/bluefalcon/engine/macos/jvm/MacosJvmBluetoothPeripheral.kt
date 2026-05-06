package dev.bluefalcon.engine.macos.jvm

import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothService

class MacosJvmBluetoothPeripheral(
    override val uuid: String,
    private val deviceName: String?
) : BluetoothPeripheral {

    private val _services = mutableListOf<BluetoothService>()

    override val name: String?
        get() = deviceName ?: uuid

    override var rssi: Float? = null

    override var mtuSize: Int? = null

    override val services: List<BluetoothService>
        get() = _services.toList()

    override val characteristics: List<BluetoothCharacteristic>
        get() = _services.flatMap { it.characteristics }

    internal fun updateServices(services: List<BluetoothService>) {
        _services.clear()
        _services.addAll(services)
    }

    override fun equals(other: Any?): Boolean =
        other is MacosJvmBluetoothPeripheral && other.uuid == uuid

    override fun hashCode(): Int = uuid.hashCode()

    override fun toString(): String = uuid
}
package dev.bluefalcon.engine.js

import dev.bluefalcon.core.BluetoothPeripheral
import kotlinx.coroutines.flow.MutableStateFlow

class JsBluetoothPeripheral internal constructor(
    internal val device: WebDevice
) : BluetoothPeripheral {

    override val name: String?
        get() = device.name

    override val uuid: String
        get() = device.id

    override var rssi: Float? = null

    override var mtuSize: Int? = null

    private val _servicesFlow = MutableStateFlow<List<JsBluetoothService>>(emptyList())

    override val services: List<dev.bluefalcon.core.BluetoothService>
        get() = _servicesFlow.value

    override val characteristics: List<dev.bluefalcon.core.BluetoothCharacteristic>
        get() = services.flatMap { it.characteristics }

    internal fun updateServices(services: List<JsBluetoothService>) {
        _servicesFlow.value = services
    }
}
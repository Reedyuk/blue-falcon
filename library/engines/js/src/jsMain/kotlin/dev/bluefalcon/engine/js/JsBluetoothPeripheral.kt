package dev.bluefalcon.engine.js

import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.Uuid
import dev.bluefalcon.engine.js.external.BluetoothDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class JsBluetoothPeripheral(
    val device: BluetoothDevice
) : BluetoothPeripheral {
    
    override val name: String?
        get() = device.name
    
    override val uuid: String
        get() = device.id
    
    override var rssi: Float? = null
    
    override var mtuSize: Int? = null
    
    private val _servicesFlow = MutableStateFlow<List<JsBluetoothService>>(emptyList())
    private val servicesFlow: StateFlow<List<JsBluetoothService>> = _servicesFlow.asStateFlow()
    
    override val services: List<dev.bluefalcon.core.BluetoothService>
        get() = _servicesFlow.value
    
    override val characteristics: List<dev.bluefalcon.core.BluetoothCharacteristic>
        get() = services.flatMap { it.characteristics }
    
    internal fun updateServices(services: List<JsBluetoothService>) {
        _servicesFlow.value = services
    }
}

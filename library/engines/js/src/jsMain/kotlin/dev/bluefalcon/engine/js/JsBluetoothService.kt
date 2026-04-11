package dev.bluefalcon.engine.js

import dev.bluefalcon.core.Uuid
import dev.bluefalcon.engine.js.external.BluetoothRemoteGATTService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.bluefalcon.core.BluetoothService as CoreBluetoothService

class JsBluetoothService(
    val service: BluetoothRemoteGATTService
) : CoreBluetoothService {
    
    override val name: String?
        get() = service.uuid
    
    private val _characteristicsFlow = MutableStateFlow<List<JsBluetoothCharacteristic>>(emptyList())
    private val characteristicsFlow: StateFlow<List<JsBluetoothCharacteristic>> = _characteristicsFlow.asStateFlow()
    
    override val characteristics: List<dev.bluefalcon.core.BluetoothCharacteristic>
        get() = _characteristicsFlow.value
    
    override val uuid: Uuid
        get() = Uuid.parse(service.uuid)
    
    internal fun updateCharacteristics(characteristics: List<JsBluetoothCharacteristic>) {
        _characteristicsFlow.value = characteristics
    }
}

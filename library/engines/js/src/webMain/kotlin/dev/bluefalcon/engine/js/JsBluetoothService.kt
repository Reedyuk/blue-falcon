package dev.bluefalcon.engine.js

import dev.bluefalcon.core.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import dev.bluefalcon.core.BluetoothService as CoreBluetoothService

class JsBluetoothService internal constructor(
    internal val service: WebService
) : CoreBluetoothService {

    override val name: String?
        get() = service.uuid

    private val _characteristicsFlow = MutableStateFlow<List<JsBluetoothCharacteristic>>(emptyList())

    override val characteristics: List<dev.bluefalcon.core.BluetoothCharacteristic>
        get() = _characteristicsFlow.value

    override val uuid: Uuid
        get() = Uuid.parse(service.uuid)

    internal fun updateCharacteristics(characteristics: List<JsBluetoothCharacteristic>) {
        _characteristicsFlow.value = characteristics
    }
}

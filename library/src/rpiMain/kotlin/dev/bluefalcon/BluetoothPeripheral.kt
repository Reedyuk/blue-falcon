package dev.bluefalcon

import com.welie.blessed.BluetoothPeripheral
import kotlinx.coroutines.flow.MutableStateFlow

actual class BluetoothPeripheral actual constructor(val device: NativeBluetoothDevice) {
    actual val name: String?
        get() = device.name
    actual val uuid: String
        get() = device.address

    actual var rssi: Float? = null
    actual var mtuSize: Int? = null

    internal actual val _servicesFlow = MutableStateFlow<List<BluetoothService>>(emptyList())
    actual val services: Map<String, BluetoothService>
        get() = _servicesFlow.value.associateBy { it.uuid }

    actual val characteristics: Map<String, BluetoothCharacteristic>
        get() = services.values
            .flatMap { it.characteristics }
            .associateBy { it.uuid }
}
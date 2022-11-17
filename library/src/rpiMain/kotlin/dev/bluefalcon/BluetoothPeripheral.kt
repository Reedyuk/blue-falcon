package dev.bluefalcon

import com.welie.blessed.BluetoothPeripheral
import kotlinx.coroutines.flow.MutableStateFlow

actual class BluetoothPeripheral(val bluetoothDevice: BluetoothPeripheral) {
    actual val name: String?
        get() = bluetoothDevice.name
    actual val services: List<BluetoothService>
        get() = _servicesFlow.value
    actual val uuid: String
        get() = bluetoothDevice.address

    actual var rssi: Float? = null

    internal actual val _servicesFlow = MutableStateFlow<List<BluetoothService>>(emptyList())
}
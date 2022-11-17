package dev.bluefalcon

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.flow.MutableStateFlow

actual class BluetoothPeripheral(val bluetoothDevice: BluetoothDevice) {
    actual val name: String?
        get() = bluetoothDevice.name ?: bluetoothDevice.address
    actual val services: List<BluetoothService>
        get() = _servicesFlow.value
    actual val uuid: String
        get() = bluetoothDevice.address

    actual var rssi: Float? = null

    internal actual val _servicesFlow = MutableStateFlow<List<BluetoothService>>(emptyList())
}
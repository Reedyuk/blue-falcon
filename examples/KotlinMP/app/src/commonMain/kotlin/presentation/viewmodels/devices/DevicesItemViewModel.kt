package presentation.viewmodels

import dev.bluefalcon.BluetoothPeripheral

class DevicesItemViewModel(private val device: BluetoothPeripheral) {
    val displayName: String = device.name ?: "Unidentified"
}
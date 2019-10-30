package presentation.viewmodels

import dev.bluefalcon.BluetoothPeripheral

class DevicesItemViewModel(device: BluetoothPeripheral) {
    val displayName: String = device.name ?: "Unidentified"
}
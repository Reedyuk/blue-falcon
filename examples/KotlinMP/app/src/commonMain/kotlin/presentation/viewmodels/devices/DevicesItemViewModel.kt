package presentation.viewmodels.devices

import dev.bluefalcon.BluetoothPeripheral

class DevicesItemViewModel(device: BluetoothPeripheral) {
    val displayName: String = device.name ?: device.uuid
}
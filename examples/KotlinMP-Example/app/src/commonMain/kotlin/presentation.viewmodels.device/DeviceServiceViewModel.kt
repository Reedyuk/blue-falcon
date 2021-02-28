package presentation.viewmodels.device

import dev.bluefalcon.BluetoothService

class DeviceServiceViewModel(service: BluetoothService) {
    val displayName = service.name
}
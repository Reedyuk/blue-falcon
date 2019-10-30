package presentation.viewmodels.deviceservice

import dev.bluefalcon.BluetoothCharacteristic
import dev.bluefalcon.BluetoothService


class DeviceCharacteristicsViewModel(
    private val output: DeviceCharacteristicsViewModelOutput,
    private val service: BluetoothService,
    private val characteristics: List<BluetoothCharacteristic>
) {

    val displayName = service.name ?: ""

    fun deviceCharacteristicViewModels(): List<DeviceCharacteristicViewModel> = characteristics.map {
        DeviceCharacteristicViewModel(it)
    }

}

interface DeviceCharacteristicsViewModelOutput {
    fun refresh()
}
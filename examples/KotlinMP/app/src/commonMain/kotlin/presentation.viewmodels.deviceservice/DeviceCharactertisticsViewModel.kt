package presentation.viewmodels.deviceservice

import dev.bluefalcon.BluetoothCharacteristic
import dev.bluefalcon.BluetoothPeripheral
import dev.bluefalcon.BluetoothService

class DeviceCharacteristicsViewModel(
    private val bluetoothService: sample.BluetoothService,
    private val bluetoothDevice: BluetoothPeripheral,
    service: BluetoothService,
    private val characteristics: List<BluetoothCharacteristic>
) {
    val displayName = service.name ?: ""

    fun deviceCharacteristicViewModels(): List<DeviceCharacteristicViewModel> = characteristics.map {
        DeviceCharacteristicViewModel(
            bluetoothService,
            bluetoothDevice,
            it
        )
    }

}
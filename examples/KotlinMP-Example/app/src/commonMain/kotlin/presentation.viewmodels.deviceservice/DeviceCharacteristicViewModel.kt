package presentation.viewmodels.deviceservice

import dev.bluefalcon.BluetoothCharacteristic
import dev.bluefalcon.BluetoothPeripheral
import sample.BluetoothService
import sample.DeviceCharacteristicDelegate

class DeviceCharacteristicViewModel(
    private val bluetoothService: BluetoothService,
    private var bluetoothDevice: BluetoothPeripheral,
    private val characteristic: BluetoothCharacteristic,
    var output: DeviceCharacteristicViewModelOutput?
): DeviceCharacteristicDelegate {
    val displayName = characteristic.name
    var notify: Boolean = false
    var value: String = ""

    init {
        bluetoothService.addDeviceCharacteristicDelegate(this)
    }

    fun readCharacteristicTapped() {
        bluetoothService.readCharacteristic(
            bluetoothDevice,
            characteristic
        )
    }

    fun notifyCharacteristicTapped() {
        notify = !notify
        bluetoothService.notifyCharacteristic(
            bluetoothDevice,
            characteristic,
            notify
        )
        output?.refresh()
    }

    fun writeCharactersticTapped(value: String) {
        bluetoothService.writeCharacteristic(
            bluetoothDevice,
            characteristic,
            value
        )
    }

    override fun didCharacteristcValueChanged(value: String) {
        this.value = value
        output?.refresh()
    }
}

interface DeviceCharacteristicViewModelOutput {
    fun refresh()
}
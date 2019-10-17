package presentation.viewmodels

import dev.bluefalcon.*
import sample.BluetoothService
import sample.DevicesDelegate
import sample.scan

class DevicesViewModel(
    private val output: DevicesViewModelOutput,
    private val bluetoothService: BluetoothService
): DevicesDelegate {

    private val devices: MutableList<BluetoothPeripheral> = mutableListOf()

    init {
        bluetoothService.addDevicesDelegate(this)
    }

    fun scan() {
        try {
            bluetoothService.scan()
        } catch (exception: Exception) {
            println(exception.message)
            if (exception is BluetoothPermissionException) {
                output.requiresBluetoothPermission()
            } else {
                //delay few seconds and retry
                scan()
            }
        }
    }

    fun deviceViewModels(): List<DevicesItemViewModel> = devices.map {
        DevicesItemViewModel(it)
    }

    override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral) {
        devices.add(bluetoothPeripheral)
        output.refresh()
    }

}

interface DevicesViewModelOutput {
    fun refresh()
    fun requiresBluetoothPermission()
}
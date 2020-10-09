package presentation.viewmodels.devices

import dev.bluefalcon.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sample.BluetoothService
import sample.DevicesDelegate
import sample.scan

class DevicesViewModel(
    private val output: DevicesViewModelOutput,
    private val bluetoothService: BluetoothService
): DevicesDelegate {

    val devices: MutableList<BluetoothPeripheral> = mutableListOf()

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
                GlobalScope.launch(Dispatchers.Main) {
                    delay(1000)
                    scan()
                }
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
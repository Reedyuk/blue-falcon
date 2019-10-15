package presentation.viewmodels

import dev.bluefalcon.BluetoothPeripheral
import sample.DevicesDelegate

class DevicesViewModel(private val output: DevicesViewModelOutput): DevicesDelegate {

    val devices: MutableList<BluetoothPeripheral> = mutableListOf()

    override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral) {
        devices.add(bluetoothPeripheral)
        output.refresh()
    }

}

interface DevicesViewModelOutput {
    fun refresh()
}
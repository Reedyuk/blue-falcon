package presentation.viewmodels.device

import dev.bluefalcon.BluetoothPeripheral
import sample.BluetoothService
import sample.DeviceConnectDelegate

class DeviceViewModel (
    private val output: DeviceViewModelOutput,
    private var bluetoothDevice: BluetoothPeripheral,
    private val bluetoothService: BluetoothService
): DeviceConnectDelegate {

    val displayName: String = bluetoothDevice.name ?: "Unidentified"

    init {
        bluetoothService.addDeviceConnectDelegate(this)
        connectDevice()
    }

    fun deviceServiceViewModels(): List<DeviceServiceViewModel> = bluetoothDevice.services.map {
        DeviceServiceViewModel()
    }

    private fun connectDevice() {
        bluetoothService.connect(bluetoothDevice)
    }

    override fun didDeviceConnect(bluetoothPeripheral: BluetoothPeripheral) {
        bluetoothDevice = bluetoothPeripheral
        output.refresh()
    }

    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {
        bluetoothDevice = bluetoothPeripheral
        output.refresh()
    }

}

interface DeviceViewModelOutput {
    fun refresh()
}
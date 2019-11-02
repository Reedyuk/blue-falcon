package presentation.viewmodels.device

import dev.bluefalcon.BluetoothPeripheral
import sample.BluetoothService
import sample.DeviceConnectDelegate

class DeviceViewModel (
    private val output: DeviceViewModelOutput,
    var bluetoothDevice: BluetoothPeripheral,
    private val bluetoothService: BluetoothService
): DeviceConnectDelegate {

    val displayName: String = bluetoothDevice.name ?: "Unidentified"
    val rssi: Float? get() = bluetoothDevice.rssi
    val services: List<dev.bluefalcon.BluetoothService> get() = bluetoothDevice.services

    init {
        bluetoothService.addDeviceConnectDelegate(this)
        connectDevice()
    }

    fun deviceServiceViewModels(): List<DeviceServiceViewModel> = bluetoothDevice.services.map {
        DeviceServiceViewModel(it)
    }

    private fun connectDevice() {
        bluetoothService.connect(bluetoothDevice)
    }

    override fun didDeviceConnect(bluetoothPeripheral: BluetoothPeripheral) {
        bluetoothDevice = bluetoothPeripheral
        if (bluetoothDevice.services.isNotEmpty()) {
            output.refresh()
        }
    }

    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {
        bluetoothDevice = bluetoothPeripheral
        output.refresh()
    }

    override fun didRssiChange(bluetoothPeripheral: BluetoothPeripheral) {
        println("didRssiChange ${bluetoothPeripheral.rssi}")
        bluetoothDevice = bluetoothPeripheral
        output.refresh()
    }

}

interface DeviceViewModelOutput {
    fun refresh()
}
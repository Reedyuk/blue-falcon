package dev.bluefalcon.viewModels

import android.util.Log
import dev.bluefalcon.BlueFalconApplication
import dev.bluefalcon.BluetoothPeripheral
import dev.bluefalcon.BluetoothService
import dev.bluefalcon.adapters.DeviceAdapter
import dev.bluefalcon.observables.StandardObservableProperty
import dev.bluefalcon.views.DeviceActivityUI
import dev.bluefalcon.activities.DeviceActivity
import dev.bluefalcon.services.BluetoothServiceConnectedDeviceDelegate

class DeviceViewModel(
    private val deviceActivity: DeviceActivity,
    var bluetoothPeripheral: BluetoothPeripheral
) : BluetoothServiceConnectedDeviceDelegate {

    val deviceActivityUI = DeviceActivityUI(this)
    val services: List<BluetoothService> get() = bluetoothPeripheral.services
    val deviceAdapter = DeviceAdapter(this)
    var connectionStatus = StandardObservableProperty("Connecting...")

    val title: String
        get() = bluetoothPeripheral.bluetoothDevice.address + " " +
                if (bluetoothPeripheral.bluetoothDevice.name != null) bluetoothPeripheral.bluetoothDevice.name else ""

    init {
        BlueFalconApplication.instance.bluetoothService.connect(bluetoothPeripheral)
    }

    fun destroy() {
        BlueFalconApplication.instance.bluetoothService.disconnect(bluetoothPeripheral)
        removeDelegate()
    }

    fun addDelegate() {
        BlueFalconApplication.instance.bluetoothService.connectedDeviceDelegates[bluetoothPeripheral.bluetoothDevice.address] = this
    }

    fun removeDelegate() {
        BlueFalconApplication.instance.bluetoothService.connectedDeviceDelegates.remove(
            bluetoothPeripheral.bluetoothDevice.address
        )
    }

    override fun connectedDevice() {
        connectionStatus.value = "Connected"
    }

    override fun discoveredServices(bluetoothPeripheral: BluetoothPeripheral) {
        Log.v("Bluefalcon", "Services in view model ${bluetoothPeripheral.services}")
        this.bluetoothPeripheral = bluetoothPeripheral
        deviceActivity.runOnUiThread {
            deviceAdapter.notifyDataSetInvalidated()
            deviceAdapter.notifyDataSetChanged()
        }
    }

}
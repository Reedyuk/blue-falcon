package dev.bluefalcon.viewModels

import android.bluetooth.BluetoothGattService
import android.util.Log
import dev.bluefalcon.BlueFalconApplication
import dev.bluefalcon.BlueFalconDelegate
import dev.bluefalcon.BluetoothPeripheral
import dev.bluefalcon.adapters.DeviceAdapter
import dev.bluefalcon.observables.StandardObservableProperty
import dev.bluefalcon.views.DeviceActivityUI
import dev.bluefalcon.activities.DeviceActivity

class DeviceViewModel(
    val deviceActivity: DeviceActivity,
    var bluetoothPeripheral: BluetoothPeripheral
) : BlueFalconDelegate {

    val deviceActivityUI = DeviceActivityUI(this)
    val services: List<BluetoothGattService> get() = bluetoothPeripheral.services
    val deviceAdapter = DeviceAdapter(this)
    var connectionStatus = StandardObservableProperty("Connecting...")

    val title: String
        get() = bluetoothPeripheral.bluetoothDevice.address + " " +
                if (bluetoothPeripheral.bluetoothDevice.name != null) bluetoothPeripheral.bluetoothDevice.name else ""

    init {
        BlueFalconApplication.instance.blueFalcon.delegates.add(this)
        BlueFalconApplication.instance.blueFalcon.connect(bluetoothPeripheral)
    }

    override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
        connectionStatus.value = "Connected"
    }

    override fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {
        Log.v("Bluefalcon", "Services in view model ${bluetoothPeripheral.services}")
        this.bluetoothPeripheral = bluetoothPeripheral
        deviceActivity.runOnUiThread {
            deviceAdapter.notifyDataSetInvalidated()
            deviceAdapter.notifyDataSetChanged()
        }
    }

    override fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {}
}